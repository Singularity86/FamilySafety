package com.example.familysafety.ui.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance

/**
 * The colour each person is drawn in: avatar, map pin, history trail, name in chat.
 *
 * Twelve colours at one perceived brightness (OKLCH lightness 0.74), spread as far apart as
 * the colour wheel allows. Before this, a colour was computed from a hue with fixed HSL
 * numbers, five different ways across the app, and HSL lightness is not what the eye sees:
 * yellows and greens came out far brighter than blues, so the white initial washed out on
 * half the presets (2.3:1 at worst).
 *
 * Every colour carries a dark initial at ≥ 7.2:1. [textOnLight] is a darker shade of the same
 * hue for drawing the colour as text on the light theme (≥ 5.1:1 on the light ground); on
 * the dark theme [fill] works as text directly.
 *
 * Wire format is unchanged: a member still stores a hue (`FamilyMember.colorHue`), on the
 * HSL wheel older builds draw with. [PersonPalette.forHue] converts that to the perceptual
 * wheel the palette is built on before snapping to the nearest colour (the two wheels do not
 * line up: HSL red is 0°, but about 29° on the perceptual one). Choosing a palette colour
 * stores its [storedHue], so peers on older builds still draw a close match.
 */
data class PersonColor(
    val name: String,
    /** Hue on the perceptual (OKLCH) wheel the palette was chosen on. */
    val hue: Float,
    /** The HSL hue written to `FamilyMember.colorHue` when someone picks this colour. */
    val storedHue: Float,
    val fill: Color,
    val textOnLight: Color,
    val pattern: PersonPattern
) {
    /** The colour to use for this person's name or other text on the current theme. */
    fun textColor(darkTheme: Boolean): Color = if (darkTheme) fill else textOnLight
}

/**
 * This person's colour for text (names) on the theme currently showing. Read from the
 * resolved colour scheme rather than the system setting, since the app's own theme choice
 * can differ from the phone's.
 */
@Composable
fun PersonColor.themedText(): Color =
    textColor(darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f)

/** Initials and other marks drawn on top of a person's [PersonColor.fill]. */
val OnPersonColor = Color(0xFF0F1A14)

object PersonPalette {

    val colors: List<PersonColor> = listOf(
        PersonColor("Red",        20f, 359f,  Color(0xFFFB8083), Color(0xFFA8353E), PersonPattern.HorizontalLines),
        PersonColor("Tangerine",  46f,  20f,  Color(0xFFF68953), Color(0xFFA14200), PersonPattern.VerticalLines),
        PersonColor("Marigold",   72f,  37f,  Color(0xFFE49921), Color(0xFF885700), PersonPattern.DiagonalUp),
        PersonColor("Olive",      98f,  52f,  Color(0xFFC4AB11), Color(0xFF736300), PersonPattern.DiagonalDown),
        PersonColor("Lime",       126f,  81f, Color(0xFF93BB48), Color(0xFF516F00), PersonPattern.Grid),
        PersonColor("Jade",       152f, 140f, Color(0xFF54C57A), Color(0xFF00773B), PersonPattern.Crosshatch),
        PersonColor("Teal",       178f, 172f, Color(0xFF00C6AB), Color(0xFF007463), PersonPattern.Dots),
        PersonColor("Lagoon",     206f, 185f, Color(0xFF00C1D2), Color(0xFF00717B), PersonPattern.Checker),
        PersonColor("Sky",        232f, 196f, Color(0xFF08BAF8), Color(0xFF006C93), PersonPattern.Zigzag),
        PersonColor("Periwinkle", 268f, 224f, Color(0xFF87A7FF), Color(0xFF3F5BB8), PersonPattern.Bricks),
        PersonColor("Orchid",     306f, 270f, Color(0xFFC290F5), Color(0xFF7947A5), PersonPattern.Dashes),
        PersonColor("Rose",       342f, 321f, Color(0xFFEA82C5), Color(0xFF99387B), PersonPattern.Rings)
    )

    /**
     * The palette colour for a stored hue: the colour older builds draw for it (HSL with the
     * saturation and lightness they used), converted to the perceptual wheel, then the
     * nearest palette hue around that wheel.
     */
    fun forHue(storedHue: Float): PersonColor {
        val h = perceptualHueOfLegacy(storedHue)
        return colors.minBy { c ->
            val d = kotlin.math.abs(c.hue - h)
            minOf(d, 360f - d)
        }
    }

    /**
     * The colour for a member: their chosen hue, or, for someone who never chose, one of the
     * twelve picked straight from their ID. Picking by index keeps automatic colours evenly
     * spread; going through a hue would not, since the old wheel bunches into a few greens
     * and purples once mapped onto the perceptual one.
     */
    fun forMember(memberId: String, colorHue: Float?): PersonColor =
        if (colorHue != null) forHue(colorHue)
        else colors[((memberId.hashCode().toLong() and 0xFFFFFFFFL) % colors.size).toInt()]

    /** The hue to store for a member who has not chosen one, matching [forMember]. */
    fun hueFromId(memberId: String): Float = forMember(memberId, null).storedHue

