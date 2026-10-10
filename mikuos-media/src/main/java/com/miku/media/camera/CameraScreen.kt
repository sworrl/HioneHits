package com.miku.media.camera

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Timer10
import androidx.compose.material.icons.rounded.Timer3
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.miku.media.gallery.ViewerActivity
import com.miku.media.ui.GlassButton
import com.miku.media.ui.GlassIconButton
import com.miku.media.ui.GlassSegments
import com.miku.media.ui.MessagePane
import com.miku.media.ui.MikuDanger
import com.miku.media.ui.MikuPink
import com.miku.media.ui.MikuTeal
import com.miku.media.ui.MikuWhite
import com.miku.media.ui.Orbitron
import com.miku.media.ui.formatDuration
import com.miku.media.ui.glass
import com.miku.media.ui.glassCircle
import com.miku.media.ui.hasPermission
import com.miku.media.ui.pressable
import com.miku.media.ui.rememberPermissionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

@Composable
fun CameraScreen(
    activity: CameraActivity,
    mode: CaptureMode,
    secure: Boolean,
    onImageResult: (Uri?, Bitmap?) -> Unit,
    onVideoResult: (Uri) -> Unit
) {
    val ctx = LocalContext.current
    val gate = rememberPermissionGate(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    val hasCamera = remember(gate.version) { ctx.hasPermission(Manifest.permission.CAMERA) }

    fun askPermission() {
        if (secure) {
            // The permission dialog cannot show over the keyguard, so unlock first.
            val km = ctx.getSystemService(KeyguardManager::class.java)
            if (km.isKeyguardLocked) {
                km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() { gate.request() }
                })
                return
            }
        }
        gate.request()
    }

    LaunchedEffect(Unit) { if (!ctx.hasPermission(Manifest.permission.CAMERA)) askPermission() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasCamera) {
            MessagePane(
                Icons.Rounded.PhotoCamera,
                "Camera is off",
                if (secure) "Unlock the device and allow camera access to take photos." else "Allow camera access to take photos and videos.",
                "Allow"
            ) { askPermission() }
        } else {
            Viewfinder(activity, mode, secure, onImageResult, onVideoResult)
        }
    }
}

private enum class Flash(val mode: Int) { OFF(ImageCapture.FLASH_MODE_OFF), AUTO(ImageCapture.FLASH_MODE_AUTO), ON(ImageCapture.FLASH_MODE_ON) }

