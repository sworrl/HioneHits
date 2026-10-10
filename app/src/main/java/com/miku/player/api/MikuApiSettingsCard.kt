package com.miku.player.api

import com.miku.player.Mascot
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.player.AudiowideFont
import com.miku.player.Haptics
import com.miku.player.MikuPink
import com.miku.player.MikuTeal
import com.miku.player.MikuTealBright
import com.miku.player.Muted
import com.miku.player.cast.MikuCastAuth
import com.miku.player.cast.MikuCastServer
import com.miku.player.remote.MikuQrEncoder
import kotlinx.coroutines.delay

/**
 * Settings for the Wi-Fi remote API (port 8765) and TV casting (port 8796). Both are off by
 * default. Everything shown comes from the servers' own state flows.
 */
@Composable
fun MikuApiSettingsCard(ctx: Context) {
    val server by MikuApiServer.state.collectAsState()
    val pair by MikuApiSecurity.pairState.collectAsState()
    val cast by MikuCastServer.state.collectAsState()
    val castPair by MikuCastAuth.state.collectAsState()
    var enabled by remember { mutableStateOf(MikuApiSecurity.isApiEnabled(ctx)) }
    var confirmReset by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000L) } }
    val tvs = remember(castPair.generation, cast.connected) { MikuCastAuth.list(ctx) }

    fun setOn(on: Boolean) {
        Haptics.tick(ctx)
        enabled = on
        MikuApiSecurity.setApiEnabled(ctx, on)
        if (on) MikuApiServer.start(ctx) else { MikuApiSecurity.closePairing(); MikuApiServer.stop(ctx) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Mascot.color(0xFF07272B), Mascot.color(0xFF041417))))
            .border(1.dp, MikuTealBright.copy(alpha = if (server.running) 0.55f else 0.3f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        // ---- Wi-Fi remote
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi remote", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                Text(
                    "Control playback from a browser on the same Wi-Fi. Off by default. Each browser has to pair once.",
                    color = Muted, fontSize = 10.5.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = { setOn(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = MikuTeal))
        }

        Spacer(Modifier.height(8.dp))
        val (line, color) = when {
            !enabled -> "Off" to Muted
            server.running -> "On at ${server.baseUrl}" to MikuTealBright
            server.error.isNotEmpty() -> server.error to MikuPink
            else -> "Starting" to Muted
        }
        Text(line, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)

        if (enabled && server.running) {
            Spacer(Modifier.height(10.dp))
            val locked = pair.lockedUntilMs > now
            when {
                locked -> Text(
                    "Too many wrong codes. Pairing is paused for ${((pair.lockedUntilMs - now) / 1000).coerceAtLeast(0)}s.",
                    color = MikuPink, fontSize = 11.sp
                )
                pair.open(now) -> {
                    val url = "${server.baseUrl}/#pair=${pair.code}"
                    val qr = remember(url) { runCatching { MikuQrEncoder.encode(url, MikuQrEncoder.Ecc.MEDIUM) }.getOrNull() }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(132.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp)) {
                            if (qr != null) QrCanvas(qr, Modifier.size(120.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Scan with the phone, or open ${server.baseUrl} and type this code:", color = Color.White, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(pair.code.chunked(3).joinToString(" "), color = MikuTealBright, fontSize = 26.sp,
                                fontWeight = FontWeight.Black, fontFamily = AudiowideFont, letterSpacing = 2.sp)
                            Text("Works once. Closes in ${((pair.expiresAtMs - now) / 1000).coerceAtLeast(0)}s.", color = Muted, fontSize = 10.sp)
                            Spacer(Modifier.height(6.dp))
                            SmallButton("Cancel") { Haptics.tick(ctx); MikuApiSecurity.closePairing() }
                        }
                    }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallButton("Pair a browser") { Haptics.tick(ctx); MikuApiSecurity.openPairing() }
                    if (pair.pairedCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text("Paired ${pair.pairedCount} since the app started.", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }

        if (enabled) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton(if (confirmReset) "Tap again to sign out all" else "Sign out all browsers", accent = MikuPink) {
                    Haptics.tick(ctx)
                    if (confirmReset) { MikuApiSecurity.resetToken(ctx); confirmReset = false } else confirmReset = true
                }
            }
            Text("Makes a new access key. Every paired browser has to pair again.", color = Muted, fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp))
        }

        // ---- TV cast
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cast to a TV", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, fontFamily = AudiowideFont)
                Text(
                    "Sends the audio to the ${Mascot.osName} TV app on the same Wi-Fi. Listens only while this is on, and turns off after 15 minutes with no TV.",
                    color = Muted, fontSize = 10.5.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = cast.active,
                onCheckedChange = { on -> Haptics.tick(ctx); if (on) MikuCastServer.start(ctx) else MikuCastServer.stop(ctx) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = MikuTeal)
            )
        }
        Spacer(Modifier.height(6.dp))
        val castLine = when {
            cast.connected -> "Playing on ${cast.clientLabel.ifBlank { "a TV" }} (${cast.clientAddress})" to MikuTealBright
            cast.active && cast.listening -> "Waiting for a TV at ${cast.address}" to MikuTealBright
            cast.lastError.isNotEmpty() -> cast.lastError to MikuPink
            cast.active -> "Starting" to Muted
            else -> "Off" to Muted
        }
        Text(castLine.first, color = castLine.second, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (cast.active) {
            Spacer(Modifier.height(6.dp))
            val locked = castPair.lockedUntilMs > now
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        locked -> "Too many wrong codes. Wait ${((castPair.lockedUntilMs - now) / 1000).coerceAtLeast(0)}s."
                        castPair.code.isNotEmpty() -> "New TV code: ${castPair.code.chunked(3).joinToString(" ")}"
                        else -> "No TV code open."
                    },
                    color = if (locked) MikuPink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (!locked) SmallButton("New code") { Haptics.tick(ctx); MikuCastAuth.newCode() }
            }
            Text("A TV you paired before connects on its own. A new TV asks for this code once.", color = Muted, fontSize = 10.sp)
        }
        if (tvs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("PAIRED TVS (${tvs.size})", color = MikuTealBright, fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
                fontFamily = AudiowideFont, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 4.dp))
            tvs.forEach { tv ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tv.label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SmallButton("Forget", accent = MikuPink) { Haptics.tick(ctx); MikuCastAuth.forget(ctx, tv.id) }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, accent: Color = MikuTeal, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.2f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (accent == MikuPink) MikuPink else MikuTealBright, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QrCanvas(qr: MikuQrEncoder.Matrix, modifier: Modifier) {
    Canvas(modifier) {
        val n = qr.size
        val cell = minOf(size.width, size.height) / n
        val ox = (size.width - cell * n) / 2f
        val oy = (size.height - cell * n) / 2f
        val dark = Mascot.color(0xFF0A1416)
        for (y in 0 until n) for (x in 0 until n) {
            if (qr.get(x, y)) drawRect(dark, topLeft = Offset(ox + x * cell, oy + y * cell), size = Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
