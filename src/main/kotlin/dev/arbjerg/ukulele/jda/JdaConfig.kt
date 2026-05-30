package dev.arbjerg.ukulele.jda

import club.minnced.discord.jdave.interop.JDaveSessionFactory
import dev.arbjerg.ukulele.config.BotProps
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.requests.GatewayIntent.DIRECT_MESSAGES
import net.dv8tion.jda.api.requests.GatewayIntent.GUILD_MESSAGES
import net.dv8tion.jda.api.requests.GatewayIntent.GUILD_MODERATION
import net.dv8tion.jda.api.requests.GatewayIntent.GUILD_PRESENCES
import net.dv8tion.jda.api.requests.GatewayIntent.GUILD_VOICE_STATES
import net.dv8tion.jda.api.requests.GatewayIntent.MESSAGE_CONTENT
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder
import net.dv8tion.jda.api.sharding.ShardManager
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.utils.messages.MessageRequest
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

@Configuration
class JdaConfig {
    init {
        MessageRequest.setDefaultMentions(emptyList())
    }

    @Bean
    fun shardManager(
        botProps: BotProps,
        eventHandler: EventHandler,
    ): ShardManager {
        if (botProps.token.isBlank()) throw RuntimeException("Discord token not configured!")
        val activity = if (botProps.game.isBlank()) Activity.playing("music") else Activity.playing(botProps.game)

        val intents =
            listOf(
                DIRECT_MESSAGES,
                GUILD_VOICE_STATES,
                GUILD_PRESENCES,
                GUILD_MESSAGES,
                GUILD_MODERATION,
                MESSAGE_CONTENT,
            )

        val daveSessionFactory = JDaveSessionFactory()

        val builder =
            DefaultShardManagerBuilder.create(botProps.token, intents)
                .setAudioModuleConfig(AudioModuleConfig().withDaveSessionFactory(daveSessionFactory))
                .disableCache(
                    CacheFlag.ACTIVITY,
                    CacheFlag.CLIENT_STATUS,
                    CacheFlag.EMOJI,
                    CacheFlag.STICKER,
                    CacheFlag.SCHEDULED_EVENTS,
                    CacheFlag.SOUNDBOARD_SOUNDS,
                )
                .setBulkDeleteSplittingEnabled(false)
                .setEnableShutdownHook(true)
                .addEventListeners(eventHandler)
                .setActivity(activity)
                .setStatus(OnlineStatus.ONLINE)

        return builder.build()
    }

    @Component
    class JdaShutdownHook(val shardManager: ShardManager) : DisposableBean {
        override fun destroy() {
            shardManager.shutdown()
        }
    }
}
