package com.miku.launcher.bpm

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Calendar

/**
 * The hidden tier: OS easter eggs earned in the BPM game.
 *
 * WHY A SECOND TIER. The visible ladder in [MikuUnlocks] is a promise ("200 judged taps → Honey
 * Sweet"), and a promise is a fine reason to keep playing. It is not a reason to be delighted.
 * These are the other kind of reward: things that change the OS in ways a player would want and
 * would never find, earned by doing something odd. The game never lists them. It shows how many
 * remain and one cryptic hint a day, and the first time anyone learns what a secret IS, is the
 * moment they earn it.
 *
 * SAME CONTRACT AS THE VISIBLE TIER. Secrets are ordinary entries in Settings.Global
 * `miku_unlocks` (written only by [MikuUnlocks.unlock]); the consumer in whichever app reads the
 * id and changes behaviour. Ids are namespaced `secret.*` and, like every unlock id, are never
 * renamed. A secret that draws something the player might not always want is toggleable from the
 * Secrets list ({"off": true} in its entry, see [MikuUnlocks.setEnabled]) and consumers check
 * [MikuUnlocks.isEnabled].
 *
 * SAME RULE AS THE VISIBLE TIER: only judged taps pay. Every condition below is fed from the
 * judged-tap branch of the game (a tap measured against a real detected beat) or from an event
 * that only a judged tap can cause. The two exceptions are honest about it: golden pickups
 * (a timed bonus target and lucky notes, which are only lucky when hit GREAT or better against a
 * real beat) and the season rank, which itself only moves on judged taps.
 */
object MikuSecrets {

    // ---- ids (never rename) -------------------------------------------------------------------

    /** Launcher top-bar battery badge + SystemUI shade battery chip draw a leek. */
    const val NEGI_BATTERY = "secret.os.battery.negi"
    /** Miku Music Tape Mode: a cassette shell that is invisible until earned. */
    const val TAPE_MIKU39 = "secret.player.tape.miku39"
    /** Miku Music Now Playing: a leek spins in the corner, one turn per two beats. */
    const val LEEK_SPIN = "secret.player.leek_spin"
    /** Lockscreen clock becomes the holographic plasma face. */
    const val HOLO_LOCK_CLOCK = "secret.os.lock.holo_clock"
    /** Home screen: double-tap the hearts clock for penlights and stage beams on the beat. */
    const val CONCERT_MODE = "secret.os.home.concert_mode"
    /** Home screen: leeks drift down the wallpaper. */
    const val NEGI_SNOW = "secret.os.home.negi_snow"
    /** A synthesized Mi-Ku chime greets every boot (same boot-sound switch as the stock jingle). */
    const val BOOT_CHIME = "secret.os.boot.chime39"
    /** Miku Music's icon on the home screen and in the drawer turns gold. */
    const val GOLDEN_ICON = "secret.os.icon.golden_music"
    /** A sixth game skin that is not in the skin list until earned. */
    const val HOLO_SKIN = "secret.game.skin.hologram"
    /** A top-bar theme that changes with the month. */
    const val SEASONAL_TOPBAR = "secret.os.topbar.seasonal"

    data class Secret(
        val id: String,
        val title: String,
        /** Where it takes effect, shown only once earned. */
        val where: String,
        /** What it does and how to use it, shown only once earned. */
        val howTo: String,
        /** The cryptic line shown while it is still hidden. Never names the reward. */
        val hint: String,
        /** True when the Secrets list offers an on/off switch for it. */
        val toggleable: Boolean
    )

    /**
     * Conditions are deliberately all different kinds of play: time of day, a clean streak,
     * fever count, the wall clock, a no-miss run on a fast track, variety, an exact tempo, a
     * grade held over a long run, lucky pickups and the monthly rank. No single way of playing
     * finds them all, which is the point of having them.
     */
    val ALL: List<Secret> = listOf(
        Secret(NEGI_BATTERY, "Leek battery icon", "Top bar and notification shade",
            "Your battery icon is a leek now. The more green, the more charge.",
            "Some leeks only grow after midnight.", true),
        Secret(TAPE_MIKU39, "MIKU 39 cassette shell", "Miku Music, Tape Mode",
            "A new cassette shell. Pick it in Tape Mode's theme list or keep tapping the shell button.",
            "Mi-ku. Thirty-nine clean notes in a row, no stumbles.", false),
        Secret(LEEK_SPIN, "Spinning leek on Now Playing", "Miku Music, Now Playing",
            "A leek spins in the corner of Now Playing, one turn every two beats.",
            "Catch the fever three times before you leave the stage.", true),
        Secret(HOLO_LOCK_CLOCK, "Hologram lock screen clock", "Lock screen",
            "The lock screen clock switches to the big hologram style.",
            "Once an hour, for one minute, the clock is listening. Be perfect then.", true),
        Secret(CONCERT_MODE, "Concert lights on the home screen", "Home screen",
            "Double-tap the clock on the home screen while music plays. Glow sticks and stage lights move to the beat.",
            "Fifty notes without a miss, on something fast.", true),
        Secret(NEGI_SNOW, "Falling leeks on the wallpaper", "Home screen wallpaper",
            "Leeks drift down your wallpaper, and fall faster while music plays.",
            "Seven different voices. Sing along with each of them.", true),
        Secret(BOOT_CHIME, "Miku startup chime", "Plays after a restart",
            "Miku's own chime plays when the device starts. It follows the boot sound switch.",
            "Her name hides in a tempo too: one, three, nine.", true),
        Secret(GOLDEN_ICON, "Gold Miku Music icon", "Home screen and app drawer",
            "Miku Music's icon is gold now. You earned it.",
            "A grade so high it turns things to gold. Hold it for a hundred notes.", true),
        Secret(HOLO_SKIN, "Hologram game skin", "BPM game, Skins",
            "A new skin: heart notes, scanline lane, glitchy hits.",
            "Gold falls from the sky sometimes. Catch ten pieces.", false),
        Secret(SEASONAL_TOPBAR, "Seasonal top bar", "Top bar themes",
            "A top bar that changes colors every month. Long-press the top bar to cycle to it.",
            "Climb to gold before the month turns.", false)
    )

