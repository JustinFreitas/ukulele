package dev.arbjerg.ukulele.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TrackTimeParserTest {
    @Test
    fun `parseTimeOffsetMs parses colon and unit timestamps`() {
        assertEquals(45_000L, TrackTimeParser.parseTimeOffsetMs("45"))
        assertEquals(90_000L, TrackTimeParser.parseTimeOffsetMs("1:30"))
        assertEquals(3_723_000L, TrackTimeParser.parseTimeOffsetMs("01:02:03"))
        assertEquals(90_000L, TrackTimeParser.parseTimeOffsetMs("90s"))
        assertEquals(90_000L, TrackTimeParser.parseTimeOffsetMs("1m30s"))
        assertEquals(7_510_000L, TrackTimeParser.parseTimeOffsetMs("2h5m10s"))
        assertNull(TrackTimeParser.parseTimeOffsetMs(""))
        assertNull(TrackTimeParser.parseTimeOffsetMs("invalid"))
    }

    @Test
    fun `parseLabelOffsetMs extracts t or start tag from queue label`() {
        assertEquals(90_000L, TrackTimeParser.parseLabelOffsetMs("[Boss Fight, v:65, t:1:30] - Song Title"))
        assertEquals(45_000L, TrackTimeParser.parseLabelOffsetMs("[t:45s, v:40] - Song Title"))
        assertEquals(120_000L, TrackTimeParser.parseLabelOffsetMs("[Dungeon, start:2:00]"))
        assertEquals(0L, TrackTimeParser.parseLabelOffsetMs("[Intro, t:0] - Song Title"))
        assertNull(TrackTimeParser.parseLabelOffsetMs("[Some Label, v:42] - Song Title"))
        assertNull(TrackTimeParser.parseLabelOffsetMs("Plain Title t:1:30"))
    }

    @Test
    fun `extractSourceAndOffset strips fragment and parses URL or local query offsets`() {
        assertEquals(
            "C:\\music\\boss.ogg" to 90_000L,
            TrackTimeParser.extractSourceAndOffset("C:\\music\\boss.ogg#t=1:30"),
        )
        assertEquals(
            "C:\\music\\boss.ogg" to 90_000L,
            TrackTimeParser.extractSourceAndOffset("C:\\music\\boss.ogg?t=1:30"),
        )
        assertEquals(
            "https://youtu.be/abc?t=90" to 90_000L,
            TrackTimeParser.extractSourceAndOffset("https://youtu.be/abc?t=90"),
        )
        assertEquals(
            "https://www.youtube.com/watch?v=abc&t=1m30s" to 90_000L,
            TrackTimeParser.extractSourceAndOffset("https://www.youtube.com/watch?v=abc&t=1m30s"),
        )
        assertEquals(
            "https://example.com/song.mp3" to 75_000L,
            TrackTimeParser.extractSourceAndOffset("https://example.com/song.mp3#start=1:15"),
        )
    }

    @Test
    fun `TrackStartOffset toString returns empty JSON object for YoutubeAudioTrack compatibility`() {
        assertEquals("{}", TrackStartOffset(90_000L).toString())
    }
}
