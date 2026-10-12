package com.hione.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Canonical Hatsune Hione Signature Palette (#39C5BB + Obsidian Carbon + Diva Pink)
val HioneTeal get() = Mascot.color(0xFF39C5BB)
val HioneTealBright get() = Mascot.color(0xFF00F5D4)
val HioneTealGlow get() = Mascot.color(0xFF56F0E0)
val HionePurple get() = Mascot.color(0xFFB388FF)
val HionePurpleDeep = Color(0xFF7C4DFF)
val HionePink get() = Mascot.color(0xFFFF2A85)
val HionePinkBright get() = Mascot.color(0xFFFF4081)
val HioneGold = Color(0xFFFFD54F)
val HioneDarkBg get() = Mascot.color(0xFF070B0D)
val HioneSurface1 get() = Mascot.color(0xFF0D1417)
val HioneSurface2 get() = Mascot.color(0xFF131E22)
val HioneCardBg get() = Mascot.color(0xFF0E191D)
val HioneMuted = Color(0xFF7E9AA0)
val HioneWhite get() = Mascot.color(0xFFF0FDFE)

fun Modifier.hioneCard(
    borderColor: Color = HioneTeal.copy(alpha = 0.35f),
    cornerRadius: Int = 16
) = this
    .clip(RoundedCornerShape(cornerRadius.dp))
    .background(HioneCardBg)
    .border(1.dp, borderColor, RoundedCornerShape(cornerRadius.dp))

fun Modifier.hioneHeroCard(
    startColor: Color = Mascot.color(0xFF0D2529),
    endColor: Color = Mascot.color(0xFF071215)
) = this
    .clip(RoundedCornerShape(18.dp))
    .background(Brush.verticalGradient(listOf(startColor, endColor)))
    .border(1.2.dp, HioneTealBright.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
