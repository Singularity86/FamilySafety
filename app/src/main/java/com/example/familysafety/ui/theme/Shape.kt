package com.example.familysafety.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Corner radius says what kind of thing something is. Each value has one job; pick by role,
// never by eye. Before this rule the app had nine values (3, 4, 6, 8, 12, 16, 24, 28 and
// pill) with nothing deciding between them, which reads as assembled rather than designed.
//
//   10 dp   anything you tap or that holds content: buttons, fields, cards, menus, panels
//   20 dp   things that slide over the screen: dialogs and bottom sheets. Twice the
//           control radius because they are larger; the same softness needs a larger number.
//   circle  people (avatars) and round icon controls
//   pill    small status tags and badges
//   square  lines rather than objects: progress bars, dividers
//
// Chat bubbles keep their own asymmetric shape: they are messages, not controls.
//
// Nested corners: when a rounded thing sits within p of another's corner and p is smaller
// than the outer radius r, give it radius r - p (or flush, p = 0: the same r) so the curves
// run parallel instead of pinching. Once p >= r the corners are too far apart to interact
// and the inner thing simply takes its own role's radius.

val ControlShape = RoundedCornerShape(10.dp)
val OverlayShape = RoundedCornerShape(20.dp)
val ChipShape    = CircleShape

// Role aliases kept for existing call sites.
val CardShape   = ControlShape
val ButtonShape = ControlShape

// All five sizes are set. Any size left out falls back to Material's defaults (4 dp text
// fields and menus, 28 dp dialogs), which is how two of the nine stray values got in.
val AppShapes = Shapes(
    extraSmall = ControlShape,  // text fields, menus, snackbars
    small      = ControlShape,
    medium     = ControlShape,  // cards
    large      = ControlShape,  // floating action button
    extraLarge = OverlayShape   // dialogs, bottom sheets, date pickers
)
