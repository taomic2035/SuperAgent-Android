package com.superagent.body.core.vision

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

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
        val SECURE_RANDOM = SecureRandom()
        val DEFAULT_TOKEN_FACTORY: () -> String = {
            val bytes = ByteArray(24).also(SECURE_RANDOM::nextBytes)
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
