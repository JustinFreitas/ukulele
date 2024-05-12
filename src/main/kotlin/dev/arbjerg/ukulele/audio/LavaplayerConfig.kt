package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import dev.lavalink.youtube.YoutubeAudioSourceManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class LavaplayerConfig {
    @Bean
    fun playerManager(): AudioPlayerManager {
        val apm = DefaultAudioPlayerManager()
        AudioSourceManagers.registerLocalSource(apm)

        // Add the new YoutubeAudioSourceManager
        apm.registerSourceManager(YoutubeAudioSourceManager(false))

        @Suppress("DEPRECATION") val exclusions =
            arrayOf(com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager::class.java)
        AudioSourceManagers.registerRemoteSources(apm, *exclusions)

        return apm
    }
}