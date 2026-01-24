package com.example.familysafety.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MembersScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val familyMembers by viewModel.familyMembers.collectAsState()
    val memberLocations by viewModel.memberLocations.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Family Members",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (familyMembers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No family members yet")
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(familyMembers) { member ->
                    MemberCard(
                        member = member,
                        location = memberLocations[member.memberId]
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberCard(
    member: com.example.familysafety.group.FamilyMember,
    location: com.example.familysafety.location.MemberLocation?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
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
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
                
                Column {
                    Text(
                        text = member.displayName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    if (location != null) {
                        val timeAgo = getTimeAgo(location.timestamp)
                        Text(
                            text = "Updated $timeAgo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Location unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (location != null) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Online",
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
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> "${diff / 86400_000}d ago"
    }
}
