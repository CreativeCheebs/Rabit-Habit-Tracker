package app.habitlab.sync

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The Reskon connected app (docs/reskon-plugin.md): the description file,
 * sign-in, one-minute tickets for the web page widget, the data feeds, and
 * "my data changed". Reskon only ever reads; ticks happen in the widget page
 * and are written through the same sync tables the app clients use.
 */

/** Keep in step with composeApp's versionName. */
private const val PLUGIN_VERSION = "1.3.1"

private val MANIFEST =
    """
    {"reskon_app":1,
     "id":"habits",
     "name":"Habit Lab",
     "version":"$PLUGIN_VERSION",
     "home_url":"/",
     "icon":"check",
     "auth":{"kind":"password","login_url":"/api/reskon/login"},
     "launch_url":"/api/reskon/launch",
     "connect_url":"/api/reskon/connect",
     "widgets":[
      {"id":"today","title":"Today's habits","kind":"page","url":"/widgets/today","size":{"w":4,"h":4},"style":"reskon"},
      {"id":"today_list","title":"Today","kind":"data","shape":"checklist","url":"/api/reskon/feeds/today_list","size":{"w":3,"h":3}},
      {"id":"last_days","title":"Done per day","kind":"data","shape":"series","url":"/api/reskon/feeds/last_days","ranged":true,"groups":["day","week"],"size":{"w":6,"h":3}},
      {"id":"streaks","title":"Streaks","kind":"data","shape":"table","url":"/api/reskon/feeds/streaks","size":{"w":6,"h":4}},
      {"id":"best_streak","title":"Longest streak","kind":"data","shape":"number","url":"/api/reskon/feeds/best_streak","size":{"w":3,"h":2}}
     ]}
    """.trimIndent()

private val PAGE_WIDGETS = setOf("today")
private val FEEDS = setOf("today_list", "last_days", "streaks", "best_streak")

/** The key shape Reskon hands out at connect_url (32 random bytes, base64url). */
private val HOOK_KEY = Regex("[A-Za-z0-9_-]{43}")

@Serializable data class ReskonLaunchRequest(val widget: String = "")
@Serializable data class ReskonSessionRequest(val ticket: String = "")
@Serializable data class ReskonConnectRequest(val changed_url: String = "", val key: String = "")
@Serializable data class ReskonTickRequest(val key: String = "", val done: Boolean = false, val tz: String = "")
@Serializable data class ReskonLaunchResponse(val ticket: String, val expires_in: Int)

/**
 * One-minute, single-use tickets for a page, and the twelve-hour page
 * sessions they become. Sessions live in memory: a restart simply makes the
 * widget ask Reskon for a fresh ticket.
 */
class ReskonPages(private val now: () -> Long = System::currentTimeMillis) {
    data class Session(val account: String, val widget: String, val until: Long)

    private val random = SecureRandom()
    private val tickets = LinkedHashMap<String, Session>()
    private val sessions = LinkedHashMap<String, Session>()

    private fun token(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private fun forgetOld() {
        val time = now()
        tickets.entries.removeIf { time >= it.value.until }
        sessions.entries.removeIf { time >= it.value.until }
    }

    @Synchronized
    fun issue(account: String, widget: String): String {
        forgetOld()
        while (tickets.size >= 1000) tickets.remove(tickets.keys.first())
        val ticket = token()
        tickets[ticket] = Session(account, widget, now() + TICKET_LIFE_MS)
        return ticket
    }

    /** Spends the ticket whether or not it was still good: one use, always. */
    @Synchronized
    fun redeem(ticket: String): String? {
        forgetOld()
        if (ticket.isEmpty() || ticket.length > 1024) return null
        val spent = tickets.remove(ticket) ?: return null
        if (now() >= spent.until) return null
        while (sessions.size >= MAX_SESSIONS) sessions.remove(sessions.keys.first())
        val session = token()
        sessions[session] = Session(spent.account, spent.widget, now() + SESSION_LIFE_MS)
        return session
    }

    @Synchronized
    fun session(token: String): Session? {
        forgetOld()
        return sessions[token]
    }

    companion object {
        const val TICKET_LIFE_MS = 60_000L
        const val SESSION_LIFE_MS = 12L * 60 * 60 * 1000
        const val MAX_SESSIONS = 1000
    }
}

/** Reskon's "my data changed" address and key, kept per account on disk. */
class ReskonConnect(private val dataDir: File = File(System.getenv("HABITLAB_SYNC_DATA") ?: "data")) {
    @Serializable
    data class Entry(val url: String, val key: String, val updated_at: Long = 0)