    fun byId(id: String): Secret? = ALL.firstOrNull { it.id == id }

    // ---- thresholds (one place to tune) ----------------------------------------------------

    private const val NIGHT_TAPS = 39
    private const val CLEAN_STREAK = 39
    private const val FEVERS_PER_SESSION = 3
    private const val FAST_TRACK_BPM = 158f       // "160 BPM or so": the detector is an estimator
    private const val FAST_RUN_TAPS = 50
    private const val ARTIST_TAPS_EACH = 20
    private const val ARTISTS_NEEDED = 7
    private const val TEMPO_39_BPM = 139f
    private const val TEMPO_39_RUN = 30
    private const val GOLD_GRADE_TAPS = 100
    private const val GOLD_GRADE_PCT = 96f
    private const val GOLDEN_PICKUPS = 10

    // ---- persisted progress ------------------------------------------------------------------

    private const val PREFS = "miku_secrets_progress"
    private var prefs: SharedPreferences? = null
    /** artist (lower-cased) -> judged taps played along to them, lifetime. */
    private val artistTaps = HashMap<String, Int>()
    private var goldenPickups = 0
    private var dirtyTaps = 0

    // ---- session progress (reset each time the stage opens) ---------------------------------

    private var nightTaps = 0
    private var cleanStreak = 0
    private var sessionFevers = 0
    private var sessionTaps = 0
    private var sessionGreat = 0
    private var fastRun = 0
    private var tempo39Run = 0
    private var runKey: String? = null

    private val _foundCount = MutableStateFlow(0)
    /** How many secrets are earned. The UI shows "N remain", never the list. */
    val foundCount: StateFlow<Int> = _foundCount.asStateFlow()

    @Synchronized
    fun init(ctx: Context) {
        if (prefs == null) {
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            goldenPickups = p.getInt("golden", 0)
            runCatching {
                val o = JSONObject(p.getString("artists", "{}") ?: "{}")
                val it = o.keys()
                while (it.hasNext()) { val k = it.next(); artistTaps[k] = o.optInt(k) }
            }
        }
        refreshFound(ctx)
    }

    fun refreshFound(ctx: Context) {
        val have = MikuUnlocks.unlockedIds(ctx)
        _foundCount.value = ALL.count { it.id in have }
    }

    /** A new set on stage. Session conditions ("in one session") start from zero. */
    @Synchronized
    fun beginSession() {
        nightTaps = 0; cleanStreak = 0; sessionFevers = 0
        sessionTaps = 0; sessionGreat = 0
        fastRun = 0; tempo39Run = 0; runKey = null
    }

    /** The track changed: runs that are "on one track" restart. */
    @Synchronized
    fun onTrackChanged(key: String?) {
        if (key == runKey) return
        runKey = key
        fastRun = 0; tempo39Run = 0
    }

    /** Fever started from judged hits (see MikuBeatClickerEngine.tap). */
    @Synchronized
    fun onFeverStarted() { sessionFevers++ }

    /** A golden leek or a lucky note was caught. Returns secrets earned by it. */
    fun onGoldenPickup(ctx: Context): List<Secret> {
        val n: Int
        synchronized(this) {
            goldenPickups++
            n = goldenPickups
            prefs?.edit()?.putInt("golden", n)?.apply()
        }
        return if (n >= GOLDEN_PICKUPS) grant(ctx, HOLO_SKIN) else emptyList()
    }

