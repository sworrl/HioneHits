package com.caf.fmradio

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Labels any closer together than this are dropped rather than drawn overlapping. */
private const val MIN_LABEL_GAP_DP = 26f
/** Hard cap: a crowded band here lists hundreds of stations and none of them would be legible. */
private const val MAX_BAND_LABELS = 14

/** Waterfall depth in rows. At the engine's 25 frames a second this is six seconds of history. */
private const val WATERFALL_ROWS = 150

/** The analyser's dB span: the engine maps −90..0 dBFS to 0..1. */
private const val FLOOR_DB = -90f

/** Labelled frequencies on the log axis; the eye reads octave-ish decades, not bin numbers. */
private val MAJOR_HZ = floatArrayOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)
private val MINOR_HZ = floatArrayOf(30f, 40f, 60f, 70f, 80f, 90f, 300f, 400f, 600f, 700f, 800f, 900f,
    3000f, 4000f, 6000f, 7000f, 8000f, 9000f, 15000f)

private fun hzLabel(hz: Float): String {
    if (hz < 1000f) return hz.roundToInt().toString()
    val k = hz / 1000f
    // 15.68 kHz (the top bin at a 32 kHz capture) reads better as "15.7k", 2000 as "2k".
    if (k >= 10f) return "${k.roundToInt()}k"
    val tenths = (k * 10f).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}k" else "${tenths / 10}.${tenths % 10}k"
}

/**
 * The tuner's big panel. Two different instruments share it, and they no longer share chrome:
 *
 * LIVE is an audio analyser of what is playing: 256 log-spaced FFT bins with peak hold, a log
 * frequency axis in Hz and a dB scale, and a spectrogram underneath. It reads its own flow
 * ([FmRadioManager.spectrum]) and nothing else at frame rate, so the rest of the screen does not
 * recompose 25 times a second. FM band furniture (call signs, the tuned-frequency cursor, the
 * MHz strip) is meaningless on an audio axis and is not drawn here.
 *
 * BAND is the swept RSSI of the whole band: x is frequency, peaks are labelled with the
 * catalogue's call signs, and tap or drag tunes.
 *
 * Nothing is synthesised: with the tuner off, starting, or silent the plot is flat. That is a
 * feature, not a gap — a flat trace while the tuner claims to be on is exactly the symptom that
 * says the audio path is not carrying anything.
 */
