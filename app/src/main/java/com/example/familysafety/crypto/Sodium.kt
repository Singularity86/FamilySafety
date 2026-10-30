package com.example.familysafety.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * The app's one libsodium binding. Never construct `SodiumAndroid()` anywhere else.
 *
 * Each `SodiumAndroid()` makes JNA register every libsodium function again, and each
 * registration holds JNI global references that are never released. Up to 1.15.5 several
 * classes built their own, among them AndroidKeyStoreLocalKeyStore, which StorageHealth builds
 * on every LocationService start (app open, heartbeat, watchdog). After about a day the global
 * reference table (102 400) overflowed and the app aborted, usually as it was being opened.
 *
 * Loading on first use also lets classes that hold it be built in JVM tests, as long as the
 * test never calls into libsodium.
 */
object Sodium {
    val instance: LazySodiumAndroid by lazy { LazySodiumAndroid(SodiumAndroid()) }
}
