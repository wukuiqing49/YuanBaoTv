package com.wkq.bao.feature.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiTvPlaybackEntryTest {
    @Test fun actualEntryMatchesFirmwareCaseSensitiveVideoDetection() {
        // 对照本机小爱 UtilsMaster 的 contains 判断，防止看起来相同的大小写改动再次失效。
        val name = com.wkq.bao.feature.app.mitv.MiTvPlayerActivity::class.java.name
        assertTrue(name.startsWith("com.wkq.bao."))
        assertTrue(name.contains("Player") && name.contains("Activity"))
        assertTrue(name.contains("mitv") || name.contains("miui"))
    }

    @Test fun originalMixedCaseEntryDoesNotMatchFirmwareMarker() {
        val name = MitvPlayerActivity::class.java.name
        assertFalse(name.contains("mitv") || name.contains("miui"))
    }
}
