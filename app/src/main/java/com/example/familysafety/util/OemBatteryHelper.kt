package com.example.familysafety.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import timber.log.Timber

/**
 * Utility for navigating users to the correct OEM-specific battery settings screen.
 *
 * Component names sourced from the AutoStarter library
 * (https://github.com/judemanutd/AutoStarter) and supplemented with community
 * findings for additional OEMs and newer firmware versions.
 *
 * [openBatterySettings] tries each known ComponentName for the detected OEM in order,
 * falling back to [Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS] when none
 * resolve (e.g. on a stock Android device).
 *
 * [getInstructions] returns a human-readable numbered step list for the detected OEM
 * without needing a Context, since detection relies only on [Build] constants.
 */
object OemBatteryHelper {

    enum class Oem {
        SAMSUNG, XIAOMI, HUAWEI, HONOR, OPPO, ONEPLUS, VIVO, REALME, MEIZU, GENERIC
    }

    fun detectOem(): Oem {
        val mfr = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return when {
            mfr == "samsung"                              -> Oem.SAMSUNG
            mfr == "xiaomi" || brand == "redmi"
                    || brand == "poco"                   -> Oem.XIAOMI
            mfr == "huawei"                              -> Oem.HUAWEI
            brand == "honor"                             -> Oem.HONOR
            mfr == "oppo"                                -> Oem.OPPO
            mfr == "oneplus"                             -> Oem.ONEPLUS
            mfr == "vivo"                                -> Oem.VIVO
            brand == "realme"                            -> Oem.REALME
            mfr == "meizu"                               -> Oem.MEIZU
            else                                         -> Oem.GENERIC
        }
    }

