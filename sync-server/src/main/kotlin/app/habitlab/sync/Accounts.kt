package app.habitlab.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed interface RegisterResult {
    data class Created(val account: String) : RegisterResult
    data object Taken : RegisterResult
    data class Invalid(val message: String) : RegisterResult
}

/**
 * Accounts people create themselves, stored (salted PBKDF2 hashes only) in accounts.sqlite beside the
 * per-account data files. Accounts listed in HABITLAB_SYNC_USERS keep working, but none are required.
 */
object Accounts {
    private const val ITERATIONS = 120_000
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    private fun dataDir() = File(System.getenv("HABITLAB_SYNC_DATA") ?: "data")

    fun signupAllowed(): Boolean = System.getenv("HABITLAB_SYNC_ALLOW_SIGNUP")?.trim()?.lowercase() != "false"

    private fun envUsers(): Map<String, String> =
        (json.parseToJsonElement(System.getenv("HABITLAB_SYNC_USERS") ?: "{}") as? JsonObject).orEmpty()
            .mapNotNull { (name, value) -> (value as? JsonPrimitive)?.content?.let { name to it } }.toMap()

    private fun open(): Connection {
        val dir = dataDir().also { it.mkdirs() }
        val db = DriverManager.getConnection("jdbc:sqlite:${File(dir, "accounts.sqlite").absolutePath}")
        db.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS account (username TEXT NOT NULL, salt BLOB NOT NULL, hash BLOB NOT NULL, created_at INTEGER NOT NULL)")
            it.execute("CREATE UNIQUE INDEX IF NOT EXISTS account_username_ci ON account(lower(username))")
        }
        return db
    }

    private fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)).encoded

    /** The canonical account name when the credentials are right, otherwise null. */
    fun verify(username: String, password: String): String? {
        envUsers()[username]?.let { expected ->
            return if (MessageDigest.isEqual(expected.toByteArray(), password.toByteArray())) username else null
        }
        open().use { db ->
            db.prepareStatement("SELECT username, salt, hash FROM account WHERE lower(username) = lower(?)").use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) { hash(password, ByteArray(16)); return null }
                    return if (MessageDigest.isEqual(rs.getBytes(3), hash(password, rs.getBytes(2)))) rs.getString(1) else null
                }
            }
        }
    }

    fun exists(account: String): Boolean {
        if (account in envUsers()) return true
        open().use { db ->
            db.prepareStatement("SELECT 1 FROM account WHERE username = ?").use { ps ->
                ps.setString(1, account)
                ps.executeQuery().use { return it.next() }
            }
        }
    }

    fun register(rawUsername: String, password: String): RegisterResult {
        val username = rawUsername.trim()
        if (username.length < 3 || username.length > 64 || !isValidAccountId(username)) {
            return RegisterResult.Invalid("Username must be 3-64 characters: letters, numbers and . _ @ + -")
        }
        if (password.length < 8 || password.length > 200) return RegisterResult.Invalid("Password must be at least 8 characters.")
        if (envUsers().keys.any { it.equals(username, ignoreCase = true) }) return RegisterResult.Taken
        val salt = ByteArray(16).also(random::nextBytes)
        open().use { db ->
            try {
                db.prepareStatement("INSERT INTO account(username, salt, hash, created_at) VALUES (?, ?, ?, ?)").use { ps ->
                    ps.setString(1, username); ps.setBytes(2, salt); ps.setBytes(3, hash(password, salt)); ps.setLong(4, System.currentTimeMillis())
                    ps.executeUpdate()
                }
            } catch (e: java.sql.SQLException) {
                if (e.message?.contains("UNIQUE", ignoreCase = true) == true) return RegisterResult.Taken
                throw e
            }
        }
        return RegisterResult.Created(username)
    }
}

/** Allows a few sign-up attempts per client address in a sliding window. */
object SignupLimiter {
    private val attempts = HashMap<String, ArrayDeque<Long>>()
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_ATTEMPTS = 8

    @Synchronized fun allow(client: String, now: Long = System.currentTimeMillis()): Boolean {
        val queue = attempts.getOrPut(client) { ArrayDeque() }
        while (queue.isNotEmpty() && now - queue.first() > WINDOW_MS) queue.removeFirst()
        if (queue.size >= MAX_ATTEMPTS) return false
        queue.addLast(now)
        return true
    }
}

/**
 * Counts failed sign-ins per client address and per username in a sliding
 * window. Blocked callers are refused before any password hashing runs, so
 * guessing is slow and cannot be used to burn server CPU.
 */
class SignInAttemptLimiter(
    private val windowMs: Long = 10 * 60 * 1000L,
    private val maxPerClient: Int = 10,
    private val maxPerUser: Int = 6,
    private val maxKeys: Int = 5000,
) {
    private val byClient = HashMap<String, ArrayDeque<Long>>()
    private val byUser = HashMap<String, ArrayDeque<Long>>()

    private fun recent(map: HashMap<String, ArrayDeque<Long>>, key: String, now: Long): ArrayDeque<Long>? {
        val queue = map[key] ?: return null
        while (queue.isNotEmpty() && now - queue.first() > windowMs) queue.removeFirst()
        if (queue.isEmpty()) map.remove(key)
        return queue.takeIf { it.isNotEmpty() }
    }

    @Synchronized fun blocked(client: String, user: String, now: Long = System.currentTimeMillis()): Boolean =
        (recent(byClient, client, now)?.size ?: 0) >= maxPerClient || (recent(byUser, user, now)?.size ?: 0) >= maxPerUser

    @Synchronized fun failure(client: String, user: String, now: Long = System.currentTimeMillis()) {
        for (map in listOf(byClient, byUser)) {
            // Usernames are attacker-chosen; keep the tables bounded.
            if (map.size >= maxKeys) map.keys.toList().forEach { recent(map, it, now) }
            if (map.size >= maxKeys) map.clear()
        }
        byClient.getOrPut(client) { ArrayDeque() }.addLast(now)
        byUser.getOrPut(user) { ArrayDeque() }.addLast(now)
    }

    @Synchronized fun success(user: String) {
        byUser.remove(user)
    }
}

object SignInLimiter {
    val instance = SignInAttemptLimiter()
}
