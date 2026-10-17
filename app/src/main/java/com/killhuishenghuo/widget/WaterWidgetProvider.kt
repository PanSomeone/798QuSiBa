package com.killhuishenghuo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.killhuishenghuo.KillHuiShengHuoApp
import com.killhuishenghuo.MainActivity
import com.killhuishenghuo.R
import com.killhuishenghuo.core.log.AppLog
import com.killhuishenghuo.data.model.AppError
import com.killhuishenghuo.data.model.Device
import com.killhuishenghuo.data.model.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面上的那颗开关。
 *
 * **1. 它不许自己编「没登录」。** 最早一版把状态存在小组件自己的 SharedPreferences 里,
 * 只有「按下按钮」时才更新 —— 缓存里的默认值是 `needLogin = true`,于是刚把小组件加到
 * 桌面上时,明明登录着,卡片上写着「还没有登录」。用户看到的正是这个。现在的规则:
 * 每次要画之前先问应用本人(session + 设备列表),缓存只在网络不通时兜底。
 *
 * **2. 它就是个开关,所以尺寸跟着格子走。** 1x1 和 1x2 只有一个按钮,上面一个字;
 * 2x1 是同样一个按钮,字长一点;2x2 才有设备名和状态行。尺寸不是猜的,是
 * `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT` 报的,用户拖动改大改小时 `onAppWidgetOptionsChanged`
 * 会重新画。
 *
 * **3. 网络只在广播里做,而且用 `goAsync()` 续命。** 一次开阀是两次请求(master 找设备
 * + dev/start),主线程不能做,而进程可能在广播返回后被回收。
 *
 * **4. 失败要说出来。** 桌面上最糟的状态不是「失败了」,而是「看起来成功了但水没出来」。
 */
class WaterWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> runAsync(context) { toggle(it) }

            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                // 先让框架照常分发(它会画出缓存的那一份),再用一次真实请求覆盖它。
                // 加组件、重启、以及系统那 30 分钟一次的刷新都走这里,频率低到可以接受。
                super.onReceive(context, intent)
                runAsync(context) { refresh(it) }
            }

            else -> super.onReceive(context, intent)
        }
    }

    /** 广播里做网络:主线程什么都不碰,`pendingResult` 负责让进程活到任务结束。 */
    private fun runAsync(context: Context, work: suspend (Context) -> Unit) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                work(app)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val snapshot = WidgetSnapshot.read(context)
        for (id in appWidgetIds) paint(context, appWidgetManager, id, snapshot)
    }

    /** 用户把组件拖大或拖小了。 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        paint(context, appWidgetManager, appWidgetId, WidgetSnapshot.read(context))
    }

    /** 问一次真状态,然后画上去。这是修掉「明明登录着却说没登录」的那一步。 */
    private suspend fun refresh(context: Context) {
        val app = context.applicationContext as KillHuiShengHuoApp

        val snapshot = if (app.session.token.isNullOrBlank()) {
            WidgetSnapshot.needLogin()
        } else {
            when (val outcome = app.repository.loadDevices()) {
                is Outcome.Success -> snapshotOf(app, outcome.value)
                // 网络不通:留着上一次的真相,别把它改成「没登录」。
                is Outcome.Failure -> WidgetSnapshot.read(context)
            }
        }

        AppLog.log("Widget", "refresh needLogin=${snapshot.needLogin} state=${snapshot.state}")
        publish(context, snapshot)
    }

    /** 读一次设备列表,对那台设备做相反的动作,然后把结果写回桌面。 */
    private suspend fun toggle(context: Context) {
        val app = context.applicationContext as KillHuiShengHuoApp

        if (app.session.token.isNullOrBlank()) {
            publish(context, WidgetSnapshot.needLogin())
            return
        }

        val devices = when (val outcome = app.repository.loadDevices()) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> {
                publish(context, WidgetSnapshot.failed(outcome.error, app.preferences.selectedDeviceId))
                return
            }
        }

        val device = pickDevice(app, devices)
        if (device == null) {
            publish(context, WidgetSnapshot.noDevice())
            return
        }

        AppLog.log(
            "Widget",
            "toggle device=${device.deviceId} isRunning=${device.isRunning} status=${device.status}",
        )

        val outcome = if (device.isRunning) {
            app.repository.closeHotWater(device)
        } else {
            app.repository.openHotWater(device)
        }

        publish(
            context,
            when (outcome) {
                is Outcome.Success -> WidgetSnapshot(
                    deviceName = device.displayName,
                    state = if (device.isRunning) "热水已关" else "热水已开",
                    running = !device.isRunning,
                    needLogin = false,
                )

                is Outcome.Failure -> WidgetSnapshot.failed(outcome.error, device.deviceId)
                    .copy(deviceName = device.displayName)
            },
        )
    }

    /**
     * 这个账号下的那一台。
     *
     * 先按记住的 id 找,找不到退回第一台 —— 只有一台热水器,而记住的 id 可能来自
     * 一个已经被解绑的设备。
     */
    private fun pickDevice(app: KillHuiShengHuoApp, devices: List<Device>): Device? =
        devices.firstOrNull { it.deviceId == app.preferences.selectedDeviceId }
            ?: devices.firstOrNull()

    private fun snapshotOf(app: KillHuiShengHuoApp, devices: List<Device>): WidgetSnapshot {
        val device = pickDevice(app, devices) ?: return WidgetSnapshot.noDevice()
        return WidgetSnapshot(
            deviceName = device.displayName,
            state = if (device.isRunning) "热水已开" else "热水已关",
            running = device.isRunning,
            needLogin = false,
        )
    }

    private fun publish(context: Context, snapshot: WidgetSnapshot) {
        snapshot.write(context)

        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, WaterWidgetProvider::class.java))
        for (id in ids) paint(context, manager, id, snapshot)
    }

    private fun paint(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        snapshot: WidgetSnapshot,
    ) {
        manager.updateAppWidget(id, widgetViews(context, snapshot, sizeOf(manager, id)))
    }

    companion object {
        const val ACTION_TOGGLE = "com.killhuishenghuo.widget.TOGGLE"

        /**
         * 这个组件现在是几个格子。
         *
         * 阈值来自真机:`OPTION_APPWIDGET_MIN_HEIGHT` 在一行时是 40–70dp,两行时
         * 110–180dp;宽度在一列时 40–70dp,两列 110–180dp。所以 110 是「一格」和
         * 「两格」之间那条线。**它们只是判断,不是换算** —— 真正的排版交给布局。
         */
        fun sizeOf(manager: AppWidgetManager, id: Int): WidgetSize {
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            return widgetSizeOf(width, height)
        }
    }
}

/** 小组件能变成的三副样子。 */
enum class WidgetSize { TINY, WIDE, FULL }

/**
 * 从格子尺寸挑布局。
 *
 * 抽成一个没有 Android 依赖的纯函数,是为了能在 JVM 测试里把边界钉住 —— 这个判断
 * 错了不会崩,只会让 1x1 显示成一张塞满字的卡片。
 */
fun widgetSizeOf(minWidthDp: Int, minHeightDp: Int): WidgetSize = when {
    minWidthDp < 110 -> WidgetSize.TINY
    minHeightDp < 110 -> WidgetSize.WIDE
    else -> WidgetSize.FULL
}

/**
 * 把一份快照画成 RemoteViews。
 *
 * **它是顶格函数而不是 provider 的私有方法,唯一原因是测试。** 第一版把它留在 provider
 * 里,于是仪器测试只能在测试文件里**复刻**这一串 set 调用 —— 那份复制漏了 `needLogin`
 * 分支,测试因此报了一个假失败。复制出来的测试测的是复制品。
 *
 * 只有三种操作:setText / setBackgroundResource / setOnClickPendingIntent。RemoteViews
 * 认识的也就这些。
 */
