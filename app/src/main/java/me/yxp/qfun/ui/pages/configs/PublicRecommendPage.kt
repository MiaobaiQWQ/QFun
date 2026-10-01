package me.yxp.qfun.ui.pages.configs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.yxp.qfun.conf.PublicRecommendConfig
import me.yxp.qfun.ui.components.listitems.PreferenceSection
import me.yxp.qfun.ui.components.listitems.SelectionGroup
import me.yxp.qfun.ui.components.listitems.SelectionItem
import me.yxp.qfun.ui.components.scaffold.ConfigPageScaffold

private val HIDE_SLOTS = listOf(
    Triple(
        PublicRecommendConfig.HIDE_ENTRY,
        "隐藏入口按钮",
        "好友动态标题栏上的「空友爱看」入口（4 个头像 + 红点）与下滑出现的粘性胶囊"
    ),
    Triple(
        PublicRecommendConfig.HIDE_TIPS_BUBBLE,
        "隐藏提示气泡",
        "进入好友动态时短暂弹出的推荐提示气泡"
    ),
    Triple(
        PublicRecommendConfig.HIDE_GUIDE_BUBBLE,
        "隐藏新手引导气泡",
        "首次进入时常驻的引导气泡（点过后不再出现）"
    )
)

@Composable
fun PublicRecommendPage(
    currentConfig: PublicRecommendConfig,
    onSave: (PublicRecommendConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var hidden by remember(currentConfig) { mutableStateOf(currentConfig.hidden) }

    fun toggle(key: String) {
        val newSet = hidden.toMutableSet()
        if (!newSet.remove(key)) newSet.add(key)
        hidden = newSet
    }

    ConfigPageScaffold(
        title = "空友爱看净化",
        configData = PublicRecommendConfig(hidden),
        onSave = onSave,
        onDismiss = onDismiss
    ) {
        PreferenceSection(title = "勾选 = 隐藏该项（不影响好友动态里的普通说说）") {
            SelectionGroup {
                HIDE_SLOTS.forEach { (key, label, desc) ->
                    SelectionItem(
                        title = label,
                        subtitle = desc,
                        isSelected = hidden.contains(key),
                        onClick = { toggle(key) }
                    )
                }
            }
        }
    }
}
