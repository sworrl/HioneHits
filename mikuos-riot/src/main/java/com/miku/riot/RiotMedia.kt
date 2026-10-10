package com.miku.riot

import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Miku Music stays the player. This is only a remote control and a library browser for it.
 *
 * It connects to Miku Music's Media3 MediaLibraryService (com.miku.player PlaybackService), the
 * same tree Android Auto browses: [root] has Albums, Artists, Songs, Playlists and Player Modes,
 * album and artist nodes hold playable tracks whose media ids are MediaStore ids. Playing works by
 * handing ids back through setMediaItems, which Miku Music resolves to real URIs in its own
 * onSetMediaItems, so the queue, the EQ, the output routing and the lockscreen card are all
 * still Miku Music's.
 *
 * Genres are the one thing that tree does not have, so they come from MediaStore directly and
 * play the same way: a list of MediaStore ids.
 */
class RiotMedia(private val ctx: Context) {

    data class Entry(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val browsable: Boolean,
        val playable: Boolean,
        val durationMs: Long = 0L
    )

    data class Now(
        val connected: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val playing: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val index: Int = 0,
        val count: Int = 0,
        val shuffle: Boolean = false,
        val repeat: Int = Player.REPEAT_MODE_OFF,
        val volume: Int = 0,
        val maxVolume: Int = 15
    )

    private var future: ListenableFuture<MediaBrowser>? = null
    var browser: MediaBrowser? = null
        private set

    fun connect(onChange: () -> Unit) {
        if (future != null) return
        val token = SessionToken(ctx, ComponentName(PLAYER_PKG, PLAYER_SERVICE))
        val f = MediaBrowser.Builder(ctx, token).buildAsync()
        future = f
        f.addListener({
            val b = runCatching { f.get() }.getOrNull()
            if (b == null) {
                Log.w(TAG, "Miku Music did not accept the connection")
                future = null
                onChange()
                return@addListener
            }
            browser = b
            b.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = onChange()
            })
            onChange()
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun release() {
        future?.let { MediaBrowser.releaseFuture(it) }
        future = null
        browser = null
    }

    fun snapshot(): Now {
        val b = browser ?: return Now()
        val m = b.mediaMetadata
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        return Now(
            connected = true,
            title = (m.title ?: m.displayTitle ?: "").toString(),
            artist = (m.artist ?: m.albumArtist ?: "").toString(),
            album = (m.albumTitle ?: "").toString(),
            playing = b.isPlaying,
            positionMs = b.currentPosition.coerceAtLeast(0L),
            durationMs = b.duration.let { if (it < 0) 0L else it },
            index = b.currentMediaItemIndex,
            count = b.mediaItemCount,
            shuffle = b.shuffleModeEnabled,
            repeat = b.repeatMode,
            volume = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0,
            maxVolume = am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 15
        )
    }

    // ---- transport --------------------------------------------------------------------------

    fun playPause() { browser?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun stop() { browser?.let { it.pause(); it.seekTo(0L) } }
    fun next() { browser?.seekToNext() }
    fun prev() { browser?.let { if (it.currentPosition > 3000L) it.seekTo(0L) else it.seekToPrevious() } }
    fun seekBy(ms: Long) { browser?.let { it.seekTo((it.currentPosition + ms).coerceIn(0L, it.duration.coerceAtLeast(0L))) } }
    fun setShuffle(on: Boolean) { browser?.shuffleModeEnabled = on }
    fun setRepeat(mode: Int) { browser?.repeatMode = mode }

    /** Plays [ids] (MediaStore ids or Miku Music container ids) from [start]. */
    fun play(ids: List<String>, start: Int) {
        val b = browser ?: return
        if (ids.isEmpty()) return
        // A very long list is cut to a window around the pick. The player resolves each id
        // against its library, and a few thousand of them is a lot of binder for one press.
        val from = (start - 500).coerceAtLeast(0)
        val to = (start + 1500).coerceAtMost(ids.size)
        val items = ids.subList(from, to).map { MediaItem.Builder().setMediaId(it).build() }
        b.setMediaItems(items, start - from, 0L)
        b.prepare()
        b.play()
    }

    /** A container (album, artist, Liked Songs) plays as a whole; Miku Music expands it. */
    fun playContainer(id: String) {
        val b = browser ?: return
        b.setMediaItem(MediaItem.Builder().setMediaId(id).build())
        b.prepare()
        b.play()
    }

    // ---- browsing ---------------------------------------------------------------------------

    suspend fun children(parentId: String): List<Entry>? {
        val b = browser ?: return null
        val f = b.getChildren(parentId, 0, Int.MAX_VALUE, null)
        val res: LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>? = await(f)
        val list = res?.value ?: return null
        return list.map { it.toEntry() }
    }

    private fun MediaItem.toEntry(): Entry {
        val m = mediaMetadata
        return Entry(
            id = mediaId,
            title = (m.title ?: m.displayTitle ?: mediaId).toString(),
            subtitle = (m.artist ?: m.subtitle)?.toString(),
            browsable = m.isBrowsable == true,
            playable = m.isPlayable == true,
            durationMs = m.durationMs ?: 0L
        )
    }

    private suspend fun <T> await(f: ListenableFuture<T>): T? = suspendCancellableCoroutine { cont ->
        f.addListener({ cont.resume(runCatching { f.get() }.getOrNull()) }, ContextCompat.getMainExecutor(ctx))
        cont.invokeOnCancellation { f.cancel(false) }
    }

    // ---- genres, straight from MediaStore ---------------------------------------------------

    fun genres(): List<Entry> {
        val out = ArrayList<Entry>()
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
                null, null, MediaStore.Audio.Genres.NAME + " COLLATE NOCASE"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)?.trim().orEmpty()
                    if (name.isNotEmpty()) out.add(Entry(GENRE_PREFIX + c.getLong(0), name, null, browsable = true, playable = true))
                }
            }
        }.onFailure { Log.w(TAG, "genres: $it") }
        return out
    }

    fun genreSongs(genreEntryId: String): List<Entry> {
        val gid = genreEntryId.removePrefix(GENRE_PREFIX).toLongOrNull() ?: return emptyList()
        val out = ArrayList<Entry>()
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Audio.Genres.Members.getContentUri("external", gid),
                arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION),
                null, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE"
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(Entry(c.getLong(0).toString(), c.getString(1) ?: "?", c.getString(2),
                        browsable = false, playable = true, durationMs = c.getLong(3)))
                }
            }
        }.onFailure { Log.w(TAG, "genre songs: $it") }
        return out
    }

    companion object {
        private const val TAG = "RiotMedia"
        const val PLAYER_PKG = "com.miku.player"
        const val PLAYER_SERVICE = "com.miku.player.PlaybackService"
        const val GENRE_PREFIX = "[riot-genre]"

        // Miku Music's browse ids (MikuMediaLibrarySession). Read, never invented.
        const val ID_ROOT = "[root]"
        const val ID_ALBUMS = "[albums]"
        const val ID_ARTISTS = "[artists]"
        const val ID_SONGS = "[songs]"
        const val ID_PLAYLISTS = "[playlists]"

        @Suppress("unused")
        private val unusedKeepImport = MediaMetadata.MEDIA_TYPE_MUSIC
    }
}
