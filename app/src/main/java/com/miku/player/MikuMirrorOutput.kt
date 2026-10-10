package com.miku.player

import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Play to every connected output at once.
 *
 * WHY THE PLAYER DOES IT. The "Dual Simultaneous Audio Matrix" used to send vendor parameters
 * (`vendor.audio.dual_output`, `bt_dual_stream`, `usb_mirror`) that appear nowhere in this
 * device's audio HAL or its configs, so it never did anything. Android itself routes a stream to
 * ONE device. Since we own the decoder, the player can instead feed the same PCM to more than one
 * AudioTrack, each pinned to a different device with setPreferredDevice.
 *
 * THE SHAPE. The primary output keeps everything it had: MikuDirectAudioSink's track, at the
 * file's native rate on the DIRECT path, pinned to the best device present (the internal DACs on
 * a wired jack first, then USB, then Bluetooth). Every OTHER connected output, except the
 * built-in speaker, gets a mirror: a writer thread with its own float AudioTrack pinned to that
 * device, fed from a ring the sink tees into right after its own write (the same point the TV
 * cast tap uses). Mirrors go through the mixer, so a cheap USB headset that only does 48 kHz
 * still plays a 96 kHz file; only the primary is bit-perfect.
 *
 * SYNC. Each output has its own buffer depth, so naively the mirror would run early or late by
 * a few hundred milliseconds. Both tracks report how many frames they have played, and each
 * byte offered is written to the primary in order, so "which source byte is the primary
 * playing now" and "which source byte is this mirror playing now" are both known. The mirror
 * corrects the difference by writing silence (when ahead) or skipping (when behind), only when
 * it exceeds [SYNC_TOLERANCE_MS], so a correction is one tiny gap rather than constant warble.
 * Bluetooth also has latency after the playback head that the head cannot see; [extraLatencyMs]
 * accounts for it per device type, and Settings.Global `miku_audio_share_offset_ms` nudges it.
 *
 * DEFAULT ON. Settings.Global `miku_audio_share_enabled` unset means on: if you plug in a second
 * pair of headphones you hear music in them. 0 turns it off.
 *
 * AUDIO THREAD RULES. [offer], [onPrimaryPause], [onPrimaryPlay] and [onPrimaryFlush] run on the
 * playback thread. They copy, set a flag, or bump a counter; they never block or allocate.
 */
object MikuMirrorOutput {

    private const val TAG = "MikuMirror"
    private const val KEY_ENABLED = "miku_audio_share_enabled"
    private const val KEY_OFFSET = "miku_audio_share_offset_ms"

    /** ~10 s of 24/96 stereo. A mirror more than this behind skips forward. */
    private const val CAPACITY = 6 * 1024 * 1024
    private const val SYNC_TOLERANCE_MS = 25
    private const val SYNC_INTERVAL_MS = 400L

    private val ring = ByteArray(CAPACITY)
    /** Bytes ever offered. Counted even while no mirror is active, so positions stay valid. */
    private val written = AtomicLong(0L)

    data class Fmt(val sampleRate: Int, val bytesPerSample: Int, val channels: Int, val isFloat: Boolean) {
        val frameSize get() = bytesPerSample * channels
    }

    @Volatile private var format: Fmt? = null
    /** [written] when the current format began; anything before it is the old format. */
    @Volatile private var formatStart = 0L

    // The primary track, for sync. Head position counts frames since it started or was flushed.
    @Volatile private var primaryTrack: AudioTrack? = null
    @Volatile private var primaryBase = 0L
    @Volatile private var primaryPlaying = false
    /** Bumped on every primary flush; a mirror that sees it change drops what it has. */
    @Volatile private var flushEpoch = 0
    @Volatile private var volume = 1f

    @Volatile private var active = false
    private val sinks = HashMap<Int, MirrorSink>()

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    @Volatile private var primaryDeviceId: Int? = null

    /** Compose-free status for logging and the settings card: what is playing where. */
    @Volatile var status: String = "off"
        private set

    // ------------------------------------------------------------------ setup

