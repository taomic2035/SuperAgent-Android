package com.superagent.body.core.vision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class VisionActionContextRegistryTest {
    private var now = 1_000L
    private var tokenIndex = 0
    private val registry = VisionActionContextRegistry(
        nowMs = { now },
        tokenFactory = { "token-${++tokenIndex}" },
        ttlMs = 120_000L,
    )

    @Test
    fun `valid token is reusable within ttl`() {
        val issued = registry.issue("shot.jpg", "pkg", "sig", 1080, 2400)

        assertEquals(VisionActionContextRegistry.Validation.Valid, registry.validate(issued, "pkg", "sig"))
        assertEquals(VisionActionContextRegistry.Validation.Valid, registry.validate(issued, "pkg", "sig"))
    }

    @Test
    fun `forged token fails closed`() {
        assertEquals(VisionActionContextRegistry.Validation.Invalid, registry.validate("forged", "pkg", "sig"))
    }

    @Test
    fun `expired token fails closed`() {
        val issued = registry.issue("shot.jpg", "pkg", "sig", 1080, 2400)

        now += 120_001L

        assertEquals(VisionActionContextRegistry.Validation.Invalid, registry.validate(issued, "pkg", "sig"))
    }

    @Test
    fun `foreground package mismatch fails closed`() {
        val issued = registry.issue("shot.jpg", "pkg", "sig", 1080, 2400)

        assertEquals(VisionActionContextRegistry.Validation.Invalid, registry.validate(issued, "other", "sig"))
    }

    @Test
    fun `non-empty signature must match`() {
        val issued = registry.issue("shot.jpg", "pkg", "sig", 1080, 2400)

        assertEquals(VisionActionContextRegistry.Validation.Invalid, registry.validate(issued, "pkg", "other"))
    }

    @Test
    fun `empty signature remains compatible`() {
        val unsignedCapture = registry.issue("unsigned.jpg", "pkg", "", 1080, 2400)
        val signedCapture = registry.issue("signed.jpg", "pkg", "sig", 1080, 2400)

        assertEquals(VisionActionContextRegistry.Validation.Valid, registry.validate(unsignedCapture, "pkg", "current"))
        assertEquals(VisionActionContextRegistry.Validation.Valid, registry.validate(signedCapture, "pkg", ""))
    }

    @Test
    @OptIn(ExperimentalEncodingApi::class)
    fun `default token encodes exactly 24 random bytes as url-safe base64 without padding`() {
        val originalBytes = ByteArray(24) { index -> index.toByte() }
        var encodedBytes: ByteArray? = null
        var encodedFlags: Int? = null

        val issued = createVisionActionToken(
            fillRandomBytes = { destination -> originalBytes.copyInto(destination) },
            encodeToString = { bytes, flags ->
                encodedBytes = bytes.copyOf()
                encodedFlags = flags
                Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
            },
        )

        assertTrue(originalBytes.contentEquals(encodedBytes))
        assertEquals(
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            encodedFlags,
        )
        assertEquals(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(originalBytes), issued)
        assertTrue(issued.matches(Regex("[A-Za-z0-9_-]{32}")))
    }
}
