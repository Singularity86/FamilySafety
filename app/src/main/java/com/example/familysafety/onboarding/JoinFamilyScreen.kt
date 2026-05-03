package com.example.familysafety.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinFamilyScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit,
    onBack: () -> Unit,
    onNavigateToScanner: () -> Unit = {},
    navController: NavController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val membershipViewModel: MembershipViewModel = hiltViewModel()

    var inviteCode by remember { mutableStateOf("") }
    var joinFailed by remember { mutableStateOf(false) }
    var joinFailedReason by remember { mutableStateOf("") }

    // Receive QR scan result from QrScannerScreen via savedStateHandle
    val currentBackStack by navController.currentBackStackEntryAsState()
    val scannedCode = currentBackStack?.savedStateHandle?.get<String>("scanned_code")
    LaunchedEffect(scannedCode) {
        if (!scannedCode.isNullOrBlank()) {
            inviteCode = scannedCode
            currentBackStack?.savedStateHandle?.remove<String>("scanned_code")
        }
    }
    val isLoading by viewModel.isLoading.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Join Family") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
        ) {
            Text(
                text = "Enter invite code",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Ask a family member to share their invite code or QR code with you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it },
                label = { Text("Invite Code") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onNavigateToScanner) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code")
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            if (isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "Sending request…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (joinFailed) {
                Text(
                    text = joinFailedReason.ifBlank {
                        "Could not send request. Check the invite code and try again."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Button(
                onClick = {
                    joinFailed = false
                    scope.launch {
                        val result = viewModel.sendJoinRequest(inviteCode)
                        when (result) {
                            is OnboardingViewModel.JoinSubmitResult.Submitted -> {
                                membershipViewModel.setPendingApproval(
                                    familyName = result.familyName,
                                    inviterName = result.inviterName,
                                    memberId = result.memberId,
                                    inviterMemberId = result.inviterMemberId,
                                    groupId = result.groupId,
                                    joinRequestJson = result.joinRequestJson
                                )
                                // State change drives navigation — no explicit call needed.
                            }
                            is OnboardingViewModel.JoinSubmitResult.Failed -> {
                                joinFailed = true
                                joinFailedReason = result.reason
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = inviteCode.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Join Family")
                }
            }
        }
    }
}
