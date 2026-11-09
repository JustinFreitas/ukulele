package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import java.util.Arrays

data class TrackFadeConfig(
    val track: AudioTrack?,
    val initialGain: Float,
    val initialTargetGain: Float = 1.0f,
    val fadeDurationMs: Long,
    @Volatile var filterCreated: Boolean = false,
)

class TrackFadeFilterFactory(
    private val configProvider: () -> TrackFadeConfig?,
    private val shouldAutoFadeOutAtEof: () -> Boolean,
    private val onFilterCreated: (TrackFadeFilter) -> Unit,
) : PcmFilterFactory {
    override fun buildChain(
        track: AudioTrack?,
        format: AudioDataFormat,
        output: UniversalPcmAudioFilter,
    ): List<AudioFilter> {
        val config = configProvider() ?: return emptyList()
        val effectiveTrack = track ?: config.track
        val isStream = effectiveTrack?.info?.isStream ?: true
        val durationMs = effectiveTrack?.duration ?: 0L
        val midTrackRebuild = config.filterCreated
        config.filterCreated = true
        val initialGain = if (midTrackRebuild) config.initialTargetGain else config.initialGain
        val initialPositionMs = effectiveTrack?.position ?: 0L

        val filter =
            TrackFadeFilter(
                downstream = output,
                sampleRate = format.sampleRate,
                initialGain = initialGain,
                initialTargetGain = config.initialTargetGain,
                fadeDurationMs = config.fadeDurationMs,
                trackDurationMs = durationMs,
                isStream = isStream,
                initialPositionMs = initialPositionMs,
                shouldAutoFadeOutAtEof = shouldAutoFadeOutAtEof,
            )
        onFilterCreated(filter)
        return listOf(filter)
    }
}

class TrackFadeFilter(
    private val downstream: FloatPcmAudioFilter,
    private val sampleRate: Int,
    initialGain: Float,
    initialTargetGain: Float = 1.0f,
    fadeDurationMs: Long,
    trackDurationMs: Long,
    isStream: Boolean,
    initialPositionMs: Long = 0L,
    private val shouldAutoFadeOutAtEof: () -> Boolean,
) : FloatPcmAudioFilter {
    private val fadeSamples = (fadeDurationMs.coerceAtLeast(1L) * sampleRate) / 1000L
    private val stepPerSample = 1.0f / fadeSamples.toFloat()
    private val eofFadeStartSample =
        if (isStream || fadeDurationMs <= 0L || trackDurationMs < fadeDurationMs * 2) {
            -1L
        } else {
            ((trackDurationMs * sampleRate) / 1000L) - fadeSamples
        }

    var currentGain: Float = initialGain.coerceIn(0.0f, 1.0f)
        private set

    @Volatile
    var targetGain: Float = initialTargetGain.coerceIn(0.0f, 1.0f)

    @Volatile
    private var fadeInOnNextSeek: Boolean = false

    private var sampleCursor: Long = (initialPositionMs.coerceAtLeast(0L) * sampleRate) / 1000L
    private var eofLatched: Boolean = false
    private var eofFadingOut: Boolean = false

    fun prepareFadeInOnSeek() {
        fadeInOnNextSeek = true
    }

    override fun process(
        input: Array<FloatArray>,
        offset: Int,
        length: Int,
    ) {
        val chunkEnd = sampleCursor + length
        if (!eofLatched && eofFadeStartSample >= 0L && chunkEnd > eofFadeStartSample) {
            eofLatched = true
            eofFadingOut = shouldAutoFadeOutAtEof()
        }

        val target = if (eofFadingOut) 0.0f else targetGain
        if (currentGain == 1.0f && target == 1.0f) {
            sampleCursor = chunkEnd
            downstream.process(input, offset, length)
            return
        }
        if (currentGain == 0.0f && target == 0.0f) {
            for (ch in input.indices) {
                Arrays.fill(input[ch], offset, offset + length, 0.0f)
            }
            sampleCursor = chunkEnd
            downstream.process(input, offset, length)
            return
        }

        for (i in 0 until length) {
            if (currentGain < target) {
                currentGain = (currentGain + stepPerSample).coerceAtMost(target)
            } else if (currentGain > target) {
                currentGain = (currentGain - stepPerSample).coerceAtLeast(target)
            }
            val g = currentGain
            if (g < 1.0f) {
                for (ch in input.indices) {
                    input[ch][offset + i] *= g
                }
            }
        }

        sampleCursor = chunkEnd
        downstream.process(input, offset, length)
    }

    override fun seekPerformed(
        requestedTime: Long,
        providedTime: Long,
    ) {
        sampleCursor = (providedTime.coerceAtLeast(0L) * sampleRate) / 1000L
        eofLatched = false
        eofFadingOut = false
        if (fadeInOnNextSeek) {
            fadeInOnNextSeek = false
            currentGain = 0.0f
            targetGain = 1.0f
        }
    }

    override fun flush() {}

    override fun close() {}
}
