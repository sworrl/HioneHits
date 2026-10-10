package com.miku.riot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * A 1-bit framebuffer at the emulated LCD's own resolution.
 *
 * Every screen is drawn here in LCD pixels, then shown scaled up by a whole number with no
 * filtering, so a pixel on the fake LCD is always an exact square of real pixels. That is what
 * makes it read as a 2002 dot-matrix panel instead of a phone UI wearing a blue tint.
 */
class LcdCanvas(val w: Int, val h: Int) {
    val px = ByteArray(w * h)

    /** Clip window; text and fills never escape it. */
    private var cx0 = 0; private var cy0 = 0; private var cx1 = w; private var cy1 = h

    fun clear() = px.fill(0)

    fun clip(x0: Int, y0: Int, x1: Int, y1: Int) {
        cx0 = x0.coerceAtLeast(0); cy0 = y0.coerceAtLeast(0)
        cx1 = x1.coerceAtMost(w); cy1 = y1.coerceAtMost(h)
    }
    fun unclip() { cx0 = 0; cy0 = 0; cx1 = w; cy1 = h }

    fun set(x: Int, y: Int, on: Boolean = true) {
        if (x < cx0 || y < cy0 || x >= cx1 || y >= cy1) return
        px[y * w + x] = if (on) 1 else 0
    }
    fun flip(x: Int, y: Int) {
        if (x < cx0 || y < cy0 || x >= cx1 || y >= cy1) return
        val i = y * w + x
        px[i] = (1 - px[i]).toByte()
    }
    fun get(x: Int, y: Int): Boolean = x in 0 until w && y in 0 until h && px[y * w + x].toInt() == 1

    fun hline(x0: Int, x1: Int, y: Int, on: Boolean = true) { for (x in x0..x1) set(x, y, on) }
    fun vline(x: Int, y0: Int, y1: Int, on: Boolean = true) { for (y in y0..y1) set(x, y, on) }
    fun rect(x: Int, y: Int, rw: Int, rh: Int) {
        hline(x, x + rw - 1, y); hline(x, x + rw - 1, y + rh - 1)
        vline(x, y, y + rh - 1); vline(x + rw - 1, y, y + rh - 1)
    }
    fun fill(x: Int, y: Int, rw: Int, rh: Int, on: Boolean = true) {
        for (yy in y until y + rh) for (xx in x until x + rw) set(xx, yy, on)
    }
    /** XOR a block: how the highlight bar inverts the row under it. */
    fun invert(x: Int, y: Int, rw: Int, rh: Int) {
        for (yy in y until y + rh) for (xx in x until x + rw) flip(xx, yy)
    }
    /** 50% checker, the way old LCD firmware drew "disabled" and the unlit scrollbar track. */
    fun dither(x: Int, y: Int, rw: Int, rh: Int) {
        for (yy in y until y + rh) for (xx in x until x + rw) if ((xx + yy) % 2 == 0) set(xx, yy)
    }

