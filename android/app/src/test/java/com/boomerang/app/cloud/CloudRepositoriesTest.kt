package com.boomerang.app.cloud

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudRepositoriesTest {
    private val user = "00000000-0000-0000-0000-000000000001"
    private val record = "10000000-0000-0000-0000-000000000001"
    private val operation = "20000000-0000-0000-0000-000000000001"
    private fun session() = AuthSession(user, "access", "refresh", 9999999999)
    private fun authBody(id: String = user) = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"user":{"id":"$id"}}"""
    private class Store(var value: AuthSession? = null) : SessionStore {
        override fun load() = value
        override fun save(session: AuthSession) { value = session }
        override fun clear() { value = null }
    }
    private class Fixture(vararg responses: HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        val replies = ArrayDeque(responses.toList())
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return replies.removeFirst()
        }
    }
    private suspend inline fun <reified T : Throwable> fails(block: () -> Unit): T {
        try { block() } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.simpleName}, got ${error.javaClass.simpleName}", error is T)
            return error as T
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun signupWithoutTokensIsPendingEmailAndDoesNotInventSession() = runBlocking<Unit> {
        val store = Store()
        val auth = AuthRepository(Fixture(HttpResponse(200, """{"id":"$user","identities":[]}""")), store)
        assertTrue(auth.signUp("a@example.org", "password") is SignUpResult.EmailConfirmationRequired)
        assertNull(store.value)
    }
    @Test fun signInUsesServerUserAndPersistsBeforeReturning() = runBlocking<Unit> {
        val store = Store()
        val fixture = Fixture(HttpResponse(200, authBody()))
        val result = AuthRepository(fixture, store, nowSeconds = { 1000 }).signIn("a@example.org", "password")
        assertEquals(user, result.userId)
        assertEquals(4600L, result.expiresAtEpochSeconds)
        assertSame(result, store.value)
        assertEquals("/auth/v1/token?grant_type=password", fixture.requests.single().path)
        assertFalse(result.toString().contains("new-access"))
    }
    @Test fun malformedSessionDoesNotReplaceStoredIdentity() = runBlocking<Unit> {
        val original = session()
        val store = Store(original)
        val auth = AuthRepository(Fixture(HttpResponse(200, """{"access_token":"x","refresh_token":"y","expires_in":3600}""")), store)
        fails<InvalidCloudResponse> { auth.signIn("a@example.org", "password") }
        assertSame(original, store.value)
    }
    @Test fun refreshRejectsChangedServerIdentity() = runBlocking<Unit> {
        val original = session()
        val store = Store(original)
        val auth = AuthRepository(Fixture(HttpResponse(200, authBody("00000000-0000-0000-0000-000000000002"))), store)
        fails<AccountIdentityMismatch> { auth.refresh() }
        assertSame(original, store.value)
    }
    @Test fun refreshRotatesTokensForSameAccount() = runBlocking<Unit> {
        val store = Store(session())
        val fixture = Fixture(HttpResponse(200, authBody()))
        val result = AuthRepository(fixture, store).refresh()
        assertEquals("new-refresh", result.refreshToken)
        assertEquals("refresh", JSONObject(fixture.requests.single().body!!).getString("refresh_token"))
    }
    @Test fun activeSessionRefreshesExpiredCredentialsBeforeReturning() = runBlocking<Unit> {
        val store = Store(AuthSession(user, "expired", "refresh", 900))
        val auth = AuthRepository(Fixture(HttpResponse(200, authBody())), store, nowSeconds = { 1000 })
        assertEquals("new-access", auth.activeSession().accessToken)
        assertEquals(4600L, store.value!!.expiresAtEpochSeconds)
    }
    @Test fun activeSessionUsesUnexpiredSessionWithoutNetwork() = runBlocking<Unit> {
        val original = session()
        assertSame(original, AuthRepository(Fixture(), Store(original)).activeSession())
    }
    @Test fun differentLoginRequiresExplicitLogoutFirst() = runBlocking<Unit> {
        val original = session()
        val store = Store(original)
        fails<AccountIdentityMismatch> {
            AuthRepository(Fixture(HttpResponse(200, authBody("00000000-0000-0000-0000-000000000002"))), store)
                .signIn("other@example.org", "password")
        }
        assertSame(original, store.value)
    }
    @Test fun storageFailurePreventsSuccessfulLogin() = runBlocking<Unit> {
        val store = object : SessionStore {
            override fun load(): AuthSession? = null
            override fun save(session: AuthSession): Unit = throw CredentialsUnavailable()
            override fun clear() = Unit
        }
        fails<CredentialsUnavailable> { AuthRepository(Fixture(HttpResponse(200, authBody())), store).signIn("a@example.org", "password") }
    }
    @Test fun failedRefreshDoesNotSilentlyBecomeAnonymous() = runBlocking<Unit> {
        val original = session()
        val store = Store(original)
        fails<CloudHttpException> { AuthRepository(Fixture(HttpResponse(401, "{}")), store).refresh() }
        assertSame(original, store.value)
    }
    @Test fun signupWithSessionPersistsVerifiedServerIdentity() = runBlocking<Unit> {
        val store = Store()
        val result = AuthRepository(Fixture(HttpResponse(200, authBody())), store).signUp("a@example.org", "password")
        assertTrue(result is SignUpResult.SignedIn)
        assertEquals(user, store.value!!.userId)
    }
    @Test fun decryptionFailureDoesNotStartNetworkOrClearStore() = runBlocking<Unit> {
        val fixture = Fixture()
        val store = object : SessionStore {
            override fun load(): AuthSession? = throw CredentialsUnavailable()
            override fun save(session: AuthSession) = throw AssertionError("must not overwrite")
            override fun clear() = throw AssertionError("must not reset")
        }
        fails<CredentialsUnavailable> { AuthRepository(fixture, store).currentSession() }
        assertTrue(fixture.requests.isEmpty())
    }
    @Test fun logoutClearsLocalSessionEvenIfServerRejectsRequest() = runBlocking<Unit> {
        val store = Store(session())
        fails<CloudHttpException> { AuthRepository(Fixture(HttpResponse(503, "secret upstream body")), store).signOut() }
        assertNull(store.value)
    }
    @Test fun paginationFetchesSecondPageAndIncludesTombstones() = runBlocking<Unit> {
        val first = JSONArray()
        repeat(100) { first.put(JSONObject().put("id", "id-$it").put("owner_id", user)) }
        val fixture = Fixture(HttpResponse(200, first.toString()), HttpResponse(200, """[{"id":"last","owner_id":"$user","deleted_at":"2026-09-17T00:00:00Z"}]"""))
        val result = BackendRepository(fixture).listAllRecords(session())
        assertEquals(101, result.size)
        assertTrue(result.last().has("deleted_at"))
        assertEquals("/rest/v1/records?select=*&order=id.asc&limit=100&offset=100", fixture.requests.last().path)
    }
    @Test fun failedLaterPageNeverReturnsPartialSuccess() = runBlocking<Unit> {
        val first = JSONArray()
        repeat(100) { first.put(JSONObject().put("id", "id-$it").put("owner_id", user)) }
        fails<CloudHttpException> {
            BackendRepository(Fixture(HttpResponse(200, first.toString()), HttpResponse(503, "down"))).listAllRecords(session())
        }
    }
    @Test fun recordsFromWrongAccountAreRejected() = runBlocking<Unit> {
        fails<AccountIdentityMismatch> {
            BackendRepository(Fixture(HttpResponse(200, """[{"id":"x","owner_id":"someone-else"}]"""))).listAllRecords(session())
        }
    }
    @Test fun upsertPreservesOperationIdAndExpectedRevision() = runBlocking<Unit> {
        val fixture = Fixture(HttpResponse(200, """{"id":"$record","owner_id":"$user","revision":4}"""))
        val result = BackendRepository(fixture).upsertRecord(session(), JSONObject().put("id", record).put("notes", "edit"), 3, operation)
        assertEquals(4, result.getInt("revision"))
        val body = JSONObject(fixture.requests.single().body!!)
        assertEquals(operation, body.getString("p_operation_id"))
        assertEquals(3, body.getInt("p_expected_revision"))
        assertEquals("Bearer access", fixture.requests.single().headers["Authorization"])
    }
    @Test fun conflictIsDistinctAndContainsNoRawUserContent() = runBlocking<Unit> {
        val error = fails<RevisionConflict> {
            BackendRepository(Fixture(HttpResponse(409, """{"code":"PT409","message":"private user text"}"""))).upsertRecord(session(), JSONObject().put("id", record), 1, operation)
        }
        assertFalse(error.message.orEmpty().contains("private user text"))
    }
    @Test fun malformedSuccessIsNotTreatedAsEmptyLibrary() = runBlocking<Unit> {
        fails<InvalidCloudResponse> { BackendRepository(Fixture(HttpResponse(200, "not json"))).listAllRecords(session()) }
    }
    @Test fun recordHistoryUsesOnlyValidatedUuidInFilter() = runBlocking<Unit> {
        val fixture = Fixture()
        fails<IllegalArgumentException> { BackendRepository(fixture).listRevisions(session(), "$record&owner_id=eq.other") }
        assertTrue(fixture.requests.isEmpty())
    }
}
