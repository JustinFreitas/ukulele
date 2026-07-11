package dev.arbjerg.ukulele.command

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import dev.arbjerg.ukulele.audio.Player
import dev.arbjerg.ukulele.config.BotProps
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class QueueCommandTest {
    private fun track(title: String): AudioTrack {
        val t = mock(AudioTrack::class.java)
        `when`(t.info).thenReturn(AudioTrackInfo(title, "Author", 60000L, "id-$title", false, "uri-$title"))
        `when`(t.duration).thenReturn(60000L)
        // Mockito's default answer for boxed numeric getters is 0, not null; make "no ReplayGain data" explicit.
        `when`(t.replayGainDb).thenReturn(null)
        return t
    }

    private fun player(tracks: List<AudioTrack>): Player {
        val player = mock(Player::class.java)
        `when`(player.tracks).thenReturn(tracks)
        `when`(player.currentTrack).thenReturn(tracks.firstOrNull())
        `when`(player.remainingDuration).thenReturn(tracks.size * 60000L)
        `when`(player.repeatTrack).thenReturn(false)
        `when`(player.queueLooping).thenReturn(false)
        return player
    }

    @Test
    fun `empty queue reports empty and current toggle states`() {
        val command = QueueCommand(BotProps())
        val message = command.printQueue(player(emptyList()), 1)

        assertTrue(message.contains("Repeat Track is off."))
        assertTrue(message.contains("Queue Looping is off."))
        assertTrue(message.contains("The queue is empty."))
    }

    @Test
    fun `single page lists all tracks with position numbers`() {
        val command = QueueCommand(BotProps())
        val tracks = listOf(track("First"), track("Second"), track("Third"))
        val message = command.printQueue(player(tracks), 1)

        assertTrue(message.contains("Page **1** of **1**"))
        assertTrue(message.contains("`[1]` **First**"))
        assertTrue(message.contains("`[3]` **Third**"))
    }

    @Test
    fun `pagination shows the requested page`() {
        val command = QueueCommand(BotProps())
        val tracks = (1..15).map { track("Track$it") }
        val message = command.printQueue(player(tracks), 2)

        assertTrue(message.contains("Page **2** of **2**"))
        assertTrue(message.contains("`[11]` **Track11**"))
        assertTrue(!message.contains("`[1]` **Track1**\n"))
    }

    @Test
    fun `out of range page index is clamped`() {
        val command = QueueCommand(BotProps())
        val tracks = listOf(track("Only"))
        val message = command.printQueue(player(tracks), 99)

        assertTrue(message.contains("Page **1** of **1**"))
    }

    @Test
    fun `replayGain badge shown for non-current tracks when normalization is enabled`() {
        val botProps = BotProps().apply { normalization = true }
        val command = QueueCommand(botProps)
        val current = track("Current")
        val upcoming = track("Upcoming")
        val message = command.printQueue(player(listOf(current, upcoming)), 1)

        assertTrue(message.contains("ReplayGain normalization is on."))
        assertTrue(message.contains("**Upcoming** `[01:00]` `🔊 RG`"))
        assertTrue(!message.contains("**Current** `[01:00]` `🔊 RG`"))
    }
}
