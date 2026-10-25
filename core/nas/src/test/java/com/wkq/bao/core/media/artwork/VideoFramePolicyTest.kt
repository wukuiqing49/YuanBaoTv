package com.wkq.bao.core.media.artwork

import org.junit.Assert.*
import org.junit.Test

class VideoFramePolicyTest {
    @Test fun `frames come from the middle and stay within short videos`() {
        assertEquals(listOf(210_000_000L, 330_000_000L, 420_000_000L), VideoFramePolicy.candidateTimesUs(600_000L))
        assertTrue(VideoFramePolicy.candidateTimesUs(3_000L).all { it in 0 until 3_000_000L })
        assertEquals(60_000_000L, VideoFramePolicy.candidateTimesUs(null).first())
    }

    @Test fun `blank frames and sparse title on black are rejected`() {
        assertFalse(VideoFramePolicy.isUseful(IntArray(336) { 0xFF000000.toInt() }))
        assertFalse(VideoFramePolicy.isUseful(IntArray(336) { 0xFFFFFFFF.toInt() }))
        assertFalse(VideoFramePolicy.isUseful(IntArray(336) { if (it < 15) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }))
        assertFalse(VideoFramePolicy.isUseful(IntArray(336) { 0xFF555555.toInt() }))
        assertTrue(VideoFramePolicy.isUseful(IntArray(336) { if (it % 2 == 0) 0xFF4070A0.toInt() else 0xFF90C060.toInt() }))
    }
}
