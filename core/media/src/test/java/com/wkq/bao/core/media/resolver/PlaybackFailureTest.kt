package com.wkq.bao.core.media.resolver

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFailureTest {
    @Test fun classifiesActionableFailuresWithoutExposingRawMessages() {
        assertEquals("certificate", PlaybackFailure.classify(2001, RuntimeException(javax.net.ssl.SSLHandshakeException("test"))))
        assertEquals("authentication", PlaybackFailure.classify(2001, java.io.IOException("WebDAV HTTP 401")))
        assertEquals("network", PlaybackFailure.classify(2001, java.net.ConnectException()))
        assertEquals("format", PlaybackFailure.classify(4003, RuntimeException()))
        assertEquals("source", PlaybackFailure.classify(2005, java.io.FileNotFoundException()))
    }
}
