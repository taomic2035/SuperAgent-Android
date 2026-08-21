package com.superagent.body.core.vision

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
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val tokenFactory: () -> String = DEFAULT_TOKEN_FACTORY,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private data class Entry(
        val screenshotRef: String,
        val appPackage: String,
        val signature: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val expiresAt: Long,
    )

    enum class Validation { Valid, Invalid }

    private val entries = ConcurrentHashMap<String, Entry>()

    fun issue(
        ref: String,
        pkg: String,
        signature: String,
        width: Int,
        height: Int,
    ): String {
        val token = tokenFactory()
        entries[token] = Entry(ref, pkg, signature, width, height, nowMs() + ttlMs)
        return token
    }

    fun validate(token: String?, currentPackage: String?, currentSignature: String): Validation {
        val entry = token?.let(entries::get) ?: return Validation.Invalid
        if (nowMs() > entry.expiresAt) {
            entries.remove(token, entry)
            return Validation.Invalid
        }
        val signatureMatches =
            entry.signature.isBlank() || currentSignature.isBlank() || entry.signature == currentSignature
        return if (currentPackage == entry.appPackage && signatureMatches) Validation.Valid else Validation.Invalid
    }

    private companion object {
        const val DEFAULT_TTL_MS = 120_000L
        val DEFAULT_TOKEN_FACTORY: () -> String = { createVisionActionToken() }
    }
}
