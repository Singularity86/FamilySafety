package com.example.familysafety.files

import com.example.familysafety.core.hexToBytes
import com.example.familysafety.crypto.GroupCipher
import java.security.MessageDigest

/**
 * Which key opens a chunk, manifest or file message stamped with a given key version.
 *
 * Kept free of Android and of the repository so the rule can be tested on its own.
 */
object FileKeys {

    private const val LEGACY_SALT = "familysafety-files-v1"

    /**
     * @param groupKeyHex the family's fileEncryptionKey, or null for a family created before
     *   1.12.0 that has none.
     * @return null when this device can't, or mustn't, open that version.
     *
     * In a family with a group key the legacy key (version 1) is refused (F22). It is
     * derived from the groupId, which is public in every topic name, so anyone on the broker
     * can compute it. Accepting it let an outsider encrypt chunks and manifests that
     * members' phones would open, store and pass on. No keyed family ever wrote version 1:
     * keyed families have always uploaded under the group key, so refusing it costs no real
     * file.
     *
     * Version 2 (the group key used raw, 1.12.0–1.13.1) stays readable: files from those
     * releases still circulate under it, and only members hold that key.
     *
     * Version 0 is never on the wire. It marks a local row written before the version column
     * existed. In a keyed family that row can only have been written under version 2, and in
     * a legacy family only under the legacy key.
     */
    fun forVersion(groupId: String, groupKeyHex: String?, keyVersion: Int): ByteArray? =
        if (groupKeyHex != null) {
            when (keyVersion) {
                FILE_KEY_VERSION_GROUP_SUBKEY ->
                    GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_FILES)
                FILE_KEY_VERSION_GROUP_SECRET, 0 -> groupKeyHex.hexToBytes()
                else -> null
            }
        } else {
            when (keyVersion) {
                FILE_KEY_VERSION_LEGACY, 0 -> legacy(groupId)
                else -> null
            }
        }

    /**
     * SHA-256(groupId + constant). Only for families with no group key, where it is the only
     * key there is. Both inputs are public, so it provides no confidentiality against anyone
     * who can read the broker.
     */
    fun legacy(groupId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest((groupId + LEGACY_SALT).toByteArray(Charsets.UTF_8))

}
