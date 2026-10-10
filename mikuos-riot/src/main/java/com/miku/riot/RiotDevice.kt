package com.miku.riot

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Body = Color(0xFF30353D)
private val BodyDark = Color(0xFF1E2228)
private val KeyFace = Color(0xFF454B55)
private val KeyPressed = Color(0xFF2A2F36)
private val Label = Color(0xFFAEB5BF)

/**
 * Colors for the four LCD levels. Measured by eye off the TechTV footage: a pale mint panel
 * with dark gray-green ink when lit, a duller gray-green when the backlight is off. Contrast
 * (0 to 14, as on the Riot) moves the ink between faint and full.
 */
fun lcdPalette(lit: Boolean, contrast: Int, asleep: Boolean): IntArray {
    if (asleep) return IntArray(4) { 0xFF5E655C.toInt() }
    val bg = if (lit) 0xCFE2C6 else 0x9AA493
    val ink = 0x1C2820
    val strength = 0.40f + contrast.coerceIn(0, 14) / 14f * 0.60f
    fun mix(t: Float): Int {
        fun ch(a: Int, b: Int, sh: Int): Int {
            val x = (a shr sh) and 0xFF; val y = (b shr sh) and 0xFF
            return (x + ((y - x) * t)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(bg, ink, 16) shl 16) or (ch(bg, ink, 8) shl 8) or ch(bg, ink, 0)
    }
    return intArrayOf(mix(0f), mix(0.28f * strength), mix(0.6f * strength), mix(strength))
}

@Composable
fun RiotDevice(shell: RiotShell) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Body, BodyDark)))
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        // Whole-number scale only, so every LCD pixel is the same square of real pixels.
        val scale = maxOf(1, widthPx / Lcd.W)
        val lcdW = with(density) { (Lcd.W * scale).toDp() }
        val lcdH = with(density) { (Lcd.H * scale).toDp() }

        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Top edge: Menu at the corner, the On / Lock switch on the right.
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LabeledKey("MENU") {
                    HwButton(shell, Key.MENU, Modifier.size(38.dp), CircleShape) { p -> menuGlyph(p) }
                }
                Spacer(Modifier.weight(1f))
                HoldSwitch(shell)
            }
            LcdView(shell, scale, Modifier.size(lcdW, lcdH))
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { TransportPad(shell) }
                Column(
                    Modifier.width(118.dp).fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    LabeledKey("SELECT") {
                        HwButton(shell, Key.SELECT, Modifier.size(70.dp, 40.dp), RoundedCornerShape(20.dp)) { p -> ring(p) }
                    }
                    LabeledKey("SCROLL") { Wheel(shell, Modifier.size(92.dp, 190.dp)) }
                    LabeledKey("BACK") {
                        HwButton(shell, Key.BACK, Modifier.size(44.dp), CircleShape) { p -> backGlyph(p) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HwButton(shell, Key.VOL_DOWN, Modifier.size(54.dp, 30.dp), RoundedCornerShape(15.dp)) { p -> pmGlyph(p, false) }
                Spacer(Modifier.width(10.dp))
                HwButton(shell, Key.VOL_UP, Modifier.size(54.dp, 30.dp), RoundedCornerShape(15.dp)) { p -> pmGlyph(p, true) }
                Spacer(Modifier.width(8.dp))
                BasicText("VOLUME", style = TextStyle(color = Label, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                ExitPinhole(shell)
            }
        }
    }
}

@Composable
private fun LcdView(shell: RiotShell, scale: Int, modifier: Modifier) {
    val bmp = remember { Bitmap.createBitmap(Lcd.W, Lcd.H, Bitmap.Config.ARGB_8888) }
    val img = remember(bmp) { bmp.asImageBitmap() }
    val buf = remember { IntArray(Lcd.W * Lcd.H) }
    Canvas(modifier.pointerInput(Unit) {
        // Touching the glass only lights the backlight, the way any key did.
        awaitEachGesture { awaitFirstDown(); shell.touchLcd() }
    }) {
        @Suppress("UNUSED_VARIABLE") val f = shell.frame.intValue
        val pal = lcdPalette(shell.lit, shell.prefs.contrast, shell.sleeping)
        val px = shell.lcd.px
        for (i in buf.indices) buf[i] = pal[px[i].toInt()]
        bmp.setPixels(buf, 0, Lcd.W, 0, 0, Lcd.W, Lcd.H)
        drawImage(img, dstOffset = IntOffset.Zero, dstSize = IntSize(Lcd.W * scale, Lcd.H * scale), filterQuality = FilterQuality.None)
        // A faint grid between pixels: the dot-matrix texture of a 2002 panel up close.
        if (scale >= 3) {
            val grid = Color(0x14000000)
            for (x in 1 until Lcd.W) drawLine(grid, Offset(x * scale.toFloat(), 0f), Offset(x * scale.toFloat(), size.height), 1f)
            for (y in 1 until Lcd.H) drawLine(grid, Offset(0f, y * scale.toFloat()), Offset(size.width, y * scale.toFloat()), 1f)
        }
    }
}

@Composable
private fun LabeledKey(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Spacer(Modifier.height(3.dp))
        BasicText(label, style = TextStyle(color = Label, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
    }
}

/** A key that reports down and up separately, so the shell can tell a tap from a hold. */
@Composable
private fun HwButton(shell: RiotShell, key: Key, modifier: Modifier, shape: Shape, glyph: DrawScope.(Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    Box(
        modifier.clip(shape)
            .background(Brush.verticalGradient(if (pressed) listOf(KeyPressed, KeyPressed) else listOf(KeyFace, Color(0xFF353A42))))
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    shell.keyDown(key)
                    waitForUpOrCancellation()
                    pressed = false
                    shell.keyUp(key)
                }
            }
    ) { Canvas(Modifier.fillMaxSize()) { glyph(pressed) } }
}

/** The round four-way pad left of the screen: Play/Pause top, Stop bottom, Back and Forward. */
@Composable
private fun TransportPad(shell: RiotShell) {
    Box(Modifier.size(196.dp).clip(CircleShape).background(BodyDark)) {
        val k = 66.dp
        HwButton(shell, Key.PLAY, Modifier.size(k).align(Alignment.TopCenter).offset(y = 6.dp), CircleShape) { p -> playPauseGlyph(p) }
        HwButton(shell, Key.STOP, Modifier.size(k).align(Alignment.BottomCenter).offset(y = (-6).dp), CircleShape) { p -> stopGlyph(p) }
        HwButton(shell, Key.REW, Modifier.size(k).align(Alignment.CenterStart).offset(x = 6.dp), CircleShape) { p -> skipGlyph(p, false) }
        HwButton(shell, Key.FWD, Modifier.size(k).align(Alignment.CenterEnd).offset(x = (-6).dp), CircleShape) { p -> skipGlyph(p, true) }
    }
}

/**
 * The thumb wheel, seen face on: a dotted disc in a curved channel. Drag up or down; every
 * detent is one step of the Scroll wheel. It does not press in, just like the real one.
 */
@Composable
private fun Wheel(shell: RiotShell, modifier: Modifier) {
    var spin by remember { mutableFloatStateOf(0f) }
    val stepPx = with(LocalDensity.current) { 22.dp.toPx() }
    Canvas(
        modifier.clip(RoundedCornerShape(46.dp)).background(BodyDark)
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(onDragEnd = { acc = 0f }) { change, dy ->
                    change.consume()
                    acc += dy
                    spin += dy
                    while (acc >= stepPx) { shell.wheel(1); acc -= stepPx }
                    while (acc <= -stepPx) { shell.wheel(-1); acc += stepPx }
                }
            }
    ) {
        val r = size.width * 0.44f
        val c = Offset(size.width / 2, size.height / 2)
        // Disc edge-on: rows of dots that slide with the drag and fade toward the ends.
        drawRoundRect(Color(0xFF3B414A), Offset(c.x - r, 6f), Size(r * 2, size.height - 12f),
            androidx.compose.ui.geometry.CornerRadius(r, r))
        val gap = stepPx
        val shift = ((spin % gap) + gap) % gap
        var y = -gap + shift
        while (y < size.height + gap) {
            val d = (y - c.y) / (size.height / 2)
            val a = (1f - d * d).coerceIn(0f, 1f)
            if (a > 0.05f) {
                for (i in -1..1) drawCircle(Color(0xFF8B939E).copy(alpha = 0.25f + 0.6f * a), 3.5f, Offset(c.x + i * r * 0.45f, y))
            }
            y += gap
        }
    }
}

