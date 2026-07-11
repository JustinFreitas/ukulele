package dev.arbjerg.ukulele.command

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import dev.arbjerg.ukulele.audio.newTestPlayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SeekCommandTest {
    private val command = SeekCommand()

    private fun track(
        title: String,
        length: Long,
        seekable: Boolean = true,
    ): AudioTrack {
        val t = mock(AudioTrack::class.java)
        `when`(t.info).thenReturn(AudioTrackInfo(title, "Author", length, "id-$title", false, "uri-$title"))
        `when`(t.isSeekable).thenReturn(seekable)
        return t
    }

    // --- parseTimeString ---

    @Test
    fun `parseTimeString parses bare seconds`() {
        assertEquals(90000L, command.parseTimeString("90"))
    }

    @Test
    fun `parseTimeString parses minutes and seconds`() {
        assertEquals(90000L, command.parseTimeString("1:30"))
    }

    @Test
    fun `parseTimeString parses hours minutes and seconds`() {
        assertEquals(3723000L, command.parseTimeString("1:02:03"))
    }

    @Test
    fun `parseTimeString returns null for non-numeric input`() {
        assertNull(command.parseTimeString("abc"))
    }

    @Test
    fun `parseTimeString returns null when seconds component is out of range`() {
        assertNull(command.parseTimeString("1:60"))
    }

    // --- formatTime ---

    @Test
    fun `formatTime formats sub-hour and hour-plus durations`() {
        assertEquals("00:00", command.formatTime(0))
        assertEquals("01:02:03", command.formatTime(3723000L))
    }

    @Test
    fun `formatTime reports LIVE for Long MAX_VALUE`() {
        assertEquals("LIVE", command.formatTime(Long.MAX_VALUE))
    }

    // --- invoke ---

    @Test
    fun `not playing anything replies with an error`() {
        val h = newTestPlayer()
        val ctx = fakeContext(command, "0:30", h.player)

        command.invokeBlocking(ctx)

        assertEquals("Not playing anything.", ctx.replies[0])
    }

    @Test
    fun `unseekable track replies with an error`() {
        val h = newTestPlayer()
        val liveStream = track("Live Stream", Long.MAX_VALUE, seekable = false)
        `when`(h.audioPlayer.playingTrack).thenReturn(liveStream)
        val ctx = fakeContext(command, "0:30", h.player)

        command.invokeBlocking(ctx)

        assertEquals("This track is not seekable", ctx.replies[0])
    }

    @Test
    fun `blank argument replies with help`() {
        val h = newTestPlayer()
        val playing = track("Song", 100000L)
        `when`(h.audioPlayer.playingTrack).thenReturn(playing)
        val ctx = fakeContext(command, "", h.player)

        command.invokeBlocking(ctx)

        assertTrue(ctx.replies.isEmpty())
        assertEquals(1, ctx.replyMessages.size)
    }

    @Test
    fun `invalid time string replies with help`() {
        val h = newTestPlayer()
        val playing = track("Song", 100000L)
        `when`(h.audioPlayer.playingTrack).thenReturn(playing)
        val ctx = fakeContext(command, "not-a-time", h.player)

        command.invokeBlocking(ctx)

        assertTrue(ctx.replies.isEmpty())
        assertEquals(1, ctx.replyMessages.size)
    }

    @Test
    fun `seeking within track length moves the position`() {
        val h = newTestPlayer()
        val playing = track("Song", 100000L)
        `when`(h.audioPlayer.playingTrack).thenReturn(playing)
        val ctx = fakeContext(command, "0:30", h.player)

        command.invokeBlocking(ctx)

        verify(playing, times(1)).position = 30000L
        assertTrue(ctx.replies[0].contains("Seeking `Song` to 00:30"))
    }

    @Test
    fun `seeking past track length skips the track instead`() {
        val h = newTestPlayer()
        val playing = track("Song", 10000L)
        `when`(h.audioPlayer.playingTrack).thenReturn(playing)
        val ctx = fakeContext(command, "5:00", h.player)

        command.invokeBlocking(ctx)

        verify(h.audioPlayer, times(1)).stopTrack()
        assertTrue(ctx.replies[0].contains("Skipped `Song`"))
    }
}
