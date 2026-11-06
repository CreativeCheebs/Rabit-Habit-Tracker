package app.habitlab.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.habitlab.db.HabitDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncClientTest {
    private fun freshDb(): HabitDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HabitDatabase.Schema.create(driver)
        ensureSyncTriggers(driver)
        return HabitDatabase(driver)
    }

    private suspend fun HttpRequestData.bodyJson(): JsonObject {
        val content = body as? OutgoingContent.ByteArrayContent ?: error("unexpected body $body")
        return Json.parseToJsonElement(String(content.bytes())) as JsonObject
    }

    @Test
    fun signInStoresAccountAndToken() = kotlinx.coroutines.test.runTest {
        val db = freshDb()
        val client = SyncClient(db, HttpClient(MockEngine { request ->
            assertEquals("/api/sign-in", request.url.encodedPath)
            val body = request.bodyJson()
            assertEquals("user@example.com", (body["username"] as JsonPrimitive).content)
            respond(
                """{"account":"user@example.com","accessToken":"tok123"}""",
                HttpStatusCode.OK,
                headersOf("Content-Type" to listOf("application/json")),
            )
        }))
        val account = client.signIn("http://sync.test:8788/", "user@example.com", "secret")
        assertEquals("user@example.com", account)
        assertEquals("http://sync.test:8788", db.schemaQueries.selectMetaValue("sync_url").executeAsOne())
        assertEquals("user@example.com", db.schemaQueries.selectMetaValue("sync_account").executeAsOne())
        assertEquals("tok123", db.schemaQueries.selectMetaValue("sync_token").executeAsOne())
        assertEquals("server", db.schemaQueries.selectMetaValue("sync_mode").executeAsOne())
    }

    private fun signInEngine(account: String = "user@example.com", body: String? = null) = MockEngine {
        respond(
            body ?: """{"account":"$account","accessToken":"tok"}""",
            HttpStatusCode.OK,
            headersOf("Content-Type" to listOf("application/json")),
        )
    }

    private fun connected(db: HabitDatabase, url: String = "http://sync.test:8788", account: String = "user@example.com") {
        db.schemaQueries.upsertMetaValue("sync_url", url)
        db.schemaQueries.upsertMetaValue("sync_account", account)
        db.schemaQueries.upsertMetaValue("token", "40")
        db.schemaQueries.upsertMetaValue("pushed", "12")
    }

    @Test
    fun switchingServerNeedsConfirmationAndChangesNothing() = kotlinx.coroutines.test.runTest {
        val db = freshDb()
        connected(db)
        db.schemaQueries.insertHabit("h1", "Mine", "Focus", 1L, 2L, "weekly", 3L)
        val client = SyncClient(db, HttpClient(signInEngine()))
        val problem = assertFailsWith<SignInProblem.SwitchNeedsConfirmation> {
            client.signIn("http://sync.test:8789", "user@example.com", "secret")
        }
        assertEquals("user@example.com", problem.account)
        assertEquals("http://sync.test:8788", db.schemaQueries.selectMetaValue("sync_url").executeAsOne())
        assertEquals("40", db.schemaQueries.selectMetaValue("token").executeAsOne())
        assertEquals(1, db.schemaQueries.selectHabits().executeAsList().size)
    }

    @Test
    fun confirmedSwitchClearsLocalDataWithoutRecordingDeletes() = kotlinx.coroutines.test.runTest {
        val db = freshDb()
        connected(db, account = "a@example.com")
        db.schemaQueries.insertHabit("h1", "Account A habit", "Focus", 1L, 2L, "weekly", 3L)
        val client = SyncClient(db, HttpClient(signInEngine("b@example.com")))
        client.signIn("http://sync.test:8789", "b@example.com", "secret", replaceLocalData = true)
        assertEquals(0, db.schemaQueries.selectHabits().executeAsList().size)
        assertEquals("0", db.schemaQueries.selectMetaValue("token").executeAsOne())
        assertEquals("0", db.schemaQueries.selectMetaValue("pushed").executeAsOne())
        assertEquals("b@example.com", db.schemaQueries.selectMetaValue("sync_account").executeAsOne())
        assertEquals(0, exportChanges(db).changes.size, "account A's data must not travel to account B")
    }

    @Test
    fun firstSignInFromOfflineKeepsLocalDataAndSendsIt() = kotlinx.coroutines.test.runTest {
        val db = freshDb()
        db.schemaQueries.upsertMetaValue("sync_mode", "offline")
        db.schemaQueries.insertHabit("h1", "Made offline", "Focus", 1L, 2L, "weekly", 3L)
        val client = SyncClient(db, HttpClient(signInEngine()))
        client.signIn("http://sync.test:8788", "user@example.com", "secret")
        assertEquals(1, db.schemaQueries.selectHabits().executeAsList().size)
        assertEquals(1, exportChanges(db).changes.size)
    }

    @Test
    fun signInAgainToSameServerKeepsSyncPosition() = kotlinx.coroutines.test.runTest {
        val db = freshDb()
        connected(db)
        val client = SyncClient(db, HttpClient(signInEngine()))
        client.signIn("http://sync.test:8788/", "user@example.com", "secret")
        assertEquals("40", db.schemaQueries.selectMetaValue("token").executeAsOne())
        assertEquals("12", db.schemaQueries.selectMetaValue("pushed").executeAsOne())
    }

    @Test
    fun malformedSignInAnswerIsReportedNotThrownRaw() = kotlinx.coroutines.test.runTest {
        val db = freshDb()
        val client = SyncClient(db, HttpClient(signInEngine(body = "<html>not json</html>")))
        assertFailsWith<SignInProblem.Refused> { client.signIn("http://sync.test:8788", "user@example.com", "secret") }
        assertEquals(null, db.schemaQueries.selectMetaValue("sync_account").executeAsOneOrNull())
    }

    @Test
    fun signInRejectsBadPasswordWithoutStoring()= kotlinx.coroutines.test.runTest {
        val db = freshDb()
        val client = SyncClient(db, HttpClient(MockEngine {
            respond("""{"error":"nope"}""", HttpStatusCode.Unauthorized)
        }))
        assertFailsWith<SignInProblem.Rejected> {
            client.signIn("http://sync.test:8788", "user@example.com", "wrong")
        }
        assertEquals(null, db.schemaQueries.selectMetaValue("sync_account").executeAsOneOrNull())
    }

    @Test
    fun syncPostsExportAndAppliesAnswer() = kotlinx.coroutines.test.runTest {
        val db = freshDb()
        db.schemaQueries.insertHabit("h1", "Morning light", "Wellbeing", 1L, 2L, "weekly", 3L)
        db.schemaQueries.upsertMetaValue("sync_url", "http://sync.test:8788")
        db.schemaQueries.upsertMetaValue("sync_account", "user@example.com")
        db.schemaQueries.upsertMetaValue("sync_token", "tok123")
        db.schemaQueries.upsertMetaValue("token", "4")

        var seenDevice = ""
        var seenToken = -1L
        var seenSent = -1
        val incoming = SyncChange(
            table = "habit",
            uid = "h2",
            values = mapOf(
                "uid" to JsonPrimitive("h2"),
                "title" to JsonPrimitive("Plan the day"),
                "group_name" to JsonPrimitive("Focus"),
                "iteration" to JsonPrimitive(1),
                "impact_points" to JsonPrimitive(3),
                "recurrence_type" to JsonPrimitive("weekly"),
                "recurrence_value" to JsonPrimitive(3),
                "archived" to JsonPrimitive(0),
            ),
            stamps = mapOf("title" to FieldStamp(5000, "other-device")),
        )
        val client = SyncClient(db, HttpClient(MockEngine { request ->
            assertEquals("/api/sync", request.url.encodedPath)
            assertEquals("user@example.com", request.headers["X-Account-Id"])
            assertEquals("Bearer tok123", request.headers["Authorization"])
            val body = request.bodyJson()
            seenDevice = (body["device"] as JsonPrimitive).content
            seenToken = (body["token"] as JsonPrimitive).long
            seenSent = body["changes"]!!.jsonArray.size
            respond(
                """{"token":9,"changes":[${incoming.toWire()}]}""",
                HttpStatusCode.OK,
                headersOf("Content-Type" to listOf("application/json"), "X-Sync-Byte-Size" to listOf("128")),
            )
        }))

        val progress = mutableListOf<Pair<Long, Long?>>()
        val outcome = client.syncOnce { downloaded, total -> progress.add(downloaded to total) }

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals(1, outcome.received)
        assertEquals(1, outcome.sent)
        assertEquals(db.schemaQueries.selectMetaValue("device").executeAsOne(), seenDevice)
        assertEquals(4L, seenToken)
        assertEquals("9", db.schemaQueries.selectMetaValue("token").executeAsOne())
        assertTrue(progress.isNotEmpty())
        assertEquals(128L, progress.first().second)
        val titles = db.schemaQueries.selectHabits().executeAsList().associate { it.uid to it.title }
        assertEquals("Plan the day", titles["h2"])
        assertEquals("Morning light", titles["h1"])
    }

    @Test
    fun syncWithoutConfigurationReportsNotConfigured() = kotlinx.coroutines.test.runTest {
        val db = freshDb()
        val client = SyncClient(db, HttpClient(MockEngine {
            respond("{}", HttpStatusCode.OK)
        }))
        assertTrue(client.syncOnce() is SyncOutcome.NotConfigured)
    }

    @Test
    fun syncTransportFailureIsReported() = kotlinx.coroutines.test.runTest {
        val db = freshDb()
        db.schemaQueries.upsertMetaValue("sync_url", "http://down.test:8788")
        db.schemaQueries.upsertMetaValue("sync_account", "user@example.com")
        db.schemaQueries.upsertMetaValue("sync_token", "tok123")
        val client = SyncClient(db, HttpClient(MockEngine {
            throw java.io.IOException("connection refused")
        }))
        val outcome = client.syncOnce()
        assertTrue(outcome is SyncOutcome.Unreachable)
    }

    @Test
    fun syncServerErrorIsReported() = kotlinx.coroutines.test.runTest {
        val db = freshDb()
        db.schemaQueries.upsertMetaValue("sync_url", "http://sync.test:8788")
        db.schemaQueries.upsertMetaValue("sync_account", "user@example.com")
        db.schemaQueries.upsertMetaValue("sync_token", "tok123")
        val client = SyncClient(db, HttpClient(MockEngine {
            respond("boom", HttpStatusCode.InternalServerError)
        }))
        val outcome = client.syncOnce()
        assertTrue(outcome is SyncOutcome.ServerRefused)
        assertEquals(500, (outcome as SyncOutcome.ServerRefused).status)
    }
}
