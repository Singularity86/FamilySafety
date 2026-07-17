package com.example.familysafety.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.familysafety.crash.CrashDetectionMonitor
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.window.Dialog
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.example.familysafety.ui.components.PermissionRationaleCard
import com.example.familysafety.ui.theme.ThemeMode
import com.example.familysafety.ui.theme.ThemePreference
import com.example.familysafety.ui.theme.UnitPreference
import com.example.familysafety.ui.theme.UnitSystem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onThemeChanged: (ThemeMode) -> Unit = {},
    onNavigateToCrop: () -> Unit = {},
    onReplayTutorial: () -> Unit = {},
    onNavigateToPrivacy: () -> Unit = {},
    onNavigateToSecurity: () -> Unit = {},
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
    var refreshRequested by remember { mutableStateOf(false) }
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

    val bgPermissionState = rememberPermissionState(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    var showBgRationaleCard by remember { mutableStateOf(false) }

    val importFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            pendingImportUri = it
            importPassword = ""
            showImportPasswordDialog = true
        }
    }

    // Battery optimization state — declared before Column so the banner appears at the top
    val batteryPowerManager = context.getSystemService(PowerManager::class.java)
    var isBatteryOptimized by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !batteryPowerManager.isIgnoringBatteryOptimizations(context.packageName)
        )
    }
    val batteryLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(batteryLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                isBatteryOptimized = !batteryPowerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        batteryLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { batteryLifecycleOwner.lifecycle.removeObserver(observer) }
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
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Battery optimization warning — shown at top so it's immediately visible
        if (isBatteryOptimized) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.BatteryAlert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "Battery Optimization Active",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Android may pause location tracking in the background. " +
                            "Tap below to keep Jibaro Family Safety running reliably.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("Fix Now")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Profile card — avatar + display name
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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

        // Units card
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Units",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                var currentUnits by remember { mutableStateOf(UnitPreference.get(context)) }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    UnitSystem.values().forEachIndexed { index, system ->
                        SegmentedButton(
                            selected = currentUnits == system,
                            onClick = {
                                currentUnits = system
                                UnitPreference.set(context, system)
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = UnitSystem.values().size
                            ),
                            label = {
                                Text(if (system == UnitSystem.IMPERIAL) "Imperial" else "Metric")
                            }
                        )
                    }
                }
                Text(
                    text = if (currentUnits == UnitSystem.IMPERIAL)
                        "Speed in mph · distance in miles"
                    else
                        "Speed in km/h · distance in km",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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

        // Tips & Help card
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Tips & Help",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                var tipsEnabled by remember {
                    mutableStateOf(
                        context.getSharedPreferences("familysafety_prefs", android.content.Context.MODE_PRIVATE)
                            .getBoolean("tips_enabled", true)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Show tips on startup",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = tipsEnabled,
                        onCheckedChange = { enabled ->
                            tipsEnabled = enabled
                            context.getSharedPreferences("familysafety_prefs", android.content.Context.MODE_PRIVATE)
                                .edit().putBoolean("tips_enabled", enabled).apply()
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onReplayTutorial,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Replay Introduction")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Live permission status — refreshes whenever user returns from system settings
                val lifecycleOwner = LocalLifecycleOwner.current
                var hasFineLocation by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }
                var hasBackgroundLocation by remember {
                    mutableStateOf(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                        else true
                    )
                }
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasFineLocation = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
                            hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                            else true
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val appSettingsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { /* permission re-check happens via ON_RESUME */ }

                PermissionStatusRow(
                    label = "Location while using app",
                    granted = hasFineLocation,
                    onGrant = {
                        appSettingsLauncher.launch(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    PermissionStatusRow(
                        label = "Location always (background)",
                        granted = hasBackgroundLocation,
                        onGrant = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                // Android 11+: OS only allows this via the Settings page
                                appSettingsLauncher.launch(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            } else {
                                // Android 10: show rationale card before the system dialog
                                showBgRationaleCard = true
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Speed Alerts card
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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

        // Crash Detection card
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Crash Detection",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                val crashPrefs = remember {
                    context.getSharedPreferences(
                        CrashDetectionMonitor.PREFS_NAME,
                        android.content.Context.MODE_PRIVATE
                    )
                }
                var crashEnabled by remember {
                    mutableStateOf(crashPrefs.getBoolean(CrashDetectionMonitor.PREF_ENABLED, false))
                }
                var crashSensitivity by remember {
                    mutableStateOf(
                        crashPrefs.getFloat(
                            CrashDetectionMonitor.PREF_SENSITIVITY,
                            CrashDetectionMonitor.SENSITIVITY_MEDIUM
                        )
                    )
                }
                Text(
                    text = "Detects sudden deceleration consistent with a vehicle collision. " +
                        "Only activates when you're in a moving vehicle (25+ mph). " +
                        "Alerts your family if you don't respond within 60 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable Crash Detection")
                    Switch(
                        checked = crashEnabled,
                        onCheckedChange = { enabled ->
                            crashEnabled = enabled
                            crashPrefs.edit()
                                .putBoolean(CrashDetectionMonitor.PREF_ENABLED, enabled).apply()
                        }
                    )
                }
                if (crashEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Sensitivity",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    val sensitivityOptions = listOf(
                        "Low (severe crashes only)" to 40f,
                        "Medium (recommended)" to 30f,
                        "High (more sensitive)" to 20f
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        sensitivityOptions.forEachIndexed { index, (label, value) ->
                            SegmentedButton(
                                selected = crashSensitivity == value,
                                onClick = {
                                    crashSensitivity = value
                                    crashPrefs.edit()
                                        .putFloat(CrashDetectionMonitor.PREF_SENSITIVITY, value).apply()
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = sensitivityOptions.size
                                ),
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Backup card
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                    onClick = {
                        viewModel.requestGroupStateRefresh()
                        refreshRequested = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Refresh Family State")
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    }
                }

                if (refreshRequested) {
                    Text(
                        text = "Requested a group refresh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

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

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Security",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                TextButton(
                    onClick = onNavigateToSecurity,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Security Dashboard")
                        Icon(imageVector = Icons.Default.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Privacy",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                TextButton(
                    onClick = onNavigateToPrivacy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Privacy & Data")
                        Icon(imageVector = Icons.Default.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Support card — optional one-time thank-you, gates nothing in the app
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Support This App",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "If Jibaro Family Safety has been useful to your family, you can send " +
                        "a thank-you of any amount. Completely optional, and nothing in the app " +
                        "changes either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://cash.app/\$OERev"))
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Say Thanks via Cash App")
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

    if (showBgRationaleCard) {
        Dialog(onDismissRequest = { showBgRationaleCard = false }) {
            PermissionRationaleCard(
                permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                title = "Background Location",
                rationale = "Background location allows your family to see your location when the app is not open. This only activates if you choose to share continuously. Your location is sent directly to your family circle — never to a server, never stored beyond 24 hours on your device.",
                onRequestPermission = {
                    showBgRationaleCard = false
                    bgPermissionState.launchPermissionRequest()
                },
                onDismiss = { showBgRationaleCard = false }
            )
        }
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

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (granted) "Granted" else "Not granted — tap to fix",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
            }
        }
        if (!granted) {
            TextButton(onClick = onGrant) { Text("Grant") }
        }
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
