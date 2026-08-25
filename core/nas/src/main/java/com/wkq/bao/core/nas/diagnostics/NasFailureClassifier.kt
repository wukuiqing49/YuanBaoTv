package com.wkq.bao.core.nas.diagnostics

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 将 NAS 异常映射为可导出但不含地址、账号、目录和文件名的诊断码。
 */
object NasFailureClassifier {
    fun code(error: Throwable?): String {
        val causes = generateSequence(error) { it.cause }.toList()
        val messages = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            causes.any { it is UnknownHostException } -> "dns"
            causes.any { it is SocketTimeoutException } || "timed out" in messages -> "timeout"
            causes.any { it is ConnectException } || "connection refused" in messages -> "connection"
            "status_logon_failure" in messages ||
                "status_access_denied" in messages ||
                "authentication" in messages ||
                "logon" in messages -> "authentication"
            "status_bad_network_name" in messages ||
                "share" in messages ||
                "directory" in messages -> "share"
            "smb" in messages || "protocol" in messages -> "protocol"
            else -> "unknown"
        }
    }
}