@Composable
private fun HoldSwitch(shell: RiotShell) {
    var locked by remember { mutableStateOf(shell.locked) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("ON", style = TextStyle(color = if (!locked) Color.White else Label, fontSize = 10.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(52.dp, 24.dp).clip(RoundedCornerShape(12.dp)).background(BodyDark)
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); locked = !locked; shell.locked = locked; waitForUpOrCancellation() } }
        ) {
            Box(
                Modifier.size(24.dp, 20.dp).align(if (locked) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp)).background(KeyFace)
            )
        }
        Spacer(Modifier.width(6.dp))
        BasicText("LOCK", style = TextStyle(color = if (locked) Color.White else Label, fontSize = 10.sp, fontWeight = FontWeight.Bold))
    }
}

/** The reset pinhole. Here it is the way out. */
@Composable
private fun ExitPinhole(shell: RiotShell) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation()?.let { shell.exitRequested() } } }
    ) {
        BasicText("EXIT RIOT MODE", style = TextStyle(color = Label, fontSize = 10.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(14.dp).clip(CircleShape).background(Color.Black))
    }
}

// ---- key glyphs ---------------------------------------------------------------------------------

private fun DrawScope.glyphColor(p: Boolean) = if (p) Color(0xFF8C939C) else Color(0xFFD5DAE0)

