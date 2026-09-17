package com.boomerang.app.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** userId originates only in a server Auth response received over authenticated TLS. */
class AuthSession(val userId: String, val accessToken: String, val refreshToken: String, val expiresAtEpochSeconds: Long) {
    override fun toString() = "AuthSession(redacted)"
}

interface SessionStore {
    fun load(): AuthSession?
    fun save(session: AuthSession)
    /** Explicit user logout only; never clear automatically on decryption failure. */
    fun clear()
}

sealed interface SignUpResult {
    data object EmailConfirmationRequired : SignUpResult
    class SignedIn(val session: AuthSession) : SignUpResult
}

/** Serialize identity changes, refresh-token rotation and secure-storage writes. */
class AuthRepository(
    private val transport: HttpTransport,
    private val store: SessionStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()

    suspend fun currentSession(): AuthSession? = withContext(Dispatchers.IO) { mutex.withLock { store.load() } }

    suspend fun signUp(email: String, password: String): SignUpResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = store.load() // Fail closed if stored credentials cannot be decrypted.
            val response = authRequest("/auth/v1/signup", credentials(email, password))
            if (!response.has("access_token") || response.isNull("access_token")) {
                // Supabase email confirmation enabled: a user object without session is normal.
                val user = response.optJSONObject("user") ?: response
                if (user.optString("id").isBlank()) throw InvalidCloudResponse()
                SignUpResult.EmailConfirmationRequired
            } else {
                val session = parseSession(response)
                ensureSameIdentity(existing, session)
                store.save(session)
                SignUpResult.SignedIn(session)
            }
        }
    }

    suspend fun signIn(email: String, password: String): AuthSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = store.load()
            val session = parseSession(authRequest("/auth/v1/token?grant_type=password", credentials(email, password)))
            ensureSameIdentity(existing, session)
            store.save(session)
            session
        }
    }

    suspend fun refresh(): AuthSession = withContext(Dispatchers.IO) {
        mutex.withLock { refreshLocked(store.load() ?: throw AuthenticationRequired()) }
    }

    /** Use immediately before a cloud operation; caller keeps this captured identity through the operation. */
    suspend fun activeSession(): AuthSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = store.load() ?: throw AuthenticationRequired()
            if (session.expiresAtEpochSeconds <= nowSeconds() + 60) refreshLocked(session) else session
        }
    }

    /** Local logout always wins even when remote revocation is unavailable. */
    suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Clearing is explicitly authorized by signOut, including an unreadable ciphertext.
            val session = try { store.load() } catch (_: CredentialsUnavailable) { null }
            store.clear()
            if (session != null) {
                transport.execute(HttpRequest("POST", "/auth/v1/logout?scope=local", headers(session.accessToken)))
                    .requireSuccess()
            }
        }
    }

    private suspend fun refreshLocked(old: AuthSession): AuthSession {
        val session = parseSession(authRequest("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", old.refreshToken)))
        ensureSameIdentity(old, session)
        store.save(session)
        return session
    }

    private suspend fun authRequest(path: String, body: JSONObject) = jsonObject(
        transport.execute(HttpRequest("POST", path, headers(), body.toString())).requireSuccess(),
    )

    private fun credentials(email: String, password: String): JSONObject {
        require(email.isNotBlank() && email.length <= 320 && password.isNotEmpty() && password.length <= 4096)
        return JSONObject().put("email", email.trim()).put("password", password)
    }

    private fun ensureSameIdentity(old: AuthSession?, new: AuthSession) {
        if (old != null && old.userId != new.userId) throw AccountIdentityMismatch()
    }

    private fun parseSession(body: JSONObject): AuthSession {
        try {
            val userId = requireUuid(body.getJSONObject("user").getString("id"))
            val access = body.getString("access_token")
            val refresh = body.getString("refresh_token")
            val duration = body.getLong("expires_in")
            if (access.isBlank() || refresh.isBlank() || duration !in 1..604800) throw InvalidCloudResponse()
            return AuthSession(userId, access, refresh, nowSeconds() + duration)
        } catch (_: Exception) { throw InvalidCloudResponse() }
    }
}
