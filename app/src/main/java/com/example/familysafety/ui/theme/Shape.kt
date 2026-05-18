package com.example.familysafety.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val CardShape   = RoundedCornerShape(8.dp)
val ButtonShape = RoundedCornerShape(8.dp)
val ChipShape   = RoundedCornerShape(999.dp)
val SheetShape  = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

val AppShapes = Shapes(
    small  = ButtonShape,
    medium = CardShape,
    large  = SheetShape
)