    // ---------------------------------------------------------------------------
    // ComponentName candidates — ordered most-likely-to-exist first per OEM.
    // Multiple entries account for firmware version differences.
    // ---------------------------------------------------------------------------
    private val oemComponents: Map<Oem, List<ComponentName>> = mapOf(

        Oem.SAMSUNG to listOf(
            // OneUI 3+ (Android 11+)
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            ),
            // OneUI 1–2 (Android 9–10)
            ComponentName(
                "com.samsung.android.sm",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            ),
            // China firmware
            ComponentName(
                "com.samsung.android.sm_cn",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
        ),

        Oem.XIAOMI to listOf(
            // MIUI 12+ — Hidden Apps / Power Keeper
            ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
            ),
            // MIUI 10–11 — Security Center autostart
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            // Older MIUI fallback
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.securitycenter.MainActivity"
            )
        ),

        Oem.HUAWEI to listOf(
            // EMUI 9+ — Startup manager
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            // EMUI 5–8 — Protected apps
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            ),
            // EMUI 4
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            )
        ),

        Oem.HONOR to listOf(
            // MagicUI (based on EMUI)
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        ),

        Oem.OPPO to listOf(
            // ColorOS 7+
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            // ColorOS 5–6
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            ),
            // ColorOS power manager
            ComponentName(
                "com.coloros.oppoguardelf",
                "com.coloros.powermanager.powersavemode.PowerSaveModeActivity"
            )
        ),

        Oem.ONEPLUS to listOf(
            // OxygenOS ≤11 (pre-ColorOS merge)
            ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            ),
            // OxygenOS 12+ (merged ColorOS base)
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
        ),

        Oem.VIVO to listOf(
            // FuntouchOS — iManager
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            ),
            // Vivo permission manager
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            // iQOO FuntouchOS fallback
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            )
        ),

        Oem.REALME to listOf(
            // Realme UI (based on ColorOS)
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
        ),

        Oem.MEIZU to listOf(
            // Flyme OS
            ComponentName(
                "com.meizu.safe",
                "com.meizu.safe.permission.SmartPermissionActivity"
            ),
            ComponentName(
                "com.meizu.safe",
                "com.meizu.safe.security.MainActivity"
            )
        )
        // Oem.GENERIC has no OEM entries — falls straight through to the standard intent
    )

    /**
     * Attempts to open the OEM-specific battery/autostart settings screen.
     * Tries each known ComponentName for the detected OEM in order.
     * Falls back to [Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS] if none resolve.
     *
     * @return true if any intent was successfully launched.
     */
    fun openBatterySettings(context: Context): Boolean {
        val oem = detectOem()
        val candidates = oemComponents[oem] ?: emptyList()

        for (component in candidates) {
            try {
                context.startActivity(
                    Intent().apply {
                        this.component = component
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                Timber.i("OemBatteryHelper: launched ${component.flattenToShortString()}")
                return true
            } catch (_: Exception) {
                // Component not present on this firmware — try next
            }
        }

        // Standard fallback: the AOSP battery optimization list
        return try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            Timber.i("OemBatteryHelper: launched standard battery settings (oem=$oem)")
            true
        } catch (e: Exception) {
            Timber.w(e, "OemBatteryHelper: could not open any battery settings screen")
            false
        }
    }

    /**
     * Human-readable step-by-step instructions for the detected OEM.
     * No Context required — detection is based purely on [Build] constants.
     */
    fun getInstructions(): List<String> = when (detectOem()) {

        Oem.SAMSUNG -> listOf(
            "Open Settings → Device Care (or Device Maintenance).",
            "Tap Battery.",
            "Tap Background usage limits.",
            "Tap Never sleeping apps.",
            "Tap the + button and add FamilySafety to the list."
        )

        Oem.XIAOMI -> listOf(
            "Open Settings → Apps → Manage apps.",
            "Find and tap FamilySafety.",
            "Tap Battery saver and choose No restrictions.",
            "Go back to FamilySafety → Other permissions.",
            "Enable Autostart so MIUI does not block background launch."
        )

        Oem.HUAWEI -> listOf(
            "Open Settings → Apps → FamilySafety.",
            "Tap Battery.",
            "Set App launch to Manage manually.",
            "Enable Auto-launch, Secondary launch, and Run in background.",
            "Also open Phone Manager → Startup manager and enable FamilySafety."
        )

        Oem.HONOR -> listOf(
            "Open Settings → Apps → FamilySafety.",
            "Tap Battery.",
            "Set App launch to Manage manually.",
            "Enable Auto-launch and Run in background.",
            "Open Phone Manager → Startup manager and enable FamilySafety."
        )

        Oem.OPPO -> listOf(
            "Open Settings → Battery → Battery optimization.",
            "Find FamilySafety and choose Don't optimize.",
            "Open Security Center → Privacy Permissions.",
            "Tap Startup manager and enable FamilySafety.",
            "Also enable Background app refresh for FamilySafety."
        )

        Oem.ONEPLUS -> listOf(
            "Open Settings → Battery → Battery optimization.",
            "Find FamilySafety and choose Don't optimize.",
            "Open Settings → Apps → FamilySafety → Battery.",
            "Enable Allow background activity.",
            "If present, open Security → App freeze and unfreeze FamilySafety."
        )

        Oem.VIVO -> listOf(
            "Open iManager (or Settings → More → Permission manager).",
            "Tap Autostart manager and enable FamilySafety.",
            "Go back and tap Background app refresh.",
            "Enable FamilySafety.",
            "Open Settings → Battery → High background power consumption.",
            "Add FamilySafety to the allow list."
        )

        Oem.REALME -> listOf(
            "Open Settings → Battery → Battery optimization.",
            "Find FamilySafety and choose Don't optimize.",
            "Open Security Center → App management → Startup manager.",
            "Enable FamilySafety.",
            "Also enable Background app refresh for FamilySafety."
        )

        Oem.MEIZU -> listOf(
            "Open Settings → Battery → Battery saver.",
            "Tap Standby power consumption and disable restrictions for FamilySafety.",
            "Open Settings → Apps → FamilySafety → Permissions.",
            "Enable Autostart.",
            "Open Flyme Security → Permission manager → Background manage.",
            "Enable FamilySafety."
        )

        Oem.GENERIC -> listOf(
            "Open Settings → Battery.",
            "Look for Battery optimization or Unrestricted access.",
            "Find FamilySafety in the list and set it to Don't optimize.",
            "If your phone has an Autostart or Background app permission, enable it for FamilySafety."
        )
    }
}
