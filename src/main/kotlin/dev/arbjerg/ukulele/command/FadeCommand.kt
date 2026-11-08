package dev.arbjerg.ukulele.command

import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import org.springframework.stereotype.Component

@Component
class FadeCommand : Command("fade", "f") {
    override suspend fun CommandContext.invoke() {
        when (val arg = argumentText.trim().lowercase()) {
            "" -> {
                player.trackFade = !player.trackFade
                reply(if (player.trackFade) "Track fade is now enabled." else "Track fade is now disabled.")
            }

            "out" -> {
                player.fadeOut()
                reply("Fading out current track.")
            }

            "in" -> {
                player.fadeIn()
                reply("Fading in current track.")
            }

            "on", "true", "yes", "1" -> {
                player.trackFade = true
                reply("Track fade is now enabled.")
            }

            "off", "false", "no", "0" -> {
                player.trackFade = false
                reply("Track fade is now disabled.")
            }

            else -> {
                player.trackFade = arg.toBoolean()
                reply(if (player.trackFade) "Track fade is now enabled." else "Track fade is now disabled.")
            }
        }
    }

    override fun HelpContext.provideHelp() {
        addUsage("")
        addDescription("Toggles automatic track fade on/off.")
        addUsage("on / off")
        addDescription("Turns automatic track fade on or off.")
        addUsage("out / in")
        addDescription("Fades the currently playing stream out to silence or back in.")
    }
}
