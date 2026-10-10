package com.caf.fmradio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Should this station come in here, on this radio?
 *
 * The catalogue's score ranks stations against each other but cannot answer that, and the
 * nearby list was showing Pittsburgh stations from a hollow outside Morgantown that had never
 * once been heard there. This turns transmitter power, height and distance into a predicted
 * level at the tuner's input in dBµV (the unit the Si4705's RSSI reports), then corrects that
 * prediction against what this device has actually measured.
 *
 * THE PATH MODEL. Within the radio horizon, Egli's empirical VHF formula (a plain-terrain fit
 * that already includes more loss than free space). Past the horizon a station is only reached
 * by diffraction and troposcatter, so an extra loss grows with every kilometre beyond it. When
 * the terrain profile for the station is known (FmFresnel), its knife-edge loss is added on top.
 * None of this is precise. It is meant to be right about "next town over" versus "two counties
 * and a ridge away", which is the question being asked.
 *
 * THE CALIBRATION. The antenna is a headphone cable and the listener is usually indoors, which
 * costs tens of dB that no formula knows about. So every band sweep is compared with the
 * prediction for the stations it heard, and the median difference becomes the offset applied
 * to everything. Until a sweep has run, a conservative default stands in for it.
 *
 * MEASUREMENT BEATS PREDICTION. A station the last sweep here heard is HEARD, whatever the model
 * says; a full-power station whose frequency read at the noise floor is NOT_HEARD.
 */
object FmReach {

    enum class Verdict(val label: String) {
        /** The last band sweep at this spot read it. */
        HEARD("heard here"),
        /** Predicted comfortably above what this tuner needs. */
        LIKELY("should come in"),
        /** Near the threshold: antenna, position and weather decide. */
        MARGINAL("marginal"),
        /** Well below threshold, or beyond the horizon. */
        UNLIKELY("out of reach"),
        /** A sweep here read only noise on its frequency. */
        NOT_HEARD("not heard here"),
        /** No ERP in the catalogue, or no position. */
        UNKNOWN("unknown"),
    }

    /** Typical RSSI where the Si4705 gives listenable mono; also roughly where RDS begins. */
    const val LISTENABLE_DBUV = 20.0
    /** Below this, nothing useful. */
    const val THRESHOLD_DBUV = 12.0
    /** A sweep reading at or under this on a frequency is the noise floor here. */
    const val NOISE_FLOOR_DBUV = 10

    private const val RX_HEIGHT_M = 1.5
    private const val FREQ_MHZ = 98.0

    /**
     * Headphone-cable antenna, indoors, before any sweep has measured the real figure. Taken
     * from the first readings on this device (2026-10-09, north-west of Morgantown): WVPM 90.9
     * at 26 km read 29 dBµV against 56 predicted (-27), WCLG 100.1 at 12 km read 8 against 57
     * (-48, low tower behind terrain). Replaced as soon as a sweep runs.
     */
    const val DEFAULT_OFFSET_DB = -38.0

    @Volatile var offsetDb: Double = DEFAULT_OFFSET_DB
        private set
    /** How many heard stations the current offset was fitted from; 0 = the default. */
    @Volatile var calibratedFrom: Int = 0
        private set

    /** Distance to the radio horizon for two antenna heights, 4/3 earth. */
    fun horizonKm(txM: Double, rxM: Double = RX_HEIGHT_M): Double = 4.12 * (sqrt(txM) + sqrt(rxM))

    /**
     * Predicted level at the tuner input, dBµV, before calibration. Null without ERP.
     * [terrainLossDb] is the Fresnel knife-edge loss when the profile is known.
     */
    fun rawDbuv(st: FmStationCatalogue.Station, terrainLossDb: Double = 0.0): Double? {
        val erpKw = st.erpKw ?: return null
        if (erpKw <= 0) return null
        val hTx = (st.haatM ?: 30.0).coerceAtLeast(10.0)
        val d = st.distanceKm.coerceAtLeast(0.5)
        val erpDbm = 10 * log10(erpKw * 1e6)
        // Egli: L = 20log f + 40log d - 20log hb + 76.3 - 10log hm   (f MHz, d km, h m; hm < 10 m)
        var loss = 20 * log10(FREQ_MHZ) + 40 * log10(d) - 20 * log10(hTx) + 76.3 - 10 * log10(RX_HEIGHT_M)
        val horizon = horizonKm(hTx)
        if (d > horizon) loss += 0.35 * (d - horizon)      // diffraction beyond the horizon, ~0.3-0.4 dB/km at VHF
        loss += terrainLossDb
        return erpDbm - loss + 107.0                        // dBm → dBµV across 50 Ω
    }

    fun predictedDbuv(st: FmStationCatalogue.Station, terrainLossDb: Double = 0.0): Double? =
        rawDbuv(st, terrainLossDb)?.let { it + offsetDb }

    /**
     * Fit [offsetDb] from a sweep: for each frequency the sweep heard (SNR above zero), the
     * strongest catalogued station there is assumed to be what was heard.
     */
    fun calibrate(stations: List<FmStationCatalogue.Station>, hits: List<FmScanHit>) {
        val diffs = ArrayList<Double>()
        for (h in hits) {
            val st = stations.filter { kotlin.math.abs(it.khz - h.freqKHz) <= 50 }.maxByOrNull { it.score } ?: continue
            val raw = rawDbuv(st) ?: continue
            diffs += (h.rssi ?: continue) - raw
        }
        if (diffs.size < 2) return
        diffs.sort()
        offsetDb = diffs[diffs.size / 2].coerceIn(-70.0, 0.0)
        calibratedFrom = diffs.size
    }

    /**
     * Annotate a station list. [sweepRssi] is RSSI per channel of the latest sweep made at
     * this position (null when there is none), indexed from [sweepLowKHz] in [sweepStepKHz].
     * [terrain] maps a call sign to its terrain profile from here, where one is known.
     */
    fun annotate(
        stations: List<FmStationCatalogue.Station>,
        sweepRssi: IntArray?,
        sweepLowKHz: Int,
        sweepStepKHz: Int,
        terrain: Map<String, FmFresnel.Profile> = emptyMap(),
    ): List<FmStationCatalogue.Station> {
        // One station per frequency owns any measurement there: the one most likely to be it.
        val owner = stations.groupBy { it.khz }.mapValues { (_, v) -> v.maxByOrNull { it.score }?.call }
        return stations.map { st ->
            val prof = terrain[st.call]
            val predicted = predictedDbuv(st, prof?.diffractionLossDb ?: 0.0)
            val measured = sweepRssi?.let { r ->
                val i = (st.khz - sweepLowKHz) / max(1, sweepStepKHz)
                if (i in r.indices && (st.khz - sweepLowKHz) % max(1, sweepStepKHz) == 0) r[i] else null
            }?.takeIf { owner[st.khz] == st.call }
            val verdict = when {
                measured != null && measured >= LISTENABLE_DBUV -> Verdict.HEARD
                measured != null && measured <= NOISE_FLOOR_DBUV && st.service == "FM" -> Verdict.NOT_HEARD
                predicted == null -> Verdict.UNKNOWN
                predicted >= LISTENABLE_DBUV + 6 -> Verdict.LIKELY
                predicted >= THRESHOLD_DBUV -> Verdict.MARGINAL
                else -> Verdict.UNLIKELY
            }
            st.copy(
                reach = verdict, predictedDbuv = predicted, measuredDbuv = measured,
                fresnelFraction = prof?.worst?.fresnelFraction,
                terrainVerdict = prof?.verdict,
                terrainLossDb = prof?.diffractionLossDb,
            )
        }
    }
}
