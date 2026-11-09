package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TrackFadeFilterTest {
    private class CapturingFilter : FloatPcmAudioFilter {
        var lastSamples: Array<FloatArray>? = null
        var lastOffset: Int = 0
        var lastLength: Int = 0

        override fun process(
            input: Array<FloatArray>,
            offset: Int,
            length: Int,
        ) {
            lastSamples = Array(input.size) { ch -> input[ch].copyOf() }
            lastOffset = offset
            lastLength = length
        }

        override fun seekPerformed(
            requestedTime: Long,
            providedTime: Long,
        ) {}

        override fun flush() {}

        override fun close() {}
    }

    private fun createTrack(
        duration: Long = 10_000L,
        isStream: Boolean = false,
        position: Long = 0L,
    ): AudioTrack {
        val track = mock(AudioTrack::class.java)
        val info = AudioTrackInfo("Test", "Author", duration, "id", isStream, "uri")
        `when`(track.info).thenReturn(info)
        `when`(track.duration).thenReturn(duration)
        `when`(track.position).thenReturn(position)
        return track
    }

    @Test
    fun `fast-path passes samples untouched when gain and targetGain are 1_0f`() {
        val downstream = CapturingFilter()
        val filter =
            TrackFadeFilter(
                downstream = downstream,
                sampleRate = 1000,
                initialGain = 1.0f,
                initialTargetGain = 1.0f,
                fadeDurationMs = 1000L,
                trackDurationMs = 10_000L,
                isStream = false,
                shouldAutoFadeOutAtEof = { false },
            )

        val input = arrayOf(floatArrayOf(0.5f, -0.5f, 1.0f), floatArrayOf(0.25f, -0.25f, 0.75f))
        filter.process(input, 0, 3)

        assertEquals(0.5f, downstream.lastSamples!![0][0], 1e-6f)
        assertEquals(-0.5f, downstream.lastSamples!![0][1], 1e-6f)
        assertEquals(0.75f, downstream.lastSamples!![1][2], 1e-6f)
    }

    @Test
    fun `fast-path zeroes samples when gain and targetGain are 0_0f`() {
        val downstream = CapturingFilter()
        val filter =
            TrackFadeFilter(
                downstream = downstream,
                sampleRate = 1000,
                initialGain = 0.0f,
                initialTargetGain = 0.0f,
                fadeDurationMs = 1000L,
                trackDurationMs = 10_000L,
                isStream = false,
                shouldAutoFadeOutAtEof = { false },
            )

        val input = arrayOf(floatArrayOf(0.8f, -0.8f, 0.4f), floatArrayOf(0.9f, -0.9f, 0.2f))
        filter.process(input, 0, 3)

        for (ch in 0..1) {
            for (i in 0..2) {
                assertEquals(0.0f, downstream.lastSamples!![ch][i], 1e-6f)
            }
        }
    }

    @Test
    fun `fades in from 0_0f to 1_0f over fadeDurationMs`() {
        val downstream = CapturingFilter()
        // sampleRate = 100 Hz, fadeDurationMs = 100 ms => 10 samples total, step = 0.1f per sample
        val filter =
            TrackFadeFilter(
                downstream = downstream,
                sampleRate = 100,
                initialGain = 0.0f,
                initialTargetGain = 1.0f,
                fadeDurationMs = 100L,
                trackDurationMs = 10_000L,
                isStream = false,
                shouldAutoFadeOutAtEof = { false },
            )

        val input = arrayOf(FloatArray(10) { 1.0f })
        filter.process(input, 0, 10)

        val out = downstream.lastSamples!![0]
        assertEquals(0.1f, out[0], 1e-5f)
        assertEquals(0.5f, out[4], 1e-5f)
        assertEquals(1.0f, out[9], 1e-5f)
    }

    @Test
    fun `fades out manually when targetGain is set to 0_0f and reverses smoothly back to 1_0f`() {
        val downstream = CapturingFilter()
        // 10 samples total fade (step = 0.1f)
        val filter =
            TrackFadeFilter(
                downstream = downstream,
                sampleRate = 100,
                initialGain = 1.0f,
                initialTargetGain = 1.0f,
                fadeDurationMs = 100L,
                trackDurationMs = 10_000L,
                isStream = false,
                shouldAutoFadeOutAtEof = { false },
            )

        // Start manual fade-out
        filter.targetGain = 0.0f
        val chunk1 = arrayOf(FloatArray(5) { 1.0f })
        filter.process(chunk1, 0, 5)
        // After 5 samples, gain should be 0.5f
        assertEquals(0.9f, downstream.lastSamples!![0][0], 1e-5f)
        assertEquals(0.5f, downstream.lastSamples!![0][4], 1e-5f)

        // Mid-ramp reversal: fade back in from 0.5f -> 1.0f
        filter.targetGain = 1.0f
        val chunk2 = arrayOf(FloatArray(5) { 1.0f })
        filter.process(chunk2, 0, 5)
        assertEquals(0.6f, downstream.lastSamples!![0][0], 1e-5f)
        assertEquals(1.0f, downstream.lastSamples!![0][4], 1e-5f)
    }

    @Test
    fun `triggers natural EOF fade-out when shouldAutoFadeOutAtEof is true and resets on backward seek`() {
        val downstream = CapturingFilter()
        // sampleRate = 100 Hz, trackDurationMs = 3_000 ms (300 samples), fadeDurationMs = 1_000 ms (100 samples)
        // eofFadeStartSample = 300 - 100 = 200
        val filter =
            TrackFadeFilter(
                downstream = downstream,
                sampleRate = 100,
                initialGain = 1.0f,
                initialTargetGain = 1.0f,
                fadeDurationMs = 1_000L,
                trackDurationMs = 3_000L,
                isStream = false,
                shouldAutoFadeOutAtEof = { true },
            )

        // Seek near EOF (2_500 ms => sampleCursor = 250 > 200)
        filter.seekPerformed(2_500L, 2_500L)
        val chunk1 = arrayOf(FloatArray(10) { 1.0f })
        filter.process(chunk1, 0, 10)
        // EOF fade-out should have reduced gain below 1.0f
        assertTrue(downstream.lastSamples!![0][9] < 1.0f)

        // Seek back to start of track (providedTime = 500 ms => sampleCursor = 50 < 200)
        filter.seekPerformed(500L, 500L)

        // Process enough samples to ramp back to 1.0f
        val chunk2 = arrayOf(FloatArray(100) { 1.0f })
        filter.process(chunk2, 0, 100)
        assertEquals(1.0f, downstream.lastSamples!![0][99], 1e-5f)
    }

    @Test
    fun `factory builds TrackFadeFilter using config fallback when track argument is null`() {
        val downstream = mock(UniversalPcmAudioFilter::class.java)
        val track = createTrack(duration = 10_000L, position = 0L)
        val format = StandardAudioDataFormats.DISCORD_OPUS
        var capturedFilter: TrackFadeFilter? = null
        val config =
            TrackFadeConfig(
                track = track,
                initialGain = 0.0f,
                initialTargetGain = 1.0f,
                fadeDurationMs = 1500L,
            )
        val factory =
            TrackFadeFilterFactory(
                configProvider = { config },
                shouldAutoFadeOutAtEof = { true },
            ) { capturedFilter = it }

        val chain = factory.buildChain(null, format, downstream)
        assertEquals(1, chain.size)
        assertTrue(capturedFilter != null)
        assertEquals(1.0f, capturedFilter!!.targetGain)
    }

    @Test
    fun `prepareFadeInOnSeek resets gain to 0_0f and ramps to 1_0f on seekPerformed`() {
        val downstream = CapturingFilter()
        val filter =
            TrackFadeFilter(
                downstream = downstream,
                sampleRate = 100,
                initialGain = 1.0f,
                initialTargetGain = 1.0f,
                fadeDurationMs = 100L,
                trackDurationMs = 10_000L,
                isStream = false,
                shouldAutoFadeOutAtEof = { false },
            )

        // Simulate cross-fade seek fade-out completed (currentGain = 0.0f, targetGain = 0.0f)
        filter.targetGain = 0.0f
        filter.process(arrayOf(FloatArray(10) { 1.0f }), 0, 10)
        assertEquals(0.0f, filter.currentGain, 1e-5f)

        // Prepare fade-in on next seek and perform seek
        filter.prepareFadeInOnSeek()
        filter.seekPerformed(3_000L, 3_000L)
        assertEquals(0.0f, filter.currentGain, 1e-5f)
        assertEquals(1.0f, filter.targetGain, 1e-5f)

        // Next 10 samples ramp from 0.1f -> 1.0f
        filter.process(arrayOf(FloatArray(10) { 1.0f }), 0, 10)
        assertEquals(0.1f, downstream.lastSamples!![0][0], 1e-5f)
        assertEquals(1.0f, downstream.lastSamples!![0][9], 1e-5f)
    }

    @Test
    fun `factory starts with initialGain 0_0f on first build even when track has start offset position`() {
        val downstream = mock(UniversalPcmAudioFilter::class.java)
        val track = createTrack(duration = 300_000L, position = 90_000L)
        val format = StandardAudioDataFormats.DISCORD_OPUS
        var capturedFilter: TrackFadeFilter? = null
        val config =
            TrackFadeConfig(
                track = track,
                initialGain = 0.0f,
                initialTargetGain = 1.0f,
                fadeDurationMs = 1500L,
            )
        val factory =
            TrackFadeFilterFactory(
                configProvider = { config },
                shouldAutoFadeOutAtEof = { true },
            ) { capturedFilter = it }

        // First buildChain for a track with start offset 90_000L still starts at 0.0f
        factory.buildChain(track, format, downstream)
        assertEquals(0.0f, capturedFilter!!.currentGain, 1e-5f)

        // Second buildChain on the same config (mid-track filter rebuild) starts at 1.0f
        factory.buildChain(track, format, downstream)
        assertEquals(1.0f, capturedFilter.currentGain, 1e-5f)
    }
}
