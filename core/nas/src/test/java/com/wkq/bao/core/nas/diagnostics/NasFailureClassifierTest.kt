package com.wkq.bao.core.nas.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NasFailureClassifierTest {
    @Test
    fun `classifies network and nas failures without retaining original details`() {
        assertEquals("dns", NasFailureClassifier.code(UnknownHostException("private-host")))
        assertEquals("timeout", NasFailureClassifier.code(SocketTimeoutException("private-host")))
        assertEquals("connection", NasFailureClassifier.code(ConnectException("private-host")))
        assertEquals("authentication", NasFailureClassifier.code(IllegalStateException("STATUS_LOGON_FAILURE")))
        assertEquals("share", NasFailureClassifier.code(IllegalStateException("STATUS_BAD_NETWORK_NAME")))
        assertEquals("unknown", NasFailureClassifier.code(IllegalStateException("unexpected")))
    }
}
