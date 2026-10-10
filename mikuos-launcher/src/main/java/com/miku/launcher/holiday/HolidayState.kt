package com.miku.launcher.holiday

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.miku.launcher.Mascot
import com.miku.launcher.MascotPack
import org.json.JSONObject
import java.io.InputStream
import java.time.LocalDate
import java.util.Locale

/**
 * The launcher's holiday and season art, and the glue to Android: the calendar from assets,
 * the user's scope from Settings.Global, the latitude from the weather fix, the active pack's
 * art slots, and the bitmaps the home screen and lockscreen draw.
 *
 * Where it shows:
 *  - Home wallpaper: holiday art in a holiday window, otherwise the season's art, but only while
 *    the user's wallpaper choice is the default one ("classic" or the Default theme) and no
 *    gallery picture is set. A wallpaper the user picked on purpose always stays.
 *  - Lockscreen: holiday art in a holiday window only. Outside one it keeps its own portrait
 *    (which the pack already swaps).
 *  - Settings.Global miku_holiday_art = 0 turns all of it off.
 */
object HolidayState {
    private const val TAG = "MikuHolidays"
    const val CALENDAR_ASSET = "holidays/calendar.json"
    /** Wallpaper ids that mean "the default": automatic art may replace them. */
    val DEFAULT_WALLPAPER_IDS = setOf("classic", "theme_default")

    /** What the engine picked for today. Null before the first refresh. */
    val active = mutableStateOf<ActiveArt?>(null)
    /** Home wallpaper override, null = draw the normal wallpaper. */
    val homeBitmap = mutableStateOf<ImageBitmap?>(null)
    /** Lockscreen backdrop override (holiday windows only), null = normal backdrop. */
    val lockBitmap = mutableStateOf<ImageBitmap?>(null)

    @Volatile private var started = false
    @Volatile private var calendar: List<Holiday>? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val packArtCache = HashMap<String, Map<String, String?>>()

    /** Called after every refresh, on the main thread (for View code such as the live wallpaper). */
    fun addListener(l: () -> Unit) { listeners += l }

    /** Idempotent. Observes the holiday settings and the mascot pack, refreshes, arms the midnight alarm. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        Mascot.init(app)
        if (started) return
        started = true
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { refresh(app) }
        }
        for (k in listOf(Mascot.HOLIDAY_SCOPE, Mascot.HOLIDAY_CUSTOM, Mascot.HOLIDAY_ART, Mascot.HOLIDAY_REMINDERS)) {
            runCatching { app.contentResolver.registerContentObserver(Settings.Global.getUriFor(k), false, obs) }
        }
        Mascot.addListener { refresh(app) }
        refresh(app)
        HolidayScheduler.scheduleNextMidnight(app)
    }

    /** Recompute on a background thread and publish the bitmaps. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            val (art, home, lock) = runCatching { compute(app) }
                .onFailure { Log.w(TAG, "refresh failed: $it") }
                .getOrElse { Triple(null, null, null) }
            main.post {
                active.value = art
                homeBitmap.value = home?.asImageBitmap()
                lockBitmap.value = lock?.asImageBitmap()
                for (l in listeners) runCatching { l() }
            }
        }.apply { isDaemon = true; name = "MikuHolidays" }.start()
    }

    /** Blocking: the home wallpaper bitmap for now, or null for the normal one. For the live wallpaper. */
    fun homeBitmapNow(ctx: Context): Bitmap? = runCatching { compute(ctx.applicationContext).second }.getOrNull()

