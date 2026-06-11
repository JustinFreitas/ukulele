package dev.arbjerg.ukulele.api

import com.sedmelluq.discord.lavaplayer.track.AudioTrack

data class GuildDto(
    val id: String,
    val name: String,
    val isPlaying: Boolean,
)

data class PlayerStatusDto(
    val guildId: String,
    val isPaused: Boolean,
    val volume: Int,
    val repeatTrack: Boolean,
    val queueLooping: Boolean,
    val currentTrack: TrackDto?,
    val remainingDuration: Long,
    val minVolume: Int,
    val maxVolume: Int,
    @get:com.fasterxml.jackson.annotation.JsonProperty("isReplayGainEnabled")
    val isReplayGainEnabled: Boolean,
    val queueSize: Int,
    val channelId: String?,
)

data class TrackDto(
    val title: String,
    val author: String,
    val uri: String,
    val duration: Long,
    val position: Long,
    @get:com.fasterxml.jackson.annotation.JsonProperty("isReplayGain")
    val isReplayGain: Boolean,
)

fun AudioTrack.toDto() =
    TrackDto(
        title = info.title,
        author = info.author,
        uri = info.uri,
        duration = duration,
        position = position,
        isReplayGain = isReplayGainApplied(),
    )

data class VoiceChannelDto(
    val id: String,
    val name: String,
)

data class PlayRequest(
    val url: String? = null,
    val channelId: String? = null,
    val fadeIn: Boolean? = null,
)

data class SkipRequest(
    val index: Int? = null,
)

data class VolumeRequest(
    val volume: Int,
)

data class RepeatRequest(
    val repeat: Boolean,
)

data class LoopRequest(
    val loop: Boolean,
)

data class SeekRequest(
    val position: Long,
)

data class SayRequest(
    val text: String,
)

data class MoveRequest(
    val channelId: String,
)
