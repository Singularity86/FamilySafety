package com.example.familysafety.core

import com.example.familysafety.location.MemberLocation
import org.junit.Assert.*
import org.junit.Test

class DataValidatorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun validLocation(
        memberId: String = "member_001",
        lat: Double = 37.7749,
        lon: Double = -122.4194,
        accuracy: Float = 10.0f,
        timestamp: Long = 1_700_000_000_000L
    ) = MemberLocation(
        memberId = memberId,
        latitude = lat,
        longitude = lon,
        accuracy = accuracy,
        timestamp = timestamp
    )

    // ── validateLocation ──────────────────────────────────────────────────────

    @Test
    fun `validateLocation returns Valid for correct data`() {
        assertEquals(ValidationResult.Valid, DataValidator.validateLocation(validLocation()))
    }

    @Test
    fun `validateLocation returns Invalid for latitude above 90`() {
        val result = DataValidator.validateLocation(validLocation(lat = 91.0))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateLocation returns Invalid for latitude below -90`() {
        val result = DataValidator.validateLocation(validLocation(lat = -91.0))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateLocation returns Invalid for longitude above 180`() {
        val result = DataValidator.validateLocation(validLocation(lon = 181.0))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateLocation returns Invalid for longitude below -180`() {
        val result = DataValidator.validateLocation(validLocation(lon = -181.0))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateLocation returns Invalid for negative accuracy`() {
        val result = DataValidator.validateLocation(validLocation(accuracy = -1.0f))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateLocation returns Invalid for future timestamp beyond drift`() {
        val farFuture = System.currentTimeMillis() + 10 * 60 * 1000L
        val result = DataValidator.validateLocation(validLocation(timestamp = farFuture))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateLocation returns Invalid for blank memberId`() {
        val result = DataValidator.validateLocation(validLocation(memberId = ""))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateLocation accumulates multiple errors`() {
        val loc = validLocation(lat = 200.0, lon = 200.0, memberId = "")
        val result = DataValidator.validateLocation(loc)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.size >= 2)
    }

    @Test
    fun `validateMnemonic returns Valid for 12 lowercase words`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertEquals(ValidationResult.Valid, DataValidator.validateMnemonic(mnemonic))
    }

    @Test
    fun `validateMnemonic returns Invalid for wrong word count`() {
        val mnemonic = "one two three"
        val result = DataValidator.validateMnemonic(mnemonic)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateMnemonic returns Invalid when a word contains uppercase`() {
        val mnemonic = "ABANDON abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val result = DataValidator.validateMnemonic(mnemonic)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateMnemonic returns Invalid for word with digits`() {
        val mnemonic = "abandon123 abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val result = DataValidator.validateMnemonic(mnemonic)
        assertTrue(result is ValidationResult.Invalid)
    }

    // ── sanitizeDisplayName ───────────────────────────────────────────────────

    @Test
    fun `sanitizeDisplayName trims leading and trailing whitespace`() {
        assertEquals("Alice", DataValidator.sanitizeDisplayName("  Alice  "))
    }

    @Test
    fun `sanitizeDisplayName truncates to 50 characters`() {
        val long = "A".repeat(80)
        assertEquals(50, DataValidator.sanitizeDisplayName(long).length)
    }

    @Test
    fun `sanitizeDisplayName removes disallowed special characters`() {
        // regex [^a-zA-Z0-9 '-] — allows space, apostrophe, hyphen
        // Strips: <, >, @, !, etc.
        val sanitized = DataValidator.sanitizeDisplayName("Alice<script>alert('xss')</script>")
        assertFalse(sanitized.contains("<"))
        assertFalse(sanitized.contains(">"))
        // apostrophes within the allowed set are KEPT
        assertTrue(sanitized.contains("'"))
    }

    @Test
    fun `sanitizeDisplayName keeps letters digits space apostrophe hyphen`() {
        val result = DataValidator.sanitizeDisplayName("O'Brien-Smith 42")
        assertEquals("O'Brien-Smith 42", result)
    }

    @Test
    fun `ValidationResult Valid is distinguishable from Invalid`() {
        val valid: ValidationResult = ValidationResult.Valid
        val invalid: ValidationResult = ValidationResult.Invalid(listOf("err"))
        assertTrue(valid is ValidationResult.Valid)
        assertTrue(invalid is ValidationResult.Invalid)
        assertNotEquals(valid, invalid)
    }

    @Test
    fun `ValidationResult Invalid carries error list`() {
        val errors = listOf("Error A", "Error B")
        val result = ValidationResult.Invalid(errors)
        assertEquals(errors, result.errors)
    }
}
