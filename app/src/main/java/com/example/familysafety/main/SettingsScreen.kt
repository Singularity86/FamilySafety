package com.example.familysafety.main

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.example.familysafety.location.LocationService
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.familysafety.ui.theme.ThemeMode
import com.example.familysafety.ui.theme.ThemePreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onThemeChanged: (ThemeMode) -> Unit = {},
    onNavigateToCrop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val groupName by viewModel.groupName.collectAsState()
    val memberAvatars by viewModel.memberAvatars.collectAsState()
    val myAvatar: Bitmap? = memberAvatars[viewModel.myMemberId]
    val myMemberId = viewModel.myMemberId
    val myColorHue by viewModel.myColorHue.collectAsState()
    val familyMembers by viewModel.familyMembers.collectAsState()
    val myDisplayName = familyMembers.find { it.memberId == myMemberId }?.displayName ?: ""
    var nameEdit by remember(myDisplayName) { mutableStateOf(myDisplayName) }
    var isEditingName by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Gallery picker launcher — routes through the crop screen before saving
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.setPendingCropUri(it); onNavigateToCrop() }
    }

    // Recovery phrase dialog state
    var showWarningDialog by remember { mutableStateOf(false) }
    var showPhraseDialog by remember { mutableStateOf(false) }
    var recoveryWords by remember { mutableStateOf<List<String>>(emptyList()) }

    // Theme picker state
    var currentTheme by remember { mutableStateOf(ThemePreference.get(context)) }

    // Backup/restore dialog state
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var backupPasswordConfirm by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var backupError by remember { mutableStateOf<String?>(null) }
    var isBackupLoading by remember { mutableStateOf(false) }

    // Leave family dialog state
    var showLeaveDialog by remember { mutableStateOf(false) }
    var isLeaving by remember { mutableStateOf(false) }

    val importFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            pendingImportUri = it
            importPassword = ""
            showImportPasswordDialog = true
        }
    }

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

        // Profile card — avatar + display name
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                    MemberAvatarClickable(
                        displayName = myDisplayName.ifBlank { "Me" },
                        memberId = myMemberId,
                        bitmap = myAvatar,
                        colorHue = myColorHue,
                        size = 56.dp,
                        onClick = { galleryLauncher.launch("image/*") }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = "Display Name",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (isEditingName) {
                    OutlinedTextField(
                        value = nameEdit,
                        onValueChange = { nameEdit = it.take(30) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            viewModel.updateMyDisplayName(nameEdit)
                            isEditingName = false
                        }),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = {
                                    viewModel.updateMyDisplayName(nameEdit)
                                    isEditingName = false
                                }) {
                                    Icon(Icons.Default.Check, contentDescription = "Save")
                                }
                                IconButton(onClick = {
                                    nameEdit = myDisplayName
                                    isEditingName = false
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                                }
                            }
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = myDisplayName.ifBlank { "Not set" },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        IconButton(onClick = { isEditingName = true }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit name",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
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

        // Appearance card — theme picker
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Text(
                    text = "App Theme",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.values().forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = currentTheme == mode,
                            onClick = {
                                currentTheme = mode
                                ThemePreference.set(context, mode)
                                onThemeChanged(mode)
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ThemeMode.values().size
                            ),
                            label = {
                                Text(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> "System"
                                        ThemeMode.LIGHT -> "Light"
                                        ThemeMode.DARK -> "Dark"
                                    }
                                )
                            }
                        )
                    }
                }
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

        // Speed Alerts card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Speed Alerts",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Get notified when a family member drives above this speed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                val speedPrefs = context.getSharedPreferences("geofence_prefs", android.content.Context.MODE_PRIVATE)
                var speedAlertsEnabled by remember {
                    mutableStateOf(speedPrefs.getBoolean("speed_alerts_enabled", true))
                }
                var speedThreshold by remember {
                    mutableIntStateOf(speedPrefs.getInt("speed_threshold_mph", 90))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable speed alerts")
                    Switch(
                        checked = speedAlertsEnabled,
                        onCheckedChange = { enabled ->
                            speedAlertsEnabled = enabled
                            speedPrefs.edit().putBoolean("speed_alerts_enabled", enabled).apply()
                        }
                    )
                }

                if (speedAlertsEnabled) {
                    Text(
                        text = "Threshold: $speedThreshold mph",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = speedThreshold.toFloat(),
                        onValueChange = { value ->
                            speedThreshold = value.toInt()
                            speedPrefs.edit().putInt("speed_threshold_mph", value.toInt()).apply()
                        },
                        valueRange = 50f..120f,
                        steps = 69, // 1 mph steps between 50 and 120
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("50 mph", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("120 mph", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Backup card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Backup",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                TextButton(
                    onClick = {
                        backupPassword = ""
                        backupPasswordConfirm = ""
                        backupError = null
                        showExportDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Export Encrypted Backup")
                        Icon(imageVector = Icons.Default.KeyboardArrowRight, contentDescription = null)
                    }
                }
                HorizontalDivider()
                TextButton(
                    onClick = { importFilePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Restore from Backup")
                        Icon(imageVector = Icons.Default.KeyboardArrowRight, contentDescription = null)
                    }
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
                    onClick = { showLeaveDialog = true },
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

    // Export backup dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text("Export Backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Set a password to protect your backup. " +
                        "You will need this password to restore.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it; backupError = null },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = backupPasswordConfirm,
                        onValueChange = { backupPasswordConfirm = it; backupError = null },
                        label = { Text("Confirm Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (backupError != null) {
                        Text(
                            text = backupError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (backupPassword.length < 8) {
                            backupError = "Password must be at least 8 characters"
                            return@TextButton
                        }
                        if (backupPassword != backupPasswordConfirm) {
                            backupError = "Passwords do not match"
                            return@TextButton
                        }
                        isBackupLoading = true
                        scope.launch {
                            val uri = viewModel.exportBackup(backupPassword)
                            isBackupLoading = false
                            if (uri != null) {
                                showExportDialog = false
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Save Backup")
                                )
                            } else {
                                backupError = "Export failed — no family group or mnemonic found"
                            }
                        }
                    },
                    enabled = !isBackupLoading
                ) {
                    if (isBackupLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("Export")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Import backup — password dialog
    if (showImportPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportPasswordDialog = false
                pendingImportUri = null
            },
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text("Restore Backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the password you used when creating this backup. " +
                        "All current data will be replaced.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it; backupError = null },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (backupError != null) {
                        Text(
                            text = backupError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingImportUri ?: return@TextButton
                        isBackupLoading = true
                        scope.launch {
                            val result = viewModel.importBackup(uri, importPassword)
                            isBackupLoading = false
                            when (result) {
                                is MainViewModel.ImportResult.Success -> {
                                    showImportPasswordDialog = false
                                    // Restart the app so Hilt rebuilds with new keys
                                    val restartIntent = context.packageManager
                                        .getLaunchIntentForPackage(context.packageName)!!
                                        .apply {
                                            addFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                                            )
                                        }
                                    context.startActivity(restartIntent)
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                }
                                is MainViewModel.ImportResult.Error -> {
                                    backupError = result.message
                                }
                            }
                        }
                    },
                    enabled = !isBackupLoading
                ) {
                    if (isBackupLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportPasswordDialog = false
                    pendingImportUri = null
                }) { Text("Cancel") }
            }
        )
    }

    // Leave family confirmation dialog
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { if (!isLeaving) showLeaveDialog = false },
            icon = { Icon(Icons.Default.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Leave Family?") },
            text = {
                Text(
                    "This will permanently erase your cryptographic keys and group membership " +
                    "from this device. You will need your 12-word recovery phrase to rejoin.\n\n" +
                    "This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isLeaving = true
                        scope.launch {
                            LocationService.stopTracking(context)
                            viewModel.leaveFamily()
                            val restartIntent = context.packageManager
                                .getLaunchIntentForPackage(context.packageName)!!
                                .apply {
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    )
                                }
                            context.startActivity(restartIntent)
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    },
                    enabled = !isLeaving,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    if (isLeaving) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    else Text("Leave Family")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }, enabled = !isLeaving) {
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
