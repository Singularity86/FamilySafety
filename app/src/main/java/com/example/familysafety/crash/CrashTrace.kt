package com.example.familysafety.crash

import kotlin.math.sqrt

/**
 * The recorded-trace format, and the replay that runs a trace back through [ImpactDecider].
 *
 * Unit tests can prove the rule is self-consistent, but they cannot answer the question that
 * actually decides whether crash detection is any good: is 30 m/s² a collision, or a pothole at
 * 60 mph, or a phone sliding off the mount? Only recorded motion answers that.
 * [CrashTraceRecorder] captures traces from real drives; this replays them against the rule, so
 * a trace that must not fire (a rough road, a dropped handset) can be kept as a fixture and
 * re-checked on every build.
 *
 * Nothing here touches Android, so replay runs in the ordinary JVM test suite.
 *
 * Traces record motion, not arming: whether ActivityRecognition thought you were in a vehicle is
 * a separate gate that no accelerometer reading can reconstruct. [replay] therefore runs with
 * detection enabled and armed, and asks only "given this motion and speed, would the rule fire?"
 */
object CrashTrace {

    const val FORMAT_VERSION = 1

    const val HEADER = "elapsed_ms,x,y,z,magnitude_ms2,speed_ms,speed_elapsed_ms"

    /**
     * A row whose speed has no GPS fix behind it writes an empty last column. An in-band numeric
     * sentinel would collide with a real value: a fix that predates the recording legitimately
     * has a negative elapsed time, and replaying it is correct.
     */
    private const val NO_SPEED = ""

    /** One accelerometer sample, with whatever the speed guard knew at the time. */
    data class Sample(
        /** Milliseconds since the recording started. */
        val elapsedMs: Long,
        val magnitudeMs2: Float,
        val speedMs: Float,
        /** When [speedMs] was last refreshed, or null if no fix had arrived yet. */
        val speedElapsedMs: Long?,
    )

    /** A point in a trace where the rule would have raised an alert. */
    data class Hit(
        val elapsedMs: Long,
        val magnitudeMs2: Float,
        val speedMs: Float,
    )

    fun formatRow(
        elapsedMs: Long,
        x: Float,
        y: Float,
        z: Float,
        speedMs: Float,
        speedElapsedMs: Long?,
    ): String {
        val magnitude = sqrt(x * x + y * y + z * z)
        return "$elapsedMs,$x,$y,$z,$magnitude,$speedMs,${speedElapsedMs ?: NO_SPEED}"
    }

    /**
     * Parses a recorded trace. Blank lines, `#` comments and the header are skipped; a row that
     * is neither of those and does not parse throws, because a fixture that silently loses rows
     * is worse than one that fails loudly.
     */
    fun parse(lines: Sequence<String>): List<Sample> {
        val samples = mutableListOf<Sample>()
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line == HEADER) return@forEachIndexed
            val parts = line.split(",")
            require(parts.size == 7) {
                "trace line ${index + 1}: expected 7 columns, got ${parts.size} — \"$line\""
            }
            val speedField = parts[6].trim()
            val speedElapsed = if (speedField.isEmpty()) {
                null
            } else {
                speedField.toLongOrNull()
                    ?: throw IllegalArgumentException("trace line ${index + 1}: bad speed_elapsed_ms")
            }
            samples += Sample(
                elapsedMs = parts[0].trim().toLongOrNull()
                    ?: throw IllegalArgumentException("trace line ${index + 1}: bad elapsed_ms"),
                magnitudeMs2 = parts[4].trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("trace line ${index + 1}: bad magnitude_ms2"),
                speedMs = parts[5].trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("trace line ${index + 1}: bad speed_ms"),
                speedElapsedMs = speedElapsed,
            )
        }
        return samples
    }

    /**
     * Runs [samples] through a fresh [ImpactDecider] at [threshold] and returns every point at
     * which it would have alerted. An empty list means the trace is quiet at that sensitivity —
     * which for a false-positive fixture is the passing result.
     */
    fun replay(
        samples: List<Sample>,
        threshold: Float = ImpactDecider.SENSITIVITY_MEDIUM,
    ): List<Hit> {
        val decider = ImpactDecider(threshold).apply {
            setEnabled(true)
            setArmed(true)
        }
        val hits = mutableListOf<Hit>()
        var lastFedSpeedAt: Long? = null
        for (sample in samples) {
            val speedAt = sample.speedElapsedMs
            if (speedAt != null && speedAt != lastFedSpeedAt) {
                decider.feedSpeed(sample.speedMs, speedAt)
                lastFedSpeedAt = speedAt
            }
            if (decider.onSample(sample.magnitudeMs2, sample.elapsedMs) == ImpactDecider.Decision.FIRE) {
                hits += Hit(sample.elapsedMs, sample.magnitudeMs2, sample.speedMs)
            }
        }
        return hits
    }
}
