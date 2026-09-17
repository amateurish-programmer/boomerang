package com.boomerang.app.cloud

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// These classes intentionally do not print headers, bodies or tokens in toString().
class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String? = null)
class HttpResponse(val status: Int, val body: String)
fun interface HttpTransport { suspend fun execute(request: HttpRequest): HttpResponse }

open class CloudException(message: String) : Exception(message)
class CloudOffline : CloudException("无法连接云端，请检查网络后重试。")
class CloudTimeout : CloudException("云端请求超时，请稍后重试。")
class InvalidCloudResponse : CloudException("云端返回的数据无法读取，请稍后重试。")
class CredentialsUnavailable : CloudException("无法读取安全保存的登录凭据，请明确退出后重新登录。")
class AuthenticationRequired : CloudException("请先登录。")
class AccountIdentityMismatch : CloudException("账号信息不一致，请退出后重新登录。")
open class CloudHttpException(val status: Int, val code: String? = null) : CloudException(
    when (status) {
        400, 422 -> "提交内容未通过校验，请检查后重试。"
        401 -> "登录已失效，请重新登录。"
        403 -> "没有访问此内容的权限。"
        409 -> "云端内容已发生变化，请对比后再保存。"
        429 -> "请求过于频繁，请稍后重试。"
        else -> "云端暂时不可用，请稍后重试。"
    },
)
class RevisionConflict : CloudHttpException(409, "PT409")

/** Uses Android's system TLS verification; redirects are refused to protect credentials. */
class UrlConnectionTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        require(request.path.startsWith("/") && !request.path.startsWith("//") && !request.path.contains('#'))
        val connection = (URL(CloudConfig.SUPABASE_URL + request.path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let {
                val bytes = it.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { stream -> stream.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2 * 1024 * 1024) throw InvalidCloudResponse()
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }.orEmpty()
            HttpResponse(status, body)
        } catch (_: SocketTimeoutException) {
            throw CloudTimeout()
        } catch (_: IOException) {
            throw CloudOffline()
        } finally {
            connection.disconnect()
        }
    }
}

internal fun headers(accessToken: String? = null) = buildMap {
    put("apikey", CloudConfig.PUBLIC_ANON_KEY)
    put("Content-Type", "application/json; charset=utf-8")
    put("Accept", "application/json")
    accessToken?.let { put("Authorization", "Bearer $it") }
}

internal fun HttpResponse.requireSuccess(): String {
    if (status !in 200..299) {
        // Never expose upstream messages: they can contain emails, user content or credentials.
        if (status == 409) throw RevisionConflict()
        val code = runCatching { JSONObject(body).optString("code") }.getOrNull()
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }
        throw CloudHttpException(status, code)
    }
    return body
}

internal fun jsonObject(text: String): JSONObject = try { JSONObject(text) } catch (_: Exception) { throw InvalidCloudResponse() }
internal fun jsonArray(text: String): JSONArray = try { JSONArray(text) } catch (_: Exception) { throw InvalidCloudResponse() }
internal fun requireUuid(value: String): String {
    require(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "Invalid UUID" }
    return value
}