internal fun widgetViews(context: Context, snapshot: WidgetSnapshot, size: WidgetSize): RemoteViews =
    if (size == WidgetSize.FULL) fullViews(context, snapshot) else switchViews(context, snapshot, size == WidgetSize.TINY)

/** 2x2 及以上:设备名、状态、一颗按钮。 */
private fun fullViews(context: Context, snapshot: WidgetSnapshot): RemoteViews =
    RemoteViews(context.packageName, R.layout.widget_water).apply {
        setTextViewText(R.id.widget_title, "惠生活 去死吧")
        setTextViewText(R.id.widget_pun, "798 748")
        setTextViewText(R.id.widget_device, snapshot.deviceName)

        setTextViewText(R.id.widget_state, snapshot.state)
        setTextColor(R.id.widget_state, if (snapshot.running) WIDGET_COLOR_WATER else WIDGET_COLOR_MUTED)

        setTextViewText(
            R.id.widget_button,
            when {
                snapshot.needLogin -> "去登录"
                snapshot.running -> "关闭热水"
                else -> "一键启动热水"
            },
        )
        setInt(
            R.id.widget_button,
            "setBackgroundResource",
            if (snapshot.running) R.drawable.widget_btn_on else R.drawable.widget_btn_off,
        )
        setTextColor(
            R.id.widget_button,
            if (snapshot.running) WIDGET_COLOR_ON_WATER else WIDGET_COLOR_ON_HEATED,
        )

        // 没登录时按钮打开应用(去登录),否则才是开阀。
        setOnClickPendingIntent(
            R.id.widget_button,
            if (snapshot.needLogin) widgetOpenApp(context) else widgetToggle(context),
        )
        // 整张卡也可以点,进应用;这一条不是必需的,但它让误触点不到「没反应」。
        setOnClickPendingIntent(R.id.widget_root, widgetOpenApp(context))
    }

/**
 * 1x1 / 2x1:整个组件就是一个按钮。
 *
 * 没有设备名、没有状态行、没有标题 —— 因为「它就是那一下」。状态用颜色和那一个字
 * 说出来:橙色 + 开 = 关着,水蓝 + 关 = 流着。
 */
private fun switchViews(context: Context, snapshot: WidgetSnapshot, tiny: Boolean): RemoteViews =
    RemoteViews(context.packageName, R.layout.widget_water_switch).apply {
        setTextViewText(
            R.id.widget_switch,
            when {
                snapshot.needLogin -> "登录"
                snapshot.running -> if (tiny) "关" else "关热水"
                else -> if (tiny) "开" else "开热水"
            },
        )
        setInt(
            R.id.widget_switch,
            "setBackgroundResource",
            if (snapshot.running) R.drawable.widget_btn_on else R.drawable.widget_btn_off,
        )
        setTextColor(
            R.id.widget_switch,
            if (snapshot.running) WIDGET_COLOR_ON_WATER else WIDGET_COLOR_ON_HEATED,
        )
        setOnClickPendingIntent(
            R.id.widget_switch,
            if (snapshot.needLogin) widgetOpenApp(context) else widgetToggle(context),
        )
    }

