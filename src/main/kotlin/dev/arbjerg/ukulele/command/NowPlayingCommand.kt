package dev.arbjerg.ukulele.command

import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioTrack
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioTrack
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.ukulele.audio.local.InMemoryLocalAudioTrack
import dev.arbjerg.ukulele.audio.local.LocalFileMemoryCache
import dev.arbjerg.ukulele.audio.local.PlaybackSource
import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import dev.arbjerg.ukulele.utils.TextUtils
import dev.lavalink.youtube.track.YoutubeAudioTrack
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

@Component
class NowPlayingCommand
    @Autowired
    constructor(
        @Autowired(required = false) private val localFileCache: LocalFileMemoryCache? = null,
    ) : Command("nowplaying", "np") {
        override suspend fun CommandContext.invoke() {
            val track = player.currentTrack ?: return reply("Not playing anything.")

            replyEmbed(buildEmbed(track))
        }

        fun buildEmbed(track: AudioTrack): MessageEmbed =
            when (track) {
                is YoutubeAudioTrack -> GetEmbed(track, localFileCache).youtube()
                is SoundCloudAudioTrack -> GetEmbed(track, localFileCache).soundcloud()
                is TwitchStreamAudioTrack -> GetEmbed(track, localFileCache).twitch()
                is LocalAudioTrack -> GetEmbed(track, localFileCache).local()
                else -> GetEmbed(track, localFileCache).default()
            }

        private class GetEmbed(
            val track: AudioTrack,
            val localFileCache: LocalFileMemoryCache?,
        ) {
            val timeField =
                if (track.info.isStream) {
                    "[Live]"
                } else {
                    "[${TextUtils.humanReadableTime(
                        track.position,
                    )} / ${TextUtils.humanReadableTime(track.info.length)}]"
                }
            val titleFixLocalUri = if (track is LocalAudioTrack) null else track.info.uri
            val safeTitle = TextUtils.truncate(track.info.title, 256)

            val sourceLabel: String =
                when {
                    track is InMemoryLocalAudioTrack -> {
                        when (track.activePlaybackSource) {
                            PlaybackSource.MEMORY -> {
                                "local (memory)"
                            }

                            PlaybackSource.DISK -> {
                                "local (disk)"
                            }

                            null -> {
                                when (localFileCache?.trackCacheState(track)) {
                                    "MEMORY" -> "local (memory)"
                                    "PREFETCHING" -> "local (prefetching)"
                                    else -> "local (disk)"
                                }
                            }
                        }
                    }

                    else -> {
                        track.sourceManager?.sourceName ?: "Unknown"
                    }
                }

            // Set up common parts of the embed
            val message =
                EmbedBuilder()
                    .setTitle(safeTitle, titleFixLocalUri)
                    .setFooter("Source: $sourceLabel")
                    .also { builder ->
                        TextUtils.replayGainLabel(track)?.let { builder.addField("ReplayGain", it, true) }
                    }

            // Prepare embeds for overrides.
            fun youtube(): MessageEmbed {
                message.setColor(YOUTUBE_RED)
                message.addField("Time", timeField, true)
                return message.build()
            }

            fun soundcloud(): MessageEmbed {
                message.setColor(SOUNDCLOUD_ORANGE)
                message.addField("Time", timeField, true)
                return message.build()
            }

            fun twitch(): MessageEmbed {
                message.setColor(TWITCH_PURPLE)
                return message.build()
            }

            fun local(): MessageEmbed {
                message.setColor(LOCAL_BLUE)
                message.addField("Time", timeField, true)
                return message.build()
            }

            fun default(): MessageEmbed {
                message.setTitle(safeTitle) // Show just the title of the radio station. Weird uri jank.
                message.setColor(DEFAULT_GREY)
                message.addField("Time", timeField, true)
                return message.build()
            }
        }

        override fun HelpContext.provideHelp() {
            addUsage("")
            addDescription("Displays information about the currently playing song.")
        }

        private companion object {
            val YOUTUBE_RED = Color(205, 32, 31).rgb
            val SOUNDCLOUD_ORANGE = Color(255, 85, 0).rgb
            val TWITCH_PURPLE = Color(100, 65, 164).rgb
            val LOCAL_BLUE = Color(66, 135, 245).rgb
            val DEFAULT_GREY = Color(100, 100, 100).rgb
        }
    }
