package com.example.familysafety.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeScreen(
    onCreateNew: () -> Unit,
    onRestore: () -> Unit,
    onJoinExisting: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🛡️",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Text(
            text = "FamilySafety",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Text(
            text = "Private, secure family location sharing",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 48.dp)
        )
        
        Button(
            onClick = onCreateNew,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Create New Family")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedButton(
            onClick = onRestore,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Restore from Recovery Phrase")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedButton(
            onClick = onJoinExisting,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Join Existing Family")
        }
    }
}
