package com.wkq.bao.core.media.resolver

/** 不向界面暴露网络地址、凭据或内部异常文本。 */
object PlaybackFailure {
    fun classify(code: Int, error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(16).toList()
        val message = causes.joinToString(" ") { it.message.orEmpty() }
        return when {
            causes.any { it is javax.net.ssl.SSLException } -> "certificate"
            "HTTP 401" in message || "HTTP 403" in message || "STATUS_LOGON_FAILURE" in message || "STATUS_ACCESS_DENIED" in message -> "authentication"
            code in 3000..4999 -> "format"
            causes.any { it is java.net.ConnectException || it is java.net.SocketTimeoutException || it is java.net.UnknownHostException } -> "network"
            else -> "source"
        }
    }
}