    @Serializable
    data class Store(val version: Int = 1, val entries: Map<String, Entry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private fun file() = File(dataDir, "reskon-connect.json")

    private fun read(): Store {
        val target = file()
        if (!target.exists()) return Store()
        return runCatching { json.decodeFromString(Store.serializer(), target.readText()) }.getOrDefault(Store())
    }

    @Synchronized
    fun save(account: String, url: String, key: String) {
        val store = read()
        val entries = store.entries + (account to Entry(url, key, System.currentTimeMillis()))
        dataDir.mkdirs()
        file().writeText(json.encodeToString(Store.serializer(), Store(entries = entries)))
    }

    @Synchronized
    fun get(account: String): Entry? = read().entries[account]

    /** The most recent Reskon address anyone connected from. */
    @Synchronized
    fun newestChangedUrl(): String? = read().entries.values.maxByOrNull { it.updated_at }?.url
}

/**
 * Calls Reskon's changed_url after a sync or a tick, at most once every two
 * seconds per account: a burst is folded into one trailing call, and a
 * failure is dropped, because widgets also refresh when they are shown.
 */
class ReskonChanged(
    private val connect: ReskonConnect,
    private val now: () -> Long = System::currentTimeMillis,
    private val schedule: (delayMs: Long, run: () -> Unit) -> Unit = { delay, run ->
        defaultScheduler.schedule(run, delay, TimeUnit.MILLISECONDS)
    },
    private val post: (url: String, key: String) -> Unit = ::postChanged,
) {
    private val lock = Any()
    private val lastSent = mutableMapOf<String, Long>()
    private val pending = mutableSetOf<String>()

    fun notify(account: String) {
        synchronized(lock) {
            if (account in pending) return
            val last = lastSent[account] ?: (now() - MIN_GAP_MS)
            val wait = (MIN_GAP_MS - (now() - last)).coerceAtLeast(0)
            pending += account
            schedule(wait) { fire(account) }
        }
    }

    private fun fire(account: String) {
        synchronized(lock) {
            pending -= account
            lastSent[account] = now()
        }
        val entry = runCatching { connect.get(account) }.getOrNull() ?: return
        runCatching { post(entry.url, entry.key) }
    }

    companion object {
        const val MIN_GAP_MS = 2000L

        private val defaultScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "reskon-changed").apply { isDaemon = true }
        }

        private val client: HttpClient by lazy {
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        }

        fun postChanged(url: String, key: String) {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer $key")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            client.send(request, HttpResponse.BodyHandlers.discarding())
        }
    }
}

/** The account whose sign-in token this is, if any. */
fun findAccount(token: String, candidates: List<String>, secret: String): String? {
    if (token.isEmpty() || token.length > 4096) return null
    val expected = token.toByteArray(Charsets.UTF_8)
    var found: String? = null
    for (account in candidates) {
        if (MessageDigest.isEqual(bearerFor(secret, account).toByteArray(Charsets.UTF_8), expected)) found = account
    }
    return found
}

/** Where Reskon lives, for framing widget pages and checking its messages. */
object ReskonConfig {
    /** The origin of an http(s) address, lowercased like a browser's; null when it is not one. */
    fun originOf(url: String): String? {
        val absolute = if (url.contains("://")) url else "http://$url"
        return try {
            val parsed = URI(absolute)
            val scheme = parsed.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (parsed.host.isNullOrEmpty()) return null
            val port = if (parsed.port != -1) ":${parsed.port}" else ""
            "$scheme://${parsed.host.lowercase()}$port"
        } catch (e: Exception) {
            null
        }
    }

