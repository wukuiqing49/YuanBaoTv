package com.wkq.bao.core.media.webdav

import com.wkq.bao.core.database.entity.NasSourceEntity
import org.junit.Assert.*
import org.junit.Test

class WebDavEndpointTest {
    private fun source(host: String = "nas.local", port: Int = 5005, root: String = "Movies") =
        NasSourceEntity(name = "Test", type = "WEBDAV", host = host, port = port, rootPath = root)

    @Test fun matchesConfiguredOriginAndDirectoryOnly() {
        val nas = source()
        assertTrue(WebDavEndpoint.matches(nas, "http://NAS.local:5005/Movies/a.mp4"))
        for (url in listOf("https://nas.local:5005/Movies/a.mp4", "http://other:5005/Movies/a.mp4",
            "http://nas.local:80/Movies/a.mp4", "http://nas.local:5005/Movies2/a.mp4",
            "http://nas.local:5005/movies/a.mp4", "http://nas.local:5005/Movies/../private/a.mp4",
            "http://nas.local:5005/Movies/%2e%2e/private/a.mp4", "http://user@nas.local:5005/Movies/a.mp4")) {
            assertFalse(url, WebDavEndpoint.matches(nas, url))
        }
    }

    @Test fun handlesDefaultPortsIpv6AndConfiguredBasePath() {
        assertTrue(WebDavEndpoint.matches(source(port = 80), "http://nas.local/Movies/a.mp4"))
        assertTrue(WebDavEndpoint.matches(source(host = "https://NAS.local", port = 443), "https://nas.local/Movies/a.mp4"))
        assertTrue(WebDavEndpoint.matches(source(host = "http://[::1]/dav", port = 8080), "http://[::1]:8080/dav/Movies/a.mp4"))
        assertFalse(WebDavEndpoint.matches(source(host = "http://[::1]/dav", port = 8080), "http://[::1]:8080/Movies/a.mp4"))
    }

    @Test fun malformedAndNonWebOriginsAreRejected() {
        assertFalse(WebDavEndpoint.matches(source(), "file:///Movies/a.mp4"))
        assertFalse(WebDavEndpoint.matches(source(host = "https://user:pass@nas.local"), "https://nas.local:5005/Movies/a.mp4"))
    }
}
