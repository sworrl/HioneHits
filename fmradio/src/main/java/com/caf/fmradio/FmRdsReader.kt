package com.caf.fmradio

import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * RDS decoded from the Si4705, by reading /dev/radio0 directly.
 *
 * WHY NOT THE API WE ALREADY HAVE. `FmReceiver.getPSInfo()` / `getRTInfo()` / `getRTPlusInfo()`
 * are the QTI HCI API, and on this board that talks to the Qualcomm WCN FM core, which has no
 * antenna: it reads RSSI 0 at every frequency in the band. Its RDS callbacks will therefore
 * never fire with anything, no matter how many group masks we enable. The part that receives is
 * the Si4705 on /dev/radio0, and HiBy's JNI exposes no RDS accessor for it at all.
 *
 * What it does expose is the device node, mode 0666. V4L2 radio drivers that set
 * V4L2_CAP_RDS_CAPTURE deliver RDS as a byte stream of three-byte blocks:
 *
 *     [0] LSB of the 16-bit block
 *     [1] MSB of the 16-bit block
 *     [2] status: bits 0-1 = block id (0=A, 1=B, 2=C, 3=D), bit 6 = corrected, bit 7 = error
 *
 * So the decoder below is ordinary RDS group parsing on top of a file read. If the driver does
 * not support RDS capture the read fails or returns nothing, and [available] stays false rather
 * than the UI inventing a station name.
 *
 * Groups handled, which is what a now-playing display needs:
 *   0A/0B  PS, the eight-character station name, and the TA/TP/MS flags
 *   2A/2B  RT, the 64-character radio text, which is where most US stations put "Artist - Title"
 *   3A     ODA announcement, used to discover which group carries RT+
 *   RT+    tagged items inside RT: artist, title, and the rest of the class table
 */
object FmRdsReader {

    private const val TAG = "FmRdsReader"
    private const val NODE = "/dev/radio0"

    /** RT+ carries typed items; these are the ones a now-playing display cares about. */
    const val RTPLUS_ITEM_TITLE = 1
    const val RTPLUS_ITEM_ALBUM = 2
    const val RTPLUS_ITEM_ARTIST = 4
    const val RTPLUS_ITEM_STATION = 32
    const val RTPLUS_INFO_NEWS = 12
    const val RTPLUS_INFO_URL = 39
    /** 0 means "no item", which is the only honest way to say a song is not currently tagged. */
    const val RTPLUS_DUMMY = 0

    data class Rds(
        /** Programme identification, the station's unique code. */
        val pi: Int? = null,
        /** Programme type, the genre code; the name depends on RDS vs RBDS. */
        val pty: Int? = null,
        /** Station name, eight characters. */
        val ps: String = "",
        /** Radio text, up to 64 characters. */
        val rt: String = "",
        /** Artist as RT+ tagged it, not as something guessed out of [rt]. */
        val rtPlusArtist: String? = null,
        /** Title as RT+ tagged it. */
        val rtPlusTitle: String? = null,
        /** Traffic programme / traffic announcement flags. */
        val tp: Boolean = false,
        val ta: Boolean = false,
        /** Music/speech flag: true = music, false = speech. Broadcast by the station, not inferred. */
        val musicSpeech: Boolean? = null,
        /** Groups successfully decoded since the last tune, so the UI can show it warming up. */
        val groups: Int = 0,
    )

    @Volatile private var reported = false

    /**
     * Whether the driver actually gives us RDS. Null until the first read has been attempted.
     * False is a real answer and the UI says so rather than showing an empty station name.
     */
    @Volatile var available: Boolean? = null
        private set