@Composable
fun RadioWaterfallSpectrum(
    currentFreqKHz: Int,
    band: FmBandPlan,
    isPowerOn: Boolean,
    isHardwareOnline: Boolean,
    hardwareError: String?,
    signalHistory: FloatArray,
    bandProfile: FloatArray,
    bandHistory: List<FloatArray>,
    favorites: List<Int>,
    scanHits: List<Int>,
    /**
     * Stations the catalogue says transmit within reach of where the device actually is.
     * Labels the BAND view's peaks; best-scoring first, thinned so they stay legible.
     */
    cataloguedStations: List<FmStationCatalogue.Station> = emptyList(),
    /** Which receiver-panel convention BAND draws. Colour never varies; geometry does. */
    skin: SdrSkin = SdrSkin.CLASSIC,
    /** Sweep times, newest first, parallel to bandHistory. Empty when nothing has swept. */
    rowTimes: List<Long> = emptyList(),
    mode: QualcommFmHardwareEngine.ViewMode = QualcommFmHardwareEngine.ViewMode.LIVE,
    onCycleSkin: () -> Unit = {},
    onTuneFreq: (Int) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF040D12))
            .border(1.2.dp, CyberGlassBorder, RoundedCornerShape(14.dp))
    ) {
        if (mode == QualcommFmHardwareEngine.ViewMode.LIVE) {
            LiveAudioPanel(signalHistory)
        } else {
            BandPanel(currentFreqKHz, band, signalHistory, bandProfile, bandHistory, favorites,
                      scanHits, cataloguedStations, skin, rowTimes, onCycleSkin, onTuneFreq)
        }
        Text(
            when {
                isHardwareOnline -> "HARDWARE ACTIVE"
                hardwareError != null -> "TUNER ERROR"
                isPowerOn -> "STARTING…"
                else -> "STANDBY"
            },
            color = when {
                isHardwareOnline -> MikuPink
                hardwareError != null -> MikuNeonPink
                isPowerOn -> CyberAmber
                else -> Color.Gray
            },
            fontSize = 7.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ============================================================================ LIVE

/**
 * Live audio: analyser on top, spectrogram below.
 *
 * Frame-rate work is confined to the draw phase. The flow is collected in a LaunchedEffect that
 * writes one waterfall row into a bitmap and bumps a state object that only the draw lambdas
 * read, so a new frame invalidates drawing here and nothing else: no recomposition, no layout.
 * Per frame the cost is one 256-pixel row write, two bitmap blits, one 256-point path and one
 * drawLines call for the peak markers, with every buffer allocated once.
 */
@Composable
private fun LiveAudioPanel(signalHistory: FloatArray) {
    val frame = remember { mutableStateOf(FmRadioManager.spectrum.value, neverEqualPolicy()) }
    val wf = remember { Waterfall(QualcommFmHardwareEngine.SPECTRUM_BINS, WATERFALL_ROWS) }
    // The axis range changes only when the decoded sample rate does, so it is its own state and
    // is the only thing that rebuilds the cached axis labels.
    val axis = remember { mutableStateOf(25f to 16_000f) }
    LaunchedEffect(Unit) {
        FmRadioManager.spectrum.collect { f ->
            if (f.level.isNotEmpty()) {
                wf.push(f.level)
                val b = f.binHz
                if (b.size >= 2) {
                    val r = b.first() to b.last()
                    if (r != axis.value) axis.value = r
                }
            }
            frame.value = f
        }
    }
    val hasAudio by remember { derivedStateOf { frame.value.level.isNotEmpty() } }

    if (!hasAudio) {
        LiveSignalFallback(signalHistory)
        return
    }

    val tm = rememberTextMeasurer()
    val trace = remember { Path() }
    val fill = remember { Path() }
    val peakLines = remember { FloatArray(QualcommFmHardwareEngine.SPECTRUM_BINS * 4) }
    val peakPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(230, 0xFF, 0x5F, 0xA2)
            strokeCap = android.graphics.Paint.Cap.BUTT
        }
    }
    // Finger readout: x in px while pressed, -1 otherwise. Read only while drawing.
    val cursorX = remember { mutableFloatStateOf(-1f) }

    Box(Modifier.fillMaxSize()) {
        Spacer(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        cursorX.floatValue = down.position.x
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            cursorX.floatValue = c.position.x
                            c.consume()
                        }
                        cursorX.floatValue = -1f
                    }
                }
                .drawWithCache {
                    val (fLo, fHi) = axis.value
                    val logSpan = ln(fHi / fLo)
                    val gutter = 24.dp.toPx()
                    val top = 16.dp.toPx()
                    val plotW = size.width - gutter - 4.dp.toPx()
                    val plotH = size.height * 0.46f - top
                    val axisH = 12.dp.toPx()
                    val wfTop = top + plotH + axisH
                    val wfH = size.height - wfTop - 3.dp.toPx()
                    fun xOf(hz: Float) = gutter + plotW * (ln(hz / fLo) / logSpan)
                    val labelStyle = TextStyle(color = MikuTeal.copy(alpha = 0.75f), fontSize = 6.sp)
                    val dbStyle = TextStyle(color = MikuTeal.copy(alpha = 0.6f), fontSize = 6.sp)
                    val freqLabels = ArrayList<Pair<Float, TextLayoutResult>>()
                    for (hz in MAJOR_HZ) if (hz in fLo..fHi) freqLabels += xOf(hz) to tm.measure(hzLabel(hz), labelStyle)
                    val minorX = MINOR_HZ.filter { it in fLo..fHi }.map { xOf(it) }
                    val dbLines = intArrayOf(0, -20, -40, -60, -80)
                    val dbLabels = dbLines.map { db ->
                        val y = top + plotH * (1f - (db - FLOOR_DB) / -FLOOR_DB)
                        y to tm.measure(if (db == 0) "0" else "$db", dbStyle)
                    }
                    val dbUnit = tm.measure("dBFS", TextStyle(color = MikuTeal.copy(alpha = 0.4f), fontSize = 5.5.sp))
                    val title = tm.measure(
                        "LIVE · FFT 4096 · ${hzLabel(fLo)}–${hzLabel(fHi)}Hz · HOLD TO READ",
                        TextStyle(color = MikuTeal.copy(alpha = 0.85f), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                    )
                    val fillBrush = Brush.verticalGradient(
                        0f to MikuPink.copy(alpha = 0.55f), 0.35f to MikuCyan.copy(alpha = 0.40f),
                        1f to MikuTeal.copy(alpha = 0.04f), startY = top, endY = top + plotH,
                    )
                    val traceStroke = Stroke(width = 1.4.dp.toPx())
                    val gridColor = MikuTeal.copy(alpha = 0.10f)
                    val majorColor = MikuTeal.copy(alpha = 0.20f)
                    val nowLabel = tm.measure("now", TextStyle(color = MikuTeal.copy(alpha = 0.5f), fontSize = 5.5.sp))
                    val peakW = (plotW / QualcommFmHardwareEngine.SPECTRUM_BINS).coerceAtLeast(1.5f)
                    peakPaint.strokeWidth = 1.6.dp.toPx()
                    val readoutStyle = TextStyle(color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)

                    onDrawBehind {
                        drawText(title, topLeft = Offset(8.dp.toPx(), 3.dp.toPx()))
                        // Grid: dB rows and log-frequency columns.
                        for ((y, lay) in dbLabels) {
                            drawLine(gridColor, Offset(gutter, y), Offset(gutter + plotW, y), 1f)
                            drawText(lay, topLeft = Offset(gutter - lay.size.width - 3f,
                                (y - lay.size.height / 2f).coerceIn(top - 4f, top + plotH - lay.size.height)))
                        }
                        drawText(dbUnit, topLeft = Offset(2f, top + plotH + 1f))
                        for (x in minorX) drawLine(gridColor, Offset(x, top), Offset(x, top + plotH), 1f)
                        for ((x, lay) in freqLabels) {
                            drawLine(majorColor, Offset(x, top), Offset(x, top + plotH + 3f), 1f)
                            drawText(lay, topLeft = Offset(
                                (x - lay.size.width / 2f).coerceIn(gutter, size.width - lay.size.width),
                                top + plotH + 2f))
                        }

                        val f = frame.value
                        val lv = f.level
                        val pk = f.peak
                        val n = lv.size
                        if (n >= 2) {
                            val dx = plotW / (n - 1)
                            val base = top + plotH
                            trace.reset(); fill.reset()
                            fill.moveTo(gutter, base)
                            for (i in 0 until n) {
                                val x = gutter + i * dx
                                val y = base - lv[i].coerceIn(0f, 1f) * plotH
                                if (i == 0) trace.moveTo(x, y) else trace.lineTo(x, y)
                                fill.lineTo(x, y)
                            }
                            fill.lineTo(gutter + plotW, base); fill.close()
                            drawPath(fill, fillBrush)
                            drawPath(trace, MikuTealBright, style = traceStroke)

                            // Peak hold: a short bar per bin, all in one native call.
                            if (pk.size == n) {
                                var k = 0
                                for (i in 0 until n) {
                                    val v = pk[i]
                                    if (v <= 0.02f) continue
                                    val x = gutter + i * dx
                                    val y = base - v.coerceIn(0f, 1f) * plotH
                                    peakLines[k++] = x - peakW * 0.45f; peakLines[k++] = y
                                    peakLines[k++] = x + peakW * 0.45f; peakLines[k++] = y
                                }
                                if (k > 0) drawIntoCanvas { it.nativeCanvas.drawLines(peakLines, 0, k, peakPaint) }
                            }
                        }

                        // Spectrogram: same x mapping (bins are log-uniform), newest row on top.
                        wf.draw(this, gutter, wfTop, plotW, wfH)
                        drawText(nowLabel, topLeft = Offset(2f, wfTop))
                        wf.ageSeconds()?.let { age ->
                            // Measuring text per frame is the one allocation here; it is a
                            // handful of characters and only changes once a second.
                            val lay = tm.measure("-${age}s", TextStyle(color = MikuTeal.copy(alpha = 0.5f), fontSize = 5.5.sp))
                            drawText(lay, topLeft = Offset(2f, wfTop + wfH - lay.size.height))
                        }

                        // Finger readout.
                        val cx = cursorX.floatValue
                        if (cx >= gutter && cx <= gutter + plotW && n >= 2) {
                            val frac = (cx - gutter) / plotW
                            val hz = fLo * kotlin.math.exp(frac * logSpan)
                            val bi = (frac * (n - 1)).roundToInt().coerceIn(0, n - 1)
                            val db = (FLOOR_DB * (1f - lv[bi])).roundToInt()
                            drawLine(Color.White.copy(alpha = 0.7f), Offset(cx, top), Offset(cx, wfTop + wfH), 1.dp.toPx())
                            val txt = tm.measure(
                                (if (hz >= 1000f) String.format(java.util.Locale.US, "%.2f kHz", hz / 1000f)
                                 else "${hz.roundToInt()} Hz") + " · $db dB", readoutStyle)
                            val bx = (cx - txt.size.width / 2f).coerceIn(gutter, size.width - txt.size.width - 4f)
                            drawRoundRect(Color(0xE6040D12), Offset(bx - 4f, top + 2f),
                                Size(txt.size.width + 8f, txt.size.height + 4f),
                                androidx.compose.ui.geometry.CornerRadius(6f))
                            drawText(txt, topLeft = Offset(bx, top + 4f))
                        }
                    }
                }
        )
    }
}

