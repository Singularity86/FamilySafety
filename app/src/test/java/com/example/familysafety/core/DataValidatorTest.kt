package com.example.familysafety.core

import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.location.MemberLocation
import org.junit.Test
import org.junit.Assert.*

class DataValidatorTest {
    
    @Test
    fun `validates correct location`() {
        val location = MemberLocation(
            memberId = "member_123",
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 10.0f,
            timestamp = System.currentTimeMillis(),
            speed = 5.0f,
            bearing = 180.0f
        )
        
        val result = DataValidator.validateLocation(location)
        assertTrue(result is ValidationResult.Valid)
    }
    
    @Test
    fun `rejects invalid latitude`() {
        val location = MemberLocation(
            memberId = "member_123",
            latitude = 91.0,
            longitude = -122.4194,
            accuracy = 10.0f,
            timestamp = System.currentTimeMillis()
        )
        
        val result = DataValidator.validateLocation(location)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.any { it.contains("latitude") })
    }
    
    @Test
    fun `rejects invalid longitude`() {
        val location = MemberLocation(
            memberId = "member_123",
            latitude = 37.7749,
            longitude = -181.0,
            accuracy = 10.0f,
            timestamp = System.currentTimeMillis()
        )
        
        val result = DataValidator.validateLocation(location)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.any { it.contains("longitude") })
    }
    
    @Test
    fun `validates correct family member`() {
        val member = FamilyMember(
            memberId = "member_123",
            displayName = "Alice",
            ed25519PublicKey = ByteArray(32) { 0 },
            x25519PublicKey = ByteArray(32) { 0 },
            addedAtEpochMs = System.currentTimeMillis()
        )
        
        val result = DataValidator.validateFamilyMember(member)
        assertTrue(result is ValidationResult.Valid)
    }
    
    @Test
    fun `rejects empty display name`() {
        val member = FamilyMember(
            memberId = "member_123",
            displayName = "",
            ed25519PublicKey = ByteArray(32) { 0 },
            x25519PublicKey = ByteArray(32) { 0 },
            addedAtEpochMs = System.currentTimeMillis()
        )
        
        val result = DataValidator.validateFamilyMember(member)
        assertTrue(result is ValidationResult.Invalid)
    }
    
    @Test
    fun `sanitizes display name correctly`() {
        val dirty = "Alice<script>alert('xss')</script>"
        val clean = DataValidator.sanitizeDisplayName(dirty)
        
        assertEquals("Alicescriptalertxssscript", clean)
        assertFalse(clean.contains("<"))
        assertFalse(clean.contains(">"))
    }
    
    @Test
    fun `validates correct group definition`() {
        val creator = FamilyMember(
            memberId = "creator_123",
            displayName = "Creator",
            ed25519PublicKey = ByteArray(32) { 0 },
            x25519PublicKey = ByteArray(32) { 0 },
            addedAtEpochMs = System.currentTimeMillis()
        )
        
        val group = GroupDefinition(
            groupId = "group_123",
            groupName = "Test Family",
            members = listOf(creator),
            version = 1,
            creatorMemberId = "creator_123"
        )
        
        val result = DataValidator.validateGroupDefinition(group)
        assertTrue(result is ValidationResult.Valid)
    }
    
    @Test
    fun `rejects group with no members`() {
        val group = GroupDefinition(
            groupId = "group_123",
            groupName = "Test Family",
            members = emptyList(),
            version = 1,
            creatorMemberId = "creator_123"
        )
        
        val result = DataValidator.validateGroupDefinition(group)
        assertTrue(result is ValidationResult.Invalid)
    }
    
    @Test
    fun `validates 12-word mnemonic`() {
        val mnemonic = "word word word word word word word word word word word word"
        val result = DataValidator.validateMnemonic(mnemonic.trim())
        assertTrue(result is ValidationResult.Valid)
    }
    
    @Test
    fun `rejects invalid word count mnemonic`() {
        val mnemonic = "word word word word word word word word word word"
        val result = DataValidator.validateMnemonic(mnemonic.trim())
        assertTrue(result is ValidationResult.Invalid)
    }
}
