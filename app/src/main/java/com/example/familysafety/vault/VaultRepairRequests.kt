package com.example.familysafety.vault

import com.example.familysafety.files.EncryptedFileMessage
import com.example.familysafety.files.FileMessageCipher
import com.example.familysafety.group.GroupDefinition
import kotlinx.serialization.json.Json

/**
 * Which incoming `vault/repair` requests get answered (agreed with iOS).
 *
 * - A sealed request is answered only if it opens under a key this phone holds. One it can't
 *   open is dropped, never re-read as plaintext.
 * - A plaintext request is answered only while [ACCEPT_PLAINTEXT] is on, for phones that
 *   haven't updated to the sealed sender.
 * - Either way, the requester must be in the family.
 */
object VaultRepairRequests {

    /** Turn off a release after every family has the sealed sender. */
    const val ACCEPT_PLAINTEXT = true

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(group: GroupDefinition, raw: String, acceptPlaintext: Boolean = ACCEPT_PLAINTEXT): VaultChunkRequest? {
        val sealed = runCatching { json.decodeFromString<EncryptedFileMessage>(raw) }.isSuccess
        val text = when {
            sealed -> FileMessageCipher.open(group, group.groupId, raw) ?: return null
            acceptPlaintext -> raw
            else -> return null
        }
        val request = runCatching { json.decodeFromString<VaultChunkRequest>(text) }.getOrNull() ?: return null
        return request.takeIf { group.findMemberById(it.requesterId) != null }
    }
}
