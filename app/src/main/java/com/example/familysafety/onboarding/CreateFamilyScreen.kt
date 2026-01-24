package com.example.familysafety.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateFamilyScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var familyName by remember { mutableStateOf("") }
    val isLoading by viewModel.isLoading.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Family") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                text = "Name your family",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Choose a name for your family group.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            OutlinedTextField(
                value = familyName,
                onValueChange = { familyName = it },
                label = { Text("Family Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g., The Smiths") }
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    scope.launch {
                        viewModel.setFamilyName(familyName)
                        val initialized = viewModel.initializeKeys()
                        if (initialized) {
                            val created = viewModel.createFamily()
                            if (created) {
                                onComplete()
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = familyName.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Create Family")
                }
            }
        }
    }
}
