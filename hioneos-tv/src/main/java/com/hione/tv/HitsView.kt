package com.hione.tv

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hione.player.ProjectMNative

@Composable
fun HitsView(
    client: HioneCastClient,
    state: HioneCastClient.ClientState,
    visuals: HioneCastClient.AudioVisuals,
    onCommand: (String) -> Unit,
    onSwitchMode: (TvViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ── CENTERPIECE: EXPANSIVE CINEMATIC PROJECTM VISUALIZER STAGE ──
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .weight(1.35f)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF040B0E))
                .border(
                    2.dp,
                    Brush.horizontalGradient(
                        listOf(
                            HioneOrange.copy(alpha = 0.85f),
                            HioneTeal.copy(alpha = 0.85f)
                        )
                    ),
                    RoundedCornerShape(22.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (ProjectMNative.available) {
                ProjectMVisualizerView(
                    client = client,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                VisualizerCanvasFallback(visuals = visuals)
            }

            // Top Stage Telemetry Bar: Engine on Left, Audio Pipeline on Right
            val exact = state.passthroughExact
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Top-Left Badge: Engine Telemetry
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC050E11))
                        .border(1.dp, HioneOrange.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(HioneTeal)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (ProjectMNative.available) "PROJECTM 4.2 GL" else "AUDIO REACTIVE",
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = AudiowideFont,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                // Top-Right Badge: Bit-Perfect Output Telemetry
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (exact) Color(0xCC002B18) else Color(0xCC332000))
                        .border(
                            1.dp,
                            if (exact) Color(0xFF00FF88) else Color(0xFFFFB020),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (exact) "BIT-PERFECT DIRECT" else "TV RESAMPLED",
                        color = if (exact) Color(0xFF00FF88) else Color(0xFFFFB020),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = AudiowideFont,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        val dur = state.durationMs
        val pos = state.positionMs

        // ── HIONE HITS NOW PLAYING METADATA CARD (AUTHENTIC DAP DESIGN) ──
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Color(0xE01A2E32), Color(0xF0071115))))
                .border(
                    1.2.dp,
                    Brush.horizontalGradient(
                        listOf(
                            HioneOrange.copy(alpha = 0.65f),
                            HioneTeal.copy(alpha = 0.55f)
                        )
                    ),
                    RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = state.title.ifBlank { if (state.connected) "Audio Stream Ready" else state.status },
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (state.artist.isNotBlank() || state.album.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.artist.isNotBlank()) {
                                Text(
                                    text = state.artist,
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (state.artist.isNotBlank() && state.album.isNotBlank()) {
                                Text("  ·  ", color = HioneMuted, fontSize = 14.sp)
                            }
                            if (state.album.isNotBlank()) {
                                Text(
                                    text = state.album,
                                    color = HioneOrange,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Tech Format Pills (Matches Hione Hits DAP)
                    val src = state.source
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvDataChip("FLAC", HioneOrange)
                        val bits = if (src.bytesPerSample > 0) src.bytesPerSample * 8 else 24
                        TvDataChip("${bits}-BIT", HioneOrange)
                        val khz = if (src.sampleRate > 0) "%.1fKHZ".format(src.sampleRate / 1000f) else "48.0KHZ"
                        TvDataChip(khz, HioneOrange)
                        TvDataChip(if (state.passthroughExact) "BIT-PERFECT DIRECT" else "DIRECT", HioneGreen)
                        if (dur > 0L) {
                            TvDataChip(formatMs(dur), HioneTeal)
                        }
                    }
                }

                Spacer(Modifier.width(16.dp))

                // Iconic Glowing Heart Badge (from Hione Hits DAP)
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0x3300E5FF))
                        .border(1.5.dp, HioneTeal, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Liked",
                        tint = HioneOrange,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── AUTHENTIC HIONE HITS WAVY PLAY BAR (WAVY SCRUBBER) ──
        Column(
            modifier = Modifier.fillMaxWidth(0.94f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            WavyScrubber(
                pos = pos,
                dur = dur,
                playing = state.isPlaying,
                accent = HioneOrange,
                accent2 = HioneTeal,
                rms = visuals.rms,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatMs(pos),
                    color = HioneTeal,
                    fontSize = 15.sp,
                    fontFamily = OrbitronFont,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = if (state.connected) "LINKED TO M500 DAP" else "STREAM READY",
                    color = HioneMuted.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontFamily = OrbitronFont,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = if (dur > 0L) formatMs(dur) else "--:--",
                    color = HioneTeal,
                    fontSize = 15.sp,
                    fontFamily = OrbitronFont,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── GLASS TRANSPORT DECK (FOCUSABLE REMOTE CONTROLS) ──
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvFocusableButton(
                onClick = { onCommand("prev") },
                icon = Icons.Default.SkipPrevious,
                label = "Prev",
                height = 44.dp
            )
            TvFocusableButton(
                onClick = { onCommand("rewind") },
                icon = Icons.Default.FastRewind,
                label = "-15s",
                height = 44.dp
            )
            TvFocusableButton(
                onClick = { onCommand("playpause") },
                icon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                label = if (state.isPlaying) "Pause" else "Play",
                primary = true,
                height = 44.dp
            )
            TvFocusableButton(
                onClick = { onCommand("forward") },
                icon = Icons.Default.FastForward,
                label = "+15s",
                height = 44.dp
            )
            TvFocusableButton(
                onClick = { onCommand("next") },
                icon = Icons.Default.SkipNext,
                label = "Next",
                height = 44.dp
            )
            Spacer(Modifier.width(12.dp))
            TvFocusableButton(
                onClick = { onSwitchMode(TvViewMode.CASSETTE) },
                icon = Icons.Default.Album,
                label = "Cassette Deck",
                height = 44.dp,
                fontSize = 12.sp
            )
            TvFocusableButton(
                onClick = { onSwitchMode(TvViewMode.VISUALIZER) },
                icon = Icons.Default.GraphicEq,
                label = "Fullscreen Stage",
                height = 44.dp,
                fontSize = 12.sp
            )
        }

        // ── STREAM PIPELINE TELEMETRY ──
        val src = state.source
        Text(
            text = if (src.sampleRate > 0)
                "STREAM: ${src.sampleRate} Hz · ${src.bytesPerSample * 8}-bit · TV OUT: ${state.grantedRate} Hz (${state.grantedEncodingLabel})"
            else
                "WAITING FOR M500 DAP AUDIO STREAM",
            color = HioneMuted.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontFamily = OrbitronFont,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun VisualizerCanvasFallback(visuals: HioneCastClient.AudioVisuals) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val spec = visuals.spectrum
        val n = spec.size
        val bw = w / (n.toFloat() * 1.4f)
        val gap = bw * 0.4f

        for (i in 0 until n) {
            val barH = (spec[i] * h * 0.4f).coerceAtLeast(4f)
            val x = i * (bw + gap) + gap
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(HioneOrange, HionePink)),
                topLeft = androidx.compose.ui.geometry.Offset(x, midY - barH),
                size = androidx.compose.ui.geometry.Size(bw, barH * 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2f, bw / 2f)
            )
        }
    }
}

internal fun formatMs(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val sec = (safe / 1000) % 60
    val min = (safe / 1000) / 60
    return "%d:%02d".format(min, sec)
}
