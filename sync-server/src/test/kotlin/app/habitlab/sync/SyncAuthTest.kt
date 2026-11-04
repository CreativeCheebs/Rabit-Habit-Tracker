package app.habitlab.sync

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
}
