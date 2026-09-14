package com.example.familysafety.ui.theme

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Android has no prefers-reduced-motion media feature; the system-wide equivalent is the
// animator duration scale (Settings > Accessibility > Remove animations, or a 0x developer
// option) collapsing to zero. ValueAnimator.getDurationScale() has read this since API 26.
@Composable
fun rememberReducedMotion(): Boolean = remember { ValueAnimator.getDurationScale() == 0f }
