package com.example.familysafety.main

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import com.example.familysafety.ui.components.AppButton
import com.example.familysafety.ui.components.ButtonState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val pendingRequests by viewModel.pendingJoinRequests.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.generateInviteCode()
            .onSuccess { inviteCode = it }
        isLoading = false
    }

    // Generate QR bitmap off the main thread
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(inviteCode) {
        val code = inviteCode ?: return@LaunchedEffect
        qrBitmap = withContext(Dispatchers.Default) {
            try {
                val size = 512
                val bits = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, size, size)
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
                for (x in 0 until size) {
                    for (y in 0 until size) {
                        bmp.setPixel(x, y, if (bits[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
                bmp
            } catch (_: Exception) { null }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invite to Family") },
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Pending join requests — shown at the top when someone is waiting
            if (pendingRequests.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Join Requests",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    pendingRequests.forEach { request ->
                        JoinRequestCard(
                            request = request,
                            onApprove = { viewModel.approveJoinRequest(request) },
                            onReject = { viewModel.rejectJoinRequest(request) }
                        )
                    }
                    HorizontalDivider()
                }
            }

            Text(
                text = "Share this with someone to invite them to your family.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (isLoading) {
                CircularProgressIndicator()
            } else {
                // QR code card
                val bmp = qrBitmap
                if (bmp != null) {
                    // Container stays white regardless of theme — the QR code's quiet
                    // zone needs light margin around the dark modules to stay scannable.
                    OutlinedCard(
                        modifier = Modifier.size(240.dp),
                        colors = CardDefaults.outlinedCardColors(containerColor = Color.White)
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Invite QR code",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        )
                    }
                }

                // Text code block
                val code = inviteCode
                if (code != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Or share the code manually:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = code,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                textAlign = TextAlign.Center
                            )
                        }

                        AppButton(
                            label = "Copy Code",
                            state = if (copied) ButtonState.Success("Copied!") else ButtonState.Idle,
                            onClick = {
                                clipboard.setText(AnnotatedString(code))
                                copied = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
