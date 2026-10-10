package com.miku.riot

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/** Small reads and writes against the rest of the OS. No state lives here. */
object RiotSystem {
    private const val TAG = "RiotSystem"

    const val UNLOCK_ID = "secret.os.riot"
    const val LAUNCHER_PKG = "com.miku.launcher"
    const val FM_PKG = "com.caf.fmradio"
    const val FM_DEBUG = "com.caf.fmradio.action.DEBUG"

    // ---- unlock -------------------------------------------------------------------------------

    /**
     * Earned when `secret.os.riot` is a key in Settings.Global `miku_unlocks`. The BPM game in the
     * launcher is the only writer. Presence alone counts, the same rule pickers use: once earned it
     * stays earned, whatever the Secrets list switch says. Anything unreadable counts as locked.
     */
    fun isUnlocked(ctx: Context): Boolean = runCatching {
        val raw = Settings.Global.getString(ctx.contentResolver, "miku_unlocks")
        !raw.isNullOrBlank() && JSONObject(raw).has(UNLOCK_ID)
    }.getOrDefault(false)

    // ---- battery and "disk" -------------------------------------------------------------------

    data class Battery(val pct: Int, val charging: Boolean)

    fun battery(ctx: Context): Battery {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(pct, charging)
    }

    /** Shared storage, standing in for the Riot's hard drive: (used bytes, total bytes). */
    fun disk(): Pair<Long, Long> = runCatching {
        val s = StatFs(Environment.getExternalStorageDirectory().path)
        val total = s.blockCountLong * s.blockSizeLong
        val free = s.availableBlocksLong * s.blockSizeLong
        (total - free) to total
    }.getOrDefault(0L to 0L)

    fun global(ctx: Context, key: String): String? =
        runCatching { Settings.Global.getString(ctx.contentResolver, key) }.getOrNull()

    // ---- FM ------------------------------------------------------------------------------------

    /**
     * The FM app's debug receiver (QualcommFmHardwareEngine.registerDebugReceiver) takes cmd=tune
     * freq=kHz, cmd=power on=1|0, cmd=seek_up, cmd=seek_down. It is registered at runtime by the
     * engine, so it only answers while the FM app's process is alive; [fmStatus] tells us whether
     * anything happened.
     */
    fun fm(ctx: Context, cmd: String, extras: Map<String, Int> = emptyMap()) {
        val i = Intent(FM_DEBUG).setPackage(FM_PKG).putExtra("cmd", cmd)
        for ((k, v) in extras) i.putExtra(k, v)
        runCatching { ctx.sendBroadcast(i) }.onFailure { Log.w(TAG, "fm $cmd: $it") }
    }

    /** "off", "101.9 WVAQ", or null when the FM app has never published anything. */
    fun fmStatus(ctx: Context): String? = global(ctx, "miku_fm_status")?.trim()?.takeIf { it.isNotEmpty() }

    fun openFmApp(ctx: Context): Boolean = launch(ctx, FM_PKG)

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    // ---- home ----------------------------------------------------------------------------------

    fun isDefaultHome(ctx: Context): Boolean {
        val r = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == ctx.packageName
    }

    /**
     * Leave Riot mode for good: hand HOME back to the Miku launcher, then open it.
     *
     * Three layers, because which one works depends on the build. The role call is a system API,
     * reached by reflection, and needs MANAGE_ROLE_HOLDERS (a platform signature permission this
     * APK asks for). Clearing our own preferred activities needs no permission at all and covers
     * a pre-role "Always" choice. If HOME still resolves to us after both, the Home app picker
     * opens so the user can pick, rather than landing back here.
     */
    fun exitToMiku(activity: Activity) {
        val ctx = activity.applicationContext
        if (isDefaultHome(ctx)) {
            if (Build.VERSION.SDK_INT >= 29) giveHomeRoleTo(ctx, LAUNCHER_PKG)
            runCatching { ctx.packageManager.clearPackagePreferredActivities(ctx.packageName) }
                .onFailure { Log.w(TAG, "clearPackagePreferredActivities: $it") }
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setPackage(LAUNCHER_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { activity.startActivity(home); true }.getOrDefault(false) ||
            launch(ctx, LAUNCHER_PKG)
        if (!started || isDefaultHome(ctx)) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        activity.finishAndRemoveTask()
    }

    private fun giveHomeRoleTo(ctx: Context, pkg: String) {
        runCatching {
            val rm = ctx.getSystemService(RoleManager::class.java) ?: return
            val m = RoleManager::class.java.getMethod(
                "addRoleHolderAsUser", String::class.java, String::class.java, Int::class.javaPrimitiveType,
                android.os.UserHandle::class.java, java.util.concurrent.Executor::class.java,
                java.util.function.Consumer::class.java)
            m.invoke(rm, RoleManager.ROLE_HOME, pkg, 0, Process.myUserHandle(), ctx.mainExecutor,
                java.util.function.Consumer<Boolean> { ok -> Log.i(TAG, "home role to $pkg: $ok") })
        }.onFailure { Log.w(TAG, "home role handover not available: $it") }
    }

    @Suppress("unused")
    fun component(ctx: Context) = ComponentName(ctx, RiotActivity::class.java)
}
