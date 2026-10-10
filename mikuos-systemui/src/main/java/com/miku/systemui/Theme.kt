package com.miku.systemui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val MikuTeal get() = Mascot.color(0xFF39C5BB)
val MikuTealBright get() = Mascot.color(0xFF00F5D4)
val MikuPink get() = Mascot.color(0xFFFF2A85)
val MikuPinkBright get() = Mascot.color(0xFFFF4081)
val MikuPurple get() = Mascot.color(0xFFB388FF)
val MikuGold = Color(0xFFFFD54F)
val MikuCyan get() = Mascot.color(0xFF00E5FF)
val MikuNeonPink get() = Mascot.color(0xFFFF4081)
val MikuTextPrimary get() = Mascot.color(0xFFE0F7FA)
val MikuTextSecondary get() = Mascot.color(0xFF80DEEA)
val MikuDarkBg get() = Mascot.color(0xFF040D12)
val MikuSurface1 get() = Mascot.color(0xFF071922)
val MikuSurface2 get() = Mascot.color(0xFF0E242C)
val MikuCardBg get() = Mascot.color(0xFF06141B)
val MikuMuted = Color(0xFF8BA6A9)
val MikuWhite get() = Mascot.color(0xFFF0FDFB)
val CyberGlassBorder get() = Mascot.color(0x3300E5FF)
val CyberGlassCard get() = Mascot.color(0xDD0A1E26)
val AudiowideFont = androidx.compose.ui.text.font.FontFamily.Monospace
val OrbitronFont = androidx.compose.ui.text.font.FontFamily.Monospace

fun Modifier.mikuTile(isActive: Boolean) = this
    .clip(RoundedCornerShape(14.dp))
    .background(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (isActive) 0.22f else 0.10f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.60f)
            )
        )
    )
    .padding(0.8.dp)
    .clip(RoundedCornerShape(13.dp))
    .background(
        if (isActive)
            Brush.verticalGradient(listOf(Mascot.color(0xFF0E3842), Mascot.color(0xFF082228), Mascot.color(0xFF030E12)))
        else
            Brush.verticalGradient(listOf(Mascot.color(0xFF0B2129), Mascot.color(0xFF05131A), Mascot.color(0xFF02090D)))
    )
    .border(
        1.1.dp,
        if (isActive)
            Brush.linearGradient(listOf(MikuTealBright, MikuPurple, MikuTealBright))
        else
            Brush.verticalGradient(listOf(CyberGlassBorder.copy(alpha = 0.5f), Color.Transparent, CyberGlassBorder.copy(alpha = 0.25f))),
        RoundedCornerShape(13.dp)
    )

fun Modifier.mikuCard(
    borderColor: Color = MikuTeal.copy(alpha = 0.35f),
    cornerRadius: Int = 16
) = this
    .clip(RoundedCornerShape(cornerRadius.dp))
    .background(MikuCardBg)
    .border(1.dp, borderColor, RoundedCornerShape(cornerRadius.dp))

fun Modifier.mikuHeroCard(
    startColor: Color = Mascot.color(0xFF0D2529),
    endColor: Color = Mascot.color(0xFF071215)
) = this
    .clip(RoundedCornerShape(18.dp))
    .background(Brush.verticalGradient(listOf(startColor, endColor)))
    .border(1.2.dp, MikuTealBright.copy(alpha = 0.5f), RoundedCornerShape(18.dp))

fun Modifier.mikuGlassCard() = this
    .clip(RoundedCornerShape(16.dp))
    .background(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.18f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.55f)
            )
        )
    )
    .padding(0.9.dp)
    .clip(RoundedCornerShape(15.dp))
    .background(
        Brush.verticalGradient(
            listOf(
                Mascot.color(0xEE0D2630),
                Mascot.color(0xFF07171E),
                Mascot.color(0xFA030B0F)
            )
        )
    )
    .border(
        1.1.dp,
        Brush.linearGradient(
            listOf(
                MikuTealBright.copy(alpha = 0.85f),
                MikuPurple.copy(alpha = 0.45f),
                MikuPinkBright.copy(alpha = 0.65f)
            )
        ),
        RoundedCornerShape(15.dp)
    )
