package dev.arbjerg.ukulele.utils

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import java.util.Locale
import java.util.concurrent.TimeUnit

object TextUtils {
    /**
     * Formats the ReplayGain adjustment applied to a track (e.g. "-5.0 dB"), or null if no
     * ReplayGain was applied. Only known once a track has begun decoding (i.e. the playing track).
     */
    fun replayGainLabel(track: AudioTrack): String? {
        val db = track.replayGainDb ?: return null
        return String.format("%+.1f dB", db)
    }

    fun truncate(
        text: String,
        maxLength: Int,
    ): String {
        if (maxLength <= 0) return ""
        if (text.length <= maxLength) return text
        if (maxLength <= 1) return "…"
        return text.substring(0, maxLength - 1) + "…"
    }

    fun humanReadableBytes(bytes: Long): String {
        val clamped = bytes.coerceAtLeast(0L)
        return when {
            clamped >= 1024L * 1024L * 1024L -> {
                String.format(Locale.US, "%.1f GB", clamped / (1024.0 * 1024.0 * 1024.0))
            }

            clamped >= 1024L * 1024L -> {
                String.format(Locale.US, "%.1f MB", clamped / (1024.0 * 1024.0))
            }

            clamped >= 1024L -> {
                String.format(Locale.US, "%.1f KB", clamped / 1024.0)
            }

            else -> {
                "$clamped B"
            }
        }
    }

    fun humanReadableTime(length: Long): String =
        if (length < 3600000) {
            String.format(
                "%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(length),
                TimeUnit.MILLISECONDS.toSeconds(length) % TimeUnit.MINUTES.toSeconds(1),
            )
        } else {
            String.format(
                "%02d:%02d:%02d",
                TimeUnit.MILLISECONDS.toHours(length),
                TimeUnit.MILLISECONDS.toMinutes(length) % TimeUnit.HOURS.toMinutes(1),
                TimeUnit.MILLISECONDS.toSeconds(length) % TimeUnit.MINUTES.toSeconds(1),
            )
        }
}
