package com.example.familysafety.onboarding

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.familysafety.ui.GlossaryTerm

private data class TutorialSlide(
    val title: String,
    val body: String,
    /** Words in [body] that should be rendered as tappable GlossaryTerms. */
    val glossaryTerms: List<String> = emptyList()
)

private val slides = listOf(
    TutorialSlide(
        title = "Welcome to Jibaro Family Safety",
        body = "Private location sharing for the people who matter most. " +
            "All data is end-to-end encrypted — nobody but your family can ever read it.",
        glossaryTerms = listOf("end-to-end encrypted")
    ),
    TutorialSlide(
        title = "Live Family Map",
        body = "See everyone's location in real time. " +
            "Create Geofence Zones and get notified the moment a family member arrives or leaves.",
        glossaryTerms = listOf("Geofence Zones")
    ),
    TutorialSlide(
        title = "Works Without Internet",
        body = "Download Offline Maps for travel or poor-signal areas. " +
            "On the same WiFi, phones use Local WiFi Mode and talk directly — no internet needed.",
        glossaryTerms = listOf("Offline Maps", "Local WiFi Mode")
    ),
    TutorialSlide(
        title = "Built-in Safety Features",
        body = "Crash Detection monitors your motion sensor while driving and alerts your family " +
            "if something goes wrong. Speed Alerts and Group Chat are included too — all encrypted.",
        glossaryTerms = listOf("Crash Detection")
    ),
    TutorialSlide(
        title = "Your Account, Your Keys",
        body = "Your identity is protected by a Recovery Phrase — 12 ordinary words " +
            "that only you have. We don't store them. Write them down somewhere safe.",
        glossaryTerms = listOf("Recovery Phrase")
    )
)

@Composable
fun TutorialScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    val slide = slides[page]
    val isLast = page == slides.lastIndex

    fun finish() {
        markTutorialShown(context)
        onFinish()
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Skip button — top right
        TextButton(
            onClick = { finish() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 8.dp)
                .statusBarsPadding()
        ) {
            Text("Skip")
        }

        // Slide content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val transform = if (targetState > initialState) {
                            slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                        } else {
                            slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                        }
                        transform.using(
                            SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> snap() })
                        )
                    },
                    label = "tutorial_slide"
                ) { targetPage ->
                    val s = slides[targetPage]
                    SlideContent(slide = s)
                }
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Page indicator dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                slides.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(if (i == page) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == page) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (page > 0) {
                    OutlinedButton(onClick = { page-- }) { Text("Back") }
                }
                Button(
                    onClick = {
                        if (isLast) finish() else page++
                    }
                ) {
                    Text(if (isLast) "Get Started" else "Next")
                }
            }
        }
    }
}

@Composable
private fun SlideContent(slide: TutorialSlide) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = slide.title,
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
            textAlign = TextAlign.Center
        )
        // Render body with glossary terms replaced by tappable GlossaryTerm composables
        SlideBody(body = slide.body, glossaryTerms = slide.glossaryTerms)
    }
}

@Composable
private fun SlideBody(body: String, glossaryTerms: List<String>) {
    if (glossaryTerms.isEmpty()) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    // Split body around the first found glossary term and render recursively
    val termFound = glossaryTerms.firstOrNull { body.contains(it, ignoreCase = true) }
    if (termFound == null) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val idx = body.indexOf(termFound, ignoreCase = true)
    val before = body.substring(0, idx)
    val after = body.substring(idx + termFound.length)
    val remaining = glossaryTerms.filter { it != termFound }

    // Wrap in a flow-like Row using a Column for line breaks
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = before.trimEnd(),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        GlossaryTerm(term = termFound)
        SlideBody(body = after.trimStart(), glossaryTerms = remaining)
    }
}

fun markTutorialShown(context: Context) {
    context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
        .edit().putBoolean("tutorial_shown", true).apply()
}

fun isTutorialShown(context: Context): Boolean =
    context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
        .getBoolean("tutorial_shown", false)
