package com.example.familysafety.crash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records raw accelerometer motion, with the GPS speed alongside it, to a CSV that
 * [CrashTrace.replay] can run back through [ImpactDecider].
 *
 * This is the calibration tool: drive a rough road, take a speed bump too fast, drop the phone in
 * the footwell, and you come away with traces that say what those events actually look like. The
 * ones that must never alert become test fixtures; the thresholds get tuned against evidence
 * rather than guesswork.
 *
 * Recording deliberately does *not* follow the detection gating. [CrashDetectionMonitor] listens
 * only while enabled and in a vehicle, but the interesting false positives happen exactly when it
 * is not listening, so this registers its own listener and captures whatever it is told to.
 *
 * It is only started from the debug settings UI, so a release build never registers the listener —
 * but it lives in the main source set because [com.example.familysafety.location.LocationService]
 * feeds it speed and cannot see a debug-only class.
 *
 * Sampling at [SensorManager.SENSOR_DELAY_GAME] is roughly 50 Hz, so a recording is expensive:
 * writes happen on a private thread, and a session stops itself at [MAX_SAMPLES].
 */
@Singleton
class CrashTraceRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    data class State(
        val isRecording: Boolean = false,
        val sampleCount: Int = 0,
        val fileName: String? = null,
        /** Set when a start attempt failed, or a session stopped itself. */
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** True on hardware that cannot record at all, so the UI can say so instead of failing. */
    val isSupported: Boolean get() = linearAccelSensor != null

    private var thread: HandlerThread? = null
    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var startedAtNs = 0L
    private var samples = 0

    @Volatile private var lastSpeedMs = 0f
    /** Absolute [SystemClock.elapsedRealtimeNanos] of the last fix; 0 means none has arrived. */
    @Volatile private var lastSpeedAtNs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val elapsed = (SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000_000
            val row = CrashTrace.formatRow(
                elapsedMs = elapsed,
                x = event.values[0],
                y = event.values[1],
                z = event.values[2],
                speedMs = lastSpeedMs,
                speedElapsedMs = lastSpeedAtNs.takeIf { it != 0L }
                    ?.let { (it - startedAtNs) / 1_000_000 },
            )
            synchronized(this@CrashTraceRecorder) {
                val out = writer ?: return
                out.write(row)
                out.newLine()
                samples++
                if (samples % FLUSH_EVERY == 0) {
                    out.flush()
                    // Publishing every sample would recompose the settings screen ~50x a second.
                    _state.value = _state.value.copy(sampleCount = samples)
                }
                if (samples >= MAX_SAMPLES) {
                    Timber.w("CrashTrace: sample cap reached, stopping")
                    stopLocked("Stopped at the $MAX_SAMPLES sample cap.")
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    @Synchronized
    fun start(): File? {
        if (writer != null) return file
        val sensor = linearAccelSensor ?: run {
            _state.value = State(message = "No linear acceleration sensor on this device.")
            return null
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(traceDir(), "trace-$stamp.csv")
        val out = try {
            target.bufferedWriter()
        } catch (e: Exception) {
            Timber.e(e, "CrashTrace: could not open $target")
            _state.value = State(message = "Could not open trace file: ${e.message}")
            return null
        }
        out.write("# familysafety crash trace v${CrashTrace.FORMAT_VERSION}")
        out.newLine()
        out.write("# recorded ${Date()} on ${android.os.Build.MODEL}")
        out.newLine()
        out.write(CrashTrace.HEADER)
        out.newLine()

        writer = out
        file = target
        samples = 0
        startedAtNs = SystemClock.elapsedRealtimeNanos()

        val handlerThread = HandlerThread("crash-trace").also { it.start() }
        thread = handlerThread
        sensorManager.registerListener(
            listener, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(handlerThread.looper)
        )
        _state.value = State(isRecording = true, fileName = target.name)
        Timber.i("CrashTrace: recording to $target")
        return target
    }

    @Synchronized
    fun stop(): File? {
        val finished = file
        stopLocked(null)
        return finished
    }

    /** Must hold this object's monitor. Safe to call when not recording. */
    private fun stopLocked(message: String?) {
        if (writer == null) {
            _state.value = _state.value.copy(message = message)
            return
        }
        sensorManager.unregisterListener(listener)
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            Timber.e(e, "CrashTrace: failed to close trace")
        }
        writer = null
        thread?.quitSafely()
        thread = null
        Timber.i("CrashTrace: wrote $samples samples to ${file?.name}")
        _state.value = State(
            isRecording = false,
            sampleCount = samples,
            fileName = file?.name,
            message = message,
        )
    }

    /** Called from LocationService on every GPS fix, whether or not a recording is running. */
    fun feedSpeed(speedMs: Float) {
        lastSpeedMs = speedMs
        lastSpeedAtNs = SystemClock.elapsedRealtimeNanos()
    }

    /** Traces already on disk, newest first. */
    fun listTraces(): List<File> =
        traceDir().listFiles { f -> f.extension == "csv" }?.sortedDescending() ?: emptyList()

    /**
     * External app-specific storage, so a trace can be pulled off the device with a plain
     * `adb pull` — falling back to internal storage when no external volume is mounted.
     */
    private fun traceDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, TRACE_DIR)
            .also { it.mkdirs() }

    companion object {
        const val TRACE_DIR = "crash-traces"

        /** ~50 Hz, so this is a little over 20 minutes of continuous recording. */
        private const val MAX_SAMPLES = 60_000

        private const val FLUSH_EVERY = 250
    }
}