    /** Every address widget pages may be framed by: the env override plus connected Reskons. */
    fun frameOrigins(): List<String> {
        val env = System.getenv("HABITLAB_RESKON_ORIGIN")?.trim()?.takeIf { it.isNotEmpty() }?.let(::originOf)
        val connected = ReskonConnect().newestChangedUrl()?.let(::originOf)
        return listOfNotNull(env, connected).distinct()
    }

    /** The origin the page checks messages against: the Reskon that connected. */
    fun pageOrigin(): String? =
        ReskonConnect().newestChangedUrl()?.let(::originOf)
            ?: System.getenv("HABITLAB_RESKON_ORIGIN")?.trim()?.takeIf { it.isNotEmpty() }?.let(::originOf)

    /** The widget pages' frame-ancestors value: the allowed origins, or 'none'. */
    fun frameCsp(): String =
        frameOrigins().joinToString(" ").ifEmpty { "'none'" }
}

private val pages by lazy { ReskonPages() }
private val connectStore by lazy { ReskonConnect() }
private val changed by lazy { ReskonChanged(connectStore) }

/** Called after a sync or a widget tick: Reskon fetches this account's widgets again. */
internal fun reskonDataChanged(account: String) = changed.notify(account)

private fun bearerOf(call: ApplicationCall): String? =
    call.request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")

private fun reskonAccount(call: ApplicationCall): String? =
    bearerOf(call)?.let { findAccount(it, Accounts.all(), requireSyncSecret()) }

private fun pageSession(call: ApplicationCall): ReskonPages.Session? = bearerOf(call)?.let { pages.session(it) }

private fun accountFile(account: String): File =
    File(System.getenv("HABITLAB_SYNC_DATA") ?: "data", "${accountFileKey(account)}.sqlite")

private fun reskonResource(name: String): String =
    checkNotNull(ReskonPages::class.java.getResourceAsStream("/reskon/$name")) { "Missing resource reskon/$name" }
        .bufferedReader().use { it.readText() }

private fun feedAnswer(account: String, id: String, call: ApplicationCall): JsonObject {
    val query = call.request.queryParameters
    val tz = query["tz"]
    val today = ReskonFeeds.todayIn(tz)
    val rows = ReskonFeeds.load(accountFile(account))
    return when (id) {
        "today_list" -> ReskonFeeds.checklist(rows, today)
        "streaks" -> ReskonFeeds.streakTable(rows, today)
        "best_streak" -> ReskonFeeds.bestStreak(rows, today)
        else -> {
            var from = query["from"]
            var to = query["to"]
            if (from == null || to == null || !ReskonFeeds.isDay(from) || !ReskonFeeds.isDay(to) || from > to) {
                to = today
                from = LocalDate.parse(today).minusDays(6).toString()
            }
            ReskonFeeds.series(rows, from, to, query["group"] ?: "day")
        }
    }
}

