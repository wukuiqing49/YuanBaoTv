package com.wkq.bao.core.media.download

import com.wkq.bao.core.database.entity.DownloadTaskEntity
import com.wkq.bao.core.database.entity.MediaFileEntity
import com.wkq.bao.core.database.entity.MediaRemoteSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadSourceSelectorTest {
    @Test
    fun `uses the source locked by the download task when multiple nas sources exist`() {
        val task = task(sourceUri = "smb://nas-b/share/show.mp4", sourceNasId = 2L)
        val selected = DownloadSourceSelector.select(task, mediaFile(), listOf(
            remoteSource(1L, "smb://nas-a/share/show.mp4", 1L),
            remoteSource(2L, "smb://nas-b/share/show.mp4", 2L)
        ))

        assertEquals("smb://nas-b/share/show.mp4", selected?.uri)
        assertEquals(2L, selected?.nasSourceId)
    }

    @Test
    fun `rejects a task uri and nas id that belong to different sources`() {
        val selected = DownloadSourceSelector.select(
            task(sourceUri = "smb://nas-a/share/show.mp4", sourceNasId = 2L),
            mediaFile(),
            listOf(remoteSource(1L, "smb://nas-a/share/show.mp4", 1L))
        )

        assertNull(selected)
    }

    @Test
    fun `keeps compatible legacy tasks when no remote source rows exist`() {
        val selected = DownloadSourceSelector.select(
            task(sourceUri = "smb://nas-a/share/show.mp4"),
            mediaFile(),
            emptyList()
        )

        assertEquals(1L, selected?.nasSourceId)
    }

    private fun task(sourceUri: String, sourceNasId: Long = 0L) = DownloadTaskEntity(
        id = 1L,
        seriesId = 1L,
        seasonId = 1L,
        episodeId = 1L,
        sourceUri = sourceUri,
        sourceNasId = sourceNasId
    )

    private fun mediaFile() = MediaFileEntity(
        id = 1L,
        seriesId = 1L,
        episodeId = 1L,
        nasSourceId = 1L,
        nasUri = "smb://nas-a/share/show.mp4"
    )

    private fun remoteSource(id: Long, uri: String, nasSourceId: Long) = MediaRemoteSourceEntity(
        id = id,
        mediaFileId = 1L,
        nasSourceId = nasSourceId,
        uri = uri,
        fileName = "show.mp4"
    )
}
