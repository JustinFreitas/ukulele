package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.getyarn.GetyarnAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.nico.NicoAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.yamusic.YandexMusicAudioSourceManager
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

        // Port the rest from `com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers.registerRemoteSources`
        // while excluding the legacy `YoutubeAudioSourceManager`
        apm.registerSourceManager(YandexMusicAudioSourceManager(false))
        apm.registerSourceManager(SoundCloudAudioSourceManager.createDefault())
        apm.registerSourceManager(BandcampAudioSourceManager())
        apm.registerSourceManager(VimeoAudioSourceManager())
        apm.registerSourceManager(TwitchStreamAudioSourceManager())
        apm.registerSourceManager(BeamAudioSourceManager())
        apm.registerSourceManager(GetyarnAudioSourceManager())
        apm.registerSourceManager(NicoAudioSourceManager())
        apm.registerSourceManager(HttpAudioSourceManager(MediaContainerRegistry.DEFAULT_REGISTRY))

        return apm
    }

}