    /** Older builds' colour for [storedHue] — HSL(h, 0.55, 0.45) — as an OKLCH hue in degrees. */
    internal fun perceptualHueOfLegacy(storedHue: Float): Float {
        val h = (((storedHue % 360f) + 360f) % 360f) / 360f
        val l = 0.45f
        val s = 0.55f
        val q = l + s - l * s
        val p = 2 * l - q
        fun channel(t0: Float): Float {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        fun linear(c: Float): Double =
            if (c <= 0.04045f) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val r = linear(channel(h + 1f / 3f))
        val g = linear(channel(h))
        val b = linear(channel(h - 1f / 3f))
        val lm = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val mm = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val sm = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val a = 1.9779984951 * lm - 2.4285922050 * mm + 0.4505937099 * sm
        val bb = 0.0259040371 * lm + 0.7827717662 * mm - 0.8086757660 * sm
        return ((Math.toDegrees(Math.atan2(bb, a)) + 360.0) % 360.0).toFloat()
    }
}

/**
 * One pattern per palette colour, for the colour-blind setting. No set of twelve colours
 * stays distinct for everyone with red-green colour blindness; a pattern does.
 */
enum class PersonPattern {
    HorizontalLines, VerticalLines, DiagonalUp, DiagonalDown, Grid, Crosshatch,
    Dots, Checker, Zigzag, Bricks, Dashes, Rings;

    /**
     * Tile this pattern over [bounds] in dark lines. The caller sets the clip (an avatar's
     * ring, a pin's body); [cell] is the tile size in px. Shared by Compose avatars and the
     * map's bitmap pins so both draw the same thing.
     */
    fun draw(canvas: Canvas, bounds: RectF, cell: Float) {
        val u = cell / 8f // the tile is designed on an 8 × 8 grid
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PatternInk
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * u
            strokeCap = Paint.Cap.BUTT
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PatternInk
            style = Paint.Style.FILL
        }
        var y = bounds.top - cell
        while (y < bounds.bottom + cell) {
            var x = bounds.left - cell
            while (x < bounds.right + cell) {
                fun X(v: Float) = x + v * u
                fun Y(v: Float) = y + v * u
                when (this) {
                    HorizontalLines -> {
                        canvas.drawLine(X(0f), Y(2f), X(8f), Y(2f), stroke)
                        canvas.drawLine(X(0f), Y(6f), X(8f), Y(6f), stroke)
                    }
                    VerticalLines -> {
                        canvas.drawLine(X(2f), Y(0f), X(2f), Y(8f), stroke)
                        canvas.drawLine(X(6f), Y(0f), X(6f), Y(8f), stroke)
                    }
                    DiagonalUp -> {
                        canvas.drawLine(X(0f), Y(8f), X(8f), Y(0f), stroke)
                        canvas.drawLine(X(-4f), Y(4f), X(4f), Y(-4f), stroke)
                        canvas.drawLine(X(4f), Y(12f), X(12f), Y(4f), stroke)
                    }
                    DiagonalDown -> {
                        canvas.drawLine(X(0f), Y(0f), X(8f), Y(8f), stroke)
                        canvas.drawLine(X(-4f), Y(4f), X(4f), Y(12f), stroke)
                        canvas.drawLine(X(4f), Y(-4f), X(12f), Y(4f), stroke)
                    }
                    Grid -> {
                        canvas.drawLine(X(0f), Y(4f), X(8f), Y(4f), stroke)
                        canvas.drawLine(X(4f), Y(0f), X(4f), Y(8f), stroke)
                    }
                    Crosshatch -> {
                        canvas.drawLine(X(0f), Y(0f), X(8f), Y(8f), stroke)
                        canvas.drawLine(X(8f), Y(0f), X(0f), Y(8f), stroke)
                    }
                    Dots -> {
                        canvas.drawCircle(X(2f), Y(2f), 1.4f * u, fill)
                        canvas.drawCircle(X(6f), Y(6f), 1.4f * u, fill)
                    }
                    Checker -> {
                        canvas.drawRect(X(0f), Y(0f), X(4f), Y(4f), fill)
                        canvas.drawRect(X(4f), Y(4f), X(8f), Y(8f), fill)
                    }
                    Zigzag -> {
                        val p = Path().apply {
                            moveTo(X(0f), Y(5f)); lineTo(X(2f), Y(2f)); lineTo(X(4f), Y(5f))
                            lineTo(X(6f), Y(2f)); lineTo(X(8f), Y(5f))
                        }
                        canvas.drawPath(p, stroke)
                    }
                    Bricks -> {
                        canvas.drawLine(X(0f), Y(0.7f), X(8f), Y(0.7f), stroke)
                        canvas.drawLine(X(0f), Y(4.7f), X(8f), Y(4.7f), stroke)
                        canvas.drawLine(X(2f), Y(0.7f), X(2f), Y(4.7f), stroke)
                        canvas.drawLine(X(6f), Y(4.7f), X(6f), Y(8.7f), stroke)
                    }
                    Dashes -> {
                        canvas.drawLine(X(0f), Y(2f), X(3f), Y(2f), stroke)
                        canvas.drawLine(X(5f), Y(2f), X(8f), Y(2f), stroke)
                        canvas.drawLine(X(1f), Y(6f), X(6f), Y(6f), stroke)
                    }
                    Rings -> canvas.drawCircle(X(4f), Y(4f), 2.2f * u, stroke)
                }
                x += cell
            }
            y += cell
        }
    }

    private companion object {
        /** [OnPersonColor] at 72 % — dark enough to read on every fill, soft enough to sit behind. */
        val PatternInk: Int = OnPersonColor.copy(alpha = 0.72f).toArgb()
    }
}

/**
 * Whether people are drawn with their pattern as well as their colour.
 *
 * Kept on this phone only and never synced: it is the need of whoever is looking at the
 * screen, not of the people shown on it. Held as Compose state so flipping it in Settings
 * redraws every avatar and pin at once, map included, without threading it through screens.
 */
object PersonPatternsPreference {
    private const val PREFS = "theme_prefs"
    private const val KEY = "person_patterns"
    private var state: MutableState<Boolean>? = null

    private fun state(context: Context): MutableState<Boolean> =
        state ?: mutableStateOf(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY, false)
        ).also { state = it }

    fun set(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
        state(context).value = enabled
    }

    @Composable
    fun enabled(): Boolean = state(LocalContext.current).value
}
