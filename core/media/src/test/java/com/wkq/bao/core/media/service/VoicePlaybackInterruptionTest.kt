package com.wkq.bao.core.media.service

import org.junit.Assert.*
import org.junit.Test

class VoicePlaybackInterruptionTest {
    @Test fun resumesOnlyInterruptedItem() {
        val state = VoicePlaybackInterruption()
        state.pause("episode-1", true)
        assertTrue(state.resume("episode-1"))
        assertFalse(state.resume("episode-1"))
    }

    @Test fun repeatedWakePausePreservesResumeIntent() {
        val state = VoicePlaybackInterruption()
        state.pause("episode-1", true)
        state.pause("episode-1", false)
        assertTrue(state.resume("episode-1"))
    }

    @Test fun doesNotResumeUserPausedItem() {
        val state = VoicePlaybackInterruption()
        state.pause("episode-1", false)
        assertFalse(state.resume("episode-1"))
    }

    @Test fun explicitUserPauseCancelsWakeResume() {
        val state = VoicePlaybackInterruption()
        state.pause("episode-1", true)
        state.clear()
        assertFalse(state.resume("episode-1"))
    }

    @Test fun changingItemCancelsWakeResume() {
        val state = VoicePlaybackInterruption()
        state.pause("episode-1", true)
        assertFalse(state.resume("episode-2"))
        assertFalse(state.resume("episode-1"))
    }

    @Test fun emptyPlayerCannotBeResumed() {
        val state = VoicePlaybackInterruption()
        state.pause(null, true)
        assertFalse(state.resume(null))
    }

    @Test fun unknownVendorOperationsAreIgnored() {
        assertEquals(MiTvPlaybackOperation.PAUSE, MiTvPlaybackOperation.parse("pause"))
        assertEquals(MiTvPlaybackOperation.RESUME, MiTvPlaybackOperation.parse("resume"))
        assertNull(MiTvPlaybackOperation.parse("exit"))
        assertNull(MiTvPlaybackOperation.parse(null))
    }
}
