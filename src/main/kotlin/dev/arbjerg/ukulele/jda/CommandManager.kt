package dev.arbjerg.ukulele.jda

import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.data.GuildPropertiesService
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class CommandManager(
    private val contextBeans: CommandContext.Beans,
    private val guildProperties: GuildPropertiesService,
    private val botProps: BotProps,
    private val commandScope: CommandScope,
    commands: Collection<Command>,
) {
    private final val registry: Map<String, Command>
    private val log: Logger = LoggerFactory.getLogger(CommandManager::class.java)

    init {
        val map = mutableMapOf<String, Command>()
        commands.forEach { c ->
            map[c.name] = c
            c.aliases.forEach { map[it] = c }
        }
        registry = map
        log.info("Registered ${commands.size} commands with ${registry.size} names")
        @Suppress("LeakingThis")
        contextBeans.commandManager = this
    }

    operator fun get(commandName: String) = registry[commandName]

    fun getCommands() = registry.values.distinct()

    /** Dispatch a Discord slash command. The caller (EventHandler) must have already deferred the reply. */
    fun onSlash(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val member = event.member ?: return
        val command =
            registry[event.name] ?: run {
                event.hook.sendMessage("Command not found.").queue()
                return
            }

        commandScope.launch {
            val guildProperties = guildProperties.getAwait(guild.idLong)

            if (event.channelType != ChannelType.TEXT) {
                event.hook.sendMessage("This command can only be used in a text channel.").queue()
                return@launch
            }

            val channel = event.channel.asTextChannel()
            val ctx = SlashCommandContext(contextBeans, guildProperties, guild, channel, member, event, command)

            log.info("Slash invocation: /${event.name} ${event.getOption("args")?.asString ?: ""}")
            command.invoke0(ctx)
        }
    }
}
