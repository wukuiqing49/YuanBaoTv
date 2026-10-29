package com.wkq.bao.core.media.webdav

import com.wkq.bao.core.database.entity.NasSourceEntity
import java.net.ServerSocket
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WebDavPlaybackTest {
    @Test fun rangePlaybackUsesConfiguredAuthentication() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        Fixture { _, headers ->
            requests.add(headers)
            response("206 Partial Content", "Content-Range: bytes 2-4/6\r\n", "cde")
        }.use { server ->
            WebDavClientManager.openPlayback(server.source.copy(username = "test"), server.url("video"), 2, 3).use {
                assertEquals("cde", it.input.bufferedReader().readText())
                assertEquals(3L, it.length)
            }
            assertTrue(requests.single().any { it.equals("Range: bytes=2-4", true) })
            assertTrue(requests.single().any { it.equals("Authorization: Basic dGVzdDo=", true) })
        }
    }

    @Test fun redirectsAndIncorrectRangesAreRejected() {
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        Fixture { path, _ ->
            paths.add(path)
            if (path.endsWith("redirect")) response("302 Found", "Location: /outside\r\n", "")
            else response("206 Partial Content", "Content-Range: bytes 0-2/6\r\n", "abc")
        }.use { server ->
            for (path in listOf("redirect", "bad-range")) {
                try {
                    WebDavClientManager.openPlayback(server.source, server.url(path), 2, 3).close()
                    fail("Expected rejection for $path")
                } catch (_: java.io.IOException) { }
            }
            assertEquals(listOf("/media/redirect", "/media/bad-range"), paths.toList())
        }
    }

    private fun response(status: String, headers: String, body: String): String =
        "HTTP/1.1 $status\r\n${headers}Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

    private class Fixture(private val respond: (String, List<String>) -> String) : java.io.Closeable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val source = NasSourceEntity(name = "Test", type = "WEBDAV", host = "http://127.0.0.1", port = server.localPort, rootPath = "media")
        fun url(path: String) = "http://127.0.0.1:${server.localPort}/media/$path"
        private val worker = kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader()
                        val request = reader.readLine() ?: return@use
                        val headers = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers.add(line)
                        }
                        socket.getOutputStream().write(respond(request.split(' ')[1], headers).toByteArray(Charsets.US_ASCII))
                    }
                } catch (error: java.net.SocketException) { if (!server.isClosed) throw error }
            }
        }
        override fun close() { server.close(); worker.join(3000) }
    }
}