/**
 * The spectrogram store: a [rows]×[bins] ARGB bitmap used as a ring.
 *
 * A new frame writes a single row at the head and moves the head back one; drawing unrolls the
 * ring with two blits so the newest row is always on top. Nothing is shifted and nothing is
 * allocated per frame. The bitmap is mutated on the main thread and HWUI re-uploads it because
 * setPixels bumps its generation id, which is the documented contract for mutable bitmaps.
 */
private class Waterfall(val bins: Int, val rows: Int) {
    private val bmp = android.graphics.Bitmap.createBitmap(bins, rows, android.graphics.Bitmap.Config.ARGB_8888)
        .also { it.eraseColor(FmWaterfallPalette.ARGB[0]) }
    private val img = bmp.asImageBitmap()
    private val row = IntArray(bins)
    private val times = LongArray(rows)
    private var head = 0
    private var filled = 0

    fun push(level: FloatArray) {
        val lut = FmWaterfallPalette.ARGB
        for (i in 0 until bins) {
            // A little contrast: the bottom of the dB range is hiss and should sit in the navy,
            // or the whole spectrogram turns into teal fog.
            val v = ((level.getOrElse(i) { 0f } - 0.12f) * 1.18f).coerceIn(0f, 1f)
            row[i] = lut[(v * 255f).toInt()]
        }
        head = (head - 1 + rows) % rows
        bmp.setPixels(row, 0, bins, 0, head, bins, 1)
        times[head] = android.os.SystemClock.elapsedRealtime()
        if (filled < rows) filled++
    }