@Composable
private fun Viewfinder(
    activity: CameraActivity,
    mode: CaptureMode,
    secure: Boolean,
    onImageResult: (Uri?, Bitmap?) -> Unit,
    onVideoResult: (Uri) -> Unit
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val engine = remember { CameraEngine(ctx) }
    val view = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    var ready by remember { mutableStateOf(false) }
    var videoMode by remember {
        mutableStateOf(mode is CaptureMode.Video || (mode is CaptureMode.Free && mode.startInVideo))
    }
    var flash by remember { mutableStateOf(Flash.OFF) }
    var torch by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var timer by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var recMs by remember { mutableLongStateOf(0L) }
    var focus by remember { mutableStateOf<Offset?>(null) }
    val focusAlpha = remember { Animatable(0f) }
    val shutterFlash = remember { Animatable(0f) }
    val session = remember { mutableStateListOf<Uri>() }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var reviewPhoto by remember { mutableStateOf<File?>(null) }
    var reviewVideo by remember { mutableStateOf<Uri?>(null) }
    var iconTurn by remember { mutableStateOf(0f) }
    val iconAngle by animateFloatAsState(iconTurn, tween(250), label = "iconTurn")

    LaunchedEffect(Unit) { ready = engine.init() }

    // Rebind whenever the camera or mode changes. Binding is the expensive step (it reopens the
    // sensor), so flash/zoom/torch changes go straight to the bound camera instead.
    LaunchedEffect(ready, engine.camIndex, videoMode) {
        if (ready) {
            torch = false
            engine.bind(owner, view, videoMode, (mode as? CaptureMode.Video)?.lowQuality == true, flash.mode)
        }
    }
    DisposableEffect(Unit) { onDispose { engine.stopRecording(); engine.unbind() } }

    // The activity is locked to portrait, so the sensor's real orientation has to be fed to
    // CameraX by hand; otherwise a photo taken holding the device sideways is saved sideways.
    DisposableEffect(Unit) {
        val l = object : OrientationEventListener(ctx) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val (rot, deg) = when (o) {
                    in 45..134 -> Surface.ROTATION_270 to -90f
                    in 135..224 -> Surface.ROTATION_180 to 180f
                    in 225..314 -> Surface.ROTATION_90 to 90f
                    else -> Surface.ROTATION_0 to 0f
                }
                engine.setRotation(rot)
                iconTurn = deg
            }
        }
        l.enable()
        onDispose { l.disable() }
    }

    // Newest photo in DCIM/Camera for the thumbnail, when we may read the library. On the lock
    // screen only this session's captures are shown.
    LaunchedEffect(Unit) {
        if (!secure && mode is CaptureMode.Free && ctx.hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
            lastUri = latestCameraItem(ctx)
        }
    }

    val thumb by produceState<ImageBitmap?>(null, lastUri) {
        value = lastUri?.let { u ->
            withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.loadThumbnail(u, android.util.Size(160, 160), null) }.getOrNull() }
        }?.asImageBitmap()
    }

    fun onFinalize(e: VideoRecordEvent.Finalize, target: Uri?) {
        recording = false; paused = false
        val ok = !e.hasError() || e.error in setOf(
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
        )
        val uri = target ?: e.outputResults.outputUri.takeIf { it != Uri.EMPTY }
        if (!ok || uri == null) {
            engine.error = "Recording failed."
            return
        }
        if (mode is CaptureMode.Video) reviewVideo = uri
        else { lastUri = uri; session.add(0, uri) }
    }

    fun startVideo() {
        val req = mode as? CaptureMode.Video
        val audio = ctx.hasPermission(Manifest.permission.RECORD_AUDIO)
        val started = engine.startRecording(
            target = req?.output,
            withAudio = audio,
            durationLimitMs = (req?.durationLimitS ?: 0) * 1000L,
            sizeLimit = req?.sizeLimit ?: 0L
        ) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> { recording = true; recMs = 0 }
                is VideoRecordEvent.Status -> recMs = ev.recordingStats.recordedDurationNanos / 1_000_000
                is VideoRecordEvent.Pause -> paused = true
                is VideoRecordEvent.Resume -> paused = false
                is VideoRecordEvent.Finalize -> onFinalize(ev, req?.output)
                else -> {}
            }
        }
        if (started) recording = true
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        scope.launch {
            if (timer > 0) {
                for (s in timer downTo 1) { countdown = s; delay(1000) }
                countdown = 0
            }
            launch { shutterFlash.snapTo(0.85f); shutterFlash.animateTo(0f, tween(220)) }
            when (mode) {
                is CaptureMode.Image -> {
                    val f = File(ctx.cacheDir, "capture.jpg")
                    if (engine.takePhotoToFile(f)) reviewPhoto = f
                }
                else -> engine.takePhotoToGallery()?.let { lastUri = it; session.add(0, it) }
            }
            busy = false
        }
    }

    fun shutter() {
        if (reviewPhoto != null || reviewVideo != null) return
        if (videoMode) {
            if (recording) engine.stopRecording() else startVideo()
        } else takePhoto()
    }

    LaunchedEffect(Unit) { activity.shutterKeys.collect { shutter() } }

    BackHandler(enabled = recording) { engine.stopRecording() }

    // ---------------- review screens for capture-for-result ----------------
    reviewPhoto?.let { f ->
        PhotoReview(
            f,
            onRetake = { f.delete(); reviewPhoto = null },
            onUse = {
                scope.launch {
                    val m = mode as CaptureMode.Image
                    if (m.output != null) {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                val os = runCatching { ctx.contentResolver.openOutputStream(m.output, "wt") }.getOrNull()
                                    ?: ctx.contentResolver.openOutputStream(m.output)
                                os?.use { out -> f.inputStream().use { it.copyTo(out) } } != null
                            }.getOrDefault(false)
                        }
                        f.delete()
                        if (ok) onImageResult(m.output, null) else engine.error = "Could not save to the requested place."
                    } else {
                        // Inline result: Binder caps a transaction near 1 MB, so keep it small.
                        val bmp = withContext(Dispatchers.IO) {
                            runCatching {
                                ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
                                    val long = max(info.size.width, info.size.height)
                                    val s = 320f / long
                                    if (s < 1f) d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                }
                            }.getOrNull()
                        }
                        f.delete()
                        onImageResult(null, bmp)
                    }
                }
            }
        )
        return
    }
    reviewVideo?.let { u ->
        VideoReview(
            u,
            onRetake = {
                val m = mode as? CaptureMode.Video
                if (m?.output == null) runCatching { ctx.contentResolver.delete(u, null, null) }
                reviewVideo = null
            },
            onUse = { onVideoResult(u) }
        )
        return
    }

    // ---------------- viewfinder ----------------
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())

        // Gesture layer: tap to focus, pinch to zoom.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        engine.focusAt(view, p.x, p.y)
                        focus = p
                        scope.launch { focusAlpha.snapTo(1f); delay(700); focusAlpha.animateTo(0f, tween(400)) }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, z, _ -> if (z != 1f) engine.setZoom(engine.zoom * z) }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (grid) {
                    // Rule of thirds over the actual preview area (4:3 or 16:9, centred).
                    val ratio = if (videoMode) 16f / 9f else 4f / 3f
                    val h = minOf(size.height, size.width * ratio)
                    val top = (size.height - h) / 2f
                    val c = Color.White.copy(alpha = 0.35f)
                    for (i in 1..2) {
                        val x = size.width * i / 3f
                        val y = top + h * i / 3f
                        drawLine(c, Offset(x, top), Offset(x, top + h), 1.dp.toPx())
                        drawLine(c, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                }
                focus?.let { p ->
                    if (focusAlpha.value > 0f) {
                        drawCircle(MikuTeal.copy(alpha = focusAlpha.value), 34.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                        drawCircle(MikuPink.copy(alpha = focusAlpha.value), 3.dp.toPx(), p)
                    }
                }
            }
        }

        if (shutterFlash.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = shutterFlash.value)))

        if (countdown > 0) {
            Text(
                "$countdown",
                fontFamily = Orbitron, fontSize = 96.sp, fontWeight = FontWeight.Bold, color = MikuWhite,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // ---------- top bar ----------
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (mode !is CaptureMode.Free) {
                GlassIconButton(Icons.Rounded.Close, "Cancel", modifier = Modifier.rotate(iconAngle)) { activity.finish() }
                Spacer(Modifier.width(8.dp))
            }
            if (engine.hasFlash) {
                if (videoMode) {
                    GlassIconButton(if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "Torch", modifier = Modifier.rotate(iconAngle),
                        tint = if (torch) MikuTeal else MikuWhite) {
                        torch = !torch; engine.setTorch(torch)
                    }
                } else {
                    GlassIconButton(
                        when (flash) { Flash.OFF -> Icons.Rounded.FlashOff; Flash.AUTO -> Icons.Rounded.FlashAuto; Flash.ON -> Icons.Rounded.FlashOn },
                        "Flash", modifier = Modifier.rotate(iconAngle), tint = if (flash == Flash.OFF) MikuWhite else MikuTeal
                    ) {
                        flash = Flash.entries[(flash.ordinal + 1) % Flash.entries.size]
                        engine.setFlash(flash.mode)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            GlassIconButton(if (grid) Icons.Rounded.GridOn else Icons.Rounded.GridOff, "Grid", modifier = Modifier.rotate(iconAngle),
                tint = if (grid) MikuTeal else MikuWhite) { grid = !grid }
            if (!videoMode) {
                Spacer(Modifier.width(8.dp))
                GlassIconButton(
                    when (timer) { 3 -> Icons.Rounded.Timer3; 10 -> Icons.Rounded.Timer10; else -> Icons.Rounded.TimerOff },
                    "Timer", modifier = Modifier.rotate(iconAngle), tint = if (timer > 0) MikuTeal else MikuWhite
                ) { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }
            }
            Spacer(Modifier.weight(1f))
            if (recording) {
                Row(Modifier.glass(18.dp, MikuPink).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (paused) MikuWhite else MikuDanger))
                    Spacer(Modifier.width(8.dp))
                    Text(formatDuration(recMs), color = MikuWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        engine.error?.let { msg ->
            Text(
                msg, color = MikuWhite, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp, start = 24.dp, end = 24.dp)
                    .glass(14.dp, MikuDanger).padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        // ---------- bottom controls ----------
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (engine.maxZoom > 1.05f) {
                ZoomChips(engine.zoom, engine.maxZoom) { engine.setZoom(it) }
                Spacer(Modifier.height(10.dp))
            }
            if (mode is CaptureMode.Free && !recording) {
                GlassSegments(listOf("Photo", "Video"), if (videoMode) 1 else 0) { videoMode = it == 1 }
                Spacer(Modifier.height(14.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: last capture, or pause while recording.
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    if (recording) {
                        GlassIconButton(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Resume" else "Pause", size = 56.dp) {
                            if (paused) engine.resumeRecording() else engine.pauseRecording()
                        }
                    } else if (mode is CaptureMode.Free) {
                        Box(
                            Modifier.size(56.dp).rotate(iconAngle).clip(CircleShape).glassCircle()
                                .border(2.dp, MikuTeal.copy(alpha = 0.7f), CircleShape)
                                .pressable(enabled = lastUri != null) { lastUri?.let { openReview(ctx, it, session.toList(), secure) } },
                            contentAlignment = Alignment.Center
                        ) {
                            thumb?.let { Image(it, "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                    }
                }
                Shutter(videoMode, recording, enabled = !busy && (if (videoMode) engine.canRecord else engine.canCapturePhoto)) { shutter() }
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    if (engine.cameras.size > 1 && !recording) {
                        GlassIconButton(Icons.Rounded.Cameraswitch, "Switch camera to ${engine.cameras[(engine.camIndex + 1) % engine.cameras.size].label.lowercase()}",
                            size = 56.dp, modifier = Modifier.rotate(iconAngle)) { engine.nextCamera() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Shutter(video: Boolean, recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(80.dp).pressable(enabled = enabled, onClick = onClick)
            .border(4.dp, MikuWhite.copy(alpha = if (enabled) 0.95f else 0.4f), CircleShape)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            recording -> Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(MikuDanger))
            video -> Box(Modifier.fillMaxSize().clip(CircleShape).background(MikuPink))
            else -> Box(Modifier.fillMaxSize().clip(CircleShape).background(MikuWhite).border(3.dp, MikuTeal, CircleShape))
        }
    }
}

@Composable
private fun ZoomChips(zoom: Float, maxZoom: Float, onZoom: (Float) -> Unit) {
    val stops = listOf(1f, 2f, 4f).filter { it <= maxZoom + 0.01f }
    Row(Modifier.glass(22.dp).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        stops.forEach { s ->
            val nearest = stops.minByOrNull { kotlin.math.abs(it - zoom) } == s
            val label = if (nearest && kotlin.math.abs(zoom - s) > 0.05f) "%.1fx".format(zoom) else "${s.toInt()}x"
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (nearest) MikuTeal.copy(alpha = 0.85f) else Color.Transparent)
                    .pressable { onZoom(s) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (nearest) Color(0xFF00201D) else MikuWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PhotoReview(file: File, onRetake: () -> Unit, onUse: () -> Unit) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
                    val long = max(info.size.width, info.size.height)
                    if (long > 1600) { val s = 1600f / long; d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    ReviewFrame(img, onRetake, onUse, ctx)
}

@Composable
private fun VideoReview(uri: Uri, onRetake: () -> Unit, onUse: () -> Unit) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.media.MediaMetadataRetriever().use { r ->
                    r.setDataSource(ctx, uri)
                    r.getFrameAtTime(0)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    ReviewFrame(img, onRetake, onUse, ctx)
}

@Composable
private fun ReviewFrame(img: ImageBitmap?, onRetake: () -> Unit, onUse: () -> Unit, @Suppress("UNUSED_PARAMETER") ctx: Context) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        img?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassButton("Retake", icon = Icons.Rounded.Refresh, modifier = Modifier.weight(1f), onClick = onRetake)
            GlassButton("Use", icon = Icons.Rounded.Check, filled = true, modifier = Modifier.weight(1f), onClick = onUse)
        }
    }
}

private fun openReview(ctx: Context, uri: Uri, session: List<Uri>, secure: Boolean) {
    val i = Intent(ctx, ViewerActivity::class.java).setData(uri)
    if (secure) {
        i.putParcelableArrayListExtra(ViewerActivity.EXTRA_SESSION_URIS, ArrayList(session))
        i.putExtra(ViewerActivity.EXTRA_SECURE, true)
    }
    ctx.startActivity(i)
}

private suspend fun latestCameraItem(ctx: Context): Uri? = withContext(Dispatchers.IO) {
    val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val sel = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
        "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND " +
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/Camera%'"
    runCatching {
        ctx.contentResolver.query(
            files,
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE),
            android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
            },
            null
        )?.use { c ->
            if (!c.moveToFirst()) null else {
                val id = c.getLong(0)
                val base = if (c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                android.content.ContentUris.withAppendedId(base, id)
            }
        }
    }.getOrNull()
}
