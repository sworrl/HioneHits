package com.miku.player.profiles

import android.os.IBinder
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Sets the three vendor properties that actually move the M500's DAC, through HiBy's AudioService.
 *
 * WHY THIS ROUTE. /vendor/etc/init/hw/init.hiby.audio.rc writes the sa_sound_setting sysfs nodes
 * when, and only when, these properties change:
 *     vendor.audio.hiby.high_power      hpower_enable | hpower_disable
 *     vendor.audio.hiby.dre_mode        dremode_enable | dremode_disable
 *     vendor.audio.hiby.digital_filter  fast_rolloff_low_latency | ... (the kernel's own strings)
 * Miku apps run as platform_app, which SELinux lets set almost no properties (not
 * vendor_audio_prop). The writes the app already made — Settings.Global rows and
 * AudioManager.setParameters("vendor.audio.hiby.hw.*") — land on rows and properties that
 * nothing reads live (the audio HAL has no hiby keys; AudioService only re-reads high_power and
 * dre_mode at boot). HiBy added IAudioService.setProperties(key, value) to the framework, which
 * calls SystemProperties.set inside system_server with no permission check; system_server is
 * allowed vendor_audio_prop (HiBy's own boot code sets vendor.audio.hiby.high_power from there).
 * IAudioService is a hidden API; platform-signed apps are exempt from the hidden-API block.
 *
 * STATUS: found by reading the firmware, NOT yet confirmed on hardware. [set] reads the property
 * back through IAudioService.getProperties, so the UI can say "property set" truthfully; whether
 * init then wrote the node (and whether the DAC changed audibly) still needs an on-device check:
 *     adb shell getprop vendor.audio.hiby.digital_filter
 *     adb shell cat /sys/devices/platform/sa_sound_setting/digital_filter
 */
object HibyDacBridge {
    private const val TAG = "HibyDacBridge"

    const val PROP_HIGH_POWER = "vendor.audio.hiby.high_power"
    const val PROP_DRE = "vendor.audio.hiby.dre_mode"
    const val PROP_FILTER = "vendor.audio.hiby.digital_filter"

    enum class Result { CONFIRMED, NOT_CONFIRMED, UNAVAILABLE }

    /** Last outcome per property, for the profile screen's honesty line. */
    val lastResults: MutableMap<String, Result> = ConcurrentHashMap()

    private val service: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "audio") as IBinder
            Class.forName("android.media.IAudioService\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.onFailure { Log.w(TAG, "IAudioService unavailable", it) }.getOrNull()
    }

    private val setMethod by lazy {
        runCatching { service?.javaClass?.getMethod("setProperties", String::class.java, String::class.java) }
            .onFailure { Log.w(TAG, "setProperties not on this framework (not a HiBy ROM?)", it) }.getOrNull()
    }

    private val getMethod by lazy {
        runCatching { service?.javaClass?.getMethod("getProperties", String::class.java, String::class.java) }
            .getOrNull()
    }

    val available: Boolean get() = setMethod != null

    /** Current value as system_server sees it, or null when the bridge is not there. */
    fun get(key: String): String? = runCatching {
        getMethod?.invoke(service, key, "") as String?
    }.getOrNull()

    /** Set [key] to [value] and read it back. Blocking binder calls: call off the main thread. */
    fun set(key: String, value: String): Result {
        val m = setMethod
        val r = if (m == null) {
            Result.UNAVAILABLE
        } else {
            runCatching { m.invoke(service, key, value) }
                .onFailure { Log.w(TAG, "setProperties($key) failed", it) }
            if (get(key) == value) Result.CONFIRMED else Result.NOT_CONFIRMED
        }
        lastResults[key] = r
        Log.i(TAG, "$key=$value -> $r")
        return r
    }
}
