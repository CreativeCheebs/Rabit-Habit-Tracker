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
@Serializable data class CreateApiTokenRequest(val name: String)
@Serializable data class ApiTokenResponse(val id: String, val name: String, val createdAt: Long, val expiresAt: Long)
@Serializable data class CreatedApiTokenResponse(val token: String, val info: ApiTokenResponse)

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

private val safeUid = Regex("[A-Za-z0-9._:@#+~-]{1,200}")
private val safeFieldName = Regex("_?[a-z][a-z_]{0,31}")

/**
 * Row ids and reference fields end up in the browser app's markup, so only a
 * plain charset is accepted. Every client generates ids within it.
 */
fun isSafeSyncRow(row: SyncRow): Boolean =
    row.u.matches(safeUid) &&
        row.v.all { (field, value) ->
            field.matches(safeFieldName) &&
                (!(field == "uid" || field.endsWith("_uid")) || value !is JsonPrimitive || !value.isString || value.content.matches(safeUid))
        } &&
        row.s.keys.all { it.matches(safeFieldName) }

private const val WEB_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
    "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"

private fun ApiTokenInfo.response() = ApiTokenResponse(id, name, createdAt, expiresAt)

/** Token management requires the account's login credential, never an integration token. */
private fun io.ktor.server.application.ApplicationCall.loginAccount(): String? {
    val account = request.headers["X-Account-Id"]?.takeIf(::isValidAccountId) ?: return null
    val bearer = request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: return null
    if (!Accounts.exists(account)) return null
    return account.takeIf { java.security.MessageDigest.isEqual(bearerFor(requireSyncSecret(), account).toByteArray(), bearer.toByteArray()) }
}

private fun tokenStore() = ApiTokenStore(File(System.getenv("HABITLAB_SYNC_DATA") ?: "data"))

fun main() {
    requireSyncSecret()
    val port = System.getenv("HABITLAB_SYNC_PORT")?.toIntOrNull() ?: 8787
    val host = System.getenv("HABITLAB_SYNC_HOST") ?: "127.0.0.1"
    embeddedServer(Netty, host = host, port = port) { syncModule() }.start(wait = true)
}

private fun Application.syncModule() {
    install(ContentNegotiation) { json(wireJson) }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("X-Frame-Options", "DENY")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        call.response.headers.append("Content-Security-Policy", WEB_CSP)
        if (call.request.local.uri.startsWith("/api/")) call.response.headers.append("Cache-Control", "no-store")
    }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok", "service" to "habitlab-sync")) }
        post("/api/sign-in") {
            val credentials = call.receive<SignInRequest>()
            val client = call.request.local.remoteAddress
            val user = credentials.username.trim().lowercase().take(254)
            val limiter = SignInLimiter.instance
            if (limiter.blocked(client, user)) {
                call.response.header("Retry-After", "600")
                call.respond(io.ktor.http.HttpStatusCode.TooManyRequests, mapOf("error" to "Too many failed attempts. Try again in a few minutes.")); return@post
            }
            val account = Accounts.verify(credentials.username, credentials.password)
            if (account == null) {
                limiter.failure(client, user)
                call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Invalid username or password")); return@post
            }
            limiter.success(user)
            call.respond(SignInResponse(account, bearerFor(requireSyncSecret(), account)))
        }
        get("/api/config") { call.respond(mapOf("signup" to Accounts.signupAllowed())) }
        post("/api/register") {
            if (!Accounts.signupAllowed()) {
                call.respond(io.ktor.http.HttpStatusCode.Forbidden, mapOf("error" to "Account creation is turned off on this server.")); return@post
            }
            if (!SignupLimiter.allow(call.request.local.remoteAddress)) {
                call.respond(io.ktor.http.HttpStatusCode.TooManyRequests, mapOf("error" to "Too many attempts. Try again in a few minutes.")); return@post
            }
            val request = call.receive<SignInRequest>()
            when (val result = Accounts.register(request.username, request.password)) {
                is RegisterResult.Created -> call.respond(SignInResponse(result.account, bearerFor(requireSyncSecret(), result.account)))
                RegisterResult.Taken -> call.respond(io.ktor.http.HttpStatusCode.Conflict, mapOf("error" to "That username is taken."))
                is RegisterResult.Invalid -> call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to result.message))
            }
        }
        get("/api/tokens") {
            val account = call.loginAccount() ?: run { call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required")); return@get }
            call.respond(tokenStore().list(account).map { it.response() })
        }
        post("/api/tokens") {
            val account = call.loginAccount() ?: run { call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required")); return@post }
            val request = call.receive<CreateApiTokenRequest>()
            try {
                val created = tokenStore().create(account, request.name, 90L * 24 * 60 * 60 * 1000)
                call.respond(io.ktor.http.HttpStatusCode.Created, CreatedApiTokenResponse(created.token, created.info.response()))
            } catch (e: IllegalArgumentException) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid token name")))
            }
        }
        delete("/api/tokens/{id}") {
            val account = call.loginAccount() ?: run { call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required")); return@delete }
            val id = call.parameters["id"] ?: ""
            if (!tokenStore().revoke(account, id)) { call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Token not found")); return@delete }
            call.respond(mapOf("revoked" to true))
        }
        get("/api/integration/{table}") {
            val bearer = call.request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            val account = bearer?.let { tokenStore().accountFor(it) }
            if (account == null || !Accounts.exists(account)) { call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Valid integration token required")); return@get }
            val table = call.parameters["table"]
            if (table !in setOf("habit", "run", "run_habit", "check_in")) { call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Unknown collection")); return@get }
            val file = File(System.getenv("HABITLAB_SYNC_DATA") ?: "data", "${accountFileKey(account)}.sqlite")
            if (!file.exists()) { call.respond(emptyList<JsonObject>()); return@get }
            val rows = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
                db.prepareStatement("SELECT uid,field_name,value_json FROM sync_field WHERE table_name = ? ORDER BY uid").use { ps ->
                    ps.setString(1, table)
                    ps.executeQuery().use { rs ->
                        val items = linkedMapOf<String, MutableMap<String, kotlinx.serialization.json.JsonElement>>()
                        while (rs.next()) {
                            val uid = rs.getString(1); val field = rs.getString(2); val raw = rs.getString(3)
                            val item = items.getOrPut(uid) { linkedMapOf("uid" to JsonPrimitive(uid)) }
                            if (raw != null) item[field] = wireJson.parseToJsonElement(raw)
                        }
                        items.values.filter { it["_deleted"] != JsonPrimitive(true) }.map { JsonObject(it) }
                    }
                }
            }
            call.respond(rows)
        }
        post("/api/sync") {
            val account = call.request.headers["X-Account-Id"]?.takeIf(::isValidAccountId)
            val bearer = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            val secret = requireSyncSecret()
            val expectedToken = account?.let { user -> bearerFor(secret, user) }
            if (account == null || !Accounts.exists(account) || bearer == null || expectedToken == null || !java.security.MessageDigest.isEqual(expectedToken.toByteArray(), bearer.toByteArray())) {
                call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required")); return@post
            }
            val req = call.receive<SyncRequest>()
            if (!req.device.matches(Regex("[a-fA-F0-9-]{8,64}")) || req.token < 0 || req.changes.size > 10000) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid sync request")); return@post
            }
            if (!req.changes.all(::isSafeSyncRow)) {
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
