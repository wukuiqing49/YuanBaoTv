package com.wkq.bao.core.media.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class NasArtworkPriorityTest {

    @Test
    fun `real sidecar image replaces generated video frame`() {
        val generatedFrame = "smb://nas/video.mkv?artworkFrame=1&artworkVersion=1"
        val sidecarImage = "smb://nas/poster.jpg"

        assertEquals(sidecarImage, NasArtworkPriority.prefer(generatedFrame, sidecarImage))
    }

    @Test
    fun `existing real image remains preferred`() {
        val existingSidecar = "smb://nas/poster.jpg"
        val generatedFrame = "smb://nas/video.mkv?artworkFrame=1&artworkVersion=2"

        assertEquals(existingSidecar, NasArtworkPriority.prefer(existingSidecar, generatedFrame))
    }
}
