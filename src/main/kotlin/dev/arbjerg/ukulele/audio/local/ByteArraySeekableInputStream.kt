package dev.arbjerg.ukulele.audio.local

import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider
import java.io.IOException
import java.util.Objects

/**
 * In-memory [SeekableInputStream] backed by a shared, immutable [ByteArray].
 *
 * Multiple concurrent track clones or probes may safely read from the same underlying [bytes]
 * array because each stream instance maintains its own independent [position] cursor.
 */
class ByteArraySeekableInputStream(
    private val bytes: ByteArray,
) : SeekableInputStream(bytes.size.toLong(), 0L) {
    private var position: Long = 0L

    override fun read(): Int {
        if (position >= bytes.size) {
            return -1
        }
        val value = bytes[position.toInt()].toInt() and 0xFF
        position++
        return value
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        Objects.checkFromIndexSize(off, len, b.size)
        if (len == 0) {
            return 0
        }
        if (position >= bytes.size) {
            return -1
        }
        val toRead = minOf(len.toLong(), bytes.size.toLong() - position).toInt()
        System.arraycopy(bytes, position.toInt(), b, off, toRead)
        position += toRead
        return toRead
    }

    override fun skip(n: Long): Long {
        if (n <= 0L || position >= bytes.size) {
            return 0L
        }
        val skipped = minOf(n, bytes.size.toLong() - position)
        position += skipped
        return skipped
    }

    override fun available(): Int = (bytes.size.toLong() - position).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    override fun markSupported(): Boolean = false

    @Synchronized
    override fun reset(): Unit = throw IOException("mark/reset not supported")

    override fun close() {
        // Shared ByteArray lifecycle is managed by LocalFileMemoryCache; nothing to close.
    }

    override fun getPosition(): Long = position

    override fun canSeekHard(): Boolean = true

    override fun seekHard(position: Long) {
        if (position < 0L) {
            throw IOException("Cannot seek to negative position: $position")
        }
        this.position = position.coerceAtMost(bytes.size.toLong())
    }

    override fun getTrackInfoProviders(): List<AudioTrackInfoProvider> = emptyList()
}
