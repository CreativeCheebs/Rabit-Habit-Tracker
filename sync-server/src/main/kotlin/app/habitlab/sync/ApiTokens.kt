package app.habitlab.sync

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID

data class ApiTokenInfo(val id: String, val name: String, val createdAt: Long, val expiresAt: Long)
data class CreatedApiToken(val token: String, val info: ApiTokenInfo)

/** Integration tokens are independent of login credentials and only their hashes are stored. */
class ApiTokenStore(private val dataDir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()

    private fun open(): java.sql.Connection {
        check(dataDir.exists() || dataDir.mkdirs()) { "Cannot create data directory" }
        return DriverManager.getConnection("jdbc:sqlite:${File(dataDir, "accounts.sqlite").absolutePath}").also { db ->
            db.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout = 5000")
                st.execute("CREATE TABLE IF NOT EXISTS api_token (id TEXT PRIMARY KEY, account TEXT NOT NULL, name TEXT NOT NULL, token_hash BLOB NOT NULL UNIQUE, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
                st.execute("CREATE INDEX IF NOT EXISTS api_token_account ON api_token(account)")
            }
        }
    }

    private fun digest(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    fun create(account: String, rawName: String, lifetimeMillis: Long): CreatedApiToken {
        val name = rawName.trim()
        require(name.isNotEmpty() && name.length <= 80 && name.none { it.isISOControl() }) { "Name must contain 1–80 printable characters." }
        require(lifetimeMillis in 60_000L..(365L * 24 * 60 * 60 * 1000)) { "Invalid token lifetime." }
        val created = now()
        val info = ApiTokenInfo(UUID.randomUUID().toString(), name, created, created + lifetimeMillis)
        val token = "hl_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        open().use { db ->
            db.prepareStatement("INSERT INTO api_token(id, account, name, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)").use { ps ->
                ps.setString(1, info.id); ps.setString(2, account); ps.setString(3, name)
                ps.setBytes(4, digest(token)); ps.setLong(5, created); ps.setLong(6, info.expiresAt); ps.executeUpdate()
            }
        }
        return CreatedApiToken(token, info)
    }

    fun list(account: String): List<ApiTokenInfo> = open().use { db ->
        db.prepareStatement("SELECT id, name, created_at, expires_at FROM api_token WHERE account = ? ORDER BY created_at DESC, id DESC").use { ps ->
            ps.setString(1, account)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(ApiTokenInfo(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4))) } }
        }
    }

    fun revoke(account: String, id: String): Boolean = open().use { db ->
        db.prepareStatement("DELETE FROM api_token WHERE account = ? AND id = ?").use { ps ->
            ps.setString(1, account); ps.setString(2, id); ps.executeUpdate() == 1
        }
    }

    fun accountFor(token: String): String? {
        if (!token.matches(Regex("hl_[A-Za-z0-9_-]{43}"))) return null
        return open().use { db ->
            db.prepareStatement("SELECT account FROM api_token WHERE token_hash = ? AND expires_at > ?").use { ps ->
                ps.setBytes(1, digest(token)); ps.setLong(2, now())
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }
}