fun Routing.reskonRoutes() {
    // The description file: readable without signing in, always contract version 1.
    get("/.well-known/reskon-app.json") {
        call.respondText(MANIFEST, ContentType.Application.Json)
    }

    // Reskon swaps the person's username and password for its token once.
    post("/api/reskon/login") {
        val credentials = call.receive<SignInRequest>()
        val client = call.request.local.remoteAddress
        val user = credentials.username.trim().lowercase().take(254)
        val limiter = SignInLimiter.instance
        if (limiter.blocked(client, user)) {
            call.response.header("Retry-After", "600")
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many failed attempts. Try again in a few minutes."))
            return@post
        }
        val account = Accounts.verify(credentials.username, credentials.password)
        if (account == null) {
            limiter.failure(client, user)
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid username or password"))
            return@post
        }
        limiter.success(user)
        call.respond(mapOf("token" to bearerFor(requireSyncSecret(), account)))
    }

    // A one-minute, single-use ticket for the page widget's browser sign-in.
    post("/api/reskon/launch") {
        val account = reskonAccount(call)
        if (account == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            return@post
        }
        val request = call.receive<ReskonLaunchRequest>()
        if (request.widget !in PAGE_WIDGETS) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No such page"))
            return@post
        }
        call.respond(ReskonLaunchResponse(pages.issue(account, request.widget), 60))
    }

    // The page spends its ticket for a session it keeps in sessionStorage.
    post("/api/reskon/session") {
        val request = call.receive<ReskonSessionRequest>()
        val session = pages.redeem(request.ticket)
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "This sign-in is used up or too old"))
            return@post
        }
        call.respond(mapOf("session" to session))
    }

    get("/api/reskon/page/today") {
        val session = pageSession(call)
        if (session == null || session.widget != "today") {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            return@get
        }
        call.respond(ReskonFeeds.pageList(ReskonFeeds.load(accountFile(session.account)), ReskonFeeds.todayIn(call.request.queryParameters["tz"])))
    }

    // The widget page's tick: written into the sync tables like any client's.
    post("/api/reskon/page/today") {
        val session = pageSession(call)
        if (session == null || session.widget != "today") {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            return@post
        }
        val request = call.receive<ReskonTickRequest>()
        val today = ReskonFeeds.todayIn(request.tz.ifBlank { call.request.queryParameters["tz"] })
        val file = accountFile(session.account)
        val rows = ReskonFeeds.load(file)
        if (request.key !in ReskonFeeds.todayHabits(rows).map { it.uid }) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No such habit"))
            return@post
        }
        val state = if (request.done) "done" else "skipped"
        if (!ReskonFeeds.writeCheckIn(file, request.key, today, state)) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Could not save that tick"))
            return@post
        }
        reskonDataChanged(session.account)
        val updated = rows.copy(
            checkIns = rows.checkIns + (request.key to (rows.checkIns[request.key] ?: emptyMap()) + (today to state)),
        )
        call.respond(ReskonFeeds.pageList(updated, today))
    }

    // One data widget's answer, checked by Reskon against the promised shape.
    get("/api/reskon/feeds/{id}") {
        val account = reskonAccount(call)
        if (account == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            return@get
        }
        val id = call.parameters["id"] ?: ""
        if (id !in FEEDS) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such feed"))
            return@get
        }
        try {
            call.respond(feedAnswer(account, id, call))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Could not read that data"))
        }
    }

    // Reskon hands over its "my data changed" address and key, once connected.
    post("/api/reskon/connect") {
        val account = reskonAccount(call)
        if (account == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Sign in required"))
            return@post
        }
        val request = call.receive<ReskonConnectRequest>()
        val changedUri = runCatching { URI(request.changed_url) }.getOrNull()
        val schemeOk = changedUri?.scheme?.lowercase() in setOf("http", "https")
        val pathOk = changedUri?.path == "/api/apps/changed" && changedUri.rawQuery == null && changedUri.rawFragment == null
        if (!schemeOk || !pathOk || changedUri?.host.isNullOrBlank() || changedUri?.userInfo != null ||
            request.changed_url.length > 2048 || ReskonConfig.originOf(request.changed_url) == null || !HOOK_KEY.matches(request.key)
        ) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "changed_url and key are not usable"))
            return@post
        }
        connectStore.save(account, request.changed_url, request.key)
        reskonDataChanged(account)
        call.respond(mapOf("ok" to true))
    }

    // The web page widget: framed by Reskon, signed in with the ticket after "#".
    get("/widgets/today") {
        call.respondText(reskonResource("today.html"), ContentType.Text.Html)
    }
    get("/widgets/today.js") {
        val origin = ReskonConfig.pageOrigin().orEmpty()
        val quoted = "\"" + origin.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        call.respondText(reskonResource("today.js").replace("__RESKON_ORIGIN__", quoted), ContentType.parse("text/javascript"))
    }
}
