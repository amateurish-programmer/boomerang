package com.boomerang.app.cloud

import org.json.JSONObject

/** Cloud results are detached JSON values. Only the sync coordinator may merge them into Room. */
class BackendRepository(private val transport: HttpTransport) {
    suspend fun listAllRecords(session: AuthSession): List<JSONObject> =
        listAll(session, "records", "")

    suspend fun listSources(session: AuthSession, recordId: String): List<JSONObject> =
        listAll(session, "sources", "&record_id=eq.${requireUuid(recordId)}&archived_at=is.null")

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

    suspend fun syncRecord(session: AuthSession, pending: PendingUpload, operationId: String): JSONObject {
        val body = JSONObject().put("p_record", JSONObject(pending.recordJson)).put("p_sources", org.json.JSONArray(pending.sourcesJson))
            .put("p_expected_revision", pending.serverRevision).put("p_operation_id", requireUuid(operationId))
        val result = jsonObject(transport.execute(HttpRequest("POST", "/rest/v1/rpc/sync_record", headers(session.accessToken), body.toString())).requireSuccess())
        checkOwner(result, session)
        if (result.optString("id") != pending.recordId || result.optLong("revision", -1) != pending.serverRevision + 1) throw InvalidCloudResponse()
        return result
    }

    fun syncRemote(): SyncRemote = object : SyncRemote {
        override suspend fun upload(session: AuthSession, pending: PendingUpload, operationId: String) = syncRecord(session, pending, operationId)
        override suspend fun records(session: AuthSession) = listAllRecords(session)
        override suspend fun sources(session: AuthSession, recordId: String) = listSources(session, recordId)
        override suspend fun acknowledgedSources(session: AuthSession, recordId: String, revision: Long): List<JSONObject> {
            require(revision > 0)
            val result = jsonArray(transport.execute(HttpRequest("GET",
                "/rest/v1/record_source_revisions?select=*&record_id=eq.${requireUuid(recordId)}&revision=eq.$revision&limit=1", headers(session.accessToken))).requireSuccess())
            if (result.length() != 1) throw InvalidCloudResponse()
            val row = result.getJSONObject(0)
            checkOwner(row, session)
            if (row.optString("record_id") != recordId || row.optLong("revision", -1) != revision) throw InvalidCloudResponse()
            return checkedSources(row.getJSONArray("snapshot"), session, recordId)
        }
        override suspend fun snapshot(session: AuthSession, row: JSONObject): RemoteRecord {
            val id = requireUuid(row.getString("id"))
            val body = JSONObject().put("p_record_id", id)
            val result = jsonObject(transport.execute(HttpRequest("POST", "/rest/v1/rpc/get_record_snapshot", headers(session.accessToken), body.toString())).requireSuccess())
            val record = result.getJSONObject("record")
            checkOwner(record, session)
            if (record.optString("id") != id || record.optLong("revision", -1) < row.getLong("revision")) throw InvalidCloudResponse()
            val sources = checkedSources(result.getJSONArray("sources"), session, id)
            return RemoteRecord(record.toString(), org.json.JSONArray(sources).toString())
        }
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

    private fun checkedSources(array: org.json.JSONArray, session: AuthSession, recordId: String): List<JSONObject> {
        if (array.length() > 1000) throw InvalidCloudResponse()
        return (0 until array.length()).map { index ->
            array.getJSONObject(index).also { source ->
                checkOwner(source, session)
                if (source.optString("record_id") != recordId) throw InvalidCloudResponse()
                requireUuid(source.getString("id"))
            }
        }
    }
    private fun checkOwner(record: JSONObject, session: AuthSession) {
        val owner = record.optString("owner_id")
        if (owner.isBlank()) throw InvalidCloudResponse()
        if (owner != session.userId) throw AccountIdentityMismatch()
    }
}
