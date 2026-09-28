package com.wkq.bao.core.media.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarArtworkResolverTest {

    @Test
    fun `directory artwork uses explicit names and is case insensitive`() {
        val artwork = SidecarArtworkResolver.resolveDirectory(
            listOf(
                SidecarArtworkResolver.Candidate("Poster.JPG", "poster-uri"),
                SidecarArtworkResolver.Candidate("FANART.webp", "backdrop-uri"),
                SidecarArtworkResolver.Candidate("ignored.gif", "ignored-uri")
            )
        )

        assertEquals("poster-uri", artwork.posterUri)
        assertEquals("backdrop-uri", artwork.backdropUri)
        assertFalse(artwork.imagesByStem.containsKey("ignored"))
    }

    @Test
    fun `season directory inherits series artwork and resolves episode thumbnail`() {
        val parent = SidecarArtworkResolver.resolveDirectory(
            listOf(
                SidecarArtworkResolver.Candidate("cover.png", "series-poster"),
                SidecarArtworkResolver.Candidate("background.jpg", "series-backdrop")
            )
        )
        val season = SidecarArtworkResolver.resolveDirectory(
            listOf(SidecarArtworkResolver.Candidate("Show.S01E02-thumb.jpg", "episode-thumbnail")),
            inherited = parent
        )

        val media = SidecarArtworkResolver.resolveMedia("Show.S01E02.mkv", season)

        assertEquals("series-poster", media.posterUri)
        assertEquals("series-backdrop", media.backdropUri)
        assertEquals("episode-thumbnail", media.thumbnailUri)
    }

    @Test
    fun `supported image detection excludes video and unsupported formats`() {
        assertTrue(SidecarArtworkResolver.isImageFile("cover.jpeg"))
        assertTrue(SidecarArtworkResolver.isImageFile("cover.WEBP"))
        assertFalse(SidecarArtworkResolver.isImageFile("movie.mkv"))
        assertFalse(SidecarArtworkResolver.isImageFile("cover.gif"))
    }

    @Test
    fun `movie specific poster and nfo take priority over folder artwork`() {
        val directory = SidecarArtworkResolver.resolveDirectory(listOf(
            SidecarArtworkResolver.Candidate("poster.jpg", "folder-poster"),
            SidecarArtworkResolver.Candidate("Movie-poster.jpg", "movie-poster"),
            SidecarArtworkResolver.Candidate("movie.nfo", "folder-nfo"),
            SidecarArtworkResolver.Candidate("Movie.nfo", "movie-nfo")
        ))
        val movie = SidecarArtworkResolver.resolveMedia("Movie.mkv", directory)
        assertEquals("movie-poster", movie.posterUri)
        assertEquals("movie-nfo", movie.nfoUri)
    }
}
