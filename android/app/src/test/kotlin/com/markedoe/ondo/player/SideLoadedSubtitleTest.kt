package com.markedoe.ondo.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markedoe.ondo.HangGuard
import com.markedoe.ondo.data.ChosenSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A subtitle downloaded from a service: loaded beside the video's tracks, chosen once its track is there. */
@RunWith(AndroidJUnit4::class)
class SideLoadedSubtitleTest {
    @get:Rule(order = Int.MIN_VALUE) val hangGuard = HangGuard()

    @Test
    fun theDownloadedSubtitleIsLoadedWithItsLanguageAndFormat() {
        val configuration = SideLoadedSubtitle.configuration(ChosenSubtitle("/data/subtitles/a.ass", "fr", "ass", "SubDL"))
        assertEquals(MimeTypes.TEXT_SSA, configuration.mimeType)
        assertEquals("fr", configuration.language)
        assertEquals("SubDL", configuration.label)
        assertEquals(C.SELECTION_FLAG_DEFAULT, configuration.selectionFlags)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, SideLoadedSubtitle.mimeType("srt"))
        assertEquals(MimeTypes.TEXT_VTT, SideLoadedSubtitle.mimeType("vtt"))
    }

    @Test
    fun itsTrackIsChosenOnceItIsThere() {
        val embedded = TrackGroup("0:2", Format.Builder().setId("2").setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("fr").build())
        val online = TrackGroup("1:" + SideLoadedSubtitle.ID, Format.Builder().setId("1:" + SideLoadedSubtitle.ID).setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setLanguage("fr").build())
        fun tracks(vararg groups: TrackGroup) = Tracks(groups.map { Tracks.Group(it, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)) })
        val off = TrackSelectionParameters.DEFAULT.buildUpon().setIgnoredTextSelectionFlags(TrackPreferences.SUBTITLES_OFF).build()
        assertNull(SideLoadedSubtitle.select(off, tracks(embedded)))
        val chosen = SideLoadedSubtitle.select(off, tracks(embedded, online))!!
        assertEquals(online, chosen.overrides.values.single().mediaTrackGroup)
        assertEquals(0, chosen.ignoredTextSelectionFlags)
    }

    @Test
    fun aTitleIsSearchedWithoutTheProvidersTagsAndWithItsYear() {
        assertEquals(SubtitleSearchHint("Tom Clancy's Jack Ryan", 2018, 3, 3), SubtitleSearchHint.of("Tom Clancy's Jack Ryan (2018)", null, 3, 3))
        assertEquals(SubtitleSearchHint("Jack Ryan", 2022, 3, 4), SubtitleSearchHint.of("|FR-4K| Jack Ryan (2022)", null, 3, 4))
        assertEquals(SubtitleSearchHint("Northern Lights", 2025, tmdbId = 42), SubtitleSearchHint.of("|EN| Northern Lights", 2025, tmdbId = 42))
        assertEquals("Tom Clancy's Jack Ryan · S03E03", SubtitleSearchHint("Tom Clancy's Jack Ryan", 2018, 3, 3).label())
        assertEquals("Northern Lights (2025)", SubtitleSearchHint("Northern Lights", 2025).label())
    }

    /**
     * The player reads a downloaded subtitle from its file: through the stream's HTTP source alone it could not (the
     * track showed, its cues never came).
     */
    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun thePlayerReadsADownloadedSubtitleFromItsFile() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.cacheDir, "online.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHello\n") }
        val source = PlayerActivity.dataSources(context, androidx.media3.datasource.DefaultHttpDataSource.Factory()).createDataSource()
        try {
            source.open(androidx.media3.datasource.DataSpec(android.net.Uri.fromFile(file)))
            val bytes = androidx.media3.datasource.DataSourceUtil.readToEnd(source)
            assertEquals(file.readText(), bytes.decodeToString())
        } finally {
            source.close()
            file.delete()
        }
    }
}
