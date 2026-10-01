package me.yxp.qfun.conf

import kotlinx.serialization.Serializable

/**
 * 「空友爱看」净化配置：[hidden] 里的 key = 要隐藏的目标，配置页与 Hook 共用。
 */
@Serializable
data class PublicRecommendConfig(
    val hidden: Set<String> = setOf(HIDE_ENTRY, HIDE_TIPS_BUBBLE, HIDE_GUIDE_BUBBLE)
) {
    companion object {
        const val HIDE_ENTRY = "hide_entry"
        const val HIDE_TIPS_BUBBLE = "hide_tips_bubble"
        const val HIDE_GUIDE_BUBBLE = "hide_guide_bubble"
    }
}
