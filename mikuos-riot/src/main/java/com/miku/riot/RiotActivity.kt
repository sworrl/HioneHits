package com.miku.riot

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
 * The whole skin is one activity: a device body drawn in Compose, an emulated LCD inside it, and
 * a [RiotShell] that owns every screen. It is a HOME activity, so it can be the launcher, and it
 * is also what com.miku.riot.action.ENTER opens.
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

        shell = RiotShell(this, unlockedOrForced(intent))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // As HOME there is nowhere "back" to go; Back is the Riot's own back/menu button.
            override fun handleOnBackPressed() { shell.input(RiotShell.Key.BACK) }
        })
        setContent { RiotDevice(shell) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shell.setUnlocked(unlockedOrForced(intent))
        // Pressing Home while already home goes back to the main menu, like a launcher should.
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
        shell.setUnlocked(unlockedOrForced(intent))
    }

    /**
     * Hardware keys. The volume ring (input "ring-keys") sends VOLUME_UP/DOWN. The shell decides
     * what that means: on lists it is the jog wheel, on Now Playing it is volume, same as the
     * Riot's own wheel. The side keys on "gpio-keys-hiby" send media keys; those are handled here
     * only to keep the LCD in step and then passed on, so Miku Music still gets them.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (shell.knob(-1)) return true
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (shell.knob(+1)) return true
            KeyEvent.KEYCODE_DPAD_UP -> { shell.wheel(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { shell.wheel(+1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { shell.input(RiotShell.Key.SELECT); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                shell.wake()
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    fun tickFeedback() {
        if (shell.prefs.clicks) {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            getSystemService(AudioManager::class.java)?.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.4f)
        }
    }

    fun exitRiot() = RiotSystem.exitToMiku(this)

    /** Real screen brightness follows the emulated backlight: dim when it times out. */
    fun setBacklight(lit: Boolean) {
        val lp = window.attributes
        val want = if (lit) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else 0.04f
        if (lp.screenBrightness != want) {
            lp.screenBrightness = want
            window.attributes = lp
        }
    }
}
