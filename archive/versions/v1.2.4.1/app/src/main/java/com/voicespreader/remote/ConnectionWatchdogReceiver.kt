package com.voicespreader.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 在部分国产 ROM 回收应用进程后，利用系统闹钟重新拉起连接前台服务。
 * 用户主动断开时服务会清除 active 标记并取消闹钟，因此不会自行恢复。
 */
class ConnectionWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != MicrophoneStreamingService.ACTION_WATCHDOG
            && action != Intent.ACTION_BOOT_COMPLETED
            && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val preferences = context.getSharedPreferences(
            MicrophoneStreamingService.SERVICE_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        if (!preferences.getBoolean(MicrophoneStreamingService.KEY_ACTIVE, false)) return

        // 即使当前 ROM 暂时拒绝拉起前台服务，也要保留下一次重试机会。
        MicrophoneStreamingService.scheduleWatchdog(context)
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MicrophoneStreamingService::class.java)
                    .setAction(MicrophoneStreamingService.ACTION_WATCHDOG),
            )
        }
    }
}
