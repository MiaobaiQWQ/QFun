package me.yxp.qfun.hook.purify

import androidx.compose.runtime.Composable
import com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.conf.MenuConfig
import me.yxp.qfun.hook.base.BaseClickableHookItem
import me.yxp.qfun.ui.pages.configs.MessageMenuPage
import me.yxp.qfun.utils.hook.hookBefore
import me.yxp.qfun.utils.qq.HostInfo
import me.yxp.qfun.utils.reflect.clazz
import me.yxp.qfun.utils.reflect.findMethod
import me.yxp.qfun.utils.reflect.findMethods
import me.yxp.qfun.utils.reflect.getObjectByTypeOrNull
import java.lang.reflect.Method

@HookItemAnnotation(
    "长按菜单精简",
    "自定义长按消息菜单显示哪些项，可逐项关闭 复制 / 引用 / 转发 / 添加表情 / 查找相关表情 等",
    HookCategory.PURIFY
)
object MessageMenuCustom : BaseClickableHookItem<MenuConfig>(MenuConfig.serializer()) {

    override val defaultConfig = MenuConfig()

    /**
     * 长按菜单项的基类。条目标签（CopyMenuItem 这类）由它的抽象方法返回，
     * 显示文字则存在子类的 String 字段里（构造函数用 context.getString 赋值）。
     */
    private const val MENU_ITEM_BASE = "com.tencent.qqnt.aio.menu.ui.e"

    private val keyPattern = Regex("^[A-Za-z][A-Za-z0-9_]*MenuItem$")

    private var keyGetters: List<Method> = emptyList()

    override fun onInit(): Boolean {

        if (!HostInfo.isQQ) return false

        val base = MENU_ITEM_BASE.clazz ?: return false

        // 方法名是混淆的，按"无参 + 返回 String"收集，运行时用 XxxMenuItem 形态筛出标签
        keyGetters = base.findMethods {
            returnType = string
            paramCount = 0
        }

        return super.onInit()
    }

    override fun onHook() {
        QQCustomMenuExpandableLayout::class.java
            .findMethod { name = "setMenu" }
            .hookBefore(this) { param ->
                if (config.hiddenKeys.isEmpty()) return@hookBefore

                val customMenu = param.args[0] ?: return@hookBefore
                val items = customMenu
                    .getObjectByTypeOrNull<MutableList<Any>>(customMenu.javaClass.superclass)
                if (items.isNullOrEmpty()) return@hookBefore

                items.removeAll { isHidden(it) }
            }
    }

    /** 标签命中（d_/XxxMenuItem 这类 id）或显示文字命中都算隐藏 */
    private fun isHidden(item: Any): Boolean {
        val text = runCatching { item.getObjectByTypeOrNull<String>() }.getOrNull()
        if (text != null && text in config.hiddenKeys) return true

        return keyGetters.any { getter ->
            val key = runCatching { getter.invoke(item) as? String }.getOrNull()
            key != null && keyPattern.matches(key) && key in config.hiddenKeys
        }
    }

    @Composable
    override fun ConfigContent(onDismiss: () -> Unit) {
        MessageMenuPage(config, ::updateConfig, onDismiss)
    }

}
