package com.boomerang.app.assistant

import com.boomerang.app.cloud.*
import com.boomerang.app.domain.RecordContent
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantRepositoryTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val id = "33333333-3333-4333-8333-333333333333"
    private class Store(var session: AuthSession?) : SessionStore {
        override fun load() = session
        override fun save(session: AuthSession) { this.session = session }
        override fun clear() { session = null }
    }
    private fun repository(body: String, status: Int = 200, requests: MutableList<HttpRequest> = mutableListOf()): AssistantRepository {
        val transport = HttpTransport { requests.add(it); HttpResponse(status, body) }
        val auth = AuthRepository(transport, Store(AuthSession(owner, "token", "refresh", Long.MAX_VALUE)))
        return AssistantRepository(auth, transport, owner)
    }
    @Test fun unavailableProviderDoesNotReturnExampleDraft() = runBlocking {
        try { repository("{}", 503).analyze("承诺", "2026-09-17", "Asia/Shanghai"); fail() }
        catch (e: CloudHttpException) { assertEquals(503, e.status) }
    }
    @Test fun foreignRestJobIsRejected() = runBlocking {
        try { repository("""[{"id":"$id","owner_id":"$other","query":"secret","status":"QUEUED"}]""").jobs(); fail() }
        catch (_: AccountIdentityMismatch) { }
    }
    @Test fun successfulEnqueueHasIdempotencyAndNoRecordWrite() = runBlocking {
        val calls = mutableListOf<HttpRequest>()
        val result = repository("""{"job_id":"$id","status":"QUEUED"}""", 202, calls)
            .research("调查", "2026-09-17", "Asia/Shanghai", id)
        assertEquals(id, result)
        assertEquals(id, JSONObject(calls.single().body!!).getString("operation_id"))
        assertEquals("/functions/v1/api/v1/research", calls.single().path)
    }
    @Test fun confirmationRequiresExplicitValidChoiceAndReason() = runBlocking {
        val calls = mutableListOf<HttpRequest>()
        try { repository("{}", requests = calls).confirm(AssistantCheck(id, id, 1, "UNVERIFIABLE", "不足"), "FULFILLED", ""); fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(calls.isEmpty())
    }
    @Test fun unsafeSourceLinksNeverBecomeOpenable() {
        listOf("https://127.0.0.1/x", "https://[::1]/", "https://private.local/x", "https://user@example.org/", "http://example.org/", "https://example.org:444/", "https://example.org/#x")
            .forEach { assertFalse(AssistantCodec.safeUrl(it)) }
        assertTrue(AssistantCodec.safeUrl("https://example.org/proof"))
    }
    @Test fun oversizedResponseFailsClosed() = runBlocking {
        try { repository(" ".repeat(131073)).jobs(); fail() } catch (_: InvalidCloudResponse) { }
    }
    @Test fun switchedIdentityCannotSendOrDisplayOldRequest() = runBlocking {
        val calls = mutableListOf<HttpRequest>()
        val store = Store(AuthSession(owner, "token", "refresh", Long.MAX_VALUE))
        val transport = HttpTransport { calls.add(it); store.session = AuthSession(other, "other", "r", Long.MAX_VALUE); HttpResponse(200, "[]") }
        try { AssistantRepository(AuthRepository(transport, store), transport, owner).jobs(); fail() }
        catch (_: AccountIdentityMismatch) { }
    }
    @Test fun weeklySummaryRejectsUnknownRecordCitations() {
        try { AssistantCodec.weekly("""{"summary":"记录 [$other]","record_ids":["$other"]}""",setOf(id)); fail() }
        catch (_: InvalidCloudResponse) { }
        assertEquals("记录 [$id]", AssistantCodec.weekly("""{"summary":"记录 [$id]","record_ids":["$id"]}""",setOf(id)))
    }
    @Test fun weeklySummaryRequiresVisibleMatchingCitations() {
        try { AssistantCodec.weekly("""{"summary":"没有引用","record_ids":["$id"]}""",setOf(id)); fail() }
        catch (_: InvalidCloudResponse) { }
    }
    @Test fun reviewedCandidateSendsOnlyEditableMetadataAndPreservesQuote() = runBlocking {
        val calls=mutableListOf<HttpRequest>()
        val content=RecordContent(originalText="Exact quote",lifecycle="DRAFT")
        val candidate=AssistantCandidate(id,content,listOf(AssistantSource("Source","https://example.org/proof","Exact quote","Exact quote")),null)
        val repo=repository("""{"id":"$id","owner_id":"$owner"}""",requests=calls)
        assertEquals(id,repo.acceptReviewed(candidate,content.copy(subject="Reviewed"),id))
        val review=JSONObject(calls.single().body!!).getJSONObject("p_review")
        assertEquals("Reviewed",review.getString("subject"));assertFalse(review.has("original_text"));assertFalse(review.has("confirmed_status"))
        try { repo.acceptReviewed(candidate,content.copy(originalText="Forged"),id);fail() } catch(_: IllegalArgumentException) { }
        assertEquals(1,calls.size)
    }
    @Test fun foreignNotificationCannotBeDisplayed() = runBlocking {
        try { repository("""[{"id":"$id","owner_id":"$other","title":"private","body":"private","record_id":null,"read_at":null}]""").notifications();fail() }
        catch(_: AccountIdentityMismatch) { }
    }
}
