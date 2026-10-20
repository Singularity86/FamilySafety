package com.example.familysafety.core

import android.content.Context
import java.security.SecureRandom

/**
 * A random ID for this install, signed into presence so the family can tell when one identity
 * is in use on two phones (F15).
 *
 * Per install, not per identity: restoring the recovery phrase on another phone gives that
 * phone a different ID, which is the point. Excluded from backup and device transfer, or a
 * copied phone would share it and look like the same install.
 */
object DeviceIdentity {
    const val PREFS_NAME = "familysafety_device"
    private const val KEY = "device_id"

    @Synchronized
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .toHex()
        prefs.edit().putString(KEY, id).commit()
        return id
    }
}
