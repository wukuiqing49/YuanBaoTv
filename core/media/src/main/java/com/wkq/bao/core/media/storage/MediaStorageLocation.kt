package com.wkq.bao.core.media.storage

/** 媒体的实际播放或下载落盘位置。 */
enum class MediaStorageLocation {
    NAS,
    INTERNAL_STORAGE,
    TF_CARD,
    USB_DRIVE,
    EXTERNAL_STORAGE;

    companion object {
        fun fromStored(value: String?): MediaStorageLocation? =
            entries.firstOrNull { it.name == value }
    }
}
