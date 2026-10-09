package com.caf.fmradio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.Settings
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every broadcast station in North America, and which of them should reach you here.
 *
 * The catalogue is 47,826 stations built from FCC public-record data (see tools/radiodb) and
 * baked into the image at /system_ext/etc/miku/stations.sqlite. Each row carries the
 * transmitter's coordinates, ERP and HAAT, so "what is on 100.1 here" is a lookup rather than
 * a guess, and so the band display can label a peak with the call sign that produced it.
 *
 * It is a catalogue, not a prediction of what you will hear. A station listed as reachable may
 * be blocked by a ridge — this device lives in Appalachian terrain where that is the normal
 * case, not the exception — and terrain is not modelled here yet. So the ranking below is
 * honest about what it is: transmitter power and height against distance, which is an upper
 * bound on what is possible, not a promise.
 */
object FmStationCatalogue {

    private const val TAG = "FmStationCatalogue"
    private val PATHS = listOf(
        "/system_ext/etc/miku/stations.sqlite",
        "/system/etc/miku/stations.sqlite",
        "/data/local/tmp/stations.sqlite",      // for trying a newer catalogue without a flash
    )

    data class Station(
        val call: String,
        val service: String,          // FM, FX translator, FL low power, AM
        val khz: Int,
        val city: String?,
        val state: String?,
        val licensee: String?,
        val lat: Double,
        val lon: Double,
        val erpKw: Double?,
        val haatM: Double?,
        val callsignSince: String?,
        /** Great-circle kilometres from the listener. */
        val distanceKm: Double,
        /**
         * Crude reachability score in dB-ish units: power and height help, distance hurts.
         * Comparable between stations, meaningless as an absolute, and blind to terrain.
         */
        val score: Double,
    ) {
        val mhz: Double get() = khz / 1000.0
        /** How the UI should describe the kind of station, in words rather than FCC codes. */
        val kind: String get() = when (service) {
            "FX" -> "translator"
            "FL" -> "low power"
            "AM" -> "AM"
            else -> "full power"
        }
    }

    @Volatile private var db: SQLiteDatabase? = null
    @Volatile private var missingReported = false

    private fun open(): SQLiteDatabase? {
        db?.let { return it }
        for (p in PATHS) {
            val f = File(p)
            if (!f.exists()) continue
            val opened = runCatching {
                SQLiteDatabase.openDatabase(p, null, SQLiteDatabase.OPEN_READONLY)
            }.onFailure { Log.w(TAG, "could not open $p: $it") }.getOrNull()
            if (opened != null) {
                db = opened
                Log.i(TAG, "catalogue open: $p")
                return opened
            }
        }
        if (!missingReported) {
            missingReported = true
            Log.w(TAG, "no station catalogue on this image; local station names unavailable")
        }
        return null
    }

    fun available(): Boolean = open() != null

    /** Where MikuLocationFusion last put us, or null if nothing has placed the device yet. */
    fun listenerPosition(ctx: Context): Pair<Double, Double>? = runCatching {
        val cr = ctx.contentResolver
        val lat = Settings.Global.getString(cr, "miku_loc_lat")?.toDoubleOrNull()
        val lon = Settings.Global.getString(cr, "miku_loc_lon")?.toDoubleOrNull()
        if (lat == null || lon == null) null else lat to lon
    }.getOrNull()

    private fun haversineKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    /**
     * Stations whose transmitters are within [radiusKm], best first.
     *
     * Bounded by a latitude/longitude box before the distance maths so the query uses the index
     * rather than walking 47,826 rows; at these radii the box is a negligible over-estimate.
     */
    fun near(
        lat: Double, lon: Double,
        radiusKm: Double = 140.0,
        services: List<String> = listOf("FM", "FX", "FL"),
        limit: Int = 80,
    ): List<Station> {
        val d = open() ?: return emptyList()
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val placeholders = services.joinToString(",") { "?" }
        val args = (services + listOf(
            (lat - dLat).toString(), (lat + dLat).toString(),
            (lon - dLon).toString(), (lon + dLon).toString()
        )).toTypedArray()
        val out = ArrayList<Station>()
        runCatching {
            d.rawQuery(
                "SELECT call,service,khz,city,state,licensee,lat,lon,erp_kw,haat_m,callsign_since " +
                    "FROM station WHERE service IN ($placeholders) AND lat IS NOT NULL " +
                    "AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?", args
            ).use { c ->
                while (c.moveToNext()) {
                    val sLat = c.getDouble(6); val sLon = c.getDouble(7)
                    val km = haversineKm(lat, lon, sLat, sLon)
                    if (km > radiusKm) continue
                    val erp = if (c.isNull(8)) null else c.getDouble(8)
                    val haat = if (c.isNull(9)) null else c.getDouble(9)
                    out += Station(
                        call = c.getString(0), service = c.getString(1), khz = c.getInt(2),
                        city = c.getString(3), state = c.getString(4), licensee = c.getString(5),
                        lat = sLat, lon = sLon, erpKw = erp, haatM = haat,
                        callsignSince = c.getString(10), distanceKm = km,
                        score = 10 * log10((erp ?: 0.01).coerceAtLeast(0.001)) +
                            10 * log10((haat ?: 30.0).coerceAtLeast(1.0)) -
                            20 * log10(km.coerceAtLeast(1.0)),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "near() failed: $it") }
        return out.sortedByDescending { it.score }.take(limit)
    }

    /** The best candidate for one frequency, for labelling the dial and the band display. */
    fun atFrequency(lat: Double, lon: Double, khz: Int, toleranceKhz: Int = 60): Station? =
        near(lat, lon, radiusKm = 200.0, limit = 400)
            .filter { abs(it.khz - khz) <= toleranceKhz }
            .maxByOrNull { it.score }

    /** How many stations the catalogue holds, for the diagnostics sheet. */
    fun size(): Int = runCatching {
        open()?.rawQuery("SELECT COUNT(*) FROM station", null)?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0
    }.getOrDefault(0)
}
