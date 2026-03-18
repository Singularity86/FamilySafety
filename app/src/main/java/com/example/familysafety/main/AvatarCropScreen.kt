package com.example.familysafety.main

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AvatarCropScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uri by viewModel.pendingCropUri.collectAsState()
    var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(uri) {
        val u = uri
        if (u == null) {
            onBack()
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            imageBitmap = context.contentResolver.openInputStream(u)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    }

    val doCancel: () -> Unit = {
        viewModel.clearPendingCropUri()
        onBack()
    }

    BackHandler { doCancel() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
    ) {
        val bmp = imageBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offset += pan
                        }
                    }
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        // Darkened overlay with circular cutout
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension * 0.42f
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addOval(Rect(center, radius))
            }
            drawPath(path, Color.Black.copy(alpha = 0.55f))
            drawCircle(Color.White.copy(alpha = 0.8f), radius = radius, style = Stroke(width = 2.dp.toPx()))
        }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = doCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "Crop photo",
                color = Color.White,
                fontSize = 18.sp
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp))
        }

        // Bottom bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = doCancel) {
                Text("Cancel", color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    imageBitmap?.let { bmp2 ->
                        imageBitmap = rotateBitmap(bmp2, -90f)
                        scale = 1f
                        offset = Offset.Zero
                    }
                },
                enabled = bmp != null
            ) {
                Icon(Icons.Default.RotateLeft, contentDescription = "Rotate left", tint = Color.White)
            }
            IconButton(
                onClick = {
                    imageBitmap?.let { bmp2 ->
                        imageBitmap = rotateBitmap(bmp2, 90f)
                        scale = 1f
                        offset = Offset.Zero
                    }
                },
                enabled = bmp != null
            ) {
                Icon(Icons.Default.RotateRight, contentDescription = "Rotate right", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val b = imageBitmap ?: return@Button
                    val cropped = computeCropBitmap(b, scale, offset, containerSize)
                    viewModel.setMyAvatarBitmap(cropped)
                    viewModel.clearPendingCropUri()
                    onBack()
                },
                enabled = bmp != null
            ) {
                Text("Save")
            }
        }
    }
}

/**
 * Compute the crop rectangle from the image's current transform state and
 * return a new [Bitmap] containing only the pixels that fall inside the crop circle.
 *
 * The crop circle is always centered on screen with radius = containerSize.minDimension * 0.42.
 * The image is displayed with ContentScale.Fit plus an additional [scale] and [offset] applied
 * via graphicsLayer (pivot at composable center).
 */
private fun computeCropBitmap(
    bitmap: Bitmap,
    scale: Float,
    offset: Offset,
    containerSize: IntSize
): Bitmap {
    val fitScale = minOf(
        containerSize.width / bitmap.width.toFloat(),
        containerSize.height / bitmap.height.toFloat()
    )
    // Total pixels-on-screen per image pixel
    val totalPixelScale = fitScale * scale

    val circleRadiusPx = minOf(containerSize.width, containerSize.height) * 0.42f

    // Circle center is at screen center; convert to image coordinates
    val cx = bitmap.width / 2f - offset.x / totalPixelScale
    val cy = bitmap.height / 2f - offset.y / totalPixelScale
    val r = circleRadiusPx / totalPixelScale

    val left = (cx - r).toInt().coerceIn(0, bitmap.width - 1)
    val top = (cy - r).toInt().coerceIn(0, bitmap.height - 1)
    val right = (cx + r).toInt().coerceIn(0, bitmap.width)
    val bottom = (cy + r).toInt().coerceIn(0, bitmap.height)
    val w = (right - left).coerceAtLeast(1)
    val h = (bottom - top).coerceAtLeast(1)

    return Bitmap.createBitmap(bitmap, left, top, w, h)
}

private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
