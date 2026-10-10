package com.miku.launcher.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max

/** Small HTTP helpers shared by the Windy/RainViewer ports. Same User-Agent as the rest of the weather code. */
internal object WxHttp {
    const val UA = "MikuMeteorology/1.0 (contact@falcontechnix.com)"

    class HttpException(val code: Int, message: String) : Exception(message)

    fun bytes(url: String, timeoutMs: Int = 8000, headers: Map<String, String> = emptyMap()): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw HttpException(code, "HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    fun json(url: String, timeoutMs: Int = 8000, headers: Map<String, String> = emptyMap()): JSONObject =
        JSONObject(String(bytes(url, timeoutMs, headers + ("Accept" to "application/json")), Charsets.UTF_8))

    /** Decode a PNG/JPEG tile without premultiplying, so palette colours come back exact. */
    fun bitmap(url: String, timeoutMs: Int = 6000): Bitmap? = runCatching {
        val b = bytes(url, timeoutMs)
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        })
    }.getOrNull()
}

/**
 * The rain nowcast engine, ported from the KDE Windy Weather widget's NowcastEngine.qml:
 * RainViewer radar frames -> dBZ grids -> storm motion -> "rain will be here in X".
 *
 * Free, no key. One analysis pulls the RainViewer catalogue plus 9 tiles per frame for 6 past
 * frames (and any nowcast frames, if RainViewer serves them again), one frame at a time so memory
 * stays at one 768 px mosaic. Runs when the weather screen asks for it, or from the background
 * check in [MikuWeatherExtras] when there is reason to think rain is around.
 */
object MikuNowcastEngine {
    private const val TAG = "MikuNowcast"
    const val ZOOM = MikuNowcast.RAIN_MAX_ZOOM // 3x3 tiles at z7 ~ 700 km across mid-latitudes
    private const val GRID_FACTOR = 2           // 768 px mosaic -> 384 px grid
    private const val PAST_FRAMES = 6
    private const val ANALYSIS_FRAMES = 4
    private const val BLOCK = 32                // grid px per motion arrow
    const val THRESHOLD = 20f                   // dBZ that counts as rain
    const val REFRESH_MS = 5 * 60_000L

    data class Frame(val time: Long, val kind: String, val overlay: Bitmap?)

