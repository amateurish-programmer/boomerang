package com.boomerang.app.cloud

import org.json.JSONObject

/** Cloud results are detached JSON values. Only the sync coordinator may merge them into Room. */
class BackendRepository(private val transport: HttpTransport) {
    suspend fun listAllRecords(session: AuthSession): List<JSONObject> =
        listAll(session, "records", "")

    suspend fun listSources(session: AuthSession, recordId: String): List<JSONObject> =
        listAll(session, "sources", "&record_id=eq.${requireUuid(recordId)}")

    suspend fun listChecks(session: AuthSession, recordId: String): List<JSONObject> =
        listAll(session, "checks", "&record_id=eq.${requireUuid(recordId)}")

    suspend fun listRevisions(session: AuthSession, recordId: String): List<JSONObject> =
        listAll(session, "record_revisions", "&record_id=eq.${requireUuid(recordId)}", "revision.asc")

    /** Reuse the exact same operationId, expectedRevision and input after an ambiguous network failure. */
    suspend fun upsertRecord(session: AuthSession, record: JSONObject, expectedRevision: Long, operationId: String): JSONObject {
        require(expectedRevision >= 0)
        requireUuid(operationId)
        val id = requireUuid(record.getString("id"))
        val body = JSONObject().put("p_record", record).put("p_expected_revision", expectedRevision)
            .put("p_operation_id", operationId).toString()
        val result = jsonObject(transport.execute(HttpRequest(
            "POST", "/rest/v1/rpc/upsert_record", headers(session.accessToken), body,
        )).requireSuccess())
        checkOwner(result, session)
        if (result.optString("id") != id || result.optLong("revision", -1) != expectedRevision + 1) throw InvalidCloudResponse()
        return result
    }

    private suspend fun listAll(session: AuthSession, table: String, filter: String, order: String = "id.asc"): List<JSONObject> {
        val complete = mutableListOf<JSONObject>()
        var offset = 0
        while (true) {
            val body = transport.execute(HttpRequest(
                "GET", "/rest/v1/$table?select=*&order=$order&limit=100&offset=$offset$filter", headers(session.accessToken),
            )).requireSuccess()
            val page = jsonArray(body)
            if (page.length() > 100) throw InvalidCloudResponse()
            for (index in 0 until page.length()) {
                val row = page.optJSONObject(index) ?: throw InvalidCloudResponse()
                checkOwner(row, session)
                complete += row
            }
            if (page.length() < 100) return complete
            // Fail explicitly instead of silently presenting an incomplete or unbounded response.
            if (offset >= 100_000) throw InvalidCloudResponse()
            offset += 100
        }
    }

    private fun checkOwner(record: JSONObject, session: AuthSession) {
        val owner = record.optString("owner_id")
        if (owner.isBlank()) throw InvalidCloudResponse()
        if (owner != session.userId) throw AccountIdentityMismatch()
    }
}
