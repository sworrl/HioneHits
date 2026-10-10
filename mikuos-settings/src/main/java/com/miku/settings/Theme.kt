package com.miku.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Canonical Hatsune Miku Signature Palette (#39C5BB + Obsidian Carbon + Diva Pink)
val MikuTeal get() = Mascot.color(0xFF39C5BB)
val MikuTealBright get() = Mascot.color(0xFF00F5D4)
val MikuTealGlow get() = Mascot.color(0xFF56F0E0)
val MikuPurple get() = Mascot.color(0xFFB388FF)
val MikuPurpleDeep = Color(0xFF7C4DFF)
val MikuPink get() = Mascot.color(0xFFFF2A85)
val MikuPinkBright get() = Mascot.color(0xFFFF4081)
val MikuGold = Color(0xFFFFD54F)
val MikuDarkBg get() = Mascot.color(0xFF070B0D)
val MikuSurface1 get() = Mascot.color(0xFF0D1417)
val MikuSurface2 get() = Mascot.color(0xFF131E22)
val MikuCardBg get() = Mascot.color(0xFF0E191D)
val MikuMuted = Color(0xFF7E9AA0)
val MikuWhite get() = Mascot.color(0xFFF0FDFE)

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
