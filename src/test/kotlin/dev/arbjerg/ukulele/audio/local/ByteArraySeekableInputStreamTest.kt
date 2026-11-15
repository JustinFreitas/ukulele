package dev.arbjerg.ukulele.audio.local

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.io.IOException

class ByteArraySeekableInputStreamTest {
    @Test
    fun `reads single bytes and array slices accurately without mutating position on EOF`() {
        val data = byteArrayOf(0x01, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x42)
        val stream = ByteArraySeekableInputStream(data)

        assertEquals(5L, stream.contentLength)
        assertEquals(5, stream.available())
        assertEquals(0x01, stream.read())
        assertEquals(0x7F, stream.read())
        assertEquals(2L, stream.position)

        val buf = ByteArray(10)
        assertEquals(0, stream.read(buf, 2, 0))
        assertEquals(2L, stream.position)

        val readCount = stream.read(buf, 1, 5)
        assertEquals(3, readCount)
        assertEquals(0x80.toByte(), buf[1])
        assertEquals(0xFF.toByte(), buf[2])
        assertEquals(0x42.toByte(), buf[3])
        assertEquals(5L, stream.position)
        assertEquals(0, stream.available())

        // Repeated EOF reads must return -1 and never decrement position (unlike upstream LocalSeekableInputStream)
        assertEquals(-1, stream.read())
        assertEquals(5L, stream.position)
        assertEquals(-1, stream.read(buf, 0, 4))
        assertEquals(5L, stream.position)
    }

    @Test
    fun `supports forward and backward hard seeks, skipFully, and rejects negative seek`() {
        val data = ByteArray(100) { it.toByte() }
        val stream = ByteArraySeekableInputStream(data)

        assertTrue(stream.canSeekHard())
        stream.seek(80L)
        assertEquals(80L, stream.position)
        assertEquals(80, stream.read())

        // Seek backwards
        stream.seek(10L)
        assertEquals(10L, stream.position)
        stream.skipFully(5L)
        assertEquals(15L, stream.position)
        assertEquals(15, stream.read())

        // Seek past EOF clamps to contentLength
        stream.seek(500L)
        assertEquals(100L, stream.position)
        assertEquals(-1, stream.read())
        assertThrows(EOFException::class.java) {
            stream.skipFully(1L)
        }

        // Negative seek throws IOException
        assertThrows(IOException::class.java) {
            stream.seek(-1L)
        }
    }

    @Test
    fun `concurrent streams over the same ByteArray maintain independent cursors`() {
        val data = byteArrayOf(10, 20, 30, 40, 50, 60)
        val s1 = ByteArraySeekableInputStream(data)
        val s2 = ByteArraySeekableInputStream(data)

        s1.seek(4L)
        s2.seek(1L)

        val out1 = ByteArray(2)
        val out2 = ByteArray(3)
        assertEquals(2, s1.read(out1, 0, 2))
        assertEquals(3, s2.read(out2, 0, 3))

        assertArrayEquals(byteArrayOf(50, 60), out1)
        assertArrayEquals(byteArrayOf(20, 30, 40), out2)
        assertEquals(6L, s1.position)
        assertEquals(4L, s2.position)
    }
}
