package com.miku.systemui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * READ-ONLY view of the unlocks earned in the BPM rhythm game (the launcher's
 * com.miku.launcher.bpm.MikuUnlocks is the only writer). Same contract as Miku Music's reader:
 *
 *     Settings.Global miku_unlocks = {"<id>": {"at": <ms>, "src": "bpm", "off": true|absent}}
 *
 * "Enabled" = earned and not switched off from the game's Secrets list. Fails CLOSED: any read
 * or parse error means nothing is enabled, so a broken store can never hand a reward out.
 * Ids are copied verbatim and must never be renamed on either side.
 */
object MikuUnlocksReader {
    private const val SETTINGS_KEY = "miku_unlocks"

    /** Secret: the battery is drawn as a leek (launcher top bar + this shade's battery chip). */
    const val SECRET_NEGI_BATTERY = "secret.os.battery.negi"

    fun isEnabled(ctx: Context, id: String): Boolean = try {
        val raw = Settings.Global.getString(ctx.contentResolver, SETTINGS_KEY)
        if (raw.isNullOrBlank()) false else {
            val e = JSONObject(raw).optJSONObject(id)
            e != null && !e.optBoolean("off", false)
        }
    } catch (_: Throwable) { false }
}

/**
 * The leek battery, same drawing as the launcher's top bar so the two surfaces agree: bulb and
 * roots on the left, the stalk fills white-to-green with charge, leaves fan out at the end.
 * pct < 0 (unknown) draws an empty stalk, never a flat battery.
 */
@Composable
fun MikuNegiBatteryGlyph(pct: Int, charging: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 26.dp, height = 12.dp)) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val bulbR = h * 0.36f
        val bulbX = bulbR + 1f
        for (k in -1..1) {
            drawLine(Color(0xFFE8E2D0), Offset(bulbX - bulbR * 0.6f, cy + k * 2.2f), Offset(0f, cy + k * 3.4f), strokeWidth = 1f)
        }
        val stalkEnd = w * 0.72f
        val stalkH = h * 0.42f
        val r = CornerRadius(stalkH / 2f, stalkH / 2f)
        drawRoundRect(Color(0x55FFFFFF), Offset(bulbX, cy - stalkH / 2f), Size(stalkEnd - bulbX, stalkH), r, style = Stroke(width = 1f))
        val frac = if (pct < 0) 0f else (pct / 100f).coerceIn(0f, 1f)
        if (frac > 0f) {
            val fill = when {
                charging -> Color(0xFF7CFF6B)
                pct <= 20 -> Color(0xFFFF6B6B)
                else -> Color(0xFF8BE36A)
            }
            drawRoundRect(fill, Offset(bulbX, cy - stalkH / 2f), Size((stalkEnd - bulbX) * frac, stalkH), r)
        }
        drawCircle(Color(0xFFF5F2E6), bulbR, Offset(bulbX, cy))
        drawLine(Color(0xFF3FAF4A), Offset(stalkEnd, cy), Offset(w, cy - h * 0.42f), strokeWidth = 2.2f, cap = StrokeCap.Round)
        drawLine(Color(0xFF3FAF4A), Offset(stalkEnd, cy), Offset(w, cy), strokeWidth = 2.2f, cap = StrokeCap.Round)
        drawLine(Color(0xFF2E8B3A), Offset(stalkEnd, cy), Offset(w - 2f, cy + h * 0.40f), strokeWidth = 2.2f, cap = StrokeCap.Round)
    }
}
