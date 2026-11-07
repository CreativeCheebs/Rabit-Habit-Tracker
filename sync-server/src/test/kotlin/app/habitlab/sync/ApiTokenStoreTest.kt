package app.habitlab.sync

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTokenStoreTest {
    @Test fun tokenIsAccountScopedStoredHashedAndRevocable() {
        val dir = Files.createTempDirectory("habitlab-token-").toFile()
        try {
            var time = 1_000L
            val store = ApiTokenStore(dir) { time }
            val created = store.create("alice", "Calendar", 60_000)
            assertEquals("alice", store.accountFor(created.token))
            assertEquals(listOf(created.info), store.list("alice"))
            assertTrue(store.list("bob").isEmpty())
            assertFalse(store.revoke("bob", created.info.id))
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("accounts.sqlite").absolutePath}").use { db ->
                db.createStatement().use { st -> st.executeQuery("SELECT token_hash FROM api_token").use { rs ->
                    assertTrue(rs.next()); assertEquals(32, rs.getBytes(1).size)
                    assertFalse(rs.getBytes(1).contentEquals(created.token.toByteArray()))
                } }
            }
            assertTrue(store.revoke("alice", created.info.id))
            assertNull(store.accountFor(created.token))
            val expiring = store.create("alice", "Short", 60_000)
            time += 60_000
            assertNull(store.accountFor(expiring.token))
        } finally { dir.deleteRecursively() }
    }
}
