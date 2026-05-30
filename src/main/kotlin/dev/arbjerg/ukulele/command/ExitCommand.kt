package dev.arbjerg.ukulele.command

import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

@Component
class ExitCommand(val botProps: BotProps) : Command("exit") {
    override suspend fun CommandContext.invoke() {
        if (message.author.id != botProps.ownerId) {
            reply("You do not have permission to run this command.")
            return
        }

        reply("Shutting down...")
        // Give JDA a moment to send the reply
        Thread.sleep(1000)
        exitProcess(0)
    }

    override fun HelpContext.provideHelp() {
        addUsage("")
        addDescription("Shuts down the bot gracefully (Owner only).")
    }
}
