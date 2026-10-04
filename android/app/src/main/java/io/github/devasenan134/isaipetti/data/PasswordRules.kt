package io.github.devasenan134.isaipetti.data

import kotlin.math.log2

/**
 * Password rules, following current NIST guidance: favour length, block known-bad passwords,
 * no "must contain a symbol" rules. The friends server has an identical copy (server/.../PasswordRules.kt).
 */
object PasswordRules {
    const val MIN_LENGTH = 10
    const val MAX_LENGTH = 128

    /** ~9,000 most-used passwords of 10+ characters (UK NCSC list via SecLists), lowercase. */
    private val common: Set<String> by lazy {
        PasswordRules::class.java.getResourceAsStream("/common-passwords.txt")!!.bufferedReader().useLines { it.toHashSet() }
    }

    enum class Strength { TooWeak, Okay, Strong }

    data class Check(val problem: String?, val strength: Strength)

    fun check(password: String, username: String): Check {
        val lower = password.lowercase()
        val problem = when {
            password.length < MIN_LENGTH -> "Use at least $MIN_LENGTH characters"
            password.length > MAX_LENGTH -> "Use at most $MAX_LENGTH characters"
            password.toSet().size < 4 -> "Too repetitive. Mix in more different characters"
            username.length >= 3 && username.lowercase() in lower -> "Don't include your username in the password"
            lower in common -> "That's one of the most commonly used passwords"
            else -> null
        }
        val strength = when {
            problem != null -> Strength.TooWeak
            estimatedBits(password) < 70 -> Strength.Okay
            else -> Strength.Strong
        }
        return Check(problem, strength)
    }

    /** Rough guessing difficulty: length × log2(size of the character pool used). */
    private fun estimatedBits(password: String): Double {
        var pool = 0
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { !it.isLetterOrDigit() && it.code < 128 }) pool += 33
        if (password.any { it.code >= 128 }) pool += 100 // Tamil and other scripts
        return password.length * log2(pool.coerceAtLeast(1).toDouble())
    }
}
