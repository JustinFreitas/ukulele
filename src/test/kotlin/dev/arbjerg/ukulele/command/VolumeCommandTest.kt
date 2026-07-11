package dev.arbjerg.ukulele.command

import dev.arbjerg.ukulele.audio.newTestPlayer
import dev.arbjerg.ukulele.data.GuildProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VolumeCommandTest {
    private val command = VolumeCommand()

    // minVolume=0, maxVolume=100 -> optimal step is ceil(1000/100) = 10
    private fun harness(startVolume: Int = 500) = newTestPlayer(guildProperties = GuildProperties(1L, startVolume))

    @Test
    fun `blank argument reports the current volume`() {
        val h = harness()
        val ctx = fakeContext(command, "", h.player)

        command.invokeBlocking(ctx)

        assertEquals("The volume is set to 50%.", ctx.replies[0])
    }

    @Test
    fun `absolute percentage sets the volume`() {
        val h = harness()
        val ctx = fakeContext(command, "75", h.player)

        command.invokeBlocking(ctx)

        assertEquals(750, h.player.volume)
        assertEquals("Changed volume from 50% to 75%.", ctx.replies[0])
    }

    @Test
    fun `plus increases volume by the optimal step`() {
        val h = harness()
        val ctx = fakeContext(command, "+", h.player)

        command.invokeBlocking(ctx)

        assertEquals(510, h.player.volume)
        assertEquals("Changed volume from 50% to 51%.", ctx.replies[0])
    }

    @Test
    fun `minus decreases volume by the optimal step`() {
        val h = harness()
        val ctx = fakeContext(command, "-", h.player)

        command.invokeBlocking(ctx)

        assertEquals(490, h.player.volume)
        assertEquals("Changed volume from 50% to 49%.", ctx.replies[0])
    }

    @Test
    fun `volume is clamped to 150 percent`() {
        val h = harness()
        val ctx = fakeContext(command, "200%", h.player)

        command.invokeBlocking(ctx)

        assertEquals(1500, h.player.volume)
        assertEquals("Changed volume from 50% to 150%.", ctx.replies[0])
    }

    @Test
    fun `volume is clamped to 0 percent`() {
        val h = harness()
        val ctx = fakeContext(command, "-500", h.player)

        command.invokeBlocking(ctx)

        assertEquals(0, h.player.volume)
        assertEquals("Changed volume from 50% to 0%.", ctx.replies[0])
    }

    @Test
    fun `invalid argument replies with help instead of changing volume`() {
        val h = harness()
        val ctx = fakeContext(command, "banana", h.player)

        command.invokeBlocking(ctx)

        assertEquals(500, h.player.volume)
        assertTrue(ctx.replies.isEmpty())
        assertEquals(1, ctx.replyMessages.size)
    }
}
