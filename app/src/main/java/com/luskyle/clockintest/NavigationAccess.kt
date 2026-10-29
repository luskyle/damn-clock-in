package com.luskyle.clockintest

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

enum class NavigationAccess(val label: String, val issue: String?) {
    DISABLED("未授权或已被系统关闭", "无障碍服务未授权或已被系统关闭，请到运行设置手动开启"),
    DISCONNECTED("已授权，暂未连接", "无障碍已授权但暂未连接，请稍候；持续未连接请检查自启动和后台设置，必要时在系统设置重开服务"),
    CONNECTED("已连接", null);

    companion object {
        fun resolve(authorized: Boolean, connected: Boolean): NavigationAccess = when {
            !authorized -> DISABLED
            connected -> CONNECTED
            else -> DISCONNECTED
        }

        fun read(context: Context): NavigationAccess {
            val ownService = ComponentName(context, NavigationService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty().split(':').any { ComponentName.unflattenFromString(it) == ownService }
            return resolve(enabled, NavigationService.instance != null)
        }
    }
}
