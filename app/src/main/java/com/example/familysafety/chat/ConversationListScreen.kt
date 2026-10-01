package com.example.familysafety.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.storage.ConversationSummary
import com.example.familysafety.storage.MessageType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Chat tab: the family chat pinned on top, then private one-to-one conversations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onOpenGroupChat: () -> Unit,
    onOpenConversation: (memberId: String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
    showTopBar: Boolean = true
) {
    val conversations by viewModel.conversations.collectAsState()
    val groupConversation by viewModel.groupConversation.collectAsState()
    val availableMembers by viewModel.availableMembers.collectAsState()
    var showNewChatDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = if (showTopBar) {
            { TopAppBar(title = { Text("Messages") }) }
        } else {
            {}
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                GroupChatItem(summary = groupConversation, onClick = onOpenGroupChat)
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Private messages",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { showNewChatDialog = true },
                        enabled = availableMembers.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New")
                    }
                }
            }

            if (conversations.isEmpty()) {
                item {
                    Text(
                        text = "Only you and the person you message can read a private " +
                            "conversation. Start one here or from someone's card in Family.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(conversations, key = { it.summary.conversationId }) { conversation ->
                    ConversationItem(
                        conversation = conversation,
                        onClick = { onOpenConversation(conversation.member.memberId) }
                    )
                }
            }
        }
    }

    if (showNewChatDialog) {
        NewChatDialog(
            members = availableMembers,
            existingConversations = conversations.map { it.member.memberId }.toSet(),
            onMemberSelected = { memberId ->
                showNewChatDialog = false
                onOpenConversation(memberId)
            },
            onDismiss = { showNewChatDialog = false }
        )
    }
}

@Composable
private fun GroupChatItem(
    summary: ConversationSummary?,
    onClick: () -> Unit
) {
    val unread = summary?.unreadCount ?: 0
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Groups,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Family Chat",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal
                    )
                    if (summary != null) {
                        Text(
                            text = formatTimestamp(summary.lastMessageTimestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = summary?.lastMessage ?: "Everyone in the family",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (unread > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        UnreadBadge(count = unread)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: ConversationWithMember,
    onClick: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = conversation.member.displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.member.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (conversation.summary.unreadCount > 0) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Normal
                        }
                    )

                    Text(
                        text = formatTimestamp(conversation.summary.lastMessageTimestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (conversation.summary.lastMessageType == MessageType.LOCATION) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        Text(
                            text = conversation.summary.lastMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (conversation.summary.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        UnreadBadge(count = conversation.summary.unreadCount)
                    }
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun NewChatDialog(
    members: List<FamilyMember>,
    existingConversations: Set<String>,
    onMemberSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New conversation") },
        text = {
            if (members.isEmpty()) {
                Text("No family members to chat with")
            } else {
                LazyColumn {
                    items(members) { member ->
                        val hasExistingConversation = existingConversations.contains(member.memberId)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onMemberSelected(member.memberId) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = member.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (hasExistingConversation) {
                                    Text(
                                        text = "Continue conversation",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60 * 1000 -> "Now"
        diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)}m"
        diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)}h"
        diff < 7 * 24 * 60 * 60 * 1000 -> {
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
