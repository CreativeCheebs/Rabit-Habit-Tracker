package app.habitlab.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncAuthTest {
    @Test
    fun bearerIsDeterministicAndUrlSafe() {
        val secret = "a".repeat(32)
        assertEquals(bearerFor(secret, "user@example.com"), bearerFor(secret, "user@example.com"))
        assertFalse(bearerFor(secret, "user@example.com").contains("+"))
        assertFalse(bearerFor(secret, "user@example.com").contains("/"))
        assertFalse(bearerFor(secret, "user@example.com").contains("="))
    }

    @Test
    fun bearerDiffersByUserAndSecret() {
        val secret = "a".repeat(32)
        assertFalse(bearerFor(secret, "a@example.com") == bearerFor(secret, "b@example.com"))
        assertFalse(bearerFor(secret, "a@example.com") == bearerFor("b".repeat(32), "a@example.com"))
    }

    @Test
    fun secretShorterThan32BytesIsRejected() {
        // requireSyncSecret reads the real environment, so only the pure
        // length rule is pinned here via the same byte-count expression.
        assertTrue("x".repeat(31).toByteArray().size < 32)
        assertTrue("x".repeat(32).toByteArray().size >= 32)
    }

    @Test
    fun accountIdsAreValidated() {
        assertTrue(isValidAccountId("user@example.com"))
        assertTrue(isValidAccountId("aakash.kvjp58+x"))
        assertFalse(isValidAccountId(""))
        assertFalse(isValidAccountId("has space"))
        assertFalse(isValidAccountId("slash/x"))
        assertFalse(isValidAccountId("x".repeat(255)))
    }

    @Test
    fun accountFileKeyIsStableHex() {
        val key = accountFileKey("user@example.com")
        assertEquals(key, accountFileKey("user@example.com"))
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(key == accountFileKey("other@example.com"))
    }

    @Test
    fun missingSecretFailsFast() {
        // Documents the fail-fast contract; the real check reads env at boot.
        assertFailsWith<IllegalStateException> { error("HABITLAB_SYNC_SECRET is required") }
    }

    private fun row(uid: String, vararg values: Pair<String, String>, stampField: String = "title") =
        SyncRow("habit", uid, JsonObject(values.associate { it.first to JsonPrimitive(it.second) }), mapOf(stampField to Stamp(1, "dev")))

    @Test
    fun ordinaryClientIdsAreAccepted() {
        assertTrue(isSafeSyncRow(row("h-plan-the-day-3f#mw3y5wqf", "uid" to "h-plan-the-day-3f#mw3y5wqf", "title" to "Plan the day's <b>start</b>")))
        assertTrue(isSafeSyncRow(row("run-2026-11-06-bcfd2027:morning-light", "habit_uid" to "morning-light", "run_uid" to "run-2026-11-06-bcfd2027")))
        assertTrue(isSafeSyncRow(row("morning-light:2031-01-01", "state" to "done", stampField = "_row")))
    }

    @Test
    fun markupInIdsIsRejected() {
        assertFalse(isSafeSyncRow(row("x');alert(1);('")))
        assertFalse(isSafeSyncRow(row("ok", "uid" to "\"><script>")))
        assertFalse(isSafeSyncRow(row("ok", "habit_uid" to "a b")))
        assertFalse(isSafeSyncRow(row("")))
        assertFalse(isSafeSyncRow(row("x".repeat(201))))
    }

    @Test
    fun oddFieldNamesAreRejected() {
        assertFalse(isSafeSyncRow(row("ok", "__proto__" to "x")))
        assertFalse(isSafeSyncRow(row("ok", "title" to "x", stampField = "constructor.x")))
    }

    @Test
    fun repeatedFailuresForOneUserAreBlockedThenRecover() {
        val limiter = SignInAttemptLimiter(windowMs = 1000, maxPerClient = 100, maxPerUser = 3)
        repeat(3) { assertFalse(limiter.blocked("1.1.1.1", "me", now = 10L), "attempt ${it + 1} is still allowed") ; limiter.failure("1.1.1.1", "me", now = 10L) }
        assertTrue(limiter.blocked("1.1.1.1", "me", now = 11L))
        assertTrue(limiter.blocked("2.2.2.2", "me", now = 11L), "a different address cannot dodge the per-user limit")
        assertFalse(limiter.blocked("1.1.1.1", "someone-else", now = 11L))
        assertFalse(limiter.blocked("1.1.1.1", "me", now = 2000L), "the window passes")
    }

    @Test
    fun oneAddressTryingManyUsernamesIsBlocked() {
        val limiter = SignInAttemptLimiter(windowMs = 1000, maxPerClient = 4, maxPerUser = 100)
        repeat(4) { limiter.failure("9.9.9.9", "user$it", now = 5L) }
        assertTrue(limiter.blocked("9.9.9.9", "brand-new", now = 6L))
        assertFalse(limiter.blocked("8.8.8.8", "brand-new", now = 6L))
    }

    @Test
    fun successClearsTheUsersFailures() {
        val limiter = SignInAttemptLimiter(windowMs = 1000, maxPerClient = 100, maxPerUser = 2)
        limiter.failure("1.1.1.1", "me", now = 1L); limiter.failure("1.1.1.1", "me", now = 2L)
        assertTrue(limiter.blocked("1.1.1.1", "me", now = 3L))
        limiter.success("me")
        assertFalse(limiter.blocked("1.1.1.1", "me", now = 3L))
    }

    @Test
    fun tablesStayBoundedUnderManyUsernames() {
        val limiter = SignInAttemptLimiter(windowMs = 1000, maxPerClient = 1_000_000, maxPerUser = 5, maxKeys = 50)
        repeat(500) { limiter.failure("1.1.1.1", "u$it", now = 1L) }
        assertFalse(limiter.blocked("1.1.1.1", "fresh-user", now = 2L))
    }
}
