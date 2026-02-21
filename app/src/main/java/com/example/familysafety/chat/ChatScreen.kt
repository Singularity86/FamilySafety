package com.example.familysafety.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.familysafety.storage.ChatMessageEntity
import com.example.familysafety.storage.MessageStatus
import com.example.familysafety.storage.MessageType
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Screen showing messages in a single conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    memberId: String,         // pass empty string for group chat
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val currentRecipient by viewModel.currentRecipient.collectAsState()
    val messages by viewModel.currentMessages.collectAsState()
    val messageInput by viewModel.messageInput.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val isGroupChat by viewModel.isGroupConversation.collectAsState()
    val memberNames by viewModel.memberNames.collectAsState()
    val memberColorHues by viewModel.memberColorHues.collectAsState()

    val listState = rememberLazyListState()

    // Open the right conversation when screen loads
    LaunchedEffect(memberId) {
        if (memberId.isEmpty()) {
            viewModel.openGroupConversation()
        } else {
            viewModel.openConversation(memberId)
        }
    }

    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isGroupChat) "G" else (currentRecipient?.displayName ?: "?").take(1).uppercase(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (isGroupChat) "Family Chat" else currentRecipient?.displayName ?: "Chat")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.closeConversation()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Group messages by date
                val groupedMessages = messages.groupBy { message ->
                    getDateKey(message.timestamp)
                }

                groupedMessages.forEach { (dateKey, dayMessages) ->
                    item {
                        DateHeader(dateKey)
                    }

                    items(dayMessages) { message ->
                        MessageBubble(
                            message = message,
                            senderName = if (!message.isOutgoing) memberNames[message.senderId] else null,
                            senderColorHue = memberColorHues[message.senderId]
                        )
                    }
                }
            }

            // Input area
            MessageInput(
                value = messageInput,
                onValueChange = viewModel::onMessageInputChanged,
                onSend = viewModel::sendMessage,
                isSending = isSending
            )
        }
    }
}

@Composable
private fun DateHeader(dateKey: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Text(
                text = dateKey,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** HSL color matching MemberAvatar, with optional override hue. */
private fun memberBubbleColor(memberId: String, colorHue: Float? = null): Color {
    val hue = colorHue ?: ((memberId.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()
    return Color.hsl(hue, 0.55f, 0.45f)
}

@Composable
private fun MessageBubble(
    message: ChatMessageEntity,
    senderName: String? = null,
    senderColorHue: Float? = null
) {
    val isOutgoing = message.isOutgoing

    // Incoming bubbles use the sender's chosen color (or auto-derived from ID).
    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primary
    } else {
        memberBubbleColor(message.senderId, senderColorHue)
    }
    val textColor = Color.White

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start) {
        if (senderName != null) {
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = bubbleColor,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isOutgoing) 16.dp else 4.dp,
                bottomEnd = if (isOutgoing) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(containerColor = bubbleColor)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Content
                when (message.messageType) {
                    MessageType.TEXT -> {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    }
                    MessageType.LOCATION -> {
                        LocationMessageContent(
                            content = message.content,
                            isOutgoing = true  // always white text on colored bg
                        )
                    }
                    MessageType.SYSTEM -> {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Timestamp and status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )

                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageStatusIcon(
                            status = message.status,
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
        } // Column
    }
}

@Composable
private fun LocationMessageContent(
    content: String,
    isOutgoing: Boolean
) {
    val textColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = textColor
        )
        Spacer(modifier = Modifier.width(8.dp))

        Column {
            Text(
                text = "Shared location",
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )

            // Parse location outside composable context
            val locationText = remember(content) {
                try {
                    val location = Json.decodeFromString<LocationShareContent>(content)
                    "%.4f, %.4f".format(location.latitude, location.longitude)
                } catch (e: Exception) {
                    null
                }
            }

            locationText?.let { coords ->
                Text(
                    text = coords,
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun MessageStatusIcon(
    status: MessageStatus,
    tint: Color
) {
    when (status) {
        MessageStatus.PENDING -> {
            Icon(
                Icons.Default.Schedule,
                contentDescription = "Sending",
                modifier = Modifier.size(14.dp),
                tint = tint
            )
        }
        MessageStatus.SENT -> {
            Icon(
                Icons.Default.Check,
                contentDescription = "Sent",
                modifier = Modifier.size(14.dp),
                tint = tint
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                Icons.Default.DoneAll,
                contentDescription = "Delivered",
                modifier = Modifier.size(14.dp),
                tint = tint
            )
        }
        MessageStatus.READ -> {
            Icon(
                Icons.Default.DoneAll,
                contentDescription = "Read",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.tertiary
            )
        }
        MessageStatus.FAILED -> {
            Icon(
                Icons.Default.Error,
                contentDescription = "Failed",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onSend,
                enabled = value.isNotBlank() && !isSending,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (value.isNotBlank() && !isSending) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (value.isNotBlank()) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

private fun getDateKey(timestamp: Long): String {
    val calendar = Calendar.getInstance()
    val today = calendar.clone() as Calendar

    calendar.timeInMillis = timestamp

    return when {
        isSameDay(calendar, today) -> "Today"
        isYesterday(calendar, today) -> "Yesterday"
        else -> SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isYesterday(cal1: Calendar, today: Calendar): Boolean {
    val yesterday = today.clone() as Calendar
    yesterday.add(Calendar.DAY_OF_YEAR, -1)
    return isSameDay(cal1, yesterday)
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
}
