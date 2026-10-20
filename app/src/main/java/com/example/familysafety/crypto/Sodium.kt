package com.example.familysafety.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * The app's one libsodium binding. Each class used to construct its own, loading the native
 * library at construction time; loading it on first use also lets classes that hold it be
 * built in JVM tests, as long as the test never calls into libsodium.
 */
object Sodium {
    val instance: LazySodiumAndroid by lazy { LazySodiumAndroid(SodiumAndroid()) }
}
