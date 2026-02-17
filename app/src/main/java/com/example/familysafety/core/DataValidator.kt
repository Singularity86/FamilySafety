package com.example.familysafety.core

import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.location.MemberLocation
import timber.log.Timber

/**
 * Validates data before processing to prevent malformed or malicious input
 */
object DataValidator {
    
    // Validation constants
    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0
    private const val MAX_ACCURACY_METERS = 10000.0f
    private const val MAX_SPEED_MS = 200.0f
    private const val MAX_NAME_LENGTH = 50
    private const val MAX_GROUP_NAME_LENGTH = 100
    private const val MAX_MEMBERS_PER_GROUP = 50
    private const val EXPECTED_PUBLIC_KEY_HEX_LENGTH = 64  // 32 bytes = 64 hex chars
    private const val MAX_TIMESTAMP_DRIFT_MS = 5 * 60 * 1000L
    
    /**
     * Validate location data
     */
    fun validateLocation(location: MemberLocation): ValidationResult {
        val errors = mutableListOf<String>()
        
        if (location.latitude < MIN_LATITUDE || location.latitude > MAX_LATITUDE) {
            errors.add("Invalid latitude: ${location.latitude}")
        }
        
        if (location.longitude < MIN_LONGITUDE || location.longitude > MAX_LONGITUDE) {
            errors.add("Invalid longitude: ${location.longitude}")
        }
        
        if (location.accuracy < 0 || location.accuracy > MAX_ACCURACY_METERS) {
            errors.add("Invalid accuracy: ${location.accuracy}")
        }
        
        location.speed?.let { speed ->
            if (speed < 0 || speed > MAX_SPEED_MS) {
                errors.add("Invalid speed: $speed")
            }
        }
        
        location.bearing?.let { bearing ->
            if (bearing < 0 || bearing > 360) {
                errors.add("Invalid bearing: $bearing")
            }
        }
        
        val now = System.currentTimeMillis()
        if (location.timestamp > now + MAX_TIMESTAMP_DRIFT_MS) {
            errors.add("Timestamp in future: ${location.timestamp}")
        }
        
        if (location.memberId.isBlank() || location.memberId.length > 128) {
            errors.add("Invalid member ID")
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            Timber.w("Location validation failed: ${errors.joinToString(", ")}")
            ValidationResult.Invalid(errors)
        }
    }
    
    /**
     * Validate family member data
     */
    fun validateFamilyMember(member: FamilyMember): ValidationResult {
        val errors = mutableListOf<String>()
        
        if (member.memberId.isBlank() || member.memberId.length > 128) {
            errors.add("Invalid member ID")
        }
        
        if (member.displayName.isBlank()) {
            errors.add("Display name cannot be empty")
        }
        if (member.displayName.length > MAX_NAME_LENGTH) {
            errors.add("Display name too long: ${member.displayName.length}")
        }
        if (!member.displayName.matches(Regex("^[a-zA-Z0-9 '-]+$"))) {
            errors.add("Display name contains invalid characters")
        }
        
        if (member.ed25519PublicKey.length != EXPECTED_PUBLIC_KEY_HEX_LENGTH) {
            errors.add("Invalid Ed25519 public key length: ${member.ed25519PublicKey.length} (expected $EXPECTED_PUBLIC_KEY_HEX_LENGTH hex chars)")
        }

        if (member.x25519PublicKey.length != EXPECTED_PUBLIC_KEY_HEX_LENGTH) {
            errors.add("Invalid X25519 public key length: ${member.x25519PublicKey.length} (expected $EXPECTED_PUBLIC_KEY_HEX_LENGTH hex chars)")
        }
        
        val now = System.currentTimeMillis()
        if (member.addedAtEpochMs > now + MAX_TIMESTAMP_DRIFT_MS) {
            errors.add("Timestamp in future: ${member.addedAtEpochMs}")
        }
        if (member.addedAtEpochMs < 0) {
            errors.add("Invalid timestamp: ${member.addedAtEpochMs}")
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            Timber.w("Family member validation failed: ${errors.joinToString(", ")}")
            ValidationResult.Invalid(errors)
        }
    }
    
    /**
     * Validate group definition
     */
    fun validateGroupDefinition(group: GroupDefinition): ValidationResult {
        val errors = mutableListOf<String>()
        
        if (group.groupId.isBlank() || group.groupId.length > 128) {
            errors.add("Invalid group ID")
        }
        
        if (group.groupName.isBlank()) {
            errors.add("Group name cannot be empty")
        }
        if (group.groupName.length > MAX_GROUP_NAME_LENGTH) {
            errors.add("Group name too long: ${group.groupName.length}")
        }
        
        if (group.members.isEmpty()) {
            errors.add("Group must have at least one member")
        }
        if (group.members.size > MAX_MEMBERS_PER_GROUP) {
            errors.add("Too many members: ${group.members.size}")
        }
        
        group.members.forEach { member ->
            when (val result = validateFamilyMember(member)) {
                is ValidationResult.Invalid -> errors.addAll(result.errors)
                ValidationResult.Valid -> {}
            }
        }
        
        if (group.version < 1) {
            errors.add("Invalid version: ${group.version}")
        }
        
        if (!group.members.any { it.memberId == group.creatorMemberId }) {
            errors.add("Creator is not a member of the group")
        }
        
        val memberIds = group.members.map { it.memberId }
        if (memberIds.size != memberIds.distinct().size) {
            errors.add("Duplicate member IDs detected")
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            Timber.w("Group definition validation failed: ${errors.joinToString(", ")}")
            ValidationResult.Invalid(errors)
        }
    }
    
    /**
     * Validate mnemonic phrase
     */
    fun validateMnemonic(mnemonic: String): ValidationResult {
        val words = mnemonic.trim().split("\\s+".toRegex())
        val errors = mutableListOf<String>()
        
        if (words.size != 12 && words.size != 24) {
            errors.add("Mnemonic must be 12 or 24 words, got ${words.size}")
        }
        
        words.forEach { word ->
            if (!word.matches(Regex("^[a-z]+$"))) {
                errors.add("Invalid word: $word")
            }
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            Timber.w("Mnemonic validation failed: ${errors.joinToString(", ")}")
            ValidationResult.Invalid(errors)
        }
    }
    
    /**
     * Sanitize user input
     */
    fun sanitizeDisplayName(name: String): String {
        return name
            .trim()
            .take(MAX_NAME_LENGTH)
            .replace(Regex("[^a-zA-Z0-9 '-]"), "")
    }
    
    /**
     * Sanitize group name
     */
    fun sanitizeGroupName(name: String): String {
        return name
            .trim()
            .take(MAX_GROUP_NAME_LENGTH)
            .replace(Regex("[^a-zA-Z0-9 '-]"), "")
    }
}

/**
 * Validation result
 */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}
