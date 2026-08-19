package com.example.familysafety.main

import android.app.Activity
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.familysafety.vault.VaultItem
import com.example.familysafety.vault.VaultViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The vault.
 *
 * Deliberately plain, and deliberately silent about what it is. It never says a code was
 * wrong, never says how many vaults exist, and never distinguishes "you opened an empty
 * vault" from "you opened a vault that has nothing in it" — because those are the same thing
 * and any difference between them would be the oracle the whole design exists to avoid.
 *
 * The screen is marked `FLAG_SECURE`, so it does not appear in the recents thumbnail and
 * cannot be screenshotted, and it drops the key the moment it stops.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    viewModel: VaultViewModel
) {
    val context = LocalContext.current
    val session by viewModel.session.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()

    var pendingDelete by remember { mutableStateOf<VaultItem?>(null) }
    var showReclaimed by remember { mutableStateOf(false) }
    var pendingFirstWrite by remember { mutableStateOf<PendingAdd?>(null) }

    // No screenshots, and no thumbnail in the recents switcher — the screen most likely to be
    // seen by someone holding the phone is the one the user is not looking at.
    val activity = context as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Backgrounding the app closes the vault. Leaving it open behind a home press is the
    // realistic way this leaks, not an attack.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.close()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The session going away — by timeout, by stop, by the user leaving — is what closes the
    // screen. There is no separate "locked" state to get out of step with the key.
    LaunchedEffect(session) {
        if (session == null) onBack()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
            ?: "document"
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        // Adding the first thing under a code that opened nothing is the moment a typo turns
        // into a second, invisible vault — the document goes somewhere real, just not where
        // the user thinks, and nothing afterwards will ever say so. It is the one point where
        // a confirmation is worth the friction.
        val session = viewModel.session.value
        if (session != null && session.items.isEmpty() && session.reclaimed.isEmpty()) {
            pendingFirstWrite = PendingAdd(uri, name, mime)
        } else {
            viewModel.addDocument(uri, name, mime)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val current = session ?: return

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                // No title. A title is a label, and a label is what someone reads over a
                // shoulder. The count is the one thing the user needs, and it is also the
                // signal that catches a mistyped code before anything is written under it.
                title = {
                    Text(
                        if (current.items.isEmpty()) "Nothing here"
                        else "${current.items.size} item${if (current.items.size == 1) "" else "s"}"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.close() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!busy) {
                FloatingActionButton(onClick = { picker.launch("*/*") }) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (current.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Says nothing about codes. This is what a fresh vault looks like and what
                    // a mistyped code looks like, and they have to look the same.
                    Text(
                        "Add something to keep it here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(current.items, key = { it.fileId }) { item ->
                        VaultRow(
                            item = item,
                            onClick = { viewModel.openDocument(item, context) },
                            onLongClick = { pendingDelete = item }
                        )
                        HorizontalDivider()
                    }
                }
            }

            if (current.reclaimed.isNotEmpty()) {
                TextButton(
                    onClick = { showReclaimed = !showReclaimed },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text(
                        if (showReclaimed) "Hide removed (${current.reclaimed.size})"
                        else "Removed (${current.reclaimed.size})"
                    )
                }
                if (showReclaimed) {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(current.reclaimed, key = { it.fileId }) { item ->
                            ListItem(
                                headlineContent = {
                                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = { Text("Removed — still recoverable") },
                                trailingContent = {
                                    IconButton(onClick = { viewModel.restore(item) }) {
                                        Icon(Icons.Default.Restore, contentDescription = "Put back")
                                    }
                                }
                            )
                        }
                    }
                }
            }

            PassphraseFooter()
        }
    }

    pendingFirstWrite?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingFirstWrite = null },
            title = { Text("Nothing is here yet") },
            // Cannot say "check your code is right", because that would imply the app knows
            // what right means. It says what is true: this place is empty, and putting
            // something here starts a new one.
            //
            // It is also the moment the passphrase stops being a guess and becomes the only
            // way back, which is the one moment where saying so can still change what someone
            // picks — the footer says it, but here it is actionable.
            text = {
                Text(
                    "This is empty, so \"${pending.name}\" will start a new collection here. " +
                        "If you expected to see things already, go back and try again — what " +
                        "you typed decides which collection you get.\n\n" +
                        "If you go ahead, what you typed becomes the only way back to it. " +
                        "Nothing can reset it, so make it something long that only your " +
                        "family would say."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addDocument(pending.uri, pending.name, pending.mimeType)
                    pendingFirstWrite = null
                }) { Text("Add it here") }
            },
            dismissButton = {
                TextButton(onClick = { pendingFirstWrite = null }) { Text("Cancel") }
            }
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove \"${item.name}\"?") },
            // Honest about what removal does. Anyone with the code can remove anything, and
            // the protection offered is that it can be undone — not that it is prevented.
            text = {
                Text(
                    "It stops showing here for everyone. You can put it back from " +
                        "\"Removed\" until the space is reused."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item)
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * The one place the app can say what protects a vault (F10).
 *
 * It cannot be said at the entry point: that is a search box, and any hint there — a minimum
 * length, a strength meter, the word "passphrase" — is a prompt in disguise, which would prove
 * a vault exists to anyone holding the phone. In here the reader has already submitted
 * something, so the fact that vaults exist is not news to them.
 *
 * Shown always, with no "got it" to dismiss and no stored state behind it. A note that appears
 * for some vaults and not others is an oracle, and remembering that this device has seen it is
 * one more thing on disk that a full vault has and an empty one does not. Repetition is the
 * cheaper price.
 *
 * The wording never distinguishes a full vault from an empty one, and never suggests that what
 * was typed might have been wrong — "anything kept here", not "your documents".
 */
@Composable
private fun PassphraseFooter() {
    HorizontalDivider()
    Text(
        "What you typed is the only thing protecting anything kept here. It is not stored on " +
            "any phone or any server, so it can never be reset or recovered — and anyone who " +
            "guesses it sees exactly what you see. Several unrelated words are far harder to " +
            "guess than one.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/** A document chosen but not yet committed, held while the first-write question is asked. */
private data class PendingAdd(
    val uri: android.net.Uri,
    val name: String,
    val mimeType: String
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VaultRow(
    item: VaultItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val added = remember(item.addedAt) {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(item.addedAt))
    }
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = {
            Text(
                item.name,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = { Text("${item.sizeBytes / 1024} KB · $added") }
    )
}
