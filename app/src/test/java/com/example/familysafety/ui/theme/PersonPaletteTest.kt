package com.example.familysafety.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonPaletteTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `twelve distinct colours, each with its own pattern`() {
        val colors = PersonPalette.colors
        assertEquals(12, colors.size)
        assertEquals(12, colors.map { it.fill }.toSet().size)
        assertEquals(PersonPattern.entries.toSet(), colors.map { it.pattern }.toSet())
    }

    @Test
    fun `choosing a colour stores a hue that comes back as the same colour`() {
        PersonPalette.colors.forEach { c ->
            assertEquals(c.name, c, PersonPalette.forHue(c.storedHue))
        }
    }

    @Test
    fun `stored hues are read on the old wheel, not compared to the new one directly`() {
        // HSL red (0°) is about 29° on the perceptual wheel: Red, not Rose.
        assertEquals("Red", PersonPalette.forHue(0f).name)
        val legacyPresets = mapOf(
            0f to "Red", 30f to "Marigold", 60f to "Olive", 90f to "Lime", 140f to "Jade",
            180f to "Lagoon", 210f to "Periwinkle", 240f to "Periwinkle", 270f to "Orchid",
            300f to "Rose", 330f to "Rose"
        )
        legacyPresets.forEach { (hue, name) ->
            assertEquals("preset $hue°", name, PersonPalette.forHue(hue).name)
        }
    }

    @Test
    fun `hues outside 0 to 360 wrap`() {
        assertEquals(PersonPalette.forHue(10f), PersonPalette.forHue(370f))
        assertEquals(PersonPalette.forHue(350f), PersonPalette.forHue(-10f))
    }

    @Test
    fun `automatic colours are stable and spread evenly across the twelve`() {
        val id = "52318daf60269d8fb37528f8db191d84"
        assertEquals(PersonPalette.forMember(id, null), PersonPalette.forMember(id, null))
        assertEquals(PersonPalette.forMember(id, null), PersonPalette.forHue(PersonPalette.hueFromId(id)))

        // Member IDs are 32 random hex characters; sample IDs of that shape.
        val random = java.util.Random(42)
        val counts = (0 until 1200)
            .map { (1..32).joinToString("") { Integer.toHexString(random.nextInt(16)) } }
            .map { PersonPalette.forMember(it, null) }
            .groupingBy { it }.eachCount()
        assertEquals(12, counts.size)
        counts.values.forEach { assertTrue("uneven spread: $counts", it in 50..150) }
    }

    @Test
    fun `initials, names and trails are readable`() {
        PersonPalette.colors.forEach { c ->
            assertTrue("${c.name} initials", contrast(OnPersonColor, c.fill) >= 7.0)
            // Names in chat: the fill on the dark theme, the darker shade on the light one.
            assertTrue("${c.name} name on dark", contrast(c.fill, Surface1) >= 4.5)
            assertTrue("${c.name} name on light", contrast(c.textOnLight, SurfaceLight0) >= 4.5)
            assertTrue("${c.name} trail on white tiles", contrast(c.textOnLight, Color.White) >= 4.5)
        }
    }
}
