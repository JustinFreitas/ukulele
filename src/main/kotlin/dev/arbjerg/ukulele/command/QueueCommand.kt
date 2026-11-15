package dev.arbjerg.ukulele.command

import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.ukulele.audio.Player
import dev.arbjerg.ukulele.audio.local.LocalFileMemoryCache
import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import dev.arbjerg.ukulele.utils.TextUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class QueueCommand
    @Autowired
    constructor(
        private val botProps: BotProps,
        @Autowired(required = false) private val localFileCache: LocalFileMemoryCache?,
    ) : Command("queue", "q", "list") {
        constructor(botProps: BotProps) : this(botProps, null)

        private val pageSize = 10

        override suspend fun CommandContext.invoke() {
            reply(TextUtils.truncate(printQueue(player, argumentText.toIntOrNull() ?: 1), MAX_DISCORD_MESSAGE_LEN))
        }

        fun printQueue(
            player: Player,
            pageIndex: Int,
        ): String {
            val repeatTrackMessage =
                when (player.repeatTrack) {
                    true -> "Repeat Track is on.\n"
                    false -> "Repeat Track is off.\n"
                }

            val queueLoopingMessage =
                when (player.queueLooping) {
                    true -> "Queue Looping is on.\n"
                    false -> "Queue Looping is off.\n"
                }

            val replayGainMessage =
                when (botProps.normalization) {
                    true -> "ReplayGain normalization is on.\n"
                    false -> "ReplayGain normalization is off.\n"
                }

            val totalDuration = player.remainingDuration
            val tracks = player.tracks
            if (tracks.isEmpty()) {
                return repeatTrackMessage + queueLoopingMessage + replayGainMessage + "The queue is empty."
            }

            val cacheSummaryLine = buildCacheSummaryLine(tracks)

            val output =
                buildString {
                    append(repeatTrackMessage)
                    append(queueLoopingMessage)
                    append(replayGainMessage)
                    if (cacheSummaryLine != null) {
                        append(cacheSummaryLine)
                    }
                    append(paginateQueue(tracks, player.currentTrack, pageIndex))
                    listQueueDurationAndLength(tracks, totalDuration)
                }

            return TextUtils.truncate(output, MAX_QUEUE_BLOCK_LEN)
        }

        private fun buildCacheSummaryLine(tracks: List<AudioTrack>): String? {
            val cache = localFileCache ?: return null
            if (!cache.isEnabled) return null
            if (tracks.none { it is LocalAudioTrack }) return null
            val stats = cache.getStats(tracks)
            if (stats.queuedLocalCount <= 0) return null
            val used = TextUtils.humanReadableBytes(stats.totalBytes)
            val max = TextUtils.humanReadableBytes(stats.maxBytes)
            return "Memory cache: **${stats.queuedInMemoryCount}/${stats.queuedLocalCount}** local tracks in RAM ($used / $max)\n"
        }

        private fun paginateQueue(
            tracks: List<AudioTrack>,
            currentTrack: AudioTrack?,
            index: Int,
        ) = buildString {
            val pageCount: Int = (tracks.size + pageSize - 1) / pageSize
            val pageIndex = index.coerceIn(1..pageCount)

            // Add header
            append("Page **$pageIndex** of **$pageCount**\n\n")

            val offset = pageSize * (pageIndex - 1)
            val pageEnd = (offset + pageSize).coerceAtMost(tracks.size)

            tracks.subList(offset, pageEnd).forEachIndexed { i, t ->
                // Show the applied gain value when known (the playing track); otherwise, when normalization
                // is enabled, show a bare badge meaning "ReplayGain will be applied if this track is tagged".
                val rg =
                    TextUtils.replayGainLabel(t)?.let { " `🔊 RG $it`" }
                        ?: if (botProps.normalization && t != currentTrack) " `🔊 RG`" else ""
                val ram =
                    when (localFileCache?.trackCacheState(t)) {
                        "MEMORY" -> " `💾 RAM`"
                        "PREFETCHING" -> " `⏳`"
                        "DISK" -> " `💿 Disk`"
                        else -> ""
                    }
                val displayTitle = TextUtils.truncate(t.info.title, MAX_TRACK_TITLE_LEN)
                appendLine(
                    "`[${offset + i + 1}]` **$displayTitle** `[${if (t.info.isStream) {
                        "Live"
                    } else {
                        TextUtils.humanReadableTime(
                            t.duration,
                        )
                    }}]`$rg$ram",
                )
            }
        }

        override fun HelpContext.provideHelp() {
            addUsage("[page]")
            addDescription("Displays the queue, by default for page 1")
        }

        companion object {
            const val MAX_DISCORD_MESSAGE_LEN = 1990
            const val MAX_QUEUE_BLOCK_LEN = 1750
            const val MAX_TRACK_TITLE_LEN = 90
        }
    }
