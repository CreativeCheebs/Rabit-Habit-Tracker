package app.habitlab.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReskonPagesTest {
    private fun ticketAndSession(pages: ReskonPages): Pair<String, String?> {
        val ticket = pages.issue("alice", "today")
        return ticket to pages.redeem(ticket)
    }

    @Test
    fun aTicketWorksExactlyOnce() {
        var time = 1_000L
        val pages = ReskonPages { time }
        val (ticket, session) = ticketAndSession(pages)
        assertTrue(!session.isNullOrEmpty())
        assertNull(pages.redeem(ticket), "the same ticket cannot be spent twice")
        assertNotEquals(ticket, session)
    }

    @Test
    fun anOldTicketIsRefused() {
        var time = 1_000L
        val pages = ReskonPages { time }
        val ticket = pages.issue("alice", "today")
        time += ReskonPages.TICKET_LIFE_MS
        assertNull(pages.redeem(ticket))
    }

    @Test
    fun aSessionCarriesTheAccountAndWidgetAndExpires() {
        var time = 1_000L
        val pages = ReskonPages { time }
        val (_, session) = ticketAndSession(pages)
        val live = pages.session(session!!)!!
        assertEquals("alice", live.account)
        assertEquals("today", live.widget)
        time += ReskonPages.SESSION_LIFE_MS
        assertNull(pages.session(session), "a twelve-hour session ages out")
    }

    @Test
    fun unknownTokensAreRefusedAndSessionsStayBounded() {
        var time = 1_000L
        val pages = ReskonPages { time }
        assertNull(pages.session(""))
        assertNull(pages.session("nope"))
        assertNull(pages.redeem(""))
        assertNull(pages.redeem("nope"))
        repeat(ReskonPages.MAX_SESSIONS + 50) { pages.issue("alice", "today") }
        assertNull(pages.session("never-issued"))
    }

    @Test
    fun connectStoresOnlyReskonShapedAddresses() {
        val dir = Files.createTempDirectory("habitlab-reskon-connect-").toFile()
        val store = ReskonConnect(dir)
        val key = "k".repeat(43)
        store.save("alice", "http://192.168.0.8:8097/hooks/changed", key)
        val entry = store.get("alice")!!
        assertEquals(key, entry.key)
        assertEquals("http://192.168.0.8:8097/hooks/changed", entry.url)
        assertNull(store.get("bob"))
        assertEquals("http://192.168.0.8:8097", ReskonConfig.originOf(entry.url))
        store.save("bob", "https://reskon.example/", "b".repeat(43))
        assertEquals("https://reskon.example", ReskonConnect(dir).newestChangedUrl()?.let { ReskonConfig.originOf(it) })
        dir.deleteRecursively()
    }

    @Test
    fun originsAreLowercasedAndHonest() {
        assertEquals("https://reskon.example:8443", ReskonConfig.originOf("HTTPS://Reskon.Example:8443/x/y"))
        assertEquals("http://localhost:8080", ReskonConfig.originOf("localhost:8080"))
        assertNull(ReskonConfig.originOf("ftp://reskon.example"))
        assertNull(ReskonConfig.originOf("not a url at all"))
        assertNull(ReskonConfig.originOf(""))
    }
}

class ReskonChangedTest {
    @Test
    fun burstsAreFoldedIntoOneCall() {
        val dir = Files.createTempDirectory("habitlab-reskon-changed-").toFile()
        val store = ReskonConnect(dir)
        store.save("alice", "http://reskon.test/hook", "k".repeat(43))
        val queued = mutableListOf<Pair<Long, () -> Unit>>()
        val posted = mutableListOf<String>()
        val changed = ReskonChanged(
            connect = store,
            now = { 0L },
            schedule = { delay, run -> queued += delay to run },
            post = { url, _ -> posted += url },
        )
        repeat(5) { changed.notify("alice") }
        assertEquals(1, queued.size, "five changes in a burst make one call")
        queued.first().second()
        assertEquals(listOf("http://reskon.test/hook"), posted)
        dir.deleteRecursively()
    }

    @Test
    fun aFailedCallIsDroppedAndTheNextOneStillGoes() {
        val dir = Files.createTempDirectory("habitlab-reskon-changed-").toFile()
        val store = ReskonConnect(dir)
        store.save("alice", "http://reskon.test/hook", "k".repeat(43))
        var time = 0L
        val queued = mutableListOf<() -> Unit>()
        val attempts = mutableListOf<String>()
        val changed = ReskonChanged(
            connect = store,
            now = { time },
            schedule = { _, run -> queued += run },
            post = { url, _ -> attempts += url; error("reskon is down") },
        )
        changed.notify("alice")
        queued.removeAt(0).invoke()
        assertEquals(1, attempts.size)
        time = ReskonChanged.MIN_GAP_MS + 1
        changed.notify("alice")
        queued.removeAt(0).invoke()
        assertEquals(2, attempts.size, "a failure does not stop later calls")
        dir.deleteRecursively()
    }

    @Test
    fun anAccountThatNeverConnectedPostsNothing() {
        val dir = Files.createTempDirectory("habitlab-reskon-changed-").toFile()
        val queued = mutableListOf<() -> Unit>()
        val changed = ReskonChanged(
            connect = ReskonConnect(dir),
            now = { 0L },
            schedule = { _, run -> queued += run },
            post = { _, _ -> error("must not be called") },
        )
        changed.notify("nobody")
        queued.removeAt(0).invoke()
        dir.deleteRecursively()
    }
}

class ReskonFindAccountTest {
    private val secret = "s".repeat(32)

    @Test
    fun findsTheOwnerOfATokenAndRefusesTheRest() {
        val token = bearerFor(secret, "alice@example.com")
        assertEquals("alice@example.com", findAccount(token, listOf("bob", "alice@example.com"), secret))
        assertNull(findAccount(token, listOf("bob"), secret))
        assertNull(findAccount(token, listOf("alice@example.com"), "t".repeat(32)))
        assertNull(findAccount("", listOf("alice@example.com"), secret))
        assertNull(findAccount("x".repeat(5000), listOf("alice@example.com"), secret))
    }

    @Test
    fun stillFindsTheAccountWhenOthersAreCheckedFirst() {
        val token = bearerFor(secret, "zoe")
        assertEquals("zoe", findAccount(token, listOf("ann", "bea", "zoe"), secret))
        assertNull(findAccount(token, listOf("ann", "bea"), secret))
    }
}
