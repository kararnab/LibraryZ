package com.libraryz.data

import com.libraryz.data.api.UpsertLibraryRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server's merge cases (internal/library/testdata/merge_cases.json,
 * generated into [MERGE_CASES_JSON]) through [applyLocally]: the app shows
 * what the server will store.
 */
class LibraryMergeCasesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    // started_at and finished_at are wall-clock, so they're left out.
    private val fields = listOf(
        "status", "shelf", "current_page", "total_pages", "progress_percent", "rating",
        "notes", "notes_other", "status_stamp", "progress_stamp", "rating_stamp", "shelf_stamp", "notes_stamp",
    )

    /** The compared fields that aren't zero (missing, null, "" and 0 all are). */
    private fun view(o: JsonObject): Map<String, String> = fields.mapNotNull { k ->
        val v = o[k]
        if (v == null || v == JsonNull) return@mapNotNull null
        val s = v.jsonPrimitive.content
        if (s == "" || s == "0") null else k to s
    }.toMap()

    @Test
    fun appliesLikeTheServer() {
        val cases = json.parseToJsonElement(MERGE_CASES_JSON).jsonObject["cases"]!!.jsonArray
        assertTrue(cases.isNotEmpty())
        val failures = cases.mapNotNull { c ->
            val case = c.jsonObject
            val name = (case["name"] as JsonPrimitive).content
            val ub = case["steps"]!!.jsonArray.fold(null as UserBook?) { ub, step ->
                val req = json.decodeFromJsonElement(UpsertLibraryRequest.serializer(), step.jsonObject["put"]!!)
                applyLocally(ub, "w1", req, "2026-10-11T00:00:00Z", null, 1)
            }
            val got = view(json.encodeToJsonElement(ub).jsonObject)
            val want = view(case["want"]!!.jsonObject)
            if (got == want) null else "$name:\n  got  $got\n  want $want"
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }
}