private fun widgetOpenApp(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(
        context,
        WIDGET_REQUEST_OPEN,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun widgetToggle(context: Context): PendingIntent {
    val intent = Intent(context, WaterWidgetProvider::class.java)
        .setAction(WaterWidgetProvider.ACTION_TOGGLE)
    return PendingIntent.getBroadcast(
        context,
        WIDGET_REQUEST_TOGGLE,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private const val WIDGET_REQUEST_OPEN = 1
private const val WIDGET_REQUEST_TOGGLE = 2

// 和主界面同一套颜色,写死在这里而不是引用 Compose 的调色板 ——
// RemoteViews 只吃资源 id 和字面值。
private const val WIDGET_COLOR_WATER = 0xFF4CC2F1.toInt()
private const val WIDGET_COLOR_MUTED = 0xFF96A2B5.toInt()
private const val WIDGET_COLOR_ON_WATER = 0xFF04202C.toInt()
private const val WIDGET_COLOR_ON_HEATED = 0xFF1A0A02.toInt()

/** 一次请求之后要贴在桌面上的四样东西。 */
data class WidgetSnapshot(
    val deviceName: String,
    val state: String,
    val running: Boolean,
    val needLogin: Boolean,
) {

    fun write(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DEVICE, deviceName)
            .putString(KEY_STATE, state)
            .putBoolean(KEY_RUNNING, running)
            .putBoolean(KEY_NEED_LOGIN, needLogin)
            .apply()
    }

    companion object {
        private const val PREFS = "widget"
        private const val KEY_DEVICE = "device"
        private const val KEY_STATE = "state"
        private const val KEY_RUNNING = "running"
        private const val KEY_NEED_LOGIN = "needLogin"

        /**
         * 要从缓存里读,但**登录与否以应用为准**。
         *
         * 这就是那个 bug 的修法。缓存里的默认值是 `needLogin = true`,于是刚加到桌面上
         * 的组件会理直气壮地说「还没有登录」,而人明明是登录着的。缓存可以过期,而
         * `SessionStore` 不会:它才是「登录了没有」的答案。
         */
        fun read(context: Context): WidgetSnapshot {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cached = WidgetSnapshot(
                deviceName = prefs.getString(KEY_DEVICE, null).orEmpty(),
                state = prefs.getString(KEY_STATE, null).orEmpty(),
                running = prefs.getBoolean(KEY_RUNNING, false),
                needLogin = prefs.getBoolean(KEY_NEED_LOGIN, true),
            )

            val signedIn = !sessionToken(context).isNullOrBlank()

            return resolve(cached, signedIn)
        }

        /**
         * 缓存 + 会话 → 该画什么。
         *
         * 单独一个纯函数,因为这里正是那个 bug 的现场:缓存说「没登录」而人会话说
         * 「登录了」,该信会话。抽出来之后它可以被 JVM 测试直接钉住,不需要一台设备。
         */
        fun resolve(cached: WidgetSnapshot, signedIn: Boolean): WidgetSnapshot = when {
            !signedIn -> needLogin()
            // 会话在、缓存却说没登录:那是缓存过期,不是状态。
            cached.needLogin || cached.deviceName.isBlank() -> WidgetSnapshot(
                deviceName = cached.deviceName.ifBlank { SIGNED_IN_NAME },
                state = "点一下看看",
                running = false,
                needLogin = false,
            )
            else -> cached
        }

        /**
         * 会话里的 token,拿不到就是 null。
         *
         * `as?` 而不是 `as`:仪器测试和别的入口也可能拿一个不是本应用 Application 的
         * Context 过来,而那种情况下答案不是「崩」,是「没有会话」。
         */
        private fun sessionToken(context: Context): String? =
            (context.applicationContext as? KillHuiShengHuoApp)?.session?.token

        fun needLogin() = WidgetSnapshot(
            deviceName = "还没登录",
            state = "点一下去登录",
            running = false,
            needLogin = true,
        )

        fun noDevice() = WidgetSnapshot(
            deviceName = "还没有设备",
            state = "扫一下机器上的二维码",
            running = false,
            needLogin = false,
        )

        /**
         * 失败时把原因写在桌面上。
         *
         * 会话失效要额外记一笔「需要登录」:否则那张卡会一直停在别的状态上,而用户
         * 按下去只会再失败一次。
         */
        fun failed(error: AppError, deviceId: String?): WidgetSnapshot = WidgetSnapshot(
            deviceName = deviceId ?: "这台设备",
            state = error.toChinese(),
            running = false,
            needLogin = error is AppError.Unauthorized,
        )

        /** 已登录但还没有读到设备名时,卡片上写这个。 */
        const val SIGNED_IN_NAME = "已登录"
    }
}