    data class State(
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val mosaic: MikuNowcast.Mosaic? = null,
        val frames: List<Frame> = emptyList(),
        val latestIndex: Int = -1,
        val basemap: Bitmap? = null,
        val motion: MikuNowcast.Motion = MikuNowcast.Motion(),
        val arrows: List<MikuNowcast.Arrow> = emptyList(),
        val result: MikuNowcast.Arrival? = null,
        val summary: MikuNowcast.Summary = MikuNowcast.Summary(),
        val fetchedMs: Long = 0L,
        val busy: Boolean = false,
        val status: String = "",
        val error: String = ""
    ) {
        val gridFactor get() = GRID_FACTOR
        val loaded get() = result != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    /** Basemap tiles survive re-analysis; nine z7 tiles barely change. */
    private val baseCache = LruCache<String, Bitmap>(18)

    /** Refresh when stale (or moved, or forced). Safe from UI; coalesces with a running one. */
    fun refresh(ctx: Context, lat: Double, lon: Double, force: Boolean = false) {
        if (lat == 0.0 && lon == 0.0) return
        val s = _state.value
        val moved = abs(s.lat - lat) > 0.05 || abs(s.lon - lon) > 0.05
        if (!force && !moved && System.currentTimeMillis() - s.fetchedMs < REFRESH_MS) return
        scope.launch { analyseNow(ctx.applicationContext, lat, lon) }
    }

    /** Re-run the wording only (units changed, Open-Meteo 15-minute data arrived, the clock moved on). */
    fun resummarize() {
        val s = _state.value
        val imperial = MikuWeatherTileEngine.state.value.units == WxUnits.IMPERIAL
        _state.update { it.copy(summary = MikuNowcast.summarize(s.result, MikuWeatherExtras.state.value.om15, imperial, System.currentTimeMillis())) }
    }

    suspend fun analyseNow(app: Context, lat: Double, lon: Double) {
        if (!mutex.tryLock()) return
        try {
            _state.update { it.copy(busy = true, error = "", status = "Getting radar frames") }
            val cat = WxHttp.json("https://api.rainviewer.com/public/weather-maps.json")
            val host = cat.optString("host", "https://tilecache.rainviewer.com")
            val radar = cat.optJSONObject("radar")
            val pastArr = radar?.optJSONArray("past")
            if (pastArr == null || pastArr.length() == 0) { fail("RainViewer sent no radar frames"); return }
            val nowArr = radar?.optJSONArray("nowcast")

            data class Ref(val time: Long, val path: String, val kind: String)
            val refs = ArrayList<Ref>()
            for (i in max(0, pastArr.length() - PAST_FRAMES) until pastArr.length()) {
                val o = pastArr.optJSONObject(i) ?: continue
                refs.add(Ref(o.optLong("time"), o.optString("path"), "past"))
            }
            if (nowArr != null) for (i in 0 until nowArr.length()) {
                val o = nowArr.optJSONObject(i) ?: continue
                refs.add(Ref(o.optLong("time"), o.optString("path"), "nowcast"))
            }
            val frameSec = if (pastArr.length() >= 2)
                max(60L, pastArr.getJSONObject(pastArr.length() - 1).optLong("time") - pastArr.getJSONObject(pastArr.length() - 2).optLong("time")).toDouble()
            else 600.0

            val m = MikuNowcast.mosaic(lat, lon, ZOOM, 3)
            val basemap = buildBasemap(m)

            val pastGrids = ArrayList<MikuNowcast.TimedGrid>()
            val nowGrids = ArrayList<MikuNowcast.TimedGrid>()
            val frames = ArrayList<Frame>()
            val px = IntArray(m.size * m.size)
            refs.forEachIndexed { k, r ->
                _state.update { it.copy(status = "Radar frame ${k + 1} of ${refs.size}") }
                java.util.Arrays.fill(px, 0)
                var ok = 0
                for (t in m.tiles) {
                    if (!t.valid) continue
                    val bmp = WxHttp.bitmap(MikuNowcast.rainviewerTile(host, r.path, m.z, t.x, t.y, false)) ?: continue
                    val tile = if (bmp.width == MikuNowcast.TILE && bmp.height == MikuNowcast.TILE) bmp
                        else Bitmap.createScaledBitmap(bmp, MikuNowcast.TILE, MikuNowcast.TILE, false)
                    tile.getPixels(px, t.row * MikuNowcast.TILE * m.size + t.col * MikuNowcast.TILE, m.size, 0, 0, MikuNowcast.TILE, MikuNowcast.TILE)
                    if (tile !== bmp) tile.recycle()
                    bmp.recycle()
                    ok++
                }
                val g = MikuNowcast.gridFromArgb(px, m.size, m.size, GRID_FACTOR, ok)
                if (r.kind == "past") pastGrids.add(MikuNowcast.TimedGrid(r.time, g)) else nowGrids.add(MikuNowcast.TimedGrid(r.time, g))
                frames.add(Frame(r.time, r.kind, overlayFor(g)))
            }
            if (pastGrids.none { it.grid.tiles > 0 }) { fail("No radar tiles came back"); return }

            // Pairwise motion over the last ANALYSIS_FRAMES past frames, newest pair last.
            val thrMotion = max(5f, THRESHOLD - 10f)
            val pairs = ArrayList<MikuNowcast.Motion>()
            for (p in max(1, pastGrids.size - ANALYSIS_FRAMES + 1) until pastGrids.size)
                pairs.add(MikuNowcast.estimateMotion(pastGrids[p - 1].grid, pastGrids[p].grid, thrMotion, 5f, 14))
            val motion = MikuNowcast.combineMotion(pairs)
            val latest = pastGrids.last()
            val prev = pastGrids.getOrNull(pastGrids.size - 2)
            val field = if (prev != null) MikuNowcast.motionField(prev.grid, latest.grid, motion, BLOCK, thrMotion, 5f) else emptyList()
            val mppGrid = m.mpp * GRID_FACTOR
            val result = MikuNowcast.arrival(pastGrids, nowGrids, m.px / GRID_FACTOR, m.py / GRID_FACTOR, mppGrid,
                motion, frameSec, THRESHOLD, System.currentTimeMillis() / 1000)
            val latestIndex = frames.indexOfLast { it.kind == "past" }

            val imperial = MikuWeatherTileEngine.state.value.units == WxUnits.IMPERIAL
            val summary = MikuNowcast.summarize(result, MikuWeatherExtras.state.value.om15, imperial, System.currentTimeMillis())
            Log.i(TAG, "analysed ${pastGrids.size} past + ${nowGrids.size} nowcast frames, motion q=${"%.2f".format(motion.quality)}: ${summary.text}")
            _state.update {
                it.copy(
                    lat = lat, lon = lon, mosaic = m, frames = frames, latestIndex = latestIndex, basemap = basemap,
                    motion = motion, arrows = MikuNowcast.arrows(field), result = result, summary = summary,
                    fetchedMs = System.currentTimeMillis(), busy = false, error = "",
                    status = "Analysed ${pastGrids.size} frames" + if (motion.quality >= 0.05) ", storm motion found" else ", no clear storm motion"
                )
            }
            MikuWeatherExtras.onNowcast(app, summary)
        } catch (t: Throwable) {
            Log.w(TAG, "nowcast failed: ${t.message}")
            fail(if (t is java.net.UnknownHostException || t is java.net.SocketTimeoutException) "Offline" else (t.message ?: "Radar failed"))
        } finally {
            mutex.unlock()
        }
    }

    private fun fail(msg: String) {
        _state.update { it.copy(busy = false, error = msg, status = msg) }
    }

    /** Dark CARTO basemap (OpenStreetMap data), the same tiles the old radar tab used. */
    private fun buildBasemap(m: MikuNowcast.Mosaic): Bitmap? = runCatching {
        val out = Bitmap.createBitmap(m.size, m.size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF0B1420.toInt())
        var got = 0
        for (t in m.tiles) {
            if (!t.valid) continue
            val url = "https://a.basemaps.cartocdn.com/dark_all/${m.z}/${t.x}/${t.y}.png"
            val bmp = baseCache.get(url) ?: WxHttp.bitmap(url)?.also { baseCache.put(url, it) } ?: continue
            c.drawBitmap(bmp, null, android.graphics.Rect(t.col * 256, t.row * 256, t.col * 256 + 256, t.row * 256 + 256), null)
            got++
        }
        if (got == 0) { out.recycle(); null } else out
    }.getOrNull()

    /** Grid -> a grid-sized bitmap in the Windy palette (drawn scaled up over the basemap). */
    private fun overlayFor(g: MikuNowcast.Grid): Bitmap? {
        if (g.cover == 0) return null
        val px = IntArray(g.w * g.h)
        for (i in px.indices) {
            val v = g.v[i]
            px[i] = if (v < 2f) 0 else MikuNowcast.windyColor(v)
        }
        return Bitmap.createBitmap(px, g.w, g.h, Bitmap.Config.ARGB_8888)
    }
}
