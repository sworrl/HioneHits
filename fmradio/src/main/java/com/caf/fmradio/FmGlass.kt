package com.caf.fmradio

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small frosted-glass look for panels and buttons, kept local to this app.
 *
 * Real background blur is not affordable here: RenderEffect blur on a full-screen panel costs a
 * whole extra pass per frame on this GPU, and the tuner screen already has a live analyser
 * redrawing at 25 fps. What sells "glass" on a dark UI is cheaper than blur anyway: a
 * translucent fill so the artwork shows through, a specular band along the top edge where light
 * would catch a curved surface, and a rim that picks up colour from its surroundings (teal into
 * pink here). All three are a gradient and an outline, cached per size by drawWithCache, so a
 * glass button costs about what a bordered box did.
 */
fun Modifier.glass(
    shape: Shape,
    accent: Color = MikuTeal,
    accent2: Color = MikuPink,
    fill: Color = Color(0xB80A1E26),
    rimAlpha: Float = 0.55f,
    rimWidth: Dp = 1.dp,
    /** 0..1: how strongly the top highlight shows. Selected/active surfaces get more. */
    shine: Float = 1f,
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val spec = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.13f * shine),
            0.42f to Color.White.copy(alpha = 0.02f * shine),
            1f to Color.Transparent,
            endY = size.height,
        )
        // A faint coloured glow pooled at the bottom, as light through tinted glass does.
        val pool = Brush.verticalGradient(
            0f to Color.Transparent, 1f to accent.copy(alpha = 0.10f * shine),
            startY = size.height * 0.45f, endY = size.height,
        )
        val rim = Brush.linearGradient(
            listOf(accent.copy(alpha = rimAlpha), accent2.copy(alpha = rimAlpha * 0.55f), accent.copy(alpha = rimAlpha * 0.25f)),
            start = Offset.Zero, end = Offset(size.width, size.height),
        )
        // The outline is drawn inside the clip, so half its width is cut away: double it.
        val stroke = Stroke(width = rimWidth.toPx() * 2f)
        onDrawWithContent {
            drawOutline(outline, fill)
            drawOutline(outline, spec)
            drawOutline(outline, pool)
            drawContent()
            drawOutline(outline, rim, style = stroke)
        }
    }

/**
 * Tap / long-press with a soft squash instead of a ripple.
 *
 * A ripple on a dark translucent surface reads as a grey smear; a small spring scale reads as a
 * physical button under a thumb, which is how this device is used. The scale is applied in a
 * graphics layer, so pressing only re-renders the layer, never recomposes the content. Long
 * press gets a haptic tick so the user knows the second action fired without looking.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    onClick: () -> Unit,
): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    val haptics = LocalHapticFeedback.current
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            interactionSource = src,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick?.let { lc -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); lc() } },
            onClick = onClick,
        )
}
