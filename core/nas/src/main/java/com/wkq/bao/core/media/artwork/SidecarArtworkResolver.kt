package com.wkq.bao.core.media.artwork

/** 统一解析本地存储和 NAS 目录中的侧车海报、背景图与分集缩略图。 */
object SidecarArtworkResolver {

    data class Candidate(
        val fileName: String,
        val uri: String
    )

    data class DirectoryArtwork(
        val posterUri: String = "",
        val backdropUri: String = "",
        val imagesByStem: Map<String, String> = emptyMap(),
        val nfoByStem: Map<String, String> = emptyMap()
    )

    data class MediaArtwork(
        val posterUri: String,
        val backdropUri: String,
        val thumbnailUri: String,
        val nfoUri: String
    )

    fun resolveDirectory(
        candidates: List<Candidate>,
        inherited: DirectoryArtwork = DirectoryArtwork()
    ): DirectoryArtwork {
        val images = candidates.asSequence()
            .filter { candidate -> candidate.fileName.extension() in IMAGE_EXTENSIONS }
            .associate { candidate -> candidate.fileName.stem() to candidate.uri }
        val nfos = candidates.asSequence()
            .filter { it.fileName.extension() == "nfo" }
            .associate { it.fileName.stem() to it.uri }
        return DirectoryArtwork(
            posterUri = POSTER_NAMES.firstNotNullOfOrNull(images::get).orEmpty()
                .ifBlank { inherited.posterUri },
            backdropUri = BACKDROP_NAMES.firstNotNullOfOrNull(images::get).orEmpty()
                .ifBlank { inherited.backdropUri },
            imagesByStem = images,
            nfoByStem = nfos
        )
    }

    fun resolveMedia(fileName: String, directory: DirectoryArtwork): MediaArtwork {
        val stem = fileName.stem()
        val thumbnailUri = listOf(stem, "$stem-thumb", "$stem-thumbnail", "$stem.thumb")
            .firstNotNullOfOrNull(directory.imagesByStem::get)
            .orEmpty()
        return MediaArtwork(
            posterUri = listOf("$stem-poster", "$stem.poster", "$stem-cover", "$stem.cover")
                .firstNotNullOfOrNull(directory.imagesByStem::get).orEmpty()
                .ifBlank { directory.posterUri },
            backdropUri = listOf("$stem-fanart", "$stem.fanart", "$stem-backdrop")
                .firstNotNullOfOrNull(directory.imagesByStem::get).orEmpty()
                .ifBlank { directory.backdropUri },
            thumbnailUri = thumbnailUri,
            nfoUri = directory.nfoByStem[stem].orEmpty()
                .ifBlank { directory.nfoByStem["movie"].orEmpty() }
        )
    }

    fun isImageFile(fileName: String): Boolean = fileName.extension() in IMAGE_EXTENSIONS

    private fun String.extension(): String = substringAfterLast('.', "").lowercase()

    private fun String.stem(): String = substringBeforeLast('.').lowercase()

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val POSTER_NAMES = listOf("poster", "folder", "cover")
    private val BACKDROP_NAMES = listOf("fanart", "backdrop", "background")
}
