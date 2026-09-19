package com.boomerang.app.updates

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class UpdateTransportTest {
    private class Connection(private val status: Int) : HttpURLConnection(URL(APK_URL)) {
        var closed = false
        var streamOpened = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream(): ByteArrayInputStream { streamOpened = true; return ByteArrayInputStream("abc".toByteArray()) }
    }

    @Test fun redirectsFailWithoutFollowingOrOpeningTheirBody() {
        val connection = Connection(302)
        val transport = UrlUpdateTransport { connection }
        assertThrows(UpdateException::class.java) { transport.open(APK_URL) }
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.streamOpened)
        assertTrue(connection.closed)
    }

    @Test fun responsesUseBoundedTimeoutsAndReleaseConnections() {
        val connection = Connection(200)
        UrlUpdateTransport { connection }.open(APK_URL).use { response ->
            assertEquals("abc", response.stream.readBytes().toString(Charsets.UTF_8))
        }
        assertTrue(connection.connectTimeout in 1..30000)
        assertTrue(connection.readTimeout in 1..30000)
        assertTrue(connection.closed)
    }

    @Test fun arbitraryUrlsCannotReachConnectionFactory() {
        val transport = UrlUpdateTransport { error("Unsafe URL reached network") }
        assertThrows(UpdateException::class.java) { transport.open("https://evil.example/app.apk") }
        assertThrows(UpdateException::class.java) { transport.open("$APK_URL?secret=x") }
    }
}