    /** Age of the oldest row on screen, whole seconds, or null until there is history. */
    fun ageSeconds(): Long? {
        if (filled < 2) return null
        val oldest = times[(head + filled - 1) % rows]
        return ((android.os.SystemClock.elapsedRealtime() - oldest) / 1000L).takeIf { it >= 1 }
    }

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, x: Float, y: Float, w: Float, h: Float) {
        if (h <= 0f) return
        val rowH = h / rows
        val firstRows = rows - head            // head..rows-1, the newest part
        val dx = x.roundToInt()
        val dw = w.roundToInt()
        val h1 = (firstRows * rowH).roundToInt()
        scope.drawImage(img, IntOffset(0, head), IntSize(bins, firstRows),
            IntOffset(dx, y.roundToInt()), IntSize(dw, h1), filterQuality = FilterQuality.Low)
        if (head > 0) {
            scope.drawImage(img, IntOffset(0, 0), IntSize(bins, head),
                IntOffset(dx, y.roundToInt() + h1), IntSize(dw, h.roundToInt() - h1),
                filterQuality = FilterQuality.Low)
        }
    }
}

/**
 * LIVE with no audio reaching the app (a wired route without a verified siphon): the tuned
 * station's own RSSI over time, which at least moves when the antenna does.
 */
@Composable
private fun LiveSignalFallback(signalHistory: FloatArray) {
    val trace = remember { Path() }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val top = 16.dp.toPx()
            val traceH = h * 0.42f
            val barTop = top + traceH + 6.dp.toPx()
            val barH = h - barTop - 4.dp.toPx()
            for (g in 1..3) {
                val gy = top + traceH * g / 4f
                drawLine(MikuTeal.copy(alpha = 0.10f), Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            }
            val cols = signalHistory.size
            if (cols > 1) {
                val colW = w / cols
                for (i in 0 until cols) {
                    val v = signalHistory[i].coerceIn(0f, 1f)
                    val bh = v * barH
                    drawRect(FmWaterfallPalette.lut(v), Offset(w - (i + 1) * colW, barTop + barH - bh), Size(colW + 0.5f, bh))
                }
                trace.reset()
                val dx = w / (cols - 1)
                for (i in 0 until cols) {
                    val x = w - i * dx
                    val y = top + traceH - signalHistory[i].coerceIn(0f, 1f) * (traceH - 4f)
                    if (i == 0) trace.moveTo(x, y) else trace.lineTo(x, y)
                }
                drawPath(trace, MikuTeal, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Text(
            "LIVE · SIGNAL OF THE TUNED STATION · NO AUDIO TO ANALYSE ON THIS OUTPUT",
            color = MikuTeal.copy(alpha = 0.85f), fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp).padding(end = 70.dp)
        )
    }
}

// ============================================================================ BAND

@Composable
private fun BandPanel(
    currentFreqKHz: Int,
    band: FmBandPlan,
    signalHistory: FloatArray,
    bandProfile: FloatArray,
    bandHistory: List<FloatArray>,
    favorites: List<Int>,
    scanHits: List<Int>,
    cataloguedStations: List<FmStationCatalogue.Station>,
    skin: SdrSkin,
    rowTimes: List<Long>,
    onCycleSkin: () -> Unit,
    onTuneFreq: (Int) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    // A band sweep gives RSSI per channel across the whole band, which is the display this
    // panel wants: x is already frequency, so the sweep drops straight onto the same axis as
    // the tuning strip, the presets and the marker.
    val hasBand = bandProfile.isNotEmpty()

    val low = band.lowKHz.toFloat()
    val span = (band.highKHz - band.lowKHz).toFloat()
    val step = band.stepKHz

    // No rounding here. Snapping belongs to the engine, which anchors the grid to the band's
    // low limit; doing it here as "nearest multiple of step" rounded from zero, which on a
    // 200 kHz grid can only ever produce even tenths and made 100.1 untunable.
    fun freqAt(fracX: Float): Int = (low + fracX.coerceIn(0f, 1f) * span).roundToInt()

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(band) {
                detectTapGestures { offset -> onTuneFreq(freqAt(offset.x / size.width)) }
            }
            .pointerInput(band) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onTuneFreq(freqAt(change.position.x / size.width))
                }
            }
    ) {
        val fftPath = remember { Path() }
        val fillPath = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val fftHeight = h * 0.42f
            val scaleHeight = 10.dp.toPx()
            val waterfallHeight = h * 0.60f - scaleHeight

            val gutter = rssiGutterWidth(skin)
            val plotW = w - gutter
            fun xOf(khz: Int) = gutter + ((khz - low) / span).coerceIn(0f, 1f) * plotW

            for (g in 1..3) {
                val gy = fftHeight * g / 4f
                drawLine(MikuTeal.copy(alpha = 0.10f), Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            }

            // The tuned channel, as a block the width of one channel rather than a bare line,
            // so you can see what the receiver is actually sitting on relative to its neighbours.
            val halfCh = (step / 2f / span) * plotW
            val cx = xOf(currentFreqKHz)
            if (skin.showsPassband) {
                drawPassband(cx, halfCh, fftHeight, MikuTeal)
            } else {
                drawRect(MikuTeal.copy(alpha = 0.16f), Offset(cx - halfCh, 0f), Size(halfCh * 2f, fftHeight))
            }

            if (hasBand && skin.showsSurface) {
                drawSurfaceWaterfall(bandHistory.take(14), fftHeight, waterfallHeight, w)
            } else if (hasBand) {
                // Band waterfall: each row is one sweep, x is frequency, newest at the top.
                // Capped: each row is one drawRect per channel, and sweeps arrive seconds apart,
                // so this redraws rarely; twelve rows is plenty of history to read.
                val rows = bandHistory.take(12)
                val rh = if (rows.isEmpty()) 0f else waterfallHeight / rows.size.coerceAtLeast(1)
                for (r in rows.indices) {
                    val prof = rows[r]
                    if (prof.isEmpty()) continue
                    val cw = plotW / prof.size
                    val y = fftHeight + r * rh
                    for (i in prof.indices) {
                        drawRect(FmWaterfallPalette.lut(prof[i]), Offset(gutter + i * cw, y), Size(cw + 0.5f, rh + 0.5f))
                    }
                }
                if (skin.showsTimestamps) {
                    drawWaterfallTimestamps(textMeasurer, rowTimes.take(rows.size), fftHeight, rh, MikuTeal)
                }
            } else {
                // Nothing swept yet: one column per poll of the tuned frequency's own RSSI, so
                // the panel still responds while you aim the wire.
                val cols = signalHistory.size
                if (cols > 1) {
                    val colW = w / cols
                    for (i in 0 until cols) {
                        val v = signalHistory[i].coerceIn(0f, 1f)
                        val barH = v * (waterfallHeight - 2f)
                        drawRect(FmWaterfallPalette.lut(v), Offset(w - (i + 1) * colW, fftHeight + (waterfallHeight - barH)), Size(colW + 0.5f, barH))
                    }
                }
            }

            val scaleTop = fftHeight + waterfallHeight
            drawRect(Color(0xFF020A0F), topLeft = Offset(0f, scaleTop), size = Size(w, scaleHeight))
            scanHits.forEach { f ->
                drawLine(MikuTeal.copy(alpha = 0.7f), Offset(xOf(f), scaleTop + scaleHeight * 0.45f),
                    Offset(xOf(f), scaleTop + scaleHeight), strokeWidth = 1.5.dp.toPx())
            }
            favorites.forEach { f ->
                drawLine(MikuPink.copy(alpha = 0.9f), Offset(xOf(f), scaleTop),
                    Offset(xOf(f), scaleTop + scaleHeight), strokeWidth = 1.5.dp.toPx())
            }
            // Catalogue ticks sit under the scan/favourite marks and are dimmer, so a station
            // the device has actually heard still reads louder than one that is merely listed.
            cataloguedStations.forEach { st ->
                val sx = xOf(st.khz)
                drawLine(MikuTeal.copy(alpha = 0.22f), Offset(sx, scaleTop + scaleHeight * 0.2f),
                    Offset(sx, scaleTop + scaleHeight), strokeWidth = 1f)
            }
            val tickStepMHz = if (span > 20000f) 4 else 2
            var mhzTick = ((band.lowKHz / 1000) / tickStepMHz + 1) * tickStepMHz
            while (mhzTick * 1000 <= band.highKHz) {
                val sx = xOf(mhzTick * 1000)
                drawLine(MikuTeal.copy(alpha = 0.3f), Offset(sx, scaleTop + scaleHeight * 0.55f),
                    Offset(sx, scaleTop + scaleHeight), strokeWidth = 1f)
                mhzTick += tickStepMHz
            }

            fftPath.reset(); fillPath.reset()
            var drew = false
            if (hasBand && !skin.showsSurface) {
                // Live trace of the newest sweep: RSSI against frequency, so peaks sit directly
                // above the frequency that produced them and can be tapped.
                val n = bandProfile.size
                val dx = plotW / (n - 1).coerceAtLeast(1)
                for (i in 0 until n) {
                    val y = fftHeight - (bandProfile[i].coerceIn(0f, 1f) * (fftHeight - 6f))
                    if (i == 0) fftPath.moveTo(gutter, y) else fftPath.lineTo(gutter + i * dx, y)
                }
                drew = n > 1
            } else if (!hasBand) {
                val n = signalHistory.size
                if (n > 1) {
                    val dx = plotW / (n - 1)
                    for (i in 0 until n) {
                        val x = w - i * dx
                        val y = fftHeight - (signalHistory[i].coerceIn(0f, 1f) * (fftHeight - 6f))
                        if (i == 0) fftPath.moveTo(x, y) else fftPath.lineTo(x, y)
                    }
                    drew = true
                }
            }
            if (drew) {
                fillPath.addPath(fftPath); fillPath.lineTo(w, fftHeight); fillPath.lineTo(gutter, fftHeight); fillPath.close()
                drawPath(fillPath, Brush.verticalGradient(listOf(Color(0x8800E5FF), Color(0x1100E5FF)), 0f, fftHeight))
                drawPath(fftPath, MikuTeal, style = Stroke(width = 2.dp.toPx()))
            }

            if (skin.showsAxis) {
                drawRssiAxis(textMeasurer, gutter, fftHeight, QualcommFmHardwareEngine.SWEEP_RSSI_FULL_SCALE, MikuTeal)
            }

            // The tuned frequency. Only meaningful on a frequency axis, so only drawn here.
            drawLine(MikuPink, Offset(cx, 0f), Offset(cx, h), strokeWidth = 2.dp.toPx())
        }

        // Call signs over the band, which is the difference between a spectrum and a dial you
        // can read. Capped hard and thinned by collision, better-scoring station first.
        if (cataloguedStations.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val bandWidth = maxWidth
                val placed = remember(cataloguedStations, band, bandWidth, skin) {
                    val out = ArrayList<Pair<FmStationCatalogue.Station, Dp>>()
                    val minGap = MIN_LABEL_GAP_DP
                    for (st in cataloguedStations) {
                        if (st.khz < band.lowKHz || st.khz > band.highKHz) continue
                        val x = bandWidth * ((st.khz - band.lowKHz).toFloat() / span)
                        if (out.any { kotlin.math.abs((it.second - x).value) < minGap }) continue
                        out.add(st to x)
                        if (out.size >= (if (skin.labelsEverything) MAX_BAND_LABELS * 3 else MAX_BAND_LABELS)) break
                    }
                    out
                }
                for ((st, x) in placed) {
                    val tuned = kotlin.math.abs(st.khz - currentFreqKHz) <= 60
                    Text(
                        st.call,
                        color = if (tuned) MikuPink else stationColor(st.call).copy(alpha = 0.7f),
                        fontSize = 6.5.sp,
                        fontWeight = if (tuned) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).offset(x = x - 13.dp, y = -1.dp)
                    )
                }
            }
        }

        Text(
            if (hasBand) "${skin.label} · RSSI vs FREQUENCY · TAP A PEAK TO TUNE"
            else "${skin.label} · ${band.lowKHz / 1000}–${band.highKHz / 1000} MHz · FIRST SWEEP…",
            color = MikuTeal.copy(alpha = 0.85f), fontSize = 7.5.sp, fontWeight = FontWeight.Bold,
            // The header doubles as the skin control. A dedicated row of buttons would eat
            // height this panel does not have, and the label already says which one is on.
            modifier = Modifier.clickable { onCycleSkin() }.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ============================================================================ record

@Composable
fun FmRecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
        if (isRecording) RecordPulse()
        Box(
            Modifier.size(44.dp)
                .then(
                    if (isRecording) Modifier.clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(Color(0xFFFF1744), Color(0xFFB71C1C))))
                        .border(1.5.dp, Color(0xFFFF5252), CircleShape)
                    else Modifier.glass(CircleShape, accent = Color(0xFFFF5252), accent2 = MikuTeal, rimAlpha = 0.5f)
                )
                .pressable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (isRecording) {
                Icon(Icons.Default.Stop, "Stop recording", tint = Color.White, modifier = Modifier.size(18.dp))
            } else {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFFFF1744)))
            }
        }
    }
}

/**
 * The pulsing ring, only composed while recording. An infinite transition keeps requesting
 * frames for as long as it exists, even animating 1 to 1, so it must not exist when idle; and
 * the scale is applied in a graphics layer so the pulse never re-lays-out or recomposes.
 */
@Composable
private fun RecordPulse() {
    val transition = rememberInfiniteTransition(label = "recPulse")
    val pulse = transition.animateFloat(
        initialValue = 1f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale"
    )
    Box(
        Modifier.size(42.dp)
            .graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }
            .clip(CircleShape)
            .background(Color(0xFFFF1744).copy(alpha = 0.35f))
            .border(1.5.dp, Color(0xFFFF1744), CircleShape)
    )
}
