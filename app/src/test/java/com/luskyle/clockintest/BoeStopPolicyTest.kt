package com.luskyle.clockintest

import org.junit.Assert.*
import org.junit.Test

class BoeStopPolicyTest {
    @Test fun onlyExactBoeAppDetailsMayBeStopped() {
        assertTrue(BoeStopPolicy.isBoeDetails("com.android.settings", "BOE移动门户"))
        assertFalse(BoeStopPolicy.isBoeDetails("another.app", "BOE移动门户"))
        assertFalse(BoeStopPolicy.isBoeDetails("com.android.settings", "永不迟到"))
        assertFalse(BoeStopPolicy.isBoeDetails("com.android.settings", ""))
    }

    @Test fun onlyConfirmAfterRequestAndOnlyExactSystemForceStopDialog() {
        assertTrue(BoeStopPolicy.isConfirmation(BoeStopPolicy.SETTINGS, BoeStopPolicy.MESSAGE, "强行停止", true))
        assertFalse(BoeStopPolicy.isConfirmation(BoeStopPolicy.SETTINGS, BoeStopPolicy.MESSAGE, "强行停止", false))
        assertFalse(BoeStopPolicy.isConfirmation("another.app", BoeStopPolicy.MESSAGE, "强行停止", true))
        assertFalse(BoeStopPolicy.isConfirmation(BoeStopPolicy.SETTINGS, "删除所有数据？", "强行停止", true))
        assertFalse(BoeStopPolicy.isConfirmation(BoeStopPolicy.SETTINGS, BoeStopPolicy.MESSAGE, "卸载", true))
    }
}
