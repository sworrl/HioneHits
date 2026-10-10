package com.miku.riot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The whole skin is one activity: a device body drawn in Compose, the emulated LCD inside it,
 * and a [RiotShell] that owns every screen. It is a HOME activity, so it can be the launcher,
 * and it is also what com.miku.riot.action.ENTER opens.
 */
class RiotActivity : ComponentActivity() {

    private lateinit var shell: RiotShell
    private var ticker: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        RiotAssets.load(this)
        shell = RiotShell(this, unlockedOrForced(intent))
        setContent { RiotDevice(shell) }
        // Gesture back arrives here rather than as a key. As HOME there is nowhere to go back
        // to, so it is the Riot's own Back key.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { shell.keyDown(Key.BACK); shell.keyUp(Key.BACK) }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shell.setUnlocked(unlockedOrForced(intent))
        // Home pressed while already home: back to the home screen, like any launcher.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) shell.goHome()
    }

    private fun unlockedOrForced(i: Intent?): Boolean =
        i?.getBooleanExtra("force", false) == true || RiotSystem.isUnlocked(this)

    override fun onStart() {
        super.onStart()
        shell.start()
        ticker = lifecycleScope.launch {
            while (isActive) { shell.tick(); delay(RiotShell.TICK_MS) }
        }
    }

    override fun onStop() {
        ticker?.cancel(); ticker = null
        shell.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (intent?.getBooleanExtra("force", false) != true) shell.setUnlocked(RiotSystem.isUnlocked(this))
    }

    // ---- hardware keys -----------------------------------------------------------------------

    /**
     * The M500's volume ring ("ring-keys") sends VOLUME_UP/DOWN: those are the Riot's Volume
     * keys. The side keys ("gpio-keys-hiby") send media keys: those are the four-way pad, so
     * Forward and Backward tune the radio in radio mode, as they did on the Riot. Back is the
     * Riot's Back key. A D-pad, if one is attached, is the Scroll wheel and Select.
     */
    private fun map(code: Int): Key? = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP -> Key.VOL_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> Key.VOL_DOWN
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK -> Key.PLAY
        KeyEvent.KEYCODE_MEDIA_STOP -> Key.STOP
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Key.FWD
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> Key.REW
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Key.SELECT
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL -> Key.BACK
        KeyEvent.KEYCODE_MENU -> Key.MENU
        else -> null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { shell.wheel(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { shell.wheel(1); return true }
        }
        val k = map(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0 || k == Key.VOL_UP || k == Key.VOL_DOWN) shell.keyDown(k)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val k = map(keyCode) ?: return super.onKeyUp(keyCode, event)
        shell.keyUp(k)
        return true
    }

    // ---- services for the shell ------------------------------------------------------------------

    fun tickFeedback() {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun exitRiot() = RiotSystem.exitToMiku(this)

    /** Power Saver: the LCD goes dark and so does the real screen, until a key wakes it. */
    fun setPowerSaving(on: Boolean) {
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    fun requestAudioPermission() {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(p), 39)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 39 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) shell.loadLibrary()
    }
}
