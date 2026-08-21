package com.superagent.body.core.vision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun `default token is non-empty url-safe and unpadded`() {
        val issued = VisionActionContextRegistry().issue("shot.jpg", "pkg", "sig", 1080, 2400)

        assertTrue(issued.isNotEmpty())
        assertTrue(issued.matches(Regex("[A-Za-z0-9_-]{32}")))
    }
}
