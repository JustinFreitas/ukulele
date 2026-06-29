package dev.arbjerg.ukulele.utils

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
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
