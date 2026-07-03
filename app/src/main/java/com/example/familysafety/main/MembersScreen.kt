package com.example.familysafety.main

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.familysafety.invite.JoinRequest
import com.example.familysafety.ui.components.ShimmerBox
import kotlinx.coroutines.delay


@Composable
fun MembersScreen(
    viewModel: MainViewModel,
    onNavigateToMap: () -> Unit = {},
    onNavigateToInvite: () -> Unit = {},
    onNavigateToHistory: (memberId: String) -> Unit = {},
    onNavigateToFiles: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val familyMembers by viewModel.familyMembers.collectAsState()
    val memberLocations by viewModel.memberLocations.collectAsState()
    val memberAvatars by viewModel.memberAvatars.collectAsState()
    val memberRouteStatuses by viewModel.memberRouteStatuses.collectAsState()
    val groupName by viewModel.groupName.collectAsState()
    val groupDefinition by viewModel.groupDefinition.collectAsState()
    val myMemberId = viewModel.myMemberId
    // Only the creator may remove other members (enforced by GroupStateManager and
    // by every peer via GroupTransitionValidator) — don't offer a button that fails.
    val canRemoveOthers = groupDefinition?.creatorMemberId == myMemberId
    val haptic = LocalHapticFeedback.current

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 88.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        text = groupName.ifEmpty { "Family Members" },
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = if (familyMembers.isEmpty()) "No one is connected yet"
                               else "${familyMembers.size} people in this group",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                itemsIndexed(familyMembers, key = { _, member -> member.memberId }) { index, member ->
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(index) {
                        delay(minOf(index, 7) * 40L)
                        visible = true
                    }
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(150)) + slideInVertically(
                            initialOffsetY = { it / 4 },
                            animationSpec = tween(150)
                        )
                    ) {
                        MemberCard(
                            member = member,
                            location = memberLocations[member.memberId],
                            routeStatus = memberRouteStatuses[member.memberId],
                            avatar = memberAvatars[member.memberId],
                            isMe = member.memberId == myMemberId,
                            onShowOnMap = {
                                viewModel.focusOnMember(member.memberId)
                                onNavigateToMap()
                            },
                            onShowDriveEstimate = {
                                viewModel.requestDriveEstimate(member.memberId)
                            },
                            onShowHistory = { onNavigateToHistory(member.memberId) },
                            onRemove = { viewModel.removeMember(member.memberId) },
                            canRemove = canRemoveOthers,
                            colorHue = member.colorHue
                        )
                    }
                }
            }
        }

        MetalActionButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onNavigateToInvite()
            },
            icon = Icons.Default.PersonAdd,
            label = "Invite",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }

}

@Composable
internal fun JoinRequestCard(
    request: JoinRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isApproving by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    if (isApproving) {
        ShimmerBox(modifier = modifier.fillMaxWidth().height(72.dp))
        return
    }

    OutlinedCard(
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
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isApproving = true
                    onApprove()
                }) {
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
    routeStatus: MainViewModel.MemberRouteStatus?,
    avatar: Bitmap? = null,
    isMe: Boolean,
    colorHue: Float? = null,
    onShowOnMap: () -> Unit = {},
    onShowDriveEstimate: () -> Unit = {},
    onShowHistory: () -> Unit = {},
    onRemove: () -> Unit = {},
    canRemove: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            icon = {
                Icon(
                    Icons.Default.PersonRemove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Remove ${member.displayName}?") },
            text = {
                Text(
                    "This will remove ${member.displayName} from the family group on all devices. " +
                    "They will need a new invite to rejoin."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showRemoveDialog = false
                        onRemove()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

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
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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

    OutlinedCard(
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
                            text = buildMemberStatusText(timeAgo, routeStatus),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = buildWaitingStatusText(isMe, routeStatus),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (location != null && !isMe) {
                    IconButton(
                        onClick = onShowDriveEstimate,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Show drive time to ${member.displayName}",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onShowHistory, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "View location history",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!isMe && canRemove) {
                    IconButton(
                        onClick = { showRemoveDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonRemove,
                            contentDescription = "Remove member",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
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

private fun buildMemberStatusText(
    locationTimeAgo: String,
    routeStatus: MainViewModel.MemberRouteStatus?
): String {
    val route = routeStatus?.routeLabel ?: "Waiting"
    return "Seen $locationTimeAgo · $route"
}

private fun buildWaitingStatusText(
    isMe: Boolean,
    routeStatus: MainViewModel.MemberRouteStatus?
): String {
    val lastSeen = routeStatus?.lastSeenMs
    if (lastSeen != null) {
        return "Seen ${getTimeAgo(lastSeen)} · ${routeStatus.routeLabel}"
    }
    return if (isMe) "Starting location..." else "Waiting for location..."
}
