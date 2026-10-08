package com.wkq.bao.core.media.subtitle

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object SubtitleTextEncoding {
    fun utf8(data: ByteArray, offset: Int, length: Int): ByteArray {
        require(length in 0..8 * 1024 * 1024) { "Subtitle exceeds size limit" }
        val bytes = data.copyOfRange(offset, offset + length)
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16
            else -> null
        }
        if (charset != null) return String(bytes, charset).toByteArray(Charsets.UTF_8)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            bytes
        } catch (_: java.nio.charset.CharacterCodingException) {
            String(bytes, charset("GB18030")).toByteArray(Charsets.UTF_8)
        }
    }
}
