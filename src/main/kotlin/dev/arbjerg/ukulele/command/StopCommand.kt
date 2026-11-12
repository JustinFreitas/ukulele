package dev.arbjerg.ukulele.command

import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import org.springframework.stereotype.Component

@Component
class StopCommand : Command("stop") {
    override suspend fun CommandContext.invoke() {
        val skipped = player.tracks.size

        guild.audioManager.closeAudioConnection()
        player.stop()

        reply("Player stopped. Removed **$skipped** tracks.")
    }

    override fun HelpContext.provideHelp() {
        addUsage("")
        addDescription("Clear all tracks from the queue and disconnect the player.")
    }
}
