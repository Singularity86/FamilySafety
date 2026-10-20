package com.example.familysafety.main

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.familysafety.BuildConfig
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.Tombstones
import com.example.familysafety.security.SecurityEvent
import com.example.familysafety.security.SecurityEventKind
import com.example.familysafety.security.SecurityRecorder
import com.example.familysafety.ui.theme.ButtonShape
import com.example.familysafety.ui.theme.Spacing
import com.example.familysafety.ui.theme.statusColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings → Security log: what this phone refused that may mean someone outside the family is
 * trying to get in, newest first, and a plain-text report the user can send wherever they
 * choose. Nothing is sent automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityLogScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val log by SecurityRecorder.log.collectAsState()
    val group by viewModel.groupDefinition.collectAsState()
    val context = LocalContext.current
    val events = log.events.asReversed()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.md)
        ) {
            Text(
                "Messages this phone refused in the last 30 days. Most are harmless, like a " +
                    "message that arrived twice. A report holds no messages, locations or keys.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.sm)
            )
            Button(
                onClick = {
                    SecurityRecorder.flush()
                    val report = log.report(group, viewModel.myMemberId, BuildConfig.VERSION_NAME, System.currentTimeMillis())
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "FamilySafety security report")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    context.startActivity(Intent.createChooser(send, "Send report"))
                },
                shape = ButtonShape,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Send report") }
            Spacer(Modifier.height(Spacing.sm))

            if (events.isEmpty()) {
                Text(
                    "Nothing unusual recorded.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = Spacing.md)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(events, key = { "${it.kind.raw}|${it.memberId}|${it.detail}|${it.firstAtMs}" }) { event ->
                        SecurityEventRow(event, group)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityEventRow(event: SecurityEvent, group: GroupDefinition?) {
    val dot = when (event.kind.severity) {
        SecurityEventKind.Severity.HIGH -> statusColors.dangerIndicator
        SecurityEventKind.Severity.MEDIUM -> statusColors.warningIndicator
        SecurityEventKind.Severity.LOW -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .background(dot, CircleShape)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(event.kind.title, style = MaterialTheme.typography.bodyLarge)
            val parts = buildList {
                add(sender(event.memberId, group))
                event.detail?.let { add(it) }
                if (event.count > 1) add("×${event.count}")
            }
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                span(event),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val rowTime = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())

private fun span(event: SecurityEvent): String {
    val first = rowTime.format(Instant.ofEpochMilli(event.firstAtMs))
    return if (event.firstAtMs == event.lastAtMs) first
    else "$first – ${rowTime.format(Instant.ofEpochMilli(event.lastAtMs))}"
}

private fun sender(memberId: String?, group: GroupDefinition?): String {
    if (memberId == null) return "Sender unknown"
    group?.findMemberById(memberId)?.let { return "From ${it.displayName}" }
    if (group != null && group.removedMemberIds.any { Tombstones.memberIdOf(it) == memberId }) {
        return "From a removed member (${memberId.take(8)})"
    }
    return "From ${memberId.take(8)}, not in the family"
}
