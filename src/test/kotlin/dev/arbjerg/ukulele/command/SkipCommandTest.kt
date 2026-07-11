package dev.arbjerg.ukulele.command

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import dev.arbjerg.ukulele.audio.newTestPlayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SkipCommandTest {
    private val command = SkipCommand()

    private fun track(title: String): AudioTrack {
        val t = mock(AudioTrack::class.java)
        `when`(t.info).thenReturn(AudioTrackInfo(title, "Author", 1000L, "id-$title", false, "uri-$title"))
        return t
    }

    @Test
    fun `bare skip skips the current track`() {
        val harness = newTestPlayer()
        val track1 = track("First")
        `when`(harness.audioPlayer.playingTrack).thenReturn(track1)

        val ctx = fakeContext(command, "", harness.player)
        command.invokeBlocking(ctx)

        verify(harness.audioPlayer, times(1)).stopTrack()
        assertEquals(1, ctx.replies.size)
        assertTrue(ctx.replies[0].contains("Skipped `First`"))
    }

    @Test
    fun `toggleshowqueue flips the flag both ways`() {
        val harness = newTestPlayer()

        command.invokeBlocking(fakeContext(command, "toggleshowqueue", harness.player))
        assertTrue(harness.player.showQueueOnSkip)

        command.invokeBlocking(fakeContext(command, "toggleshowqueue", harness.player))
        assertTrue(!harness.player.showQueueOnSkip)
    }

    @Test
    fun `index 1 restarts the current track`() {
        val harness = newTestPlayer()
        val track1 = track("Current")
        `when`(harness.audioPlayer.playingTrack).thenReturn(track1)

        val ctx = fakeContext(command, "1", harness.player)
        command.invokeBlocking(ctx)

        verify(track1, times(1)).position = 0L
        verify(harness.audioPlayer, never()).stopTrack()
        assertTrue(ctx.replies[0].contains("restarting"))
    }

    @Test
    fun `index past end of queue stops the player`() {
        val harness = newTestPlayer()
        val track1 = track("Current")
        `when`(harness.audioPlayer.playingTrack).thenReturn(track1)

        val ctx = fakeContext(command, "5", harness.player)
        command.invokeBlocking(ctx)

        verify(harness.audioPlayer, times(1)).stopTrack()
        assertTrue(ctx.replies[0].contains("Skipping past end of queue"))
    }

    @Test
    fun `single index mid-queue skips through that position`() {
        val harness = newTestPlayer()
        val track1 = track("Current")
        `when`(harness.audioPlayer.playingTrack).thenReturn(track1)
        val track2 = track("Second")
        val track3 = track("Third")
        val track4 = track("Fourth")
        harness.player.add(track2, track3, track4)

        val ctx = fakeContext(command, "3", harness.player)
        command.invokeBlocking(ctx)

        verify(harness.audioPlayer, times(1)).stopTrack()
        assertTrue(ctx.replies[0].contains("Skipped `2 tracks`"))
        assertEquals(listOf(track3, track4), harness.player.upcomingTracks)
    }

    @Test
    fun `invalid index replies with an error`() {
        val harness = newTestPlayer()
        val ctx = fakeContext(command, "notanumber", harness.player)

        command.invokeBlocking(ctx)

        assertTrue(ctx.replies[0].contains("Invalid index"))
        verify(harness.audioPlayer, never()).stopTrack()
    }

    @Test
    fun `range skips a contiguous span without stopping the current track`() {
        val harness = newTestPlayer()
        val track1 = track("Current")
        `when`(harness.audioPlayer.playingTrack).thenReturn(track1)
        val track2 = track("Second")
        val track3 = track("Third")
        harness.player.add(track2, track3)

        val ctx = fakeContext(command, "2 3", harness.player)
        command.invokeBlocking(ctx)

        verify(harness.audioPlayer, never()).stopTrack()
        assertTrue(ctx.replies[0].contains("Skipped `2 tracks`"))
        assertTrue(harness.player.upcomingTracks.isEmpty())
    }

    @Test
    fun `range past end of queue stops the player`() {
        val harness = newTestPlayer()
        val track1 = track("Current")
        `when`(harness.audioPlayer.playingTrack).thenReturn(track1)
        harness.player.add(track("Second"))

        val ctx = fakeContext(command, "1 100", harness.player)
        command.invokeBlocking(ctx)

        verify(harness.audioPlayer, times(1)).stopTrack()
        assertTrue(ctx.replies[0].contains("Skipping past end of queue"))
    }

    @Test
    fun `range with non-numeric values replies with an error`() {
        val harness = newTestPlayer()
        val ctx = fakeContext(command, "a b", harness.player)

        command.invokeBlocking(ctx)

        assertTrue(ctx.replies[0].contains("Invalid range"))
    }
}
