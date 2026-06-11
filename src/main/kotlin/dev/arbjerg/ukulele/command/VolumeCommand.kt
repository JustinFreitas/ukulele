package dev.arbjerg.ukulele.command

import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import org.springframework.stereotype.Component

@Component
class VolumeCommand : Command("volume", "v") {
    override suspend fun CommandContext.invoke() {
        if (argumentText.isBlank()) return reply("The volume is set to ${player.volume / 10}%.")

        val arg = argumentText.removeSuffix("%")
        var num: Int? = null

        val input = arg.toIntOrNull()
        if (input != null) {
            // User provided an absolute percentage (e.g. 50 -> 500)
            num = input * 10
        } else {
            val step = player.getOptimalVolumeStep()
            num =
                when (arg) {
                    "+" -> player.volume + step
                    "-" -> player.volume - step
                    else -> null
                }
        }

        if (num == null) return replyHelp()

        val formerVolume = player.volume

        // Clamp to 0-150% (0-1500)
        if (num > 1500) num = 1500
        if (num < 0) num = 0
        player.volume = num

        reply("Changed volume from ${formerVolume / 10}% to ${player.volume / 10}%.")
    }

    override fun HelpContext.provideHelp() {
        addUsage("")
        addDescription("Displays the current volume.")
        addUsage("<0-150>%")
        addDescription("Sets the volume to the given percentage.")
        addUsage("+")
        addDescription("Increases volume by the optimal step for your range.")
        addUsage("-")
        addDescription("Decreases volume by the optimal step for your range.")
    }
}
