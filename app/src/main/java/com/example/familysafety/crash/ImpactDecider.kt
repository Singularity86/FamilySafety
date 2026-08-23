package com.example.familysafety.crash

/**
 * The crash-detection rule itself, with no Android dependencies.
 *
 * [CrashDetectionMonitor] owns the platform plumbing — registering a [android.hardware.Sensor]
 * listener, posting the full-screen notification. This class owns the question that plumbing
 * exists to ask: given this acceleration sample, should we wake the driver? Keeping the two
 * apart means the rule can be exercised in ordinary JVM unit tests, and later replayed against
 * accelerometer traces recorded from real drives.
 *
 * A sample raises an alert only when all of these hold:
 *  - detection is switched on in settings ([setEnabled]) and armed by ActivityRecognition
 *    reporting IN_VEHICLE ([setArmed]);
 *  - the linear-acceleration magnitude reaches the configured sensitivity threshold;
 *  - the last GPS speed was at or above [SPEED_GUARD_MS] and is no older than
 *    [SPEED_MAX_AGE_MS] — a phone dropped on the driveway must not read as a collision, and
 *    neither must a stale speed left over from a drive that has already ended;
 *  - no alert has fired within [ALERT_COOLDOWN_MS].
 *
 * Callers pass `nowMs` rather than letting this class read the clock, so tests control time.
 * Every method is synchronized: samples arrive on the sensor thread while speed arrives from
 * the location callback.
 */
class ImpactDecider(threshold: Float = SENSITIVITY_MEDIUM) {

    /**
     * The outcome of a single acceleration sample. Non-[FIRE] values name the guard that
     * rejected the sample, which is what makes a false negative diagnosable from a log line.
     */
    enum class Decision {
        /** All guards passed — raise the alert. */
        FIRE,

        /** Crash detection is switched off in settings. */
        NOT_ENABLED,

        /** Switched on, but ActivityRecognition does not report us in a vehicle. */
        NOT_ARMED,

        /** The impact was gentler than the configured sensitivity. */
        BELOW_THRESHOLD,

        /** No GPS speed has arrived recently enough to trust. */
        SPEED_TOO_STALE,

        /** The last GPS speed was below the vehicle-speed guard. */
        TOO_SLOW,

        /** An alert fired recently; don't fire a second one for the same event. */
        IN_COOLDOWN,
    }

    private var thresholdMs2: Float = threshold
    private var enabled = false
    private var armed = false

    private var lastSpeedMs = 0f
    private var lastSpeedAtMs: Long? = null
    private var lastAlertAtMs: Long? = null

    /** True when the sensor listener should currently be registered. */
    val shouldListen: Boolean
        @Synchronized get() = enabled && armed

    /** The sensitivity threshold in m/s², one of the `SENSITIVITY_*` constants. */
    val threshold: Float
        @Synchronized get() = thresholdMs2

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    @Synchronized
    fun setArmed(inVehicle: Boolean) {
        armed = inVehicle
    }

    @Synchronized
    fun setThreshold(thresholdMs2: Float) {
        this.thresholdMs2 = thresholdMs2
    }

    /** Records the speed from a GPS fix. [nowMs] is when the fix arrived, for the staleness guard. */
    @Synchronized
    fun feedSpeed(speedMs: Float, nowMs: Long) {
        lastSpeedMs = speedMs
        lastSpeedAtMs = nowMs
    }

    /**
     * Evaluates one linear-acceleration sample. Returns [Decision.FIRE] at most once per
     * [ALERT_COOLDOWN_MS]; the cooldown starts on the sample that fires.
     */
    @Synchronized
    fun onSample(magnitudeMs2: Float, nowMs: Long): Decision {
        if (!enabled) return Decision.NOT_ENABLED
        if (!armed) return Decision.NOT_ARMED
        if (magnitudeMs2 < thresholdMs2) return Decision.BELOW_THRESHOLD

        val speedAt = lastSpeedAtMs ?: return Decision.SPEED_TOO_STALE
        if (nowMs - speedAt > SPEED_MAX_AGE_MS) return Decision.SPEED_TOO_STALE
        if (lastSpeedMs < SPEED_GUARD_MS) return Decision.TOO_SLOW

        val lastAlert = lastAlertAtMs
        if (lastAlert != null && nowMs - lastAlert <= ALERT_COOLDOWN_MS) return Decision.IN_COOLDOWN

        lastAlertAtMs = nowMs
        return Decision.FIRE
    }

    companion object {
        /** Minimum GPS speed in m/s before impact before arming the alert (25 mph = 11.2 m/s). */
        const val SPEED_GUARD_MS = 11.2f

        /**
         * How old the last GPS fix may be and still satisfy the speed guard. Fixes arrive every
         * [com.example.familysafety.location.LocationService.LOCATION_INTERVAL_NORMAL] (30s) while
         * moving, so this tolerates a couple of missed fixes without trusting a speed left over
         * from a drive that ended minutes ago.
         */
        const val SPEED_MAX_AGE_MS = 90_000L

        /** Don't fire again within 10 minutes of a previous alert. */
        const val ALERT_COOLDOWN_MS = 10 * 60 * 1000L

        /** Linear acceleration thresholds in m/s² (gravity already removed by sensor type). */
        const val SENSITIVITY_LOW = 40f     // ~4g — severe crashes only
        const val SENSITIVITY_MEDIUM = 30f  // ~3g — default
        const val SENSITIVITY_HIGH = 20f    // ~2g — catches moderate impacts
    }
}
