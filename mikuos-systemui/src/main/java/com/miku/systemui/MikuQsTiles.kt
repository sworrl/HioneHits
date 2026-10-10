package com.miku.systemui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Quick-settings tiles as glass buttons. Three states, readable at a glance on a 4" screen:
 *   on           accent-filled lit glass, dark ink, neon halo
 *   off          frosted dark glass, light ink, refraction rim only
 *   unavailable  dimmed glass, muted ink, no halo; a tap still tries (the codec may answer now)
 * Press: sinks and squashes, shadow tightens, frost blooms from the finger; release rebounds.
 * The active state animates on the settle spring, label/subtitle changes slide in.
 */

private val GridTileShape = RoundedCornerShape(22.dp)
private val CompactTileShape = RoundedCornerShape(24.dp)

private fun stateText(t: QsTile) = when {
    !t.isAvailable -> "unavailable"
    t.isActive -> "on"
    else -> "off"
}

/** Full-grid tile: icon well, label, live secondary text. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassGridTile(
    t: QsTile,
    modifier: Modifier,
    accentBright: Color = MikuTealBright,
    onLongPressOpened: () -> Unit = {}
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val press = rememberMikuPress(interaction)
    val on = t.isActive && t.isAvailable
    val activeAnim = animateFloatAsState(if (on) 1f else 0f, MikuMotion.settle(), label = "tileActive")
    val ink by animateColorAsState(if (on) MikuDarkBg else MikuWhite, MikuMotion.ease(), label = "tileInk")
    val subInk by animateColorAsState(
        when { on -> MikuDarkBg.copy(alpha = 0.78f); !t.isAvailable -> MikuMuted; else -> MikuTextSecondary },
        MikuMotion.ease(), label = "tileSubInk"
    )
    val iconInk by animateColorAsState(
        when { on -> MikuDarkBg; !t.isAvailable -> MikuMuted; else -> accentBright },
        MikuMotion.ease(), label = "tileIconInk"
    )
    Row(
        modifier
            .height(64.dp)
            .mikuPressScale(press)
            .mikuGlass(GridTileShape, MikuGlass.Tile, accent = accentBright, active = { activeAnim.value }, pressed = press, dimmed = !t.isAvailable)
            .semantics { stateDescription = stateText(t) }
            .combinedClickable(
                interactionSource = interaction, indication = null, role = Role.Switch,
                onClick = { MikuHaptics.tick(view); t.onClick() },
                onLongClick = t.onLongClick?.let { lc -> { MikuHaptics.pop(view); lc(); if (t.longPressOpensUi) onLongPressOpened() } }
            )
            .padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconWell(t.icon, t.label, iconInk, on, 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.label, color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedContent(
                targetState = t.subtitle,
                transitionSpec = {
                    (slideInVertically(MikuMotion.ease()) { it / 2 } + fadeIn(MikuMotion.ease()))
                        .togetherWith(slideOutVertically(MikuMotion.ease()) { -it / 2 } + fadeOut(MikuMotion.ease(120)))
                },
                label = "tileSubtitle"
            ) { sub ->
                Text(sub, color = subInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Quick-row tile in the collapsed shade: icon over a short caps label. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCompactTile(
    t: QsTile,
    modifier: Modifier,
    accentBright: Color = MikuTealBright,
    onLongPressOpened: () -> Unit = {}
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val press = rememberMikuPress(interaction)
    val on = t.isActive && t.isAvailable
    val activeAnim = animateFloatAsState(if (on) 1f else 0f, MikuMotion.settle(), label = "compactActive")
    val ink by animateColorAsState(
        when { on -> MikuDarkBg; !t.isAvailable -> MikuMuted; else -> MikuTextSecondary },
        MikuMotion.ease(), label = "compactInk"
    )
    val iconInk by animateColorAsState(
        when { on -> MikuDarkBg; !t.isAvailable -> MikuMuted; else -> accentBright },
        MikuMotion.ease(), label = "compactIconInk"
    )
    Column(
        modifier
            .height(56.dp)
            .mikuPressScale(press)
            .mikuGlass(CompactTileShape, MikuGlass.Tile, accent = accentBright, active = { activeAnim.value }, pressed = press, dimmed = !t.isAvailable)
            .semantics { stateDescription = stateText(t) }
            .combinedClickable(
                interactionSource = interaction, indication = null, role = Role.Switch,
                onClickLabel = t.label,
                onClick = { MikuHaptics.tick(view); t.onClick() },
                onLongClick = t.onLongClick?.let { lc -> { MikuHaptics.pop(view); lc(); if (t.longPressOpensUi) onLongPressOpened() } }
            ),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(t.icon, t.label, tint = iconInk, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(3.dp))
        val short = QsTileOrder.spec(t.id)?.short ?: t.label.uppercase().take(10)
        Text(
            short, color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, letterSpacing = 0.5.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 3.dp)
        )
    }
}

/** Round well the tile icon sits in: a darker dimple on lit glass, a faint teal one on dark glass. */
@Composable
private fun IconWell(icon: ImageVector, desc: String, tint: Color, on: Boolean, size: Dp) {
    Box(
        Modifier
            .size(size)
            .mikuGlass(
                CircleShape,
                if (on) MikuGlass.Groove.copy(bodyTop = MikuDarkBg.copy(alpha = 0.30f), bodyBottom = MikuDarkBg.copy(alpha = 0.12f), opacity = 1f, rim = 0.2f)
                else MikuGlass.Groove.copy(opacity = 0.75f, rim = 0.5f)
            ),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp)) }
}

/** Round glass icon button (header chevron, footer actions). 40dp target minimum. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    accent: Color = tint,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val press = rememberMikuPress(interaction)
    Box(
        modifier
            .size(size)
            .mikuPressScale(press, depth = 0.10f)
            .mikuGlass(CircleShape, MikuGlass.Chip, accent = accent, pressed = press)
            .clickable(
                interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = contentDescription,
                onClick = { MikuHaptics.confirm(view); onClick() }
            ),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

/** Pill-shaped glass text button. [filled] = accent-lit (primary action). */
@Composable
fun GlassChipButton(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = MikuTealBright,
    filled: Boolean = false,
    textColor: Color = if (filled) MikuDarkBg else accent,
    height: Dp = 36.dp,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val press = rememberMikuPress(interaction)
    val lit = animateFloatAsState(if (filled) 1f else 0f, MikuMotion.settle(), label = "chipLit")
    Box(
        modifier
            .height(height)
            .mikuPressScale(press, depth = 0.07f)
            .mikuGlass(RoundedCornerShape(height / 2), MikuGlass.Chip, accent = accent, active = { lit.value }, pressed = press)
            .clickable(
                interactionSource = interaction, indication = null, role = Role.Button,
                onClick = { MikuHaptics.confirm(view); onClick() }
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
    }
}
