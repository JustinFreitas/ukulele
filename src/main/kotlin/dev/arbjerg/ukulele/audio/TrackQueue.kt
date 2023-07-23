package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.track.AudioTrack

class TrackQueue {

    private val queue = mutableListOf<AudioTrack>()
    val tracks: List<AudioTrack> get() = queue
    val duration: Long get() = queue.filterNot { it.info.isStream }.sumOf { it.info.length } // Streams don't have a valid time.

    fun add(vararg tracks: AudioTrack) { queue.addAll(tracks) }
    fun addFirst(track: AudioTrack) { queue.add(0, track) }
    fun take() = queue.removeFirstOrNull()
    fun clear() = queue.clear()

    fun removeRange(range: IntRange): List<AudioTrack> {
        val list = queue.slice(range)
        queue.removeAll(list.toSet())
        return list
    }

    fun shuffle() {
        queue.shuffle()
    }
}