    fun init(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        val am = a.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = rescan("added")
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = rescan("removed")
        }, main)
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = rescan("setting")
        }
        a.contentResolver.registerContentObserver(Settings.Global.getUriFor(KEY_ENABLED), false, obs)
        a.contentResolver.registerContentObserver(Settings.Global.getUriFor(KEY_OFFSET), false, obs)
        rescan("init")
    }

    fun isEnabled(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, KEY_ENABLED, 1) != 0 }.getOrDefault(true)

    /**
     * Outputs that can take music. Not the built-in speaker (the point is headphones and
     * speakers you plugged in), not SCO (call audio), not the internal plumbing types.
     */
    private val ELIGIBLE = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_DOCK,
    )

    /** Lower is preferred as the primary (bit-perfect) output. */
    private fun rank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 0
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> 1
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 2
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_DOCK -> 3
        else -> 4
    }

    /** The outputs that would be shared to right now, primary first; fewer than 2 = no sharing. */
    fun plannedOutputs(ctx: Context): List<AudioDeviceInfo> {
        if (!isEnabled(ctx)) return emptyList()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        val all = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
            .filter { it.isSink && it.type in ELIGIBLE }
        // One entry per physical device: a Bluetooth headset can list both A2DP and LE Audio.
        val dedup = all.groupBy { it.address.ifEmpty { "type${it.type}:${it.id}" } }
            .map { (_, same) -> same.minByOrNull { if (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) 0 else 1 }!! }
        return dedup.sortedBy { rank(it.type) }
    }

    /** True when the player is sharing, so MikuUsbDacOutput must leave USB routing alone. */
    fun isSharing(ctx: Context): Boolean = plannedOutputs(ctx).size >= 2

    private fun rescan(reason: String) {
        val a = app ?: return
        val outs = plannedOutputs(a)
        val primary = outs.firstOrNull()
        val mirrors = if (outs.size >= 2) outs.drop(1) else emptyList()
        val wanted = mirrors.associateBy { it.id }

        synchronized(sinks) {
            sinks.keys.filter { it !in wanted }.forEach { id -> sinks.remove(id)?.stop() }
            wanted.forEach { (id, dev) -> if (id !in sinks) sinks[id] = MirrorSink(dev).also { it.start() } }
            active = sinks.isNotEmpty()
        }

        val newPrimary = if (mirrors.isNotEmpty()) primary?.id else null
        if (newPrimary != primaryDeviceId) {
            primaryDeviceId = newPrimary
            routePrimary(if (mirrors.isNotEmpty()) primary else null)
        }
        status = if (mirrors.isEmpty()) "single output"
        else "primary ${label(primary!!)} + " + mirrors.joinToString { label(it) }
        Log.i(TAG, "rescan ($reason): $status")
        // USB routing defers to us while sharing and takes over again when we stop.
        MikuUsbDacOutput.refresh(reason = "mirror:$reason")
    }

    private fun label(d: AudioDeviceInfo) =
        "${d.productName?.toString()?.ifBlank { null } ?: "type${d.type}"}(${d.type})"

    /** Pin the player's own track to the primary, or release it when not sharing. */
    private fun routePrimary(dev: AudioDeviceInfo?) {
        val player = PlayerHolder.player ?: run {
            // init runs before the player exists; forget the choice so the retry re-applies it.
            primaryDeviceId = null
            main.postDelayed({ rescan("player-ready") }, 1000)
            return
        }
        val apply = {
            runCatching { player.setPreferredAudioDevice(dev) }
                .onFailure { Log.e(TAG, "setPreferredAudioDevice failed", it) }
            Unit
        }
        val looper = player.applicationLooper
        if (Looper.myLooper() == looper) apply() else Handler(looper).post(apply)
    }

    // ------------------------------------------------------------------ the sink's side

    @JvmStatic
    fun onFormat(sampleRate: Int, bytesPerSample: Int, channels: Int, isFloat: Boolean) {
        val f = Fmt(sampleRate, bytesPerSample, channels, isFloat)
        if (f != format) {
            formatStart = written.get()
            format = f
        }
    }

    /** The sink built (or rebuilt) its AudioTrack; its head position starts from here. */
    @JvmStatic
    fun onPrimaryTrack(track: AudioTrack) {
        primaryBase = written.get()
        primaryTrack = track
    }

    @JvmStatic fun onPrimaryPlay() { primaryPlaying = true }
    @JvmStatic fun onPrimaryPause() { primaryPlaying = false }

    /** A seek: the primary threw away its buffer and its head position restarts at 0. */
    @JvmStatic
    fun onPrimaryFlush() {
        primaryBase = written.get()
        flushEpoch++
    }

    @JvmStatic
    fun setVolume(v: Float) { volume = v }

    /** AUDIO THREAD. Leaves the buffer's position as found. */
    @JvmStatic
    fun offer(buffer: ByteBuffer, size: Int) {
        if (size <= 0) return
        if (!active) { written.addAndGet(size.toLong()); return }
        val pos = buffer.position()
        try {
            val start = (written.get() % CAPACITY).toInt()
            val first = minOf(size, CAPACITY - start)
            buffer.get(ring, start, first)
            if (first < size) buffer.get(ring, 0, size - first)
        } catch (_: Throwable) {
        } finally {
            buffer.position(pos)
            written.addAndGet(size.toLong())
        }
    }

    // ------------------------------------------------------------------ one mirror

    /** Delay after the playback head that the head cannot see: the radio link, mostly. */
    private fun extraLatencyMs(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> 180
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> 10
        else -> 0
    }

    private class MirrorSink(val device: AudioDeviceInfo) {
        @Volatile private var running = true
        private var thread: Thread? = null

        fun start() {
            thread = Thread({ loop() }, "MikuMirror-${device.id}").apply { isDaemon = true; start() }
            Log.i(TAG, "mirror up: ${label(device)}")
        }

        fun stop() {
            running = false
            thread?.interrupt()
            Log.i(TAG, "mirror down: ${label(device)}")
        }

        private var track: AudioTrack? = null
        private var trackFmt: Fmt? = null
        private var cursor = 0L
        /** Source byte the mirror's head position 0 corresponds to. */
        private var base = 0L
        private var epoch = -1
        private var lastSyncAt = 0L
        private var floats = FloatArray(0)
        private val chunk = ByteArray(16 * 1024)

        private fun loop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                while (running) {
                    val f = format
                    if (f == null) { Thread.sleep(50); continue }
                    if (track == null || trackFmt != f) {
                        if (!build(f)) { Thread.sleep(500); continue }
                        cursor = maxOf(written.get(), formatStart)
                        base = cursor
                    }
                    val t = track!!
                    if (epoch != flushEpoch) {
                        epoch = flushEpoch
                        t.pause(); t.flush()
                        cursor = written.get(); base = cursor
                    }
                    if (!primaryPlaying) {
                        if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                        Thread.sleep(15); continue
                    }
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                    t.setVolume(volume)
                    sync(t, f)

                    val now = written.get()
                    if (now - cursor > CAPACITY) { base += now - CAPACITY - cursor; cursor = now - CAPACITY }
                    val avail = (now - cursor).toInt()
                    val n = minOf(avail, chunk.size) / f.frameSize * f.frameSize
                    if (n <= 0) { Thread.sleep(5); continue }
                    val start = (cursor % CAPACITY).toInt()
                    val first = minOf(n, CAPACITY - start)
                    System.arraycopy(ring, start, chunk, 0, first)
                    if (first < n) System.arraycopy(ring, 0, chunk, first, n - first)
                    cursor += n
                    val samples = toFloat(chunk, n, f)
                    t.write(floats, 0, samples, AudioTrack.WRITE_BLOCKING)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                Log.e(TAG, "mirror ${label(device)} failed", t)
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                track = null
            }
        }

        private fun build(f: Fmt): Boolean {
            runCatching { track?.release() }
            track = null
            val ch = if (f.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val outCh = if (f.channels == 1) 1 else 2
            val min = AudioTrack.getMinBufferSize(f.sampleRate, ch, AudioFormat.ENCODING_PCM_FLOAT)
            if (min <= 0) { Log.w(TAG, "no float track at ${f.sampleRate} Hz for ${label(device)}"); return false }
            // ~80 ms of float: deep enough not to glitch, shallow enough to correct quickly.
            val size = maxOf(min, f.sampleRate * outCh * 4 * 80 / 1000)
            return runCatching {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(f.sampleRate).setChannelMask(ch).build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                t.setPreferredDevice(device)
                track = t
                trackFmt = f
                epoch = flushEpoch
                Log.i(TAG, "mirror track for ${label(device)}: ${f.sampleRate} Hz x$outCh float, buffer $size")
                true
            }.getOrElse { Log.e(TAG, "mirror track build failed for ${label(device)}", it); false }
        }

        /**
         * Compare which source byte each output is playing and close the gap when it is past
         * tolerance. Ahead: write that much silence. Behind: skip that much of the ring.
         */
        private fun sync(t: AudioTrack, f: Fmt) {
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastSyncAt < SYNC_INTERVAL_MS) return
            lastSyncAt = nowMs
            val p = primaryTrack ?: return
            val pHead = runCatching { p.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrNull() ?: return
            val mHead = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrNull() ?: return
            if (pHead == 0L || mHead == 0L) return       // not started yet; nothing to compare
            val primaryAt = primaryBase + pHead * f.frameSize
            val mirrorAt = base + mHead * f.frameSize
            val offsetMs = extraLatencyMs(device.type) +
                runCatching { Settings.Global.getInt(app!!.contentResolver, KEY_OFFSET, 0) }.getOrDefault(0)
            val bytesPerMs = f.sampleRate.toLong() * f.frameSize / 1000
            // Positive: the mirror is ahead of where it should be.
            val err = mirrorAt - primaryAt - offsetMs * bytesPerMs
            val errMs = err / bytesPerMs
            if (kotlin.math.abs(errMs) < SYNC_TOLERANCE_MS) return
            val frames = (kotlin.math.abs(err) / f.frameSize).toInt()
            if (err > 0) {
                val outCh = if (f.channels == 1) 1 else 2
                val silence = FloatArray(minOf(frames, f.sampleRate) * outCh)
                t.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                base -= silence.size / outCh * f.frameSize.toLong()
            } else {
                val skip = frames.toLong() * f.frameSize
                cursor += skip
                base += skip
            }
            Log.d(TAG, "sync ${label(device)}: ${if (err > 0) "ahead" else "behind"} ${kotlin.math.abs(errMs)} ms, corrected")
        }

        /** Decode [n] bytes of [f] into [floats], stereo or mono. Returns the sample count. */
        private fun toFloat(src: ByteArray, n: Int, f: Fmt): Int {
            val frames = n / f.frameSize
            val outCh = if (f.channels == 1) 1 else 2
            if (floats.size < frames * outCh) floats = FloatArray(frames * outCh)
            var o = 0
            for (fr in 0 until frames) {
                val at = fr * f.frameSize
                for (c in 0 until outCh) {
                    val i = at + c * f.bytesPerSample
                    floats[o++] = when {
                        f.isFloat -> java.lang.Float.intBitsToFloat(
                            (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8) or
                                ((src[i + 2].toInt() and 0xFF) shl 16) or (src[i + 3].toInt() shl 24))
                        f.bytesPerSample == 2 ->
                            ((src[i].toInt() and 0xFF) or (src[i + 1].toInt() shl 8)) / 32768f
                        f.bytesPerSample == 3 ->
                            ((src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8) or
                                (src[i + 2].toInt() shl 16)) / 8388608f
                        else ->
                            ((src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8) or
                                ((src[i + 2].toInt() and 0xFF) shl 16) or (src[i + 3].toInt() shl 24)) / 2147483648f
                    }
                }
            }
            return o
        }
    }
}
