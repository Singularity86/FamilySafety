package com.example.familysafety.replication

import com.example.familysafety.location.MemberLocation
import com.example.familysafety.storage.ChatMessageEntity
import java.security.MessageDigest
import kotlin.math.floor

/**
 * What a message's or a location fix's author signs, so history can be passed on by anyone
 * and still be traced to whoever wrote it (F24).
 *
 * Replicated history used to carry no proof of authorship: the envelope says who *relayed* a
 * batch, not who wrote each item, so any member could slip in a message "from" someone else or
 * move their past positions. Live traffic was already bound to its sender; history wasn't.
 *
 * Pure JVM; Ed25519 is passed in.
 */
object AuthorSignatures {

    /**
     * @param conversationId the stored one: the groupId for group chat, "idA:idB" for private
     */
    fun chatMessage(
        messageId: String,
        conversationId: String,
        senderId: String,
        timestamp: Long,
        messageType: String,
        replyToMessageId: String?,
        content: String
    ): ByteArray =
        ("CHAT:v1:$messageId:$conversationId:$senderId:$timestamp:$messageType:" +
            "${replyToMessageId ?: ""}:${sha256Hex(content)}").toByteArray(Charsets.UTF_8)

    fun chatMessage(entity: ChatMessageEntity): ByteArray = chatMessage(
        entity.messageId, entity.conversationId, entity.senderId, entity.timestamp,
        entity.messageType.name, entity.replyToMessageId, entity.content
    )

    /**
     * Numbers as fixed-point integers, worked out in double precision with half rounded up
     * (`floor(x * k + 0.5)`), so both platforms produce the same digits: printing floats
     * differs between languages, and so does the rounding of negative halves.
     */
    fun location(location: MemberLocation): ByteArray =
        ("LOC:v1:${location.memberId}:${location.timestamp}:" +
            "${fixed(location.latitude, 1e7)}:${fixed(location.longitude, 1e7)}:" +
            "${fixed(location.accuracy.toDouble(), 100.0)}:" +
            "${location.speed?.let { fixed(it.toDouble(), 100.0) } ?: ""}:" +
            "${location.bearing?.let { fixed(it.toDouble(), 100.0) } ?: ""}").toByteArray(Charsets.UTF_8)

    internal fun fixed(value: Double, scale: Double): String = floor(value * scale + 0.5).toLong().toString()

    /**
     * What to do with an item of replicated history.
     *
     * Unsigned items are still taken while older builds re-serve history without signatures
     * and history from before this release has none; [acceptUnsigned] ends that.
     */
    enum class Verdict { ACCEPT, DROP }

    fun judge(
        authorSigHex: String?,
        authorEd25519Hex: String?,
        message: ByteArray,
        acceptUnsigned: Boolean,
        verify: (message: ByteArray, signatureHex: String, ed25519Hex: String) -> Boolean
    ): Verdict {
        // An author no longer in the family can't be checked.
        val key = authorEd25519Hex ?: return Verdict.DROP
        val sig = authorSigHex ?: return if (acceptUnsigned) Verdict.ACCEPT else Verdict.DROP
        val valid = runCatching { verify(message, sig, key) }.getOrDefault(false)
        return if (valid) Verdict.ACCEPT else Verdict.DROP
    }

    /** One release of grace for unsigned history (F24); see [judge]. */
    const val ACCEPT_UNSIGNED_HISTORY = true

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