    /** Open the node for reading. Returns null with one logged reason if that is not possible. */
    fun open(): FileInputStream? = try {
        val f = File(NODE)
        when {
            !f.exists() -> { fail("$NODE is not present"); null }
            !f.canRead() -> { fail("$NODE exists but is not readable by this process"); null }
            else -> FileInputStream(f)
        }
    } catch (t: Throwable) {
        fail("${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun fail(why: String) {
        available = false
        if (!reported) { reported = true; Log.w(TAG, "RDS unavailable: $why") }
    }

    /** Reset the accumulated text when the station changes; none of it survives a retune. */
    fun newStation() {
        psChars.fill(' ')
        rtChars.fill(' ')
        rtPlusArtist = null
        rtPlusTitle = null
        rtPlusGroup = null
        groups = 0
        state = Rds()
    }

    private val psChars = CharArray(8) { ' ' }
    private val rtChars = CharArray(64) { ' ' }
    private var rtPlusArtist: String? = null
    private var rtPlusTitle: String? = null
    private var rtPlusGroup: Int? = null
    private var groups = 0
    private var lastRtAb: Int = -1

    @Volatile var state = Rds()
        private set

    /**
     * Feed one three-byte V4L2 RDS block. Returns true when the decoded state changed.
     *
     * Blocks flagged in error are dropped: a corrupted PS block is how radios end up showing
     * garbage station names, and a dropped block only costs a second of latency.
     */
    fun offerBlock(b0: Int, b1: Int, b2: Int): Boolean {
        if (b2 and 0x80 != 0) return false                       // error bit set, discard
        val block = ((b1 and 0xFF) shl 8) or (b0 and 0xFF)
        val which = b2 and 0x03
        when (which) {
            0 -> { pendingPi = block; have = 1 }                  // block A: PI
            1 -> if (have >= 1) { pendingB = block; have = 2 }    // block B: group type + flags
            2 -> if (have >= 2) { pendingC = block; have = 3 }
            3 -> if (have >= 3) { return decodeGroup(pendingPi, pendingB, pendingC, block) }
        }
        return false
    }

    private var pendingPi = 0
    private var pendingB = 0
    private var pendingC = 0
    private var have = 0

    private fun decodeGroup(a: Int, b: Int, c: Int, d: Int): Boolean {
        have = 0
        groups++
        val groupType = (b shr 12) and 0x0F
        val version = (b shr 11) and 0x01          // 0 = A, 1 = B
        val tp = (b shr 10) and 0x01 == 1
        val pty = (b shr 5) and 0x1F
        var changed = false

        when (groupType) {
            0 -> {                                  // 0A / 0B: programme service name
                val ta = (b shr 4) and 0x01 == 1
                val ms = (b shr 3) and 0x01 == 1
                val idx = (b and 0x03) * 2
                val c1 = ((d shr 8) and 0xFF).toChar()
                val c2 = (d and 0xFF).toChar()
                if (idx in 0..6) {
                    if (psChars[idx] != c1 || psChars[idx + 1] != c2) changed = true
                    psChars[idx] = c1
                    psChars[idx + 1] = c2
                }
                state = state.copy(tp = tp, ta = ta, musicSpeech = ms)
            }
            2 -> {                                  // 2A / 2B: radio text
                val ab = (b shr 4) and 0x01
                if (ab != lastRtAb) {               // A/B flip means a new message, clear the old
                    rtChars.fill(' ')
                    lastRtAb = ab
                    changed = true
                }
                val seg = b and 0x0F
                if (version == 0) {
                    val base = seg * 4
                    val chars = charArrayOf(
                        ((c shr 8) and 0xFF).toChar(), (c and 0xFF).toChar(),
                        ((d shr 8) and 0xFF).toChar(), (d and 0xFF).toChar()
                    )
                    for (i in chars.indices) if (base + i < 64) {
                        if (rtChars[base + i] != chars[i]) changed = true
                        rtChars[base + i] = chars[i]
                    }
                } else {
                    val base = seg * 2
                    val chars = charArrayOf(((d shr 8) and 0xFF).toChar(), (d and 0xFF).toChar())
                    for (i in chars.indices) if (base + i < 64) {
                        if (rtChars[base + i] != chars[i]) changed = true
                        rtChars[base + i] = chars[i]
                    }
                }
            }
            3 -> {                                  // 3A: which group carries RT+, if any
                if (version == 0 && d == 0x4BD7) {  // the RT+ ODA application id
                    rtPlusGroup = (c shr 1) and 0x1F
                    Log.i(TAG, "station carries RT+ in group ${rtPlusGroup}")
                }
            }
            else -> {
                if (rtPlusGroup != null && groupType == rtPlusGroup) {
                    changed = decodeRtPlus(c, d) || changed
                }
            }
        }

        val psNow = String(psChars).trim()
        val rtNow = String(rtChars).trim()
        val next = state.copy(
            pi = a, pty = pty, ps = psNow, rt = rtNow,
            rtPlusArtist = rtPlusArtist, rtPlusTitle = rtPlusTitle, groups = groups,
        )
        if (next != state) { state = next; changed = true }
        return changed
    }

    /**
     * RT+ packs two tagged items into blocks C and D: for each, a content class and a
     * start/length pair indexing into the radio text. So the artist and title are not parsed out
     * of RT with a guess at the separator, they are the ranges the station pointed at.
     */
    private fun decodeRtPlus(c: Int, d: Int): Boolean {
        val class1 = ((c shr 1) and 0x3F)
        val start1 = ((c and 0x01) shl 5) or ((d shr 11) and 0x1F)
        val len1 = (d shr 5) and 0x3F
        val class2 = ((d shr 1) and 0x0F)          // truncated; the rest is in the next group
        val rt = String(rtChars)
        var changed = false

        fun slice(start: Int, len: Int): String? {
            if (len <= 0 || start >= rt.length) return null
            return rt.substring(start, minOf(start + len + 1, rt.length)).trim().ifEmpty { null }
        }

        when (class1) {
            RTPLUS_ITEM_TITLE -> slice(start1, len1)?.let { if (it != rtPlusTitle) { rtPlusTitle = it; changed = true } }
            RTPLUS_ITEM_ARTIST -> slice(start1, len1)?.let { if (it != rtPlusArtist) { rtPlusArtist = it; changed = true } }
            RTPLUS_DUMMY -> {
                // The station is explicitly saying there is no tagged item right now, which is
                // what it sends between songs. Clear rather than leave the last track showing.
                if (rtPlusTitle != null || rtPlusArtist != null) {
                    rtPlusTitle = null; rtPlusArtist = null; changed = true
                }
            }
        }
        if (class2 == RTPLUS_DUMMY && class1 == RTPLUS_DUMMY) { /* nothing tagged */ }
        return changed
    }

    /** Mark RDS as working once a group has actually decoded. */
    fun markAvailable() { if (available != true) { available = true; Log.i(TAG, "RDS is decoding") } }
}