    /**
     * One JUDGED tap. [bpm] is the detected tempo (0 when none is locked — then no tempo-based
     * condition can advance). Returns the secrets this tap earned. Call off the main thread: a
     * grant writes Settings.Global.
     */
    fun onJudgedTap(ctx: Context, accuracy: HitAccuracy, bpm: Float, artist: String?): List<Secret> {
        val earned = ArrayList<String>()
        synchronized(this) {
            sessionTaps++
            if (accuracy.isGreatOrBetter) sessionGreat++

            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)

            if (hour in 0..4) { nightTaps++; if (nightTaps >= NIGHT_TAPS) earned.add(NEGI_BATTERY) }

            cleanStreak = if (accuracy.isGreatOrBetter) cleanStreak + 1 else 0
            if (cleanStreak >= CLEAN_STREAK) earned.add(TAPE_MIKU39)

            if (sessionFevers >= FEVERS_PER_SESSION) earned.add(LEEK_SPIN)

            if (accuracy == HitAccuracy.PERFECT && minute == 39) earned.add(HOLO_LOCK_CLOCK)

            // No-miss run on a fast track. A slower stretch (or no tempo) restarts it, so the
            // whole fifty were played against a fast beat.
            fastRun = if (accuracy == HitAccuracy.MISS || bpm < FAST_TRACK_BPM) 0 else fastRun + 1
            if (fastRun >= FAST_RUN_TAPS) earned.add(CONCERT_MODE)

            // 139 BPM, with the detector's own tolerance, held for a run.
            tempo39Run = if (bpm > 0f && MikuRhythmTiming.bpmMatches(bpm, TEMPO_39_BPM)) tempo39Run + 1 else 0
            if (tempo39Run >= TEMPO_39_RUN) earned.add(BOOT_CHIME)

            if (sessionTaps >= GOLD_GRADE_TAPS && sessionGreat * 100f / sessionTaps >= GOLD_GRADE_PCT) {
                earned.add(GOLDEN_ICON)
            }

            val a = artist?.trim()?.lowercase()
            if (!a.isNullOrEmpty()) {
                val c = (artistTaps[a] ?: 0) + 1
                artistTaps[a] = c
                dirtyTaps++
                // Persist on the tap that crosses the bar (it matters) and otherwise in batches.
                if (c == ARTIST_TAPS_EACH || dirtyTaps >= 15) persistArtists()
                if (artistTaps.values.count { it >= ARTIST_TAPS_EACH } >= ARTISTS_NEEDED) earned.add(NEGI_SNOW)
            }

            val rank = MikuBpmSeasonsEngine.currentSeason.value.rank
            if (rank.ordinal >= MikuBpmSeasonsEngine.SeasonRank.GOLD.ordinal) earned.add(SEASONAL_TOPBAR)
        }
        if (earned.isEmpty()) return emptyList()
        val out = ArrayList<Secret>()
        for (id in earned) out.addAll(grant(ctx, id))
        return out
    }

    private fun persistArtists() {
        dirtyTaps = 0
        val o = JSONObject()
        for ((k, v) in artistTaps) o.put(k, v)
        prefs?.edit()?.putString("artists", o.toString())?.apply()
    }

    /** Grant through the one writer; returns the secret only on its FIRST grant. */
    private fun grant(ctx: Context, id: String): List<Secret> {
        if (MikuUnlocks.isUnlocked(ctx, id)) return emptyList()
        if (!MikuUnlocks.unlock(ctx, id)) return emptyList()
        refreshFound(ctx)
        val s = byId(id) ?: return emptyList()
        MikuCelebrations.push(
            MikuCelebrations.Item(true, s.title, s.where, s.howTo)
        )
        return listOf(s)
    }

    /**
     * Today's hint: one locked secret's cryptic line, the same all day so it reads as a clue
     * rather than a slot machine. Null once every secret is found.
     */
    fun hintOfTheDay(ctx: Context): String? {
        val have = MikuUnlocks.unlockedIds(ctx)
        val locked = ALL.filter { it.id !in have }
        if (locked.isEmpty()) return null
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        return locked[day % locked.size].hint
    }
}

/**
 * The unlock moment, as a queue. Anything that grants a reward (visible or secret) pushes here;
 * the game shows one full-screen celebration at a time and pops it when the player taps.
 *
 * A queue rather than a single slot because two rewards can land on the same tap (a clean streak
 * that also crosses a judged-tap milestone), and the second one must not silently overwrite the
 * first — a reward nobody saw is a reward that did not happen.
 */
object MikuCelebrations {
    data class Item(
        val secret: Boolean,
        val title: String,
        val where: String,
        val howTo: String
    )

    private val _queue = MutableStateFlow<List<Item>>(emptyList())
    val queue: StateFlow<List<Item>> = _queue.asStateFlow()

    @Synchronized
    fun push(item: Item) {
        _queue.value = _queue.value + item
        com.miku.launcher.audio.MikuSeasonalAudioEngine.playLevelUpFanfare()
    }

    @Synchronized
    fun dismiss() {
        val q = _queue.value
        if (q.isNotEmpty()) _queue.value = q.drop(1)
    }
}
