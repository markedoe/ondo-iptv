package com.markedoe.ondo.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.HangGuard
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The subtitle tracks of a real Matroska file reach the Subtitles panel's list once ExoPlayer has read the file's
 * start, with the player set up as [PlayerActivity] sets it up (Media3 reads text and picture subtitles into cues
 * while extracting).
 *
 * `media/subtitles.mkv` (2 s, 64×64, made for this test with ffmpeg): an H.264 picture, an SRT track in English, an
 * ASS track in French and a PGS (picture) track in German, the last written by hand as a two-pixel .sup.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddedSubtitlesTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    private val words = TrackWords(
        track = { "Track $it" },
        forced = "Forced",
        hearingImpaired = "SDH",
        commentary = "Commentary",
        audioDescription = "Audio description",
        mono = "Mono",
        stereo = "Stereo",
        unsupported = "Cannot be shown on this device",
    )

    @Test
    fun theTextAndPictureSubtitlesOfAnMkvAreListed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "subtitles.mkv")
        javaClass.getResourceAsStream("/media/subtitles.mkv")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val player = ExoPlayer.Builder(context).build()
        var error: PlaybackException? = null
        val seen = mutableListOf<Tracks>()
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                seen += tracks
            }

            override fun onPlayerError(failure: PlaybackException) {
                error = failure
            }
        })
        try {
            // Before the file is read, there is nothing to list yet (the panel says the tracks are coming).
            assertTrue(player.currentTracks.isEmpty)
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            val deadline = System.currentTimeMillis() + 30_000
            while (player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT } && error == null && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertNull(error)
            val choices = TrackChoices.of(player.currentTracks, C.TRACK_TYPE_TEXT, words, Locale.ENGLISH)
            assertEquals(listOf("English", "French", "German"), choices.map { it.name })
            assertEquals(listOf(true, true, true), choices.map { it.available })
            // They came with onTracksChanged, which refreshes the panel (PlayerControls.refresh on each event).
            assertTrue(seen.any { tracks -> tracks.groups.count { it.type == C.TRACK_TYPE_TEXT } == 3 })
        } finally {
            player.release()
            file.delete()
        }
    }
}
