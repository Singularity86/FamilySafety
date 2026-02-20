package com.example.familysafety.avatar

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File-based local storage for avatar images.
 * Each avatar is stored as a JPEG at filesDir/avatars/{memberId}.jpg.
 */
@Singleton
class AvatarStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dir: File
        get() = File(context.filesDir, "avatars").also { it.mkdirs() }

    private fun file(memberId: String) = File(dir, "$memberId.jpg")

    fun save(memberId: String, jpeg: ByteArray) {
        file(memberId).writeBytes(jpeg)
    }

    fun load(memberId: String): ByteArray? =
        file(memberId).takeIf { it.exists() }?.readBytes()

    fun delete(memberId: String) {
        file(memberId).delete()
    }

    /** SHA-256 of the cached JPEG, or null if not cached. */
    fun cachedHash(memberId: String): String? =
        load(memberId)?.let { sha256Hex(it) }

    companion object {
        fun sha256Hex(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
