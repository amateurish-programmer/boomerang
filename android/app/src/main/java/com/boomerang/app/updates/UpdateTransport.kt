package com.boomerang.app.updates

import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

fun interface UpdateTransport { fun open(url: String): UpdateResponse }

class UpdateResponse(val stream: InputStream, val contentLength: Long, private val disconnect: () -> Unit = {}) : Closeable {
    override fun close() { try { stream.close() } finally { disconnect() } }
}

class UrlUpdateTransport(
    private val connection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : UpdateTransport {
    override fun open(url: String): UpdateResponse {
        if (!UpdatePolicy.trustedNetworkUrl(url)) throw UpdateException("更新下载地址不可信")
        val request = connection(URL(url))
        try {
            request.instanceFollowRedirects = false
            request.connectTimeout = 15000
            request.readTimeout = 15000
            request.useCaches = false
            request.setRequestProperty("Accept-Encoding", "identity")
            if (request.responseCode != HttpURLConnection.HTTP_OK) throw UpdateException("更新服务暂不可用，请稍后重试")
            return UpdateResponse(request.inputStream, request.contentLengthLong, request::disconnect)
        } catch (error: Exception) {
            request.disconnect()
            if (error is UpdateException) throw error
            throw UpdateException("无法连接更新服务，请检查网络后重试", error)
        }
    }
}
