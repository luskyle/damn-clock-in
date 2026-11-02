package com.luskyle.clockintest

object BoeStopPolicy {
    const val SETTINGS = "com.android.settings"
    const val APP_NAME = "com.android.settings:id/app_name"
    const val STOP_BUTTON = "com.android.settings:id/middle_button"
    const val CONFIRM = "android:id/button1"
    const val MESSAGE = "强行停止某个应用可能会导致其出现异常。"

    fun isBoeDetails(packageName: String, appName: String): Boolean =
        packageName == SETTINGS && appName == "BOE移动门户"

    fun isConfirmation(packageName: String, message: String, button: String, requested: Boolean): Boolean =
        requested && packageName == SETTINGS && message == MESSAGE && button == "强行停止"
}
