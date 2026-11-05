package app.habitlab.sync

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.DriverManager
import java.io.File

@Serializable data class Stamp(val c: Long, val d: String)
@Serializable data class SyncRow(val t: String, val u: String, val v: JsonObject, val s: Map<String, Stamp>)
@Serializable data class SyncRequest(val device: String, val token: Long, val changes: List<SyncRow>)
@Serializable data class SyncResponse(val token: Long, val changes: List<SyncRow>)
@Serializable data class SignInRequest(val username: String, val password: String)
@Serializable data class SignInResponse(val account: String, val accessToken: String)

private val wireJson = Json { ignoreUnknownKeys = true }

fun requireSyncSecret(): String {
    val secret = System.getenv("HABITLAB_SYNC_SECRET")
        ?: error("HABITLAB_SYNC_SECRET is required")
    require(secret.toByteArray().size >= 32) { "HABITLAB_SYNC_SECRET must be at least 32 bytes" }
    return secret
}

fun bearerFor(secret: String, username: String): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            doFinal(username.toByteArray())
        }
    )

fun accountFileKey(account: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
        .joinToString("") { "%02x".format(it) }

fun isValidAccountId(account: String): Boolean =
    account.matches(Regex("[A-Za-z0-9._@+-]{1,254}"))

fun main() {
    requireSyncSecret()
    val port = System.getenv("HABITLAB_SYNC_PORT")?.toIntOrNull() ?: 8787
    val host = System.getenv("HABITLAB_SYNC_HOST") ?: "127.0.0.1"
    embeddedServer(Netty, host = host, port = port) { syncModule() }.start(wait = true)
}

private fun Application.syncModule() {
    install(ContentNegotiation) { json(wireJson) }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok", "service" to "habitlab-sync")) }
        post("/api/sign-in") {
            val credentials = call.receive<SignInRequest>()
            val users = wireJson.parseToJsonElement(System.getenv("HABITLAB_SYNC_USERS") ?: "{}").let { it as? JsonObject ?: JsonObject(emptyMap()) }
            val expected = (users[credentials.username] as? JsonPrimitive)?.content
            if (expected == null || !java.security.MessageDigest.isEqual(expected.toByteArray(), credentials.password.toByteArray())) {
                call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Invalid username or password")); return@post
            }
            val secret = requireSyncSecret()
            call.respond(SignInResponse(credentials.username, bearerFor(secret, credentials.username)))
        }
        post("/api/sync") {
            val account = call.request.headers["X-Account-Id"]?.takeIf(::isValidAccountId)
            val bearer = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            val secret = requireSyncSecret()
            val expectedToken = account?.let { user -> bearerFor(secret, user) }
            val users = wireJson.parseToJsonElement(System.getenv("HABITLAB_SYNC_USERS") ?: "{}").let { it as? JsonObject ?: JsonObject(emptyMap()) }
            if (account == null || account !in users || bearer == null || expectedToken == null || !java.security.MessageDigest.isEqual(expectedToken.toByteArray(), bearer.toByteArray())) {
                call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required")); return@post
            }
            val req = call.receive<SyncRequest>()
            if (!req.device.matches(Regex("[a-fA-F0-9-]{8,64}")) || req.token < 0 || req.changes.size > 10000) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid sync request")); return@post
            }
            val accountKey = accountFileKey(account)
            val file = File(System.getenv("HABITLAB_SYNC_DATA") ?: "data", "$accountKey.sqlite")
            file.parentFile?.mkdirs()
            val response = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
                db.createStatement().use { st ->
                    st.execute("PRAGMA temp_store = MEMORY")
                    st.execute("CREATE TABLE IF NOT EXISTS sync_field (table_name TEXT NOT NULL, uid TEXT NOT NULL, field_name TEXT NOT NULL, value_json TEXT, clock INTEGER NOT NULL, device TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(table_name,uid,field_name))")
                    st.execute("CREATE TABLE IF NOT EXISTS sync_counter (id INTEGER PRIMARY KEY CHECK(id=1), seq INTEGER NOT NULL)")
                    st.execute("INSERT OR IGNORE INTO sync_counter VALUES (1,0)")
                }
                db.autoCommit = false
                try {
                    db.prepareStatement("SELECT clock, device, value_json FROM sync_field WHERE table_name=? AND uid=? AND field_name=?").use { get ->
                      db.prepareStatement("INSERT INTO sync_field(table_name,uid,field_name,value_json,clock,device,seq) VALUES (?,?,?,?,?,?,?) ON CONFLICT(table_name,uid,field_name) DO UPDATE SET value_json=excluded.value_json,clock=excluded.clock,device=excluded.device,seq=excluded.seq").use { save ->
                        val increment = db.prepareStatement("UPDATE sync_counter SET seq=seq+1 WHERE id=1")
                        req.changes.forEach { row ->
                            if (row.t !in setOf("habit", "run", "run_habit", "check_in", "goal", "tracker", "tracker_entry")) throw IllegalArgumentException("Unknown table ${row.t}")
                            val fields = row.v.entries.associate { it.key to it.value.toString() }.toMutableMap()
                            fields["_deleted"] = row.s["_deleted"]?.let { "true" } ?: "false"
                            val names = (fields.keys + row.s.keys).toSet()
                            names.forEach { field ->
                                val stamp = row.s[field] ?: row.s["_row"] ?: return@forEach
                                val incomingValue = fields[field]
                                get.setString(1,row.t); get.setString(2,row.u); get.setString(3,field)
                                val wins = get.executeQuery().use { rs ->
                                    if (!rs.next()) true else {
                                        val oldClock=rs.getLong(1); val oldDevice=rs.getString(2); val oldValue=rs.getString(3) ?: ""
                                        stamp.c > oldClock || (stamp.c == oldClock && (stamp.d > oldDevice || (stamp.d == oldDevice && (incomingValue ?: "") > oldValue)))
                                    }
                                }
                                if (wins) {
                                    increment.executeUpdate()
                                    val seq=db.createStatement().use { st -> st.executeQuery("SELECT seq FROM sync_counter WHERE id=1").use { rs -> rs.next(); rs.getLong(1) } }
                                    save.setString(1,row.t); save.setString(2,row.u); save.setString(3,field); save.setString(4,incomingValue); save.setLong(5,stamp.c); save.setString(6,stamp.d); save.setLong(7,seq); save.addBatch()
                                }
                            }
                        }
                        save.executeBatch(); increment.close()
                      }
                    }
                    val current = db.createStatement().use { st -> st.executeQuery("SELECT seq FROM sync_counter WHERE id=1").use { rs -> rs.next(); rs.getLong(1) } }
                    val rows = db.prepareStatement("SELECT table_name,uid,field_name,value_json,clock,device FROM sync_field WHERE seq > ? AND device <> ? ORDER BY seq").use { ps ->
                        ps.setLong(1, req.token); ps.setString(2, req.device)
                        ps.executeQuery().use { rs ->
                          val grouped=linkedMapOf<Pair<String,String>,Triple<MutableMap<String,kotlinx.serialization.json.JsonElement>,MutableMap<String,Stamp>,String>>()
                          while (rs.next()) {
                            val key=rs.getString(1) to rs.getString(2); val field=rs.getString(3); val raw=rs.getString(4)
                            val item=grouped.getOrPut(key) { Triple(linkedMapOf(),linkedMapOf(),rs.getString(6)) }
                            if (field == "_deleted") item.first[field]=JsonPrimitive(raw == "true")
                            else if (raw != null) item.first[field]=wireJson.parseToJsonElement(raw)
                            item.second[field]=Stamp(rs.getLong(5),rs.getString(6))
                          }
                          grouped.map { (key,value) -> SyncRow(key.first,key.second,JsonObject(value.first),value.second) }
                        }
                    }
                    db.commit(); SyncResponse(current, rows)
                } catch (e: Exception) { db.rollback(); throw e } finally { db.autoCommit = true }
            }
            // Byte size ahead of the body so a first download can show progress.
            call.response.header("X-Sync-Byte-Size", wireJson.encodeToString(SyncResponse.serializer(), response).length.toString())
            call.respond(response)
        }
        // Web app bundle (static, same origin as the API so no CORS is needed).
        staticResources("/", "webapp") { default("index.html") }
    }
}