    private fun compute(ctx: Context): Triple<ActiveArt?, Bitmap?, Bitmap?> {
        if (!artEnabled(ctx)) return Triple(null, null, null)
        val pack = Mascot.pack
        val art = HolidayEngine.activeArt(
            LocalDate.now(), calendar(ctx), scope(ctx), latitude(ctx), pack.id, seasonArt(ctx, pack),
        )
        val maxDim = ctx.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }.coerceAtLeast(1280)
        val home = art.art?.let { loadArt(ctx, it, pack.id, maxDim) }
        val lock = if (art.kind == "holiday") home else null
        return Triple(art, home, lock)
    }

    // ── Inputs ──────────────────────────────────────────────────────────────────────────────

    fun calendar(ctx: Context): List<Holiday> {
        calendar?.let { return it }
        val list = runCatching {
            ctx.assets.open(CALENDAR_ASSET).use { HolidayCalendar.parse(it.readBytes().toString(Charsets.UTF_8)) }
        }.onFailure { Log.i(TAG, "no holiday calendar in assets ($it), seasons only") }.getOrDefault(emptyList())
        calendar = list
        return list
    }

    fun artEnabled(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Mascot.HOLIDAY_ART, 1) }.getOrDefault(1) != 0

    fun remindersEnabled(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, Mascot.HOLIDAY_REMINDERS, 1) }.getOrDefault(1) != 0

    fun scope(ctx: Context): HolidayScope {
        val cr = ctx.contentResolver
        val mode = runCatching { Settings.Global.getString(cr, Mascot.HOLIDAY_SCOPE) }.getOrNull()
            ?.trim()?.lowercase()?.ifEmpty { null } ?: "local"
        val custom = HolidayScope.parseCustom(runCatching { Settings.Global.getString(cr, Mascot.HOLIDAY_CUSTOM) }.getOrNull())
        return HolidayScope(mode, localRegions(ctx), custom)
    }

    /** Regions from the weather location's country, the SIM, and the locale, in that order. */
    fun localRegions(ctx: Context): Set<String> {
        val out = LinkedHashSet<String>()
        val p = ctx.getSharedPreferences("miku_weather", Context.MODE_PRIVATE)
        if (p.getBoolean("manual_loc_enabled", false)) {
            HolidayCalendar.normRegion(p.getString("manual_country", null))?.let { out += it }
        }
        HolidayCalendar.normRegion(p.getString("last_country", null))?.let { out += it }
        runCatching {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            HolidayCalendar.normRegion(tm?.simCountryIso)?.let { out += it }
            HolidayCalendar.normRegion(tm?.networkCountryIso)?.let { out += it }
        }
        HolidayCalendar.normRegion(Locale.getDefault().country)?.let { out += it }
        out += "intl"
        return out
    }

    /** The weather fix's latitude (manual location first), or null when none is saved. */
    fun latitude(ctx: Context): Double? {
        val p = ctx.getSharedPreferences("miku_weather", Context.MODE_PRIVATE)
        val key = if (p.getBoolean("manual_loc_enabled", false) && p.contains("manual_lat")) "manual_lat" else "last_lat"
        if (!p.contains(key)) return null
        val v = java.lang.Double.longBitsToDouble(p.getLong(key, 0L))
        return if (v == 0.0 || v.isNaN()) null else v
    }

    // ── Pack art slots ──────────────────────────────────────────────────────────────────────

    /** Wallpaper slots of packs/miku/pack.json, compiled in (the Miku pack is the tree itself). */
    val MIKU_SEASONS: Map<String, String?> = mapOf(
        "default" to "drawable:miku_wallpaper",
        "spring" to "drawable:miku_wallpaper",
        "summer" to "drawable:theme_beach_icecream_wallpaper",
        "autumn" to "drawable:theme_cozy_cafe_wallpaper",
        "winter" to "drawable:theme_cozy_cafe_wallpaper",
    )

    /** art.wallpaper of the active pack: compiled in for Miku, read through Miku Update's provider otherwise. */
    fun seasonArt(ctx: Context, pack: MascotPack): Map<String, String?> {
        if (pack.id == "miku") return MIKU_SEASONS
        val ck = "${pack.id}@${pack.version}"
        synchronized(packArtCache) { packArtCache[ck]?.let { return it } }
        val slots = runCatching {
            ctx.contentResolver.openInputStream(Mascot.fileUri("pack.json", pack.id))?.use { s ->
                parseSeasonSlots(JSONObject(s.readBytes().toString(Charsets.UTF_8)))
            }
        }.getOrNull() ?: mapOf("default" to "drawable:miku_wallpaper")
        synchronized(packArtCache) { packArtCache[ck] = slots }
        return slots
    }

    fun parseSeasonSlots(o: JSONObject): Map<String, String?> {
        val w = o.optJSONObject("art")?.optJSONObject("wallpaper") ?: return mapOf("default" to "drawable:miku_wallpaper")
        val out = LinkedHashMap<String, String?>()
        for (k in w.keys()) out[k] = if (w.isNull(k)) null else w.optString(k).ifBlank { null }
        if (out["default"] == null) out["default"] = "drawable:miku_wallpaper"
        return out
    }

    // ── Loading ─────────────────────────────────────────────────────────────────────────────

    /**
     * Decode [ref]: "drawable:<name>" from the launcher's own resources (the pack's replacement of
     * that drawable when it has one), or a path inside pack [packId] through Miku Update's
     * provider. Sampled down so the long side is about [maxDim].
     */
    fun loadArt(ctx: Context, ref: String, packId: String, maxDim: Int): Bitmap? {
        if (ref.startsWith("drawable:")) {
            val id = ctx.resources.getIdentifier(ref.removePrefix("drawable:"), "drawable", ctx.packageName)
            if (id == 0) return null
            Mascot.artFor(ctx, id)?.let { return it }
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(ctx.resources, id, b)
            val o = BitmapFactory.Options().apply { inSampleSize = sample(b.outWidth, b.outHeight, maxDim) }
            return BitmapFactory.decodeResource(ctx.resources, id, o)
        }
        val uri = Mascot.fileUri(ref, packId)
        fun open(): InputStream? = runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, b) } ?: return null
        val o = BitmapFactory.Options().apply { inSampleSize = sample(b.outWidth, b.outHeight, maxDim) }
        return open()?.use { BitmapFactory.decodeStream(it, null, o) }
    }

    private fun sample(w: Int, h: Int, maxDim: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxDim) s *= 2
        return s
    }
}