    /** A sprite given as rows of '#' and '.'. */
    fun sprite(x: Int, y: Int, rows: Array<String>, scale: Int = 1) {
        rows.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                if (ch == '#') fill(x + c * scale, y + r * scale, scale, scale)
            }
        }
    }

    // ---- text ---------------------------------------------------------------------------

    private val fallbackPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        color = Color.WHITE
        typeface = Typeface.DEFAULT
        textSize = 10f
    }
    private val scratch = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888)
    private val scratchCanvas = Canvas(scratch)
    private val scratchPx = IntArray(24 * 14)

    fun charWidth(c0: Char, scale: Int = 1): Int {
        val c = PixelFont.fold(c0)
        val a = PixelFont.advance(c)
        if (a > 0) return a * scale
        return (fallbackPaint.measureText(c.toString()).toInt().coerceIn(3, 12) + 1) * scale
    }

    fun textWidth(s: String, scale: Int = 1): Int {
        var n = 0
        for (c in s) n += charWidth(c, scale)
        return (n - scale).coerceAtLeast(0)
    }

    /** Draws [s] with its top-left at x,y. Returns the x after the last glyph. */
    fun text(x: Int, y: Int, s: String, scale: Int = 1, on: Boolean = true): Int {
        var pen = x
        for (c0 in s) {
            if (pen >= cx1) break
            val c = PixelFont.fold(c0)
            val g = PixelFont.glyph(c)
            if (g != null) {
                for (r in 0 until PixelFont.HEIGHT) {
                    val bits = g.rows[r]
                    if (bits == 0) continue
                    for (col in 0 until g.width) if (bits and (1 shl col) != 0) {
                        if (scale == 1) set(pen + col, y + r, on)
                        else fill(pen + col * scale, y + r * scale, scale, scale, on)
                    }
                }
                pen += (g.width + 1) * scale
            } else {
                pen += drawFallback(pen, y, c, scale, on)
            }
        }
        return pen
    }

    /**
     * Anything outside the hand-drawn table goes through the system font with aliasing off and
     * then through a hard threshold, so kana and kanji land on the same pixel grid as everything
     * else. They are small and rough, which is honest: the real thing could not show them at all.
     */
    private fun drawFallback(x: Int, y: Int, c: Char, scale: Int, on: Boolean): Int {
        scratch.eraseColor(Color.TRANSPARENT)
        scratchCanvas.drawText(c.toString(), 0f, 9f, fallbackPaint)
        val cw = fallbackPaint.measureText(c.toString()).toInt().coerceIn(3, 12)
        scratch.getPixels(scratchPx, 0, 24, 0, 0, 24, 14)
        for (r in 0 until 11) for (col in 0 until cw) {
            val a = (scratchPx[r * 24 + col] ushr 24) and 0xFF
            if (a > 127) {
                if (scale == 1) set(x + col, y - 1 + r, on)
                else fill(x + col * scale, y + (r - 1) * scale, scale, scale, on)
            }
        }
        return (cw + 1) * scale
    }

    /** Text cut to fit [maxW] with a trailing "..". */
    fun fitted(s: String, maxW: Int, scale: Int = 1): String {
        if (textWidth(s, scale) <= maxW) return s
        val dots = textWidth("..", scale) + scale
        var n = 0; var used = 0
        while (n < s.length) {
            val cw = charWidth(s[n], scale)
            if (used + cw > maxW - dots) break
            used += cw; n++
        }
        return s.substring(0, n).trimEnd() + ".."
    }

    fun textRight(xRight: Int, y: Int, s: String, scale: Int = 1, on: Boolean = true) =
        text(xRight - textWidth(s, scale), y, s, scale, on)

    fun textCenter(xc: Int, y: Int, s: String, scale: Int = 1, on: Boolean = true) =
        text(xc - textWidth(s, scale) / 2, y, s, scale, on)

    /**
     * A line that scrolls sideways when it does not fit, the way the old players crawled long
     * titles. [tick] is a slow counter from the caller; the text pauses at each end.
     */
    fun marquee(x: Int, y: Int, maxW: Int, s: String, tick: Long, scale: Int = 1, on: Boolean = true) {
        val tw = textWidth(s, scale)
        val saved = intArrayOf(cx0, cy0, cx1, cy1)
        clip(x, y - 1, x + maxW, y + PixelFont.HEIGHT * scale + 1)
        if (tw <= maxW) {
            text(x, y, s, scale, on)
        } else {
            val travel = tw - maxW
            val pause = 8
            val cycle = pause + travel + pause + travel
            val t = (tick % cycle).toInt()
            val off = when {
                t < pause -> 0
                t < pause + travel -> t - pause
                t < pause + travel + pause -> travel
                else -> travel - (t - pause - travel - pause)
            }
            text(x - off, y, s, scale, on)
        }
        cx0 = saved[0]; cy0 = saved[1]; cx1 = saved[2]; cy1 = saved[3]
    }
}
