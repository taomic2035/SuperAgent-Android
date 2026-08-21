package com.superagent.body.core.vision

import android.os.SystemClock
import android.util.Base64
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

private const val TOKEN_BYTE_COUNT = 24
private const val TOKEN_BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
private val SECURE_RANDOM = SecureRandom()

internal fun createVisionActionToken(
    fillRandomBytes: (ByteArray) -> Unit = SECURE_RANDOM::nextBytes,
    encodeToString: (ByteArray, Int) -> String = Base64::encodeToString,
): String {
    val bytes = ByteArray(TOKEN_BYTE_COUNT).also(fillRandomBytes)
    return encodeToString(bytes, TOKEN_BASE64_FLAGS)
}

class VisionActionContextRegistry(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val tokenFactory: () -> String = DEFAULT_TOKEN_FACTORY,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class Entry(
        val screenshotRef: String,
        val appPackage: String,
        val signature: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val expiresAt: Long,
        val issuedOrder: Long,
    )

    enum class Validation { Valid, Invalid }

    private val entries = ConcurrentHashMap<String, Entry>()
    private var nextIssuedOrder = 0L

    @Synchronized
    fun issue(
        ref: String,
        pkg: String,
        signature: String,
        width: Int,
        height: Int,
    ): String {
        val now = nowMs()
        removeExpired(now)
        val token = tokenFactory()
        entries[token] = Entry(ref, pkg, signature, width, height, now + ttlMs, nextIssuedOrder++)
        trimToMaxEntries()
        return token
    }

    fun validate(token: String?, currentPackage: String?, currentSignature: String): Validation {
        val entry = token?.let(entries::get) ?: return Validation.Invalid
        if (nowMs() > entry.expiresAt) {
            entries.remove(token, entry)
            return Validation.Invalid
        }
        val signatureMatches = entry.signature == currentSignature
        return if (currentPackage == entry.appPackage && signatureMatches) Validation.Valid else Validation.Invalid
    }

    internal fun sizeForTest(): Int = entries.size

    private fun removeExpired(now: Long) {
        entries.forEach { (token, entry) ->
            if (now > entry.expiresAt) entries.remove(token, entry)
        }
    }

    private fun trimToMaxEntries() {
        while (entries.size > maxEntries) {
            val oldest = entries.entries.minWithOrNull(
                compareBy<Map.Entry<String, Entry>> { it.value.expiresAt }
                    .thenBy { it.value.issuedOrder }
                    .thenBy { it.key },
            ) ?: return
            entries.remove(oldest.key, oldest.value)
        }
    }

    private companion object {
        const val DEFAULT_TTL_MS = 120_000L
        const val DEFAULT_MAX_ENTRIES = 128
        val DEFAULT_TOKEN_FACTORY: () -> String = { createVisionActionToken() }
    }
}
