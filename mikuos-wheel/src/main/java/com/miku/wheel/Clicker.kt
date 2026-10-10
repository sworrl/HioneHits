package com.miku.wheel

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sin

/**
 * The wheel's tick. The originals clicked through a tiny piezo speaker in the case, so this is a
 * synthesized few-millisecond tick (no sampled audio), played through SoundPool for low latency,
 * plus a haptic tick. Both can be turned off in Settings.
 */
class Clicker(ctx: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
    private var tickId = 0
    private var ready = false
    private var lastAt = 0L

    var sound = true
    var haptic = true

    init {
        pool.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        try {
            val f = File(ctx.cacheDir, "tick.wav")
            if (!f.exists()) writeTick(f)
            tickId = pool.load(f.path, 1)
        } catch (_: Throwable) { }
    }

    /** One detent. Rate-limited so a fast spin doesn't turn into a buzz. */
    fun tick(view: View?) {
        val now = System.nanoTime()
        if (now - lastAt < 18_000_000L) return
        lastAt = now
        if (sound && ready) pool.play(tickId, 0.55f, 0.55f, 1, 0, 1f)
        if (haptic) view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A button press: a slightly firmer feel, same sound. */
    fun press(view: View?) {
        if (sound && ready) pool.play(tickId, 0.7f, 0.7f, 1, 0, 0.85f)
        if (haptic) view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun release() = pool.release()

    private fun writeTick(f: File) {
        val rate = 44100
        val n = rate * 7 / 1000
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            // A piezo "tick": a fast-decaying 3.2 kHz burst with a sharp attack.
            val env = exp(-t * 900.0)
            val s = if (sin(2 * Math.PI * 3200 * t) >= 0) 1.0 else -1.0
            pcm[i] = (s * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
        }
        val data = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(n * 2)
        for (s in pcm) data.putShort(s)
        FileOutputStream(f).use { it.write(data.array()) }
    }
}
