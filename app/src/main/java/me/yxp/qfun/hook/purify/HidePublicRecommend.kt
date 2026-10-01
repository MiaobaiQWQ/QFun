package me.yxp.qfun.hook.purify

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.Composable
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.conf.PublicRecommendConfig
import me.yxp.qfun.hook.base.BaseClickableHookItem
import me.yxp.qfun.ui.pages.configs.PublicRecommendPage
import me.yxp.qfun.utils.dexkit.DexKitTask
import me.yxp.qfun.utils.hook.hookAfter
import me.yxp.qfun.utils.hook.hookReplace
import me.yxp.qfun.utils.log.LogUtils
import me.yxp.qfun.utils.qq.HostInfo
import me.yxp.qfun.utils.reflect.ClassUtils
import me.yxp.qfun.utils.reflect.clazz
import me.yxp.qfun.utils.reflect.findMethods
import me.yxp.qfun.utils.reflect.getObjectByTypeOrNull
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.base.BaseMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「空友爱看」（QZone PublicRecommend）入口净化：只隐藏它的入口/气泡/红点，通知铃铛与设置齿轮保留。
 *
 * 维护要点：
 * - 入口控件 `QzoneTitleIconWithTextButton` 只在 `QzoneFriendFeedProTitlePart.ma()` 里创建，
 *   而**通知/设置按钮也在同一个方法里创建** ⇒ 不能整体跳过 `ma()`：让它跑完，再按实例锁定并屏蔽其 void 方法。
 * - 反复把它显示出来的是观察者 lambda `…$bindPublicRecommendRedDot$1`（类名保留、但没有 InnerClasses 元数据，
 *   只能按类名加载），它的 TIPS/NONE 分支自己就调 `l0()`。
 * - 下滑时还会出现一个 ViewStub 展开的粘性胶囊 `QzoneFeedProExpandCapsuleView`，它不走 `ma()`。
 * - 混淆方法名一律用日志特征串经 DexKit 定位（见 [getQueryMap]），不要写死短名。
 */
