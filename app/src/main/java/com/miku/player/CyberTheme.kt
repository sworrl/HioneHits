package com.miku.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Cyber Hatsune Miku Palette Tokens
val CyberDarkBg get() = Mascot.color(0xFF040D12)
val CyberGlassCard get() = Mascot.color(0xDD0A1E26)
val CyberGlassBorder get() = Mascot.color(0x3300E5FF)
val MikuCyan get() = Mascot.color(0xFF00E5FF)
val MikuNeonPink get() = Mascot.color(0xFFFF4081)
val MikuPurple get() = Mascot.color(0xFFB388FF)
val MikuTextPrimary get() = Mascot.color(0xFFE0F7FA)
val MikuTextSecondary get() = Mascot.color(0xFF80DEEA)
fun cyber24BitColorShift(phaseDeg: Float, saturation: Float = 0.88f, brightness: Float = 1.0f, alpha: Float = 1.0f): Color {
    val normHue = (phaseDeg % 360f + 360f) % 360f
    val hsv = floatArrayOf(normHue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    val argb = android.graphics.Color.HSVToColor((alpha.coerceIn(0f, 1f) * 255).toInt(), hsv)
    return Color(argb)
}
