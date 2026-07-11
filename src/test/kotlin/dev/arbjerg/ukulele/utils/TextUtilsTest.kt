package dev.arbjerg.ukulele.utils

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TextUtilsTest {
    @Test
    fun `replayGainLabel returns formatted string when replayGainDb is present`() {
        val track = mock(AudioTrack::class.java)
        `when`(track.replayGainDb).thenReturn(-5.24f)

        val label = TextUtils.replayGainLabel(track)
        assertEquals("-5.2 dB", label)
    }

    @Test
    fun `replayGainLabel returns null when replayGainDb is null`() {
        val track = mock(AudioTrack::class.java)
        `when`(track.replayGainDb).thenReturn(null)

        val label = TextUtils.replayGainLabel(track)
        assertNull(label)
    }

    @Test
    fun `humanReadableTime formats durations correctly`() {
        // Less than an hour (mm:ss)
        assertEquals("00:00", TextUtils.humanReadableTime(0))
        assertEquals("00:05", TextUtils.humanReadableTime(5000))
        assertEquals("09:59", TextUtils.humanReadableTime(599000))
        assertEquals("59:59", TextUtils.humanReadableTime(3599000))

        // More than an hour (hh:mm:ss)
        assertEquals("01:00:00", TextUtils.humanReadableTime(3600000))
        assertEquals("02:30:15", TextUtils.humanReadableTime(9015000))
        assertEquals("12:00:00", TextUtils.humanReadableTime(43200000))
    }
}
