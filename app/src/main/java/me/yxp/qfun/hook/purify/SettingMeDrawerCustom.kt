package me.yxp.qfun.hook.purify

import androidx.compose.runtime.Composable
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.conf.DrawerConfig
import me.yxp.qfun.hook.base.BaseClickableHookItem
import me.yxp.qfun.ui.pages.configs.SettingMeDrawerPage
import me.yxp.qfun.utils.hook.hookAfter
import me.yxp.qfun.utils.qq.HostInfo
import me.yxp.qfun.utils.reflect.clazz
import me.yxp.qfun.utils.reflect.findMethod
import me.yxp.qfun.utils.reflect.findMethods
import java.lang.reflect.Method

@HookItemAnnotation(
    "侧边栏精简",
    "隐藏主页侧边栏中的 相册 / 收藏 / 文件 / 钱包 / 会员中心 / 个性装扮 / 免流量 入口",
    HookCategory.PURIFY
)
object SettingMeDrawerCustom : BaseClickableHookItem<DrawerConfig>(DrawerConfig.serializer()) {

    override val isNeedRestart: Boolean = true

    override val defaultConfig = DrawerConfig()

    /**
     * QQSettingMe 的条目隐藏谓词。返回 true 时 QQ 会在把列表交给 adapter 之前
     * 自己 iterator.remove() 掉该条目，因此被隐藏的条目根本不会创建 View、不参与测量，
     * 滑动时不会被额外布局拖慢。
     */
    private const val HIDE_PREDICATE = "com.tencent.mobileqq.activity.qqsettingme.utils.a"

    /** 侧边栏条目 bean，条目 id 形如 d_album / d_vip_card（免流量） */
    private const val BIZ_BEAN = "com.tencent.mobileqq.activity.qqsettingme.config.QQSettingMeBizBean"

    private val idPattern = Regex("^d_[a-z0-9_]+$")

    private lateinit var isItemHidden: Method
    private var idGetters: List<Method> = emptyList()
    private var cachedIdGetter: Method? = null

    override fun onInit(): Boolean {

        if (!HostInfo.isQQ) return false

        val bean = BIZ_BEAN.clazz ?: return false

        isItemHidden = HIDE_PREDICATE.clazz?.findMethod {
            returnType = boolean
            paramTypes(bean)
        } ?: return false

        // getter 名是混淆的，按"无参 + 返回 String"收集，运行时靠 d_ 前缀认出条目 id
        idGetters = bean.findMethods {
            returnType = string
            paramCount = 0
        }

        return super.onInit()
    }

    override fun onHook() {
        isItemHidden.hookAfter(this) { param ->
            val id = beanId(param.args[0]) ?: return@hookAfter
            if (id in config.hiddenIds) param.result = true
        }
    }

    /** 首次命中后记住是哪个 getter，之后每个条目只花一次反射调用 */
    private fun beanId(bean: Any?): String? {
        if (bean == null) return null

        cachedIdGetter?.let { getter ->
            val value = runCatching { getter.invoke(bean) as? String }.getOrNull()
            if (value != null && idPattern.matches(value)) return value
        }

        idGetters.forEach { getter ->
            val value = runCatching { getter.invoke(bean) as? String }.getOrNull()
            if (value != null && idPattern.matches(value)) {
                cachedIdGetter = getter
                return value
            }
        }

        return null
    }

    @Composable
    override fun ConfigContent(onDismiss: () -> Unit) {
        SettingMeDrawerPage(config, ::updateConfig, onDismiss)
    }

}
