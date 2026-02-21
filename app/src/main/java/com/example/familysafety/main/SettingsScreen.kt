package com.example.familysafety.main

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val groupName by viewModel.groupName.collectAsState()
    val memberAvatars by viewModel.memberAvatars.collectAsState()
    val myAvatar: Bitmap? = memberAvatars[viewModel.myMemberId]
    val myMemberId = viewModel.myMemberId
    val myColorHue by viewModel.myColorHue.collectAsState()
    val scope = rememberCoroutineScope()

    // Gallery picker launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.setMyAvatar(it) }
    }

    // Recovery phrase dialog state
    var showWarningDialog by remember { mutableStateOf(false) }
    var showPhraseDialog by remember { mutableStateOf(false) }
    var recoveryWords by remember { mutableStateOf<List<String>>(emptyList()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Avatar card
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Profile Photo",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (myAvatar != null) "Tap to change" else "Tap to set a photo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Reuse MemberAvatar composable via a wrapper Box
                val myDisplayName by remember {
                    derivedStateOf {
                        viewModel.familyMembers.value
                            .find { it.memberId == myMemberId }?.displayName ?: "Me"
                    }
                }
                MemberAvatarClickable(
                    displayName = myDisplayName,
                    memberId = myMemberId,
                    bitmap = myAvatar,
                    colorHue = myColorHue,
                    size = 56.dp,
                    onClick = { galleryLauncher.launch("image/*") }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Color picker card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "My Color",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Choose the color others see for you on the map and in chat",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                ColorSwatchPicker(
                    selectedHue = myColorHue ?: memberHueFromId(myMemberId),
                    onHueSelected = { viewModel.updateMyColorHue(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Group Information",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Group Name")
                    Text(
                        text = groupName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Location Sharing",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                var locationEnabled by remember { mutableStateOf(true) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Share my location")
                    Switch(
                        checked = locationEnabled,
                        onCheckedChange = { locationEnabled = it }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Account",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                TextButton(
                    onClick = { showWarningDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("View Recovery Phrase")
                        Icon(imageVector = Icons.Default.KeyboardArrowRight, contentDescription = null)
                    }
                }

                HorizontalDivider()

                TextButton(
                    onClick = { /* TODO: Leave family */ },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Leave Family")
                        Icon(imageVector = Icons.Default.ExitToApp, contentDescription = null)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Version 1.0.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }

    // Step 1 — Security warning before revealing the phrase
    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text("View Recovery Phrase") },
            text = {
                Text(
                    "Your 12-word recovery phrase is the only way to restore your account " +
                    "if you lose your device.\n\n" +
                    "Never share it with anyone. Make sure no one can see your screen before continuing."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showWarningDialog = false
                    scope.launch {
                        recoveryWords = viewModel.getMnemonic()
                        showPhraseDialog = true
                    }
                }) {
                    Text("Reveal Phrase")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarningDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Step 2 — Show the words
    if (showPhraseDialog) {
        AlertDialog(
            onDismissRequest = {
                showPhraseDialog = false
                recoveryWords = emptyList()
            },
            title = { Text("Recovery Phrase") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (recoveryWords.isEmpty()) {
                        Text(
                            "Recovery phrase not available. This can happen if the account " +
                            "was set up before this feature was added. Please create a new " +
                            "account to generate a recoverable phrase."
                        )
                    } else {
                        Text(
                            text = "Write these words down in order and keep them somewhere safe.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        // Display in a 2-column numbered grid
                        val rows = recoveryWords.chunked(2)
                        rows.forEachIndexed { rowIndex, rowWords ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                rowWords.forEachIndexed { colIndex, word ->
                                    val number = rowIndex * 2 + colIndex + 1
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$number.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(24.dp)
                                        )
                                        Text(
                                            text = word,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                    }
                                }
                                // Pad odd-count lists
                                if (rowWords.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPhraseDialog = false
                    recoveryWords = emptyList()
                }) {
                    Text("Done")
                }
            }
        )
    }
}

/** Preset hues evenly spaced around the color wheel (same saturation/lightness as avatars). */
private val presetHues = listOf(0f, 30f, 60f, 90f, 140f, 180f, 210f, 240f, 270f, 300f, 330f)

/** Derive the auto hue from memberId (mirrors MemberAvatar logic). */
fun memberHueFromId(memberId: String): Float =
    ((memberId.hashCode().toLong() and 0xFFFFFFFFL) % 360).toFloat()

@Composable
private fun ColorSwatchPicker(
    selectedHue: Float,
    onHueSelected: (Float) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(presetHues) { hue ->
            val color = Color.hsl(hue, 0.55f, 0.45f)
            val isSelected = kotlin.math.abs(hue - selectedHue) < 5f
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (isSelected) Modifier.border(3.dp, Color.White, CircleShape)
                        else Modifier
                    )
                    .clickable { onHueSelected(hue) }
            )
        }
    }
}

@Composable
private fun MemberAvatarClickable(
    displayName: String,
    memberId: String,
    bitmap: Bitmap?,
    colorHue: Float? = null,
    size: Dp,
    onClick: () -> Unit
) {
    MemberAvatar(
        displayName = displayName,
        memberId = memberId,
        bitmap = bitmap,
        colorHue = colorHue,
        size = size,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
    )
}
