package com.example.familysafety.main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders every state of the map markers onto one sheet and writes it to the app's
 * external files directory, to be pulled with `adb pull` and looked at.
 *
 * The pins are Canvas drawing: a unit test can prove the clustering arithmetic (see
 * `MarkerClusteringTest`) but nothing on the JVM can say whether the result is legible.
 * This is the cheapest way to see the artwork without waiting for four family members to
 * stand in the same doorway.
 */
@RunWith(AndroidJUnit4::class)
class MarkerRenderHarness {

    @Test
    fun renderMarkerSheet() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val sizePx = (52 * density).toInt()

        fun face(
            id: String,
            name: String,
            hue: Float,
            avatar: Bitmap? = null,
            stale: Boolean = false
        ) = ClusterFace(id, name, avatar, hue, stale)

        val photo = solidBitmap(96, Color.rgb(90, 140, 200))

        val samples = listOf<Pair<String, Bitmap>>(
            "one person" to memberMarkerBitmap("Ana Ruiz", "m-ana", null, sizePx, 210f),
            "one person, photo" to memberMarkerBitmap("Ana Ruiz", "m-ana", photo, sizePx, 210f),
            "two together" to clusterMarkerBitmap(
                listOf(face("a", "Ana Ruiz", 210f), face("b", "Beto", 30f)),
                totalCount = 2, sizePx = sizePx, highlightMemberId = null, isOpen = false
            ),
            "three together" to clusterMarkerBitmap(
                listOf(face("a", "Ana", 210f), face("b", "Beto", 30f), face("c", "Cruz", 120f)),
                totalCount = 3, sizePx = sizePx, highlightMemberId = null, isOpen = false
            ),
            "five together" to clusterMarkerBitmap(
                listOf(face("a", "Ana", 210f), face("b", "Beto", 30f), face("c", "Cruz", 120f)),
                totalCount = 5, sizePx = sizePx, highlightMemberId = null, isOpen = false
            ),
            "twelve together" to clusterMarkerBitmap(
                listOf(face("a", "Ana", 210f), face("b", "Beto", 30f), face("c", "Cruz", 120f)),
                totalCount = 12, sizePx = sizePx, highlightMemberId = null, isOpen = false
            ),
            "with a photo" to clusterMarkerBitmap(
                listOf(face("a", "Ana", 210f, photo), face("b", "Beto", 30f), face("c", "Cruz", 120f)),
                totalCount = 4, sizePx = sizePx, highlightMemberId = null, isOpen = false
            ),
            "asked-for member ringed" to clusterMarkerBitmap(
                listOf(face("b", "Beto", 30f), face("a", "Ana", 210f), face("c", "Cruz", 120f)),
                totalCount = 3, sizePx = sizePx, highlightMemberId = "b", isOpen = false
            ),
            "open" to clusterMarkerBitmap(
                listOf(face("a", "Ana", 210f), face("b", "Beto", 30f), face("c", "Cruz", 120f)),
                totalCount = 3, sizePx = sizePx, highlightMemberId = null, isOpen = true
            ),
            "one face stale" to clusterMarkerBitmap(
                listOf(face("a", "Ana", 210f), face("b", "Beto", 30f, stale = true)),
                totalCount = 2, sizePx = sizePx, highlightMemberId = null, isOpen = false
            )
        )

        val sheet = layOut(samples, density)
        val out = File(context.getExternalFilesDir(null), "marker_sheet.png")
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue("sheet was not written to ${out.absolutePath}", out.length() > 0)
    }

    /** Stacks the samples in a column with a caption beside each, on a map-grey ground. */
    private fun layOut(samples: List<Pair<String, Bitmap>>, density: Float): Bitmap {
        val pad = (16 * density).toInt()
        val labelW = (200 * density).toInt()
        val rowH = samples.maxOf { it.second.height } + pad
        val width = labelW + samples.maxOf { it.second.width } + pad * 2
        val height = rowH * samples.size + pad

        val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        // Roughly the grey-green of OSM landmass, so the white bubble is judged against
        // what it will actually sit on rather than against white.
        canvas.drawColor(Color.rgb(233, 229, 220))

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 40)
            textSize = 13 * density
        }

        var y = pad
        for ((caption, bmp) in samples) {
            canvas.drawText(caption, pad.toFloat(), y + rowH / 2f, text)
            canvas.drawBitmap(bmp, labelW.toFloat(), y.toFloat(), null)
            y += rowH
        }
        return sheet
    }

    private fun solidBitmap(size: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return bmp
    }
}
