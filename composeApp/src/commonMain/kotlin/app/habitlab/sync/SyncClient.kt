package app.habitlab.sync

import app.habitlab.db.HabitDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

const val SYNC_MODE_KEY = "sync_mode"
const val SYNC_MODE_SERVER = "server"
const val SYNC_MODE_OFFLINE = "offline"
const val SYNC_URL_KEY = "sync_url"
const val SYNC_ACCOUNT_KEY = "sync_account"
const val SYNC_TOKEN_KEY = "sync_token"

private val syncJson = Json { ignoreUnknownKeys = true }

sealed interface SyncOutcome {
    data class Success(val received: Int, val sent: Int) : SyncOutcome
    data object NotConfigured : SyncOutcome
    data class LocalProblem(val message: String) : SyncOutcome
    data class Unreachable(val message: String) : SyncOutcome
    data class ServerRefused(val status: Int, val message: String) : SyncOutcome
}

sealed class SignInProblem(message: String) : Exception(message) {
    class Rejected : SignInProblem("The server did not accept that username or password.")
    class Unreachable(val url: String) : SignInProblem("Can't reach the sync server at $url.")
    class ServerError(val status: Int) : SignInProblem("The server answered $status.")
}

/**
 * Device side of the guide's one-call sync: export own changes, POST them,
 * apply the answer, then advance token and pushed only on success so a failed
 * sync simply repeats next time.
 */
class SyncClient(private val db: HabitDatabase, private val http: HttpClient) {
    fun mode(): String? = db.metaValue(SYNC_MODE_KEY)
    fun setMode(mode: String) = db.setMetaValue(SYNC_MODE_KEY, mode)
    fun serverUrl(): String? = db.metaValue(SYNC_URL_KEY)
    fun account(): String? = db.metaValue(SYNC_ACCOUNT_KEY)

    suspend fun signIn(url: String, username: String, password: String): String {
        val base = url.trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "The server address must start with http:// or https://."
        }
        val body = JsonObject(
            mapOf("username" to JsonPrimitive(username), "password" to JsonPrimitive(password)),
        )
        val response = try {
            http.post("$base/api/sign-in") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: Exception) {
            throw SignInProblem.Unreachable(base)
        }
        if (!response.status.isSuccess()) {
            if (response.status.value == 401) throw SignInProblem.Rejected()
            throw SignInProblem.ServerError(response.status.value)
        }
        val parsed = syncJson.parseToJsonElement(response.bodyAsText()) as JsonObject
        val account = (parsed["account"] as JsonPrimitive).content
        val token = (parsed["accessToken"] as JsonPrimitive).content
        db.setMetaValue(SYNC_URL_KEY, base)
        db.setMetaValue(SYNC_ACCOUNT_KEY, account)
        db.setMetaValue(SYNC_TOKEN_KEY, token)
        db.setMetaValue(SYNC_MODE_KEY, SYNC_MODE_SERVER)
        return account
    }

    suspend fun syncOnce(onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> }): SyncOutcome {
        val base = serverUrl() ?: return SyncOutcome.NotConfigured
        val account = account() ?: return SyncOutcome.NotConfigured
        val authToken = db.metaValue(SYNC_TOKEN_KEY) ?: return SyncOutcome.NotConfigured
        val device: String
        val token: Long
        val changes: List<SyncChange>
        val upto: Long
        try {
            device = db.metaValue("device") ?: error("sync_meta.device is missing")
            token = db.metaValue("token")?.toLongOrNull() ?: 0L
            val exported = exportChanges(db)
            changes = exported.changes
            upto = exported.upto
        } catch (e: Exception) {
            return SyncOutcome.LocalProblem("This phone's data could not be read: ${e.message}")
        }
        val request = JsonObject(
            mapOf(
                "device" to JsonPrimitive(device),
                "token" to JsonPrimitive(token),
                "changes" to kotlinx.serialization.json.JsonArray(changes.map { it.toWire() }),
            ),
        )
        val response = try {
            http.post("$base/api/sync") {
                header("X-Account-Id", account)
                header("Authorization", "Bearer $authToken")
                contentType(ContentType.Application.Json)
                setBody(request.toString())
            }
        } catch (e: Exception) {
            return SyncOutcome.Unreachable("Can't reach the sync server at $base.")
        }
        if (!response.status.isSuccess()) {
            return SyncOutcome.ServerRefused(response.status.value, "The server answered ${response.status.value}.")
        }
        val total = response.headers["X-Sync-Byte-Size"]?.toLongOrNull()
        onProgress(0, total)
        val text = response.bodyAsText()
        onProgress(text.length.toLong(), total)
        return try {
            val parsed = syncJson.parseToJsonElement(text) as JsonObject
            val serverToken = (parsed["token"] as JsonPrimitive).long
            val incoming = (parsed["changes"] as kotlinx.serialization.json.JsonArray).map {
                syncChangeFromWire(it as JsonObject)
            }
            applyChanges(db, incoming)
            db.setMetaValue("token", serverToken.toString())
            markExported(db, upto)
            SyncOutcome.Success(received = incoming.size, sent = changes.size)
        } catch (e: Exception) {
            SyncOutcome.LocalProblem("This phone's data could not be updated: ${e.message}")
        }
    }

    private fun HabitDatabase.metaValue(key: String): String? =
        schemaQueries.selectMetaValue(key).executeAsOneOrNull()

    private fun HabitDatabase.setMetaValue(key: String, value: String) {
        schemaQueries.upsertMetaValue(key, value)
    }
}
