package com.example.familysafety.core

import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.location.MemberLocation
import org.junit.Assert.*
import org.junit.Test

class DataValidatorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun validMember(
        id: String = "member_001",
        name: String = "Alice",
        ed25519Key: String = "a".repeat(64),
        x25519Key: String = "b".repeat(64),
        addedAt: Long = 1000L
    ) = FamilyMember(
        memberId = id,
        displayName = name,
        ed25519PublicKey = ed25519Key,
        x25519PublicKey = x25519Key,
        addedAtEpochMs = addedAt
    )

    private fun validGroup(
        members: Set<FamilyMember> = setOf(validMember()),
        version: Long = 1L
    ) = GroupDefinition(
        groupId = "group_123",
        groupName = "Test Family",
        createdAtEpochMs = 1000L,
        creatorMemberId = members.first().memberId,
        members = members,
        version = version
    )

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

    // ── validateFamilyMember ──────────────────────────────────────────────────

    @Test
    fun `validateFamilyMember returns Valid for correct member`() {
        assertEquals(ValidationResult.Valid, DataValidator.validateFamilyMember(validMember()))
    }

    @Test
    fun `validateFamilyMember returns Invalid for blank display name`() {
        val result = DataValidator.validateFamilyMember(validMember(name = ""))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateFamilyMember returns Invalid for name exceeding 50 chars`() {
        val result = DataValidator.validateFamilyMember(validMember(name = "A".repeat(51)))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateFamilyMember returns Invalid for ed25519 key with wrong length`() {
        val result = DataValidator.validateFamilyMember(validMember(ed25519Key = "ab".repeat(10))) // 20 chars, not 64
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateFamilyMember returns Invalid for x25519 key with wrong length`() {
        val result = DataValidator.validateFamilyMember(validMember(x25519Key = "cd")) // 2 chars, not 64
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateFamilyMember returns Invalid for negative addedAtEpochMs`() {
        val result = DataValidator.validateFamilyMember(validMember(addedAt = -1L))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateFamilyMember returns Invalid for display name with disallowed chars`() {
        // Only a-z, A-Z, 0-9, space, apostrophe, hyphen are allowed
        val result = DataValidator.validateFamilyMember(validMember(name = "Alice@Corp"))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateFamilyMember accepts display name with allowed special chars`() {
        // Apostrophe and hyphen are allowed
        val result = DataValidator.validateFamilyMember(validMember(name = "O'Brien-Smith"))
        assertEquals(ValidationResult.Valid, result)
    }

    // ── validateGroupDefinition ───────────────────────────────────────────────

    @Test
    fun `validateGroupDefinition returns Valid for correct group`() {
        assertEquals(ValidationResult.Valid, DataValidator.validateGroupDefinition(validGroup()))
    }

    @Test
    fun `validateGroupDefinition returns Invalid for empty members`() {
        val group = validGroup().copy(members = emptySet())
        val result = DataValidator.validateGroupDefinition(group)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateGroupDefinition returns Invalid when creator not in members`() {
        val member = validMember()
        val group = validGroup(members = setOf(member)).copy(creatorMemberId = "nonexistent_id")
        val result = DataValidator.validateGroupDefinition(group)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateGroupDefinition returns Invalid for version less than 1`() {
        val group = validGroup(version = 0L)
        val result = DataValidator.validateGroupDefinition(group)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateGroupDefinition returns Invalid for blank group name`() {
        val group = validGroup().copy(groupName = "")
        val result = DataValidator.validateGroupDefinition(group)
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `validateGroupDefinition cascades member validation errors`() {
        val badMember = validMember(ed25519Key = "short")
        val group = GroupDefinition(
            groupId = "group_123",
            groupName = "Test Family",
            createdAtEpochMs = 1000L,
            creatorMemberId = badMember.memberId,
            members = setOf(badMember),
            version = 1L
        )
        val result = DataValidator.validateGroupDefinition(group)
        assertTrue(result is ValidationResult.Invalid)
    }

    // ── validateMnemonic ──────────────────────────────────────────────────────

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

    // ── sanitizeGroupName ─────────────────────────────────────────────────────

    @Test
    fun `sanitizeGroupName trims whitespace`() {
        assertEquals("My Family", DataValidator.sanitizeGroupName("  My Family  "))
    }

    @Test
    fun `sanitizeGroupName truncates to 100 characters`() {
        val long = "A".repeat(120)
        assertEquals(100, DataValidator.sanitizeGroupName(long).length)
    }

    @Test
    fun `sanitizeGroupName removes disallowed special characters`() {
        val sanitized = DataValidator.sanitizeGroupName("Family <3 & More!")
        assertFalse(sanitized.contains("<"))
        assertFalse(sanitized.contains("&"))
        assertFalse(sanitized.contains("!"))
    }

    // ── ValidationResult ──────────────────────────────────────────────────────

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
