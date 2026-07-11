package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TrackQueueTest {
    private lateinit var trackQueue: TrackQueue
    private lateinit var track1: AudioTrack
    private lateinit var track2: AudioTrack
    private lateinit var streamTrack: AudioTrack

    @BeforeEach
    fun setUp() {
        trackQueue = TrackQueue()

        track1 = mock(AudioTrack::class.java)
        val info1 = AudioTrackInfo("Title 1", "Author 1", 300000L, "id1", false, "uri1")
        `when`(track1.info).thenReturn(info1)

        track2 = mock(AudioTrack::class.java)
        val info2 = AudioTrackInfo("Title 2", "Author 2", 150000L, "id2", false, "uri2")
        `when`(track2.info).thenReturn(info2)

        streamTrack = mock(AudioTrack::class.java)
        val streamInfo = AudioTrackInfo("Stream", "Author Stream", Long.MAX_VALUE, "id3", true, "uri3")
        `when`(streamTrack.info).thenReturn(streamInfo)
    }

    @Test
    fun `add adds tracks to the queue`() {
        trackQueue.add(track1, track2)
        assertEquals(2, trackQueue.size)
        assertEquals(track1, trackQueue.get(0))
        assertEquals(track2, trackQueue.get(1))
    }

    @Test
    fun `addFirst adds track to the front of the queue`() {
        trackQueue.add(track2)
        trackQueue.addFirst(track1)
        assertEquals(2, trackQueue.size)
        assertEquals(track1, trackQueue.get(0))
        assertEquals(track2, trackQueue.get(1))
    }

    @Test
    fun `duration sums only non-stream track lengths`() {
        trackQueue.add(track1, track2, streamTrack)
        assertEquals(450000L, trackQueue.duration)
    }

    @Test
    fun `take removes and returns the first track`() {
        trackQueue.add(track1, track2)
        val taken = trackQueue.take()
        assertEquals(track1, taken)
        assertEquals(1, trackQueue.size)
        assertEquals(track2, trackQueue.get(0))
    }

    @Test
    fun `take returns null if queue is empty`() {
        assertNull(trackQueue.take())
    }

    @Test
    fun `clear empties the queue`() {
        trackQueue.add(track1, track2)
        trackQueue.clear()
        assertEquals(0, trackQueue.size)
        assertTrue(trackQueue.tracks.isEmpty())
    }

    @Test
    fun `removeRange removes specified index range and returns removed tracks`() {
        trackQueue.add(track1, track2, streamTrack)
        val removed = trackQueue.removeRange(0..1)

        assertEquals(2, removed.size)
        assertEquals(track1, removed[0])
        assertEquals(track2, removed[1])

        assertEquals(1, trackQueue.size)
        assertEquals(streamTrack, trackQueue.get(0))
    }

    @Test
    fun `removeRange returns empty list on invalid range`() {
        trackQueue.add(track1)
        val removed = trackQueue.removeRange(2..4)
        assertTrue(removed.isEmpty())
        assertEquals(1, trackQueue.size)
    }

    @Test
    fun `removeAt removes track at index`() {
        trackQueue.add(track1, track2)
        val removed = trackQueue.removeAt(0)
        assertEquals(track1, removed)
        assertEquals(1, trackQueue.size)
        assertEquals(track2, trackQueue.get(0))
    }

    @Test
    fun `removeAt returns null for out of bounds index`() {
        trackQueue.add(track1)
        assertNull(trackQueue.removeAt(5))
        assertNull(trackQueue.removeAt(-1))
    }

    @Test
    fun `reorder shifts track positions in queue`() {
        trackQueue.add(track1, track2, streamTrack)
        val success = trackQueue.reorder(0, 2)

        assertTrue(success)
        assertEquals(track2, trackQueue.get(0))
        assertEquals(streamTrack, trackQueue.get(1))
        assertEquals(track1, trackQueue.get(2))
    }

    @Test
    fun `reorder returns false for invalid indices`() {
        trackQueue.add(track1, track2)
        assertFalse(trackQueue.reorder(0, 5))
        assertFalse(trackQueue.reorder(-1, 1))
    }
}
