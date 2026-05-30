package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.track.AudioTrack

class TrackQueue {
    private val queue = mutableListOf<AudioTrack>()
    val tracks: List<AudioTrack> get() = synchronized(queue) { queue.toList() }
    val duration: Long get() =
        synchronized(queue) {
            queue.filterNot { it.info.isStream }.sumOf { it.info.length }
        } // Streams don't have a valid time.

    fun add(vararg tracks: AudioTrack) {
        synchronized(queue) { queue.addAll(tracks) }
    }

    fun addFirst(track: AudioTrack) {
        synchronized(queue) { queue.add(0, track) }
    }

    fun take() = synchronized(queue) { queue.removeFirstOrNull() }

    fun clear() = synchronized(queue) { queue.clear() }

    fun removeRange(range: IntRange): List<AudioTrack> {
        synchronized(queue) {
            val start = range.first.coerceAtLeast(0)
            val end = range.last.coerceAtMost(queue.lastIndex)
            if (start > end) return emptyList()
            val validRange = start..end

            val list = queue.slice(validRange)
            queue.subList(validRange.first, validRange.last + 1).clear()
            return list
        }
    }

    fun shuffle() {
        synchronized(queue) { queue.shuffle() }
    }
}