private fun DrawScope.playPauseGlyph(p: Boolean) {
    val c = glyphColor(p); val s = size.minDimension
    val cx = size.width / 2; val cy = size.height / 2
    val tri = Path().apply {
        moveTo(cx - s * 0.20f, cy - s * 0.13f); lineTo(cx - s * 0.20f, cy + s * 0.13f); lineTo(cx - s * 0.02f, cy); close()
    }
    drawPath(tri, c)
    drawRect(c, Offset(cx + s * 0.05f, cy - s * 0.13f), Size(s * 0.05f, s * 0.26f))
    drawRect(c, Offset(cx + s * 0.14f, cy - s * 0.13f), Size(s * 0.05f, s * 0.26f))
}

private fun DrawScope.stopGlyph(p: Boolean) {
    val s = size.minDimension * 0.24f
    drawRect(glyphColor(p), Offset(size.width / 2 - s / 2, size.height / 2 - s / 2), Size(s, s))
}

private fun DrawScope.skipGlyph(p: Boolean, fwd: Boolean) {
    val c = glyphColor(p); val s = size.minDimension
    val cx = size.width / 2; val cy = size.height / 2
    val dir = if (fwd) 1f else -1f
    for (k in 0..1) {
        val x0 = cx + dir * (-s * 0.17f + k * s * 0.14f)
        val tri = Path().apply {
            moveTo(x0, cy - s * 0.12f); lineTo(x0, cy + s * 0.12f); lineTo(x0 + dir * s * 0.14f, cy); close()
        }
        drawPath(tri, c)
    }
    drawRect(c, Offset(if (fwd) cx + s * 0.11f else cx - s * 0.15f, cy - s * 0.12f), Size(s * 0.04f, s * 0.24f))
}

private fun DrawScope.ring(p: Boolean) {
    val r = size.minDimension * 0.22f
    drawCircle(glyphColor(p), r, center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.35f))
    drawCircle(glyphColor(p), r * 0.3f, center)
}

private fun DrawScope.menuGlyph(p: Boolean) {
    val c = glyphColor(p); val w = size.width * 0.42f; val h = size.height * 0.07f
    for (i in -1..1) drawRect(c, Offset(center.x - w / 2, center.y + i * h * 2.4f - h / 2), Size(w, h))
}

private fun DrawScope.backGlyph(p: Boolean) {
    val c = glyphColor(p); val s = size.minDimension
    val path = Path().apply {
        moveTo(center.x, center.y - s * 0.2f); lineTo(center.x - s * 0.17f, center.y); lineTo(center.x + s * 0.17f, center.y); close()
    }
    drawPath(path, c)
    drawRect(c, Offset(center.x - s * 0.05f, center.y), Size(s * 0.1f, s * 0.18f))
}

private fun DrawScope.pmGlyph(p: Boolean, plus: Boolean) {
    val c = glyphColor(p); val w = size.height * 0.42f; val t = size.height * 0.1f
    drawRect(c, Offset(center.x - w / 2, center.y - t / 2), Size(w, t))
    if (plus) drawRect(c, Offset(center.x - t / 2, center.y - w / 2), Size(t, w))
}
