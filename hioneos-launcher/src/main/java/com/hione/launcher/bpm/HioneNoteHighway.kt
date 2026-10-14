package com.hione.launcher.bpm

import com.hione.launcher.Mascot
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Vertical Multi-Lane Note Highway (Top to Bottom).
 *
 * Supports 1x up to 6x simultaneous noteways (and hidden 8x / 10x past Level 1000)
 * with full two-thumb multitouch support.
 *
 * HOW IT WORKS:
 * - Notes travel from TOP (Y = 0) to BOTTOM, arriving at the hit line (Y = hitY).
 * - Multi-lane distribution: notes are rhythmically assigned across lanes. On downbeats,
 *   chords spawn across both halves so players hit notes simultaneously with two thumbs!
 * - Two-thumb Multitouch: pointerInput detects independent touch downs across all lanes.
 * - Reactive skin styling: note silhouettes, vertical scrolling textures, and hit effects.
 */
@Composable
fun HioneNoteHighway(
    lastPulseEpochMs: Long,
    beatIntervalMs: Long,
    calibrationMs: Int,
    windows: HioneRhythmTiming.Windows,
    lastTapMs: Long,
    lastOffsetMs: Int,
    accent: Color,
    accent2: Color = accent,
    laneHeight: androidx.compose.ui.unit.Dp = 220.dp,
    hitFraction: Float = 0.82f,
    laneCount: Int = 1,
    lastTappedLane: Int = 0,
    onTapLane: ((laneIndex: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    skin: HioneBeatClickerEngine.BpmSkin = HioneBeatClickerEngine.BpmSkin.SAKURA_DREAM,
    fever: Boolean = false,
    chaos: HioneStagePerformance.Chaos = HioneStagePerformance.Chaos.NONE,
    comboRung: Int = 0,
    lastAccuracy: HitAccuracy? = null,
    content: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null
) {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val sparkAge = remember { mutableFloatStateOf(1f) }
    val lastSeenTap = remember { mutableLongStateOf(0L) }
    val sparkLane = remember { mutableIntStateOf(0) }

    val notePath = remember(skin.nodeShape) { unitShapePath(skin.nodeShape) }
    val hexPath = remember { unitShapePath(HioneBeatClickerEngine.NodeShape.HEX) }

    LaunchedEffect(Unit) {
        while (true) {
            awaitFrame()
            now.longValue = System.currentTimeMillis()
            if (lastSeenTap.longValue != lastTapMs) {
                lastSeenTap.longValue = lastTapMs
                sparkAge.floatValue = 0f
                sparkLane.intValue = lastTappedLane.coerceIn(0, (laneCount - 1).coerceAtLeast(0))
            }
            if (sparkAge.floatValue < 1f) {
                sparkAge.floatValue = (sparkAge.floatValue + 0.05f).coerceAtMost(1f)
            }
        }
    }

    val activeLanes = laneCount.coerceIn(1, 10)

    Box(
        modifier
            .fillMaxWidth()
            .height(laneHeight)
            .pointerInput(activeLanes, onTapLane) {
                if (onTapLane != null) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                if (change.changedToDown()) {
                                    val laneW = size.width.toFloat() / activeLanes
                                    val lane = (change.position.x / laneW).toInt().coerceIn(0, activeLanes - 1)
                                    onTapLane(lane)
                                    change.consume()
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Canvas(Modifier.fillMaxWidth().height(laneHeight)) {
            val w = size.width
            val h = size.height

            // Hit line sits near bottom (Y = h * hitFraction)
            val hitY = h * hitFraction
            val period = beatIntervalMs.toFloat()
            val live = lastPulseEpochMs > 0L && beatIntervalMs in 60..3000

            val runway = hitY
            val speed = if (chaos == HioneStagePerformance.Chaos.DOUBLE_TIME) 0.5f else 1f
            val lookaheadMs = (period * 2.2f).coerceIn(700f, 2400f) * speed
            val pxPerMs = runway / lookaheadMs

            val t = now.longValue - calibrationMs
            val sinceLast = (t - lastPulseEpochMs).toFloat()
            val nextIdx = kotlin.math.ceil(sinceLast / period).toInt()

            val phase = (sinceLast.mod(period)) / period
            val beat = (1f - phase).coerceIn(0f, 1f).let { it * it }

            // Vertical background wash
            drawVerticalLane(w, h, hitY, accent, accent2, fever)

            // Texture scrolling downwards
            val scrollPx = (t.mod(60_000L)).toFloat() * pxPerMs
            drawVerticalLaneTexture(skin.laneStyle, w, h, scrollPx, beat, accent, accent2, hexPath)

            // Multiple lane dividers
            val laneW = w / activeLanes
            for (k in 1 until activeLanes) {
                val divX = k * laneW
                val divAlpha = 0.20f + 0.05f * comboRung.coerceIn(0, 6) + 0.15f * beat
                drawLine(
                    accent.copy(alpha = divAlpha),
                    Offset(divX, 0f),
                    Offset(divX, h),
                    strokeWidth = 1.2f
                )
            }

            // Window timing bands around hit line
            drawVerticalWindowBands(w, hitY, pxPerMs, windows, accent, accent2)

            // Receptors on the hit line for each lane
            for (k in 0 until activeLanes) {
                val receptorX = (k + 0.5f) * laneW
                drawReceptor(receptorX, hitY, laneW * 0.38f, accent, accent2, beat, fever, k == sparkLane.intValue && sparkAge.floatValue < 0.3f)
            }

            // Hit line across all lanes
            drawVerticalHitLine(w, hitY, if (fever) Color.White else accent, phase)

            if (!live) return@Canvas

            // Notes flowing downwards
            for (i in (nextIdx - 1)..(nextIdx + 12)) {
                val beatAt = lastPulseEpochMs + i.toLong() * beatIntervalMs
                val msAway = (beatAt - t).toFloat()
                if (msAway > lookaheadMs) break
                val y = hitY - msAway * pxPerMs
                if (y < -40f || y > h + 40f) continue

                val strong = (i.mod(4)) == 0
                val lucky = HioneRhythmTiming.isLuckyBeat(beatAt, beatIntervalMs)
                val noteColor = when {
                    lucky -> LUCKY_GOLD
                    fever -> Color.hsv(((i * 47f + t / 9f) % 360f + 360f) % 360f, 0.75f, 1f)
                    else -> lerpColor(accent, accent2, (i.mod(8)) / 8f)
                }

                val fog = if (chaos.hidesBeat) ((msAway / lookaheadMs - 0.35f) / 0.25f).coerceIn(0f, 1f) else 1f
                if (fog <= 0f) continue

                // Which lanes this beat appears in (supports dual chords for thumbs)
                val targetLanes = noteLanesForBeat(i, activeLanes)
                for (lane in targetLanes) {
                    val noteX = (lane + 0.5f) * laneW
                    drawVerticalNote(
                        noteX, y, laneW * 0.42f, msAway, lookaheadMs,
                        strong, noteColor, notePath, skin.nodeShape, fog, lucky, t
                    )
                }
            }

            // Hit spark in the tapped lane
            val age = sparkAge.floatValue
            if (lastTapMs > 0L && age < 1f) {
                val targetLane = sparkLane.intValue.coerceIn(0, activeLanes - 1)
                val sparkX = (targetLane + 0.5f) * laneW
                val sparkY = hitY - lastOffsetMs * pxPerMs
                val a = 1f - age
                val c = sparkColor(lastAccuracy, accent)

                drawCircle(c.copy(alpha = 0.6f * a), radius = (laneW * 0.35f) * (0.8f + 0.5f * age), center = Offset(sparkX, sparkY))
                drawLine(c.copy(alpha = a), Offset(sparkX - laneW * 0.4f, sparkY), Offset(sparkX + laneW * 0.4f, sparkY), strokeWidth = 2.5f)

                if (lastAccuracy?.isGreatOrBetter == true) {
                    drawCircle(
                        c.copy(alpha = 0.4f * a),
                        radius = (laneW * 0.45f) * (1f + 0.8f * age),
                        center = Offset(sparkX, sparkY),
                        style = Stroke(width = 2f)
                    )
                }
                if (lastAccuracy == HitAccuracy.PERFECT) {
                    val r1 = laneW * (0.25f + 0.2f * age)
                    val r2 = r1 + laneW * 0.35f * a
                    for (k in 0 until 8) {
                        val ang = k * 0.785398f + age
                        drawLine(
                            Color.White.copy(alpha = 0.85f * a),
                            Offset(sparkX + cos(ang) * r1, sparkY + sin(ang) * r1),
                            Offset(sparkX + cos(ang) * r2, sparkY + sin(ang) * r2),
                            strokeWidth = 2f
                        )
                    }
                }
            }
        }

        // Overlay content (e.g. central beat node for 1-lane mode)
        if (content != null && activeLanes == 1) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(laneHeight)
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        val x = (constraints.maxWidth - p.width) / 2
                        val y = (constraints.maxHeight * hitFraction).toInt() - p.height / 2
                        layout(constraints.maxWidth, constraints.maxHeight) { p.place(x, y) }
                    },
                contentAlignment = androidx.compose.ui.Alignment.Center,
                content = content
            )
        }
    }
}

/** Determines which lane(s) a beat appears in. Downbeats generate 2-thumb chords! */
fun noteLanesForBeat(beatIdx: Int, laneCount: Int): List<Int> {
    if (laneCount <= 1) return listOf(0)
    if (laneCount == 2) {
        if (beatIdx.mod(4) == 0) return listOf(0, 1) // Simultaneous 2-thumb downbeat chord!
        return listOf(beatIdx.mod(2))
    }
    // 3 to 10 lanes:
    if (beatIdx.mod(4) == 0) {
        // 2 simultaneous notes for two thumbs on downbeats
        val half = laneCount / 2
        val left = abs(beatIdx * 2) % half
        val right = half + (abs(beatIdx * 2 + 1) % (laneCount - half))
        return listOf(left, right)
    }
    val primary = abs(beatIdx * 3 + (beatIdx / 4)) % laneCount
    return listOf(primary)
}

private val LUCKY_GOLD = Color(0xFFFFD700)

private fun sparkColor(a: HitAccuracy?, accent: Color): Color = when (a) {
    HitAccuracy.PERFECT -> Mascot.color(0xFFFF3385)
    HitAccuracy.GREAT -> accent
    HitAccuracy.GOOD -> Mascot.color(0xFF66E0D8)
    HitAccuracy.OK -> Color(0xFFFFD166)
    HitAccuracy.MISS -> Color(0xFFFF4D5E)
    null -> accent
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f
)

internal fun unitShapePath(shape: HioneBeatClickerEngine.NodeShape): Path {
    val p = Path()
    fun poly(sides: Int, rot: Double) {
        for (i in 0 until sides) {
            val a = Math.toRadians(rot + i * 360.0 / sides)
            val x = cos(a).toFloat(); val y = sin(a).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }
    fun star(points: Int, inner: Float) {
        val steps = points * 2
        for (i in 0 until steps) {
            val r = if (i % 2 == 0) 1f else inner
            val a = Math.toRadians(-90.0 + i * 360.0 / steps)
            val x = (r * cos(a)).toFloat(); val y = (r * sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }
    when (shape) {
        HioneBeatClickerEngine.NodeShape.ORB -> p.addOval(androidx.compose.ui.geometry.Rect(-1f, -1f, 1f, 1f))
        HioneBeatClickerEngine.NodeShape.DIAMOND -> poly(4, -90.0)
        HioneBeatClickerEngine.NodeShape.HEX -> poly(6, -90.0)
        HioneBeatClickerEngine.NodeShape.STAR -> star(5, 0.46f)
        HioneBeatClickerEngine.NodeShape.SNOWFLAKE -> star(6, 0.55f)
        HioneBeatClickerEngine.NodeShape.HEART -> {
            p.moveTo(0f, 0.85f)
            p.cubicTo(-1.25f, 0.05f, -0.75f, -1.05f, 0f, -0.45f)
            p.cubicTo(0.75f, -1.05f, 1.25f, 0.05f, 0f, 0.85f)
            p.close()
        }
    }
    return p
}

private fun DrawScope.drawVerticalLane(w: Float, h: Float, hitY: Float, accent: Color, accent2: Color, fever: Boolean) {
    val lift = if (fever) 0.12f else 0f
    drawRect(
        brush = Brush.verticalGradient(
            listOf(
                accent2.copy(alpha = 0.04f + lift),
                accent.copy(alpha = 0.12f + lift),
                accent2.copy(alpha = 0.22f + lift),
                accent.copy(alpha = 0.32f + lift)
            )
        ),
        size = Size(w, h)
    )
}

private fun DrawScope.drawVerticalLaneTexture(
    style: HioneBeatClickerEngine.LaneStyle,
    w: Float, h: Float, scrollPx: Float, beat: Float,
    accent: Color, accent2: Color, hexPath: Path
) {
    when (style) {
        HioneBeatClickerEngine.LaneStyle.PETAL_STREAM -> {
            val spacing = h / 7f
            for (i in 0 until 9) {
                val y = ((i * spacing + scrollPx) % h + h) % h
                val x = w * (0.2f + 0.6f * (((i * 37) % 10) / 10f)) + sin((y / h) * 6.28f + i) * w * 0.04f
                drawCircle(accent.copy(alpha = 0.16f), radius = 6f, center = Offset(x, y))
                drawCircle(accent2.copy(alpha = 0.10f), radius = 9f, center = Offset(x + 5f, y))
            }
        }
        HioneBeatClickerEngine.LaneStyle.NEON_GRID -> {
            val spacing = h * 0.18f
            val n = (h / spacing).toInt() + 2
            val off = ((scrollPx % spacing) + spacing) % spacing
            for (i in 0..n) {
                val y = i * spacing + off
                drawLine(accent.copy(alpha = 0.10f + 0.12f * beat), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
        }
        HioneBeatClickerEngine.LaneStyle.HONEYCOMB -> {
            val r = 16f
            val spacing = r * 2.2f
            val n = (h / spacing).toInt() + 2
            val off = ((scrollPx % spacing) + spacing) % spacing
            for (i in 0..n) {
                val y = i * spacing + off
                val x = (w * 0.5f) + sin(i * 1.5f) * (w * 0.3f)
                translate(x, y) {
                    scale(r, r, pivot = Offset.Zero) {
                        drawPath(hexPath, accent.copy(alpha = 0.08f + 0.06f * beat))
                    }
                }
            }
        }
        HioneBeatClickerEngine.LaneStyle.VELVET_LACE -> {
            drawRect(accent2.copy(alpha = 0.08f * beat), size = Size(w, h))
            val spacing = h * 0.12f
            val n = (h / spacing).toInt() + 2
            val off = ((scrollPx % spacing) + spacing) % spacing
            for (i in 0..n) {
                val y = i * spacing + off
                val big = i % 2 == 0
                drawCircle(accent.copy(alpha = if (big) 0.28f else 0.15f), radius = if (big) 3.5f else 2f, center = Offset(w * 0.1f, y))
                drawCircle(accent.copy(alpha = if (big) 0.28f else 0.15f), radius = if (big) 3.5f else 2f, center = Offset(w * 0.9f, y))
            }
        }
        HioneBeatClickerEngine.LaneStyle.FROST_STREAK -> {
            val spacing = h / 6f
            for (i in 0 until 8) {
                val y = ((i * spacing + scrollPx) % h + h) % h
                val x = w * (0.2f + 0.15f * (i % 4))
                drawLine(
                    Color.White.copy(alpha = 0.12f + 0.08f * beat),
                    Offset(x, y), Offset(x + w * 0.15f, y + h * 0.1f),
                    strokeWidth = 1.2f
                )
            }
        }
        HioneBeatClickerEngine.LaneStyle.HOLO_SCAN -> {
            val band = h * 0.10f
            val roll = ((scrollPx * 0.2f) % (band * 2f) + band * 2f) % (band * 2f)
            var y = -band * 2f + roll
            while (y < h) {
                drawRect(accent.copy(alpha = 0.06f), topLeft = Offset(0f, y), size = Size(w, band))
                y += band * 2f
            }
        }
    }
}

private fun DrawScope.drawReceptor(
    x: Float, hitY: Float, radius: Float,
    accent: Color, accent2: Color, beat: Float, fever: Boolean, tapped: Boolean
) {
    val r = radius * (if (tapped) 1.15f else 1.0f)
    val alpha = if (tapped) 0.85f else (0.25f + 0.25f * beat)

    // Outer glow ring
    drawCircle(
        brush = Brush.radialGradient(
            listOf(accent.copy(alpha = alpha), Color.Transparent),
            center = Offset(x, hitY),
            radius = r * 1.5f
        ),
        radius = r * 1.5f,
        center = Offset(x, hitY)
    )

    // Receptor base
    drawCircle(
        color = Color(0x3310121A),
        radius = r,
        center = Offset(x, hitY)
    )

    // Target ring
    drawCircle(
        color = if (fever) Color.White else accent.copy(alpha = alpha),
        radius = r,
        center = Offset(x, hitY),
        style = Stroke(width = if (tapped) 3f else 2f)
    )

    // Inner pip
    drawCircle(
        color = (if (fever) Color.White else accent2).copy(alpha = 0.4f + 0.4f * beat),
        radius = r * 0.35f,
        center = Offset(x, hitY)
    )
}

private fun DrawScope.drawVerticalHitLine(w: Float, hitY: Float, c: Color, phase: Float) {
    val beat = (1f - phase).coerceIn(0f, 1f).let { it * it }
    drawLine(
        c.copy(alpha = 0.55f + 0.45f * beat),
        Offset(0f, hitY),
        Offset(w, hitY),
        strokeWidth = 3f + 1.5f * beat
    )
}

private fun DrawScope.drawVerticalWindowBands(
    w: Float, hitY: Float, pxPerMs: Float,
    windows: HioneRhythmTiming.Windows, accent: Color, accent2: Color
) {
    bandV(w, hitY, windows.okMs * pxPerMs, accent2.copy(alpha = 0.05f))
    bandV(w, hitY, windows.goodMs * pxPerMs, accent2.copy(alpha = 0.10f))
    bandV(w, hitY, windows.greatMs * pxPerMs, accent.copy(alpha = 0.18f))
    bandV(w, hitY, windows.perfectMs * pxPerMs, accent.copy(alpha = 0.30f))
}

private fun DrawScope.bandV(w: Float, hitY: Float, halfPx: Float, color: Color) {
    if (halfPx <= 0.5f) return
    drawRect(color = color, topLeft = Offset(0f, hitY - halfPx), size = Size(w, halfPx * 2f))
}

private fun DrawScope.drawVerticalNote(
    x: Float, y: Float, maxRadius: Float, msAway: Float, lookaheadMs: Float,
    strong: Boolean, color: Color, unitPath: Path,
    shape: HioneBeatClickerEngine.NodeShape, fog: Float, lucky: Boolean, t: Long
) {
    val near = (1f - (msAway / lookaheadMs)).coerceIn(0f, 1f)
    val past = msAway < 0f
    val fade = (if (past) (1f + msAway / 260f).coerceIn(0f, 1f) else 1f) * fog
    if (fade <= 0f) return

    val baseR = maxRadius * (if (strong || lucky) 0.65f else 0.50f)
    val r = baseR * (0.6f + 0.4f * near)
    val alpha = (0.35f + 0.65f * near) * fade

    // Downward trail behind the note
    if (!past && near > 0.15f) {
        drawLine(
            color.copy(alpha = 0.22f * alpha),
            Offset(x, y - r * 2.2f),
            Offset(x, y),
            strokeWidth = max(2f, r * 0.6f)
        )
    }

    // Outer glow
    drawCircle(color.copy(alpha = 0.25f * alpha), radius = r * 1.6f, center = Offset(x, y))

    // Shape body
    if (shape == HioneBeatClickerEngine.NodeShape.ORB) {
        drawCircle(color.copy(alpha = alpha), radius = r, center = Offset(x, y))
    } else {
        translate(x, y) {
            scale(r * 1.15f, r * 1.15f, pivot = Offset.Zero) {
                drawPath(unitPath, color.copy(alpha = alpha))
            }
        }
    }

    // Specular center dot
    drawCircle(Color.White.copy(alpha = 0.75f * alpha * near), radius = r * 0.35f, center = Offset(x, y))

    if (strong && !lucky) {
        drawCircle(color.copy(alpha = 0.6f * alpha), radius = r * 1.3f, center = Offset(x, y), style = Stroke(width = 1.5f))
    }

    if (lucky) {
        val spin = (t % 2000L) / 2000f * 6.283f
        val r1 = r * 1.3f
        val r2 = r * 2.1f
        for (k in 0 until 4) {
            val a = spin + k * 1.5708f
            drawLine(
                Color.White.copy(alpha = 0.85f * alpha),
                Offset(x + cos(a) * r1, y + sin(a) * r1),
                Offset(x + cos(a) * r2, y + sin(a) * r2),
                strokeWidth = 2f
            )
        }
        drawCircle(LUCKY_GOLD.copy(alpha = 0.6f * alpha), radius = r * 1.5f, center = Offset(x, y), style = Stroke(width = 2f))
    }
}
