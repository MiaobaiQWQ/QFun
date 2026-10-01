package me.yxp.qfun.hook.purify

import android.os.SystemClock
import me.yxp.qfun.annotation.HookCategory
import me.yxp.qfun.annotation.HookItemAnnotation
import me.yxp.qfun.hook.base.BaseSwitchHookItem
import me.yxp.qfun.utils.hook.hookAfter
import me.yxp.qfun.utils.log.LogUtils
import me.yxp.qfun.utils.qq.HostInfo
import me.yxp.qfun.utils.qq.QQCurrentEnv
import me.yxp.qfun.utils.reflect.callStaticMethod
import me.yxp.qfun.utils.reflect.clazz
import me.yxp.qfun.utils.reflect.findMethodOrNull
import me.yxp.qfun.utils.reflect.toClass
import java.lang.reflect.Method

/**
 * 点「动态」tab 直达空间动态（好友动态）。
 *
 * 不动 QQ 的 tab/frame 装配（`needShowQzoneFrame` 只在老年模式为真，强行改配置真机是空白页/个人主页），
 * 只监听 tab 点击回调，命中「动态」时用 QZone 自己的 scheme 打开好友动态页；
 * 代价是它以独立页面形式呈现，乐吧 tab 本身不变。
 */
@HookItemAnnotation(
    "动态Tab直达空间动态",
    "点击底部「动态」直接打开空间动态（好友动态）页面，跳过乐吧的农场/小游戏/游戏中心等入口页",
    HookCategory.PURIFY
)
object LebaTabToQzone : BaseSwitchHookItem() {

    /** QQ 拿 frame 类名当 tab key，这两个即「乐吧」与「空间动态」 */
    private const val LEBA_FRAME_KEY = "com.tencent.mobileqq.leba.Leba"
    private const val QZONE_FRAME_KEY = "com.tencent.mobileqq.activity.leba.QzoneFrame"

    private const val CLICK_EVENT_CLASS = "com.tencent.mobileqq.activity.framebusiness.LebaInjectImpl"
    private const val QZONE_ROUTE_API_CLASS = "com.tencent.qzonehub.api.IQZoneRouteApi"
    private const val QROUTE_CLASS = "com.tencent.mobileqq.qroute.QRoute"

    /** QZone 的好友动态地址（active feed），交给 QZone 自己的 scheme 路由 */
    private const val FRIEND_FEED_SCHEME = "mqzone://arouse/activefeed"

    /** 同一点击可能连着触发几次，去抖避免连开两页 */
    private const val LAUNCH_DEBOUNCE_MS = 800L

    private var tabClickEvent: Method? = null
    private var routeApi: Any? = null
    private var launchMethod: Method? = null
    private var lastLaunchAt = 0L

    override fun onInit(): Boolean {

        if (!HostInfo.isQQ) return false

        tabClickEvent = runCatching {
            CLICK_EVENT_CLASS.clazz?.findMethodOrNull {
                name = "onTabClickEvent"
                paramCount = 1
                paramTypes(string)
                returnType = void
            }
        }.getOrNull()

        if (tabClickEvent == null) {
            return false
        }

        // QRoute.api(IQZoneRouteApi) → launchQZoneScheme(Context, String)
        runCatching {
            val apiClass = QZONE_ROUTE_API_CLASS.clazz ?: return@runCatching
            routeApi = QROUTE_CLASS.toClass.callStaticMethod("api", apiClass)
            launchMethod = routeApi
                ?.javaClass
                ?.methods
                ?.firstOrNull {
                    it.name == "launchQZoneScheme" &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[1] == String::class.java
                }
                ?.apply { isAccessible = true }
        }

        return super.onInit()
    }

    override fun onHook() {
        tabClickEvent?.hookAfter(this) { param ->
            val key = param.args.firstOrNull() as? String
            if (key == LEBA_FRAME_KEY || key == QZONE_FRAME_KEY) launchFriendFeed()
        }
    }

    private fun launchFriendFeed() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAt < LAUNCH_DEBOUNCE_MS) return
        lastLaunchAt = now

        runCatching {
            val context = QQCurrentEnv.activity ?: HostInfo.hostContext
            val api = routeApi ?: return
            val method = launchMethod ?: return
            method.invoke(api, context, FRIEND_FEED_SCHEME)
        }.onFailure { LogUtils.e(this, it) }
    }
}
