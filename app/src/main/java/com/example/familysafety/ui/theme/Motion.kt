package com.example.familysafety.ui.theme

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Android has no prefers-reduced-motion media feature; the system-wide equivalent is the
// animator duration scale collapsing to zero (Settings > Accessibility > Remove animations,
// or the developer option). ValueAnimator.areAnimatorsEnabled() reports exactly that, and
// also goes false when Battery Saver switches animations off. It is public since API 26, the
// app's minSdk.
//
// This used ValueAnimator.getDurationScale(), which only became public in API 33. On Android
// 8–12 it resolved to a hidden, non-SDK method: it worked on most phones, but nothing
// guaranteed it, and a device or update that blocked it would crash the bottom tab bar.
@Composable
fun rememberReducedMotion(): Boolean = remember { !ValueAnimator.areAnimatorsEnabled() }