@HookItemAnnotation(
    "空友爱看净化",
    "只隐藏好友动态标题栏的「空友爱看」入口胶囊与其提示气泡，通知/设置图标不受影响",
    HookCategory.PURIFY
)
object HidePublicRecommend :
    BaseClickableHookItem<PublicRecommendConfig>(PublicRecommendConfig.serializer()), DexKitTask {

    private const val ENTRY_BUTTON_CLASS =
        "com.qzone.reborn.feedpro.widget.header.QzoneTitleIconWithTextButton"

    /** 必须用 DEX 原始类名（运行时可能被加固改名），不能拼 `declaringClass.name` */
    private const val ENTRY_PART_CLASS = "com.qzone.reborn.feedpro.part.QzoneFriendFeedProTitlePart"
    private const val OBSERVER_LAMBDA_CLASS = "$ENTRY_PART_CLASS\$bindPublicRecommendRedDot\$1"

    /** 下滑时粘在顶部的胶囊（ViewStub 展开，不走 ma()） */
    private const val CAPSULE_VIEW_CLASS = "com.qzone.reborn.feedpro.widget.QzoneFeedProExpandCapsuleView"

    /** 云控文案兜底值：标题＝空友爱看、跳转串含 publicrecommend */
    private val fallbackMarks = listOf("空友爱看", "publicrecommend")

    private var initEntryBtn: Method? = null
    private var applyAvatarStyle: Method? = null
    private var applyTipsStyle: Method? = null
    private var showGuideBubble: Method? = null

    /** 粘性胶囊的滚动显示点（`[showStickyAndHideOriginal]`）：跑完后再压一次可见性 */
    private var capsuleShow: Method? = null

    /** 粘性胶囊当前文案（正常态是「展开好友动态」；只有图标时＝空友爱看入口态） */
    private val capsuleTexts: MutableMap<View, String> = Collections.synchronizedMap(WeakHashMap())

    /** 已锁定的入口按钮实例（弱引用）：它们的 void 方法会被整体屏蔽 */
    private val lockedButtons: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** 从 QQ 递过来的胶囊上学到的文案指纹：云控改文案后仍能认出后续新实例 */
    private val entryMarks: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override val defaultConfig = PublicRecommendConfig()

    private val entryButtonClass: Class<*>? by lazy { runCatching { ENTRY_BUTTON_CLASS.clazz }.getOrNull() }

    override fun onInit(): Boolean {

        if (!HostInfo.isQQ) return false

        initEntryBtn = methodOrNull("initEntryBtn")
        applyAvatarStyle = methodOrNull("applyAvatarStyle")
        applyTipsStyle = methodOrNull("applyTipsStyle")
        showGuideBubble = methodOrNull("showGuideBubble")
        capsuleShow = runCatching { requireMethod("capsuleShow") }.getOrNull()

        if (initEntryBtn == null) return false

        return super.onInit()
    }

    override fun onHook() {

        // ma() 跑完（QQ 把入口实例写进 TitlePart 字段）后按实例锁定
        initEntryBtn?.hookAfter(this) { param ->
            if (hidden(PublicRecommendConfig.HIDE_ENTRY)) lockEntryButton(param.thisObject)
        }

        // 观察者 lambda：TIPS/NONE 分支自己会 l0() 显示入口，必须整体跳过
        observerLambda()?.findMethods { name = "invoke" }?.forEach { method ->
            method.hookReplace(this) { chain ->
                if (hidden(PublicRecommendConfig.HIDE_ENTRY)) Unit else chain.proceed()
            }
        }

        // 锁定实例的所有 void 方法屏蔽（l0/q0/r0/h0/bindData…），只作用于锁定实例，不影响通知/设置。
        // 静态方法（如布局回调调的 g0(btn, …)）没有 thisObject，要改用第 1 个参数取实例。
        entryButtonClass
            ?.findMethods { returnType = void }
            ?.forEach { method ->
                if (Modifier.isStatic(method.modifiers)) {
                    method.hookReplace(this) { chain ->
                        val view = chain.args.firstOrNull() as? View
                        if (view != null && view in lockedButtons) null else chain.proceed()
                    }
                } else {
                    method.hookReplace(this) { chain ->
                        val view = chain.thisObject as? View
                        if (view != null && view in lockedButtons) null else chain.proceed()
                    }
                }
            }

        // QQ 会把入口控件当参数递给这三个只跟空友爱看有关的方法，这里补一次锁定
        applyAvatarStyle?.hookReplace(this) { chain ->
            lockPillInArgs(chain.args)
            if (hidden(PublicRecommendConfig.HIDE_ENTRY)) null else chain.proceed()
        }

        applyTipsStyle?.hookReplace(this) { chain ->
            lockPillInArgs(chain.args)
            if (hidden(PublicRecommendConfig.HIDE_TIPS_BUBBLE)) null else chain.proceed()
        }

        showGuideBubble?.hookReplace(this) { chain ->
            lockPillInArgs(chain.args)
            if (hidden(PublicRecommendConfig.HIDE_GUIDE_BUBBLE)) null else chain.proceed()
        }

        // 新建实例会先 bindData()，此时文案/无障碍描述已是云控文案 → 用它（或学到的指纹）兜底识别
        entryButtonClass
            ?.findMethods { name = "bindData" }
            ?.forEach { method ->
                method.hookAfter(this) { param ->
                    if (hidden(PublicRecommendConfig.HIDE_ENTRY)) matchPillByText(param.thisObject)
                }
            }

        // 下滑才出现的粘性胶囊：文案为空（只剩图标）＝空友爱看入口态。
        // QQ 在同一次滚动处理里先写状态再 setVisibility(VISIBLE)，所以状态写入时压一次、
        // 滚动处理跑完后（hookAfter）再压一次。
        val capsuleClass = capsuleViewClass()
        capsuleClass
            ?.findMethods { name = "setNormalText" }
            ?.forEach { method ->
                method.hookAfter(this) { param ->
                    if (!hidden(PublicRecommendConfig.HIDE_ENTRY)) return@hookAfter
                    val capsule = param.thisObject as? View ?: return@hookAfter
                    val text = param.args.firstOrNull()?.toString().orEmpty()
                    capsuleTexts[capsule] = text
                    applyCapsuleVisibility(capsule, text)
                }
            }

        capsuleShow?.hookAfter(this) {
            if (hidden(PublicRecommendConfig.HIDE_ENTRY)) hideEntryCapsules()
        }
    }

    private fun hidden(key: String) = key in config.hidden

    private fun methodOrNull(name: String): Method? = runCatching { requireMethod(name) }.getOrNull()

    private fun capsuleViewClass(): Class<*>? =
        runCatching { ClassUtils.loadClassOrNull(CAPSULE_VIEW_CLASS) }.getOrNull()

    /** 只有图标（文案为空）或文案带"爱看"＝空友爱看入口态；正常态的「展开好友动态」不动 */
    private fun isEntryCapsule(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        return t.isEmpty() || fallbackMarks.any { t.contains(it, ignoreCase = true) }
    }

    private fun applyCapsuleVisibility(capsule: View, text: String) {
        runCatching {
            if (isEntryCapsule(text)) {
                capsule.visibility = View.GONE
            } else if (capsule.visibility == View.GONE) {
                capsule.visibility = View.VISIBLE
            }
        }.onFailure { LogUtils.e(this, it) }
    }

    private fun hideEntryCapsules() {
        runCatching {
            capsuleTexts.forEach { (capsule, text) ->
                if (isEntryCapsule(text)) capsule.visibility = View.GONE
            }
        }.onFailure { LogUtils.e(this, it) }
    }

    /** 观察者 lambda：按类名加载（没有 InnerClasses 元数据，declaredClasses 拿不到） */
    private fun observerLambda(): Class<*>? = runCatching {
        ClassUtils.loadClassOrNull(OBSERVER_LAMBDA_CLASS)
    }.getOrNull()

    /** 按字段类型从 `TitlePart` 实例上取 QQ 赋的入口按钮，锁定并隐藏 */
    private fun lockEntryButton(part: Any?) {
        val buttonClass = entryButtonClass ?: return
        runCatching {
            val button = part?.getObjectByTypeOrNull(buttonClass, null) as? View ?: return
            rememberMarks(button)
            lockView(button)
        }.onFailure { LogUtils.e(this, it) }
    }

    /** QQ 把入口控件当参数传进来时（Z9/aa/Q9），顺手锁定 */
    private fun lockPillInArgs(args: Array<Any?>) {
        if (!hidden(PublicRecommendConfig.HIDE_ENTRY)) return
        val buttonClass = entryButtonClass ?: return
        args.forEach { arg ->
            val view = arg as? View ?: return@forEach
            if (buttonClass.isInstance(view)) lockView(view)
        }
    }

    /** bindData 之后：按文案/无障碍描述确认是不是空友爱看入口 */
    private fun matchPillByText(target: Any?) {
        val view = target as? View ?: return
        if (view in lockedButtons) return

        val texts = mutableListOf<String>()
        view.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
        collectTexts(view, texts)
        if (texts.isEmpty()) return

        val hit = texts.any { text ->
            text in entryMarks || fallbackMarks.any { mark -> text.contains(mark, ignoreCase = true) }
        }
        if (hit) lockView(view)
    }

    private fun rememberMarks(view: View) {
        view.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { entryMarks.add(it) }
        val texts = mutableListOf<String>()
        collectTexts(view, texts)
        entryMarks.addAll(texts)
    }

    private fun collectTexts(view: View, out: MutableList<String>) {
        if (view is TextView) {
            view.text?.toString()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectTexts(view.getChildAt(i), out)
        }
    }

    /** 置 GONE + 从父容器摘掉（QQ 只改子控件，摘掉后任何路径都显示不出来）+ layout 自愈 */
    private fun lockView(view: View) {
        if (!lockedButtons.add(view)) return
        runCatching {
            view.visibility = View.GONE
            (view.parent as? ViewGroup)?.removeView(view)
            view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.visibility != View.GONE) v.visibility = View.GONE
            }
        }.onFailure { LogUtils.e(this, it) }
    }

    @Composable
    override fun ConfigContent(onDismiss: () -> Unit) =
        PublicRecommendPage(config, ::updateConfig, onDismiss)

    override fun getQueryMap(): Map<String, BaseMatcher> = mapOf(
        "initEntryBtn" to FindMethod().apply {
            matcher {
                usingStrings("[initPublicRecommendBtn] restoringScroll=")
            }
        },
        "applyAvatarStyle" to FindMethod().apply {
            matcher {
                usingStrings("[applyAvatarStyleIfAllowed] suppress avatar by guide bubble")
            }
        },
        "applyTipsStyle" to FindMethod().apply {
            matcher {
                usingStrings("[applyTipsStyleIfAllowed] suppress tips by guide bubble")
            }
        },
        "showGuideBubble" to FindMethod().apply {
            matcher {
                usingStrings("showPublicRecommendBtnBubbleIfNeeded show, showGuide=")
            }
        },
        // 粘性胶囊的滚动处理（内部会 `[showStickyAndHideOriginal]` 把胶囊显示出来）
        "capsuleShow" to FindMethod().apply {
            matcher {
                usingStrings("[showStickyAndHideOriginal]")
            }
        }
    )
}
