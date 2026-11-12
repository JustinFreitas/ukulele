package dev.arbjerg.ukulele.command

import dev.arbjerg.ukulele.audio.Player
import net.dv8tion.jda.api.managers.AudioManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SayAndStopCommandTest {
    @Test
    fun `say command shows help instead of sending blank message when argumentText is empty`() {
        val sayCommand = SayCommand()
        val ctx = fakeContext(sayCommand, "   ")

        sayCommand.invokeBlocking(ctx)

        assertTrue(ctx.replies.isEmpty())
        assertEquals(1, ctx.replyMessages.size)
        assertTrue(ctx.replyMessages[0].content.contains("say"))
    }

    @Test
    fun `say command echoes non-blank argumentText`() {
        val sayCommand = SayCommand()
        val ctx = fakeContext(sayCommand, "Hello tavern!")

        sayCommand.invokeBlocking(ctx)

        assertEquals(listOf("Hello tavern!"), ctx.replies)
    }

    @Test
    fun `stop command closes audio connection before stopping player so published state has null channelId`() {
        val stopCommand = StopCommand()
        val player = mock(Player::class.java)
        `when`(player.tracks).thenReturn(emptyList())
        val ctx = fakeContext(stopCommand, "", player)
        val audioManager = mock(AudioManager::class.java)
        `when`(ctx.guild.audioManager).thenReturn(audioManager)

        stopCommand.invokeBlocking(ctx)

        val order = inOrder(audioManager, player)
        order.verify(audioManager).closeAudioConnection()
        order.verify(player).stop()
        assertEquals(listOf("Player stopped. Removed **0** tracks."), ctx.replies)
    }
}
