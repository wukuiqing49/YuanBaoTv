package com.wkq.bao.core.media.external

import org.junit.Assert.*
import org.junit.Test

class PlaybackByteReaderTest {
    private class FakeSource : PlaybackByteReader.Source {
        var position = 0
        val opens = mutableListOf<Long>()
        var closes = 0
        override fun openAt(offset: Long) { opens += offset; position = offset.toInt() }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = minOf(2, length, 10 - position)
            if (count <= 0) return -1
            repeat(count) { buffer[offset + it] = (position++).toByte() }
            return count
        }
        override fun close() { closes++ }
    }

    @Test fun partialReadsAreCombinedAndContinuousReadsReuseConnection() {
        val source = FakeSource(); val reader = PlaybackByteReader(source, 10)
        val buffer = ByteArray(5)
        assertEquals(5, reader.readAt(0, 5, buffer))
        assertArrayEquals(byteArrayOf(0,1,2,3,4), buffer)
        assertEquals(5, reader.readAt(5, 5, buffer))
        assertEquals(listOf(0L), source.opens)
    }

    @Test fun backwardSeekReopensAtRequestedOffset() {
        val source = FakeSource(); val reader = PlaybackByteReader(source, 10); val buffer = ByteArray(4)
        reader.readAt(0,4,buffer); reader.readAt(2,4,buffer)
        assertEquals(listOf(0L,2L),source.opens)
        assertArrayEquals(byteArrayOf(2,3,4,5),buffer)
    }

    @Test fun clampsReadsAtEndAndReturnsZeroAtEof() {
        val reader = PlaybackByteReader(FakeSource(),10); val buffer = ByteArray(5)
        assertEquals(2,reader.readAt(8,5,buffer))
        assertEquals(0,reader.readAt(10,5,buffer))
    }

    @Test fun releaseIsIdempotentAndRejectsFurtherReads() {
        val source = FakeSource(); val reader = PlaybackByteReader(source,10)
        reader.close(); reader.close(); assertEquals(1,source.closes)
        assertThrows(IllegalStateException::class.java) { reader.readAt(0,1,ByteArray(1)) }
    }
}
