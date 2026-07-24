package dev.arbjerg.ukulele.features

import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import net.dv8tion.jda.api.utils.MarkdownUtil
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder

class HelpContext(
    // Nullable so the slash-command registration can run provideHelp() purely to extract the
    // description text, without constructing a full live CommandContext.
    private val commandContext: CommandContext?,
    private val command: Command,
) {
    private val lines = mutableListOf<String>()

    fun addUsage(usage: String) = addUsages(usage)

    private fun addUsages(vararg usages: String) {
        if (usages.isEmpty()) throw IllegalArgumentException("Expected at least one usage!")
        lines.add(
            usages.joinToString(" OR ") {
                "/" + command.name + " " + it.trim()
            },
        )
    }

    fun addDescription(text: String) {
        lines.add("# " + text.trim())
    }

    /** First description line (without the leading "# "), used as a slash command description. */
    fun firstDescription(): String? =
        lines
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
            ?.ifBlank { null }

    fun buildMessage() =
        MessageCreateBuilder()
            .addContent(MarkdownUtil.codeblock("md", toString()))
            .build()

    override fun toString() = lines.joinToString(separator = "\n")
}
