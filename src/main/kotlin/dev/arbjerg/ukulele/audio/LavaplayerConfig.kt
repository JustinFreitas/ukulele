package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import dev.lavalink.youtube.YoutubeAudioSourceManager
import dev.lavalink.youtube.YoutubeSourceOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class LavaplayerConfig(
    val botProps: dev.arbjerg.ukulele.config.BotProps,
) {
    @Bean(destroyMethod = "shutdown")
    fun playerManager(): AudioPlayerManager {
        val apm = DefaultAudioPlayerManager()
        apm.configuration.isReplayGainEnabled = botProps.normalization

        AudioSourceManagers.registerLocalSource(apm)

        val ytOptions =
            YoutubeSourceOptions()
                .setAllowSearch(false)
                .setAllowDirectVideoIds(true)
                .setAllowDirectPlaylistIds(true)

        if (!botProps.youtubeRemoteCipherUrl.isNullOrEmpty()) {
            ytOptions.setRemoteCipher(
                botProps.youtubeRemoteCipherUrl,
                botProps.youtubeRemoteCipherPassword,
                botProps.youtubeRemoteCipherUserAgent,
            )
        }

        val ytSourceManager =
            YoutubeAudioSourceManager(
                ytOptions,
                *YoutubeAudioSourceManager.DEFAULT_CLIENTS,
            )

        // Add the new YoutubeAudioSourceManager
        apm.registerSourceManager(ytSourceManager)

        @Suppress("DEPRECATION")
        val exclusions =
            arrayOf(
                com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager::class.java,
                com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager::class.java,
            )
        AudioSourceManagers.registerRemoteSources(apm, *exclusions)

        return apm
    }
}
