package com.example.familysafety.main

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.familysafety.invite.JoinRequest


@Composable
fun MembersScreen(
    viewModel: MainViewModel,
    onNavigateToMap: () -> Unit = {},
    onNavigateToInvite: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val familyMembers by viewModel.familyMembers.collectAsState()
    val memberLocations by viewModel.memberLocations.collectAsState()
    val pendingRequests by viewModel.pendingJoinRequests.collectAsState()
    val memberAvatars by viewModel.memberAvatars.collectAsState()
    val myMemberId = viewModel.myMemberId

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToInvite,
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                text = { Text("Invite") }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    text = "Family Members",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Pending join requests section
            if (pendingRequests.isNotEmpty()) {
                item {
                    Text(
                        text = "Join Requests",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                items(pendingRequests, key = { it.requestId }) { request ->
                    JoinRequestCard(
                        request = request,
                        onApprove = { viewModel.approveJoinRequest(request) },
                        onReject = { viewModel.rejectJoinRequest(request) }
                    )
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }

            if (familyMembers.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No family members yet")
                    }
                }
            } else {
                items(familyMembers, key = { it.memberId }) { member ->
                    MemberCard(
                        member = member,
                        location = memberLocations[member.memberId],
                        avatar = memberAvatars[member.memberId],
                        isMe = member.memberId == myMemberId,
                        onShowOnMap = {
                            viewModel.focusOnMember(member.memberId)
                            onNavigateToMap()
                        },
                        colorHue = member.colorHue
                    )
                }
            }
        }
    }

}

@Composable
private fun JoinRequestCard(
    request: JoinRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = request.displayName,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Wants to join your family",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onReject) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Reject",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                IconButton(onClick = onApprove) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Approve",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
internal fun MemberAvatar(
    displayName: String,
    memberId: String,
    bitmap: Bitmap? = null,
    colorHue: Float? = null,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = displayName,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
        )
        return
    }

    // Initials fallback
    val initials = displayName
        .trim()
        .split("\\s+".toRegex())
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }

    val hue = colorHue ?: ((memberId.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()
    val bgColor = Color.hsl(hue, 0.55f, 0.45f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor)
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = (size.value * 0.38f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MemberCard(
    member: com.example.familysafety.group.FamilyMember,
    location: com.example.familysafety.location.MemberLocation?,
    avatar: Bitmap? = null,
    isMe: Boolean,
    colorHue: Float? = null,
    onShowOnMap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(member.displayName) },
            text = {
                if (location != null) {
                    Text("Last seen ${getTimeAgo(location.timestamp)}. Show their location on the map?")
                } else {
                    Text("No location available yet for ${member.displayName}.")
                }
            },
            confirmButton = {
                if (location != null) {
                    TextButton(onClick = {
                        showDialog = false
                        onShowOnMap()
                    }) {
                        Text("Show on map")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = { showDialog = true }
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MemberAvatar(
                    displayName = member.displayName,
                    memberId = member.memberId,
                    bitmap = avatar,
                    colorHue = colorHue
                )

                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = member.displayName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isMe) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "You",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    if (location != null) {
                        val timeAgo = getTimeAgo(location.timestamp)
                        Text(
                            text = "Updated $timeAgo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = if (isMe) "Starting location..." else "Waiting for location...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (location != null) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Location available",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun getTimeAgo(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> "${diff / 86_400_000}d ago"
    }
}
