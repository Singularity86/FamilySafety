package com.example.familysafety.security

import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.Tombstones
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Something this phone refused that may mean someone outside the family is trying to get in
 * (Roadmap "Long-term: intrusion detection", phase 1). Each one is a drop point that already
 * existed; the log only makes them visible. Never holds message content.
 *
 * Mirrors iOS `SecurityEventKind` (`SecurityLog.swift`): [raw] and [title] must stay identical
 * so one family's reports from both apps read the same.
 */
@Serializable
enum class SecurityEventKind(val raw: String, val severity: Severity, val title: String) {
    @SerialName("identityInUseElsewhere")
    IDENTITY_IN_USE_ELSEWHERE("identityInUseElsewhere", Severity.HIGH, "Your account was online on another phone"),
    @SerialName("memberOnTwoPhones")
    MEMBER_ON_TWO_PHONES("memberOnTwoPhones", Severity.HIGH, "A member was online on two phones at once"),
    @SerialName("joinRequestRejected")
    JOIN_REQUEST_REJECTED("joinRequestRejected", Severity.MEDIUM, "A join request failed its checks"),
    @SerialName("impersonatedJoin")
    IMPERSONATED_JOIN("impersonatedJoin", Severity.HIGH, "A join request used a member's ID with different keys"),
    @SerialName("removedMemberActivity")
    REMOVED_MEMBER_ACTIVITY("removedMemberActivity", Severity.HIGH, "Someone removed from the family sent something"),
    @SerialName("forgedSignature")
    FORGED_SIGNATURE("forgedSignature", Severity.MEDIUM, "A message claiming to be from a member had a bad signature"),
    @SerialName("forgedControlMessage")
    FORGED_CONTROL_MESSAGE("forgedControlMessage", Severity.MEDIUM, "An unencrypted message was sent in a member's name"),
    @SerialName("downgrade")
    DOWNGRADE("downgrade", Severity.MEDIUM, "A member's messages switched to an older, weaker format"),
    @SerialName("rosterUpdateRejected")
    ROSTER_UPDATE_REJECTED("rosterUpdateRejected", Severity.MEDIUM, "A family update broke the rules and was refused"),
    @SerialName("replayRefused")
    REPLAY_REFUSED("replayRefused", Severity.LOW, "An old message was sent again and refused"),
    @SerialName("unknownSender")
    UNKNOWN_SENDER("unknownSender", Severity.LOW, "Someone not in the family sent something"),
    @SerialName("unknownGroupKey")
    UNKNOWN_GROUP_KEY("unknownGroupKey", Severity.LOW, "A message used a family key this phone doesn't have");

    enum class Severity { LOW, MEDIUM, HIGH }
}

/** One kind of event from one sender, grouped while it keeps happening. */
@Serializable
data class SecurityEvent(
    val kind: SecurityEventKind,
    /** The member ID it names, or null when there is none to name. */
    val memberId: String? = null,
    /** A short machine reason (e.g. "invite already used"); never message content. */
    val detail: String? = null,
    val firstAtMs: Long,
    val lastAtMs: Long,
    val count: Int = 1
)

/** The phone's security log: oldest first, repeats within an hour grouped, kept 30 days, capped. */
@Serializable
data class SecurityLog(val events: List<SecurityEvent> = emptyList()) {

    fun record(kind: SecurityEventKind, memberId: String?, detail: String?, now: Long): SecurityLog {
        val kept = events.filter { now - it.lastAtMs <= KEEP_MS }.toMutableList()
        val i = kept.indexOfLast { it.kind == kind && it.memberId == memberId && it.detail == detail }
        if (i >= 0 && now - kept[i].lastAtMs <= GROUP_WINDOW_MS) {
            kept[i] = kept[i].copy(lastAtMs = now, count = kept[i].count + 1)
            return SecurityLog(kept)
        }
        kept += SecurityEvent(kind, memberId, detail, firstAtMs = now, lastAtMs = now)
        return SecurityLog(kept.takeLast(CAP))
    }

    /**
     * A plain-text report the user can send wherever they choose. Member IDs are shortened to
     * 8 characters and named when they're in the family; no keys, no message content. The same
     * format as iOS `SecurityLog.report`.
     */
    fun report(group: GroupDefinition?, myMemberId: String, appVersion: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        fun whenAt(ms: Long) = format.format(Instant.ofEpochMilli(ms))
        fun who(id: String?): String {
            if (id == null) return "—"
            group?.findMemberById(id)?.let { return "${it.displayName} (${id.take(8)})" }
            if (group != null && group.removedMemberIds.any { Tombstones.memberIdOf(it) == id }) return "${id.take(8)}, removed member"
            return "${id.take(8)}, not in the family"
        }
        val lines = mutableListOf(
            "FamilySafety security report",
            "Made ${whenAt(now)} (${zone.id}) on this phone (${myMemberId.take(8)}), app $appVersion",
            "Family: ${group?.groupName ?: "none"}",
            "Events in the last 30 days: ${events.size}",
            "No messages, locations or keys are included.",
            ""
        )
        for (e in events.asReversed()) {
            val span = if (e.firstAtMs == e.lastAtMs) whenAt(e.firstAtMs) else "${whenAt(e.firstAtMs)} – ${whenAt(e.lastAtMs)}"
            val line = StringBuilder("[${e.kind.severity.name}] $span · ${e.kind.title} · from ${who(e.memberId)}")
            e.detail?.let { line.append(" · ").append(it) }
            if (e.count > 1) line.append(" · ×").append(e.count)
            lines += line.toString()
        }
        if (events.isEmpty()) lines += "Nothing unusual recorded."
        return lines.joinToString("\n")
    }

    companion object {
        const val GROUP_WINDOW_MS = 60 * 60 * 1000L
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
        const val CAP = 300
    }
}
