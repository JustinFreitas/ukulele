package dev.arbjerg.ukulele.audio.local

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.ukulele.command.NowPlayingCommand
import dev.arbjerg.ukulele.config.BotProps
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

class InMemoryLocalAudioSourceManagerTest {
    private var apm: DefaultAudioPlayerManager? = null
    private var cache: LocalFileMemoryCache? = null

    @AfterEach
    fun tearDown() {
        apm?.shutdown()
        cache?.destroy()
    }

    /**
     * Synthesizes a valid 16-bit mono 8kHz PCM WAV file (~0.25s) so Lavaplayer's real WAV container probe
     * and playback pipeline can decode actual audio frames without external test fixtures.
     */
    private fun writeValidWavFile(
        target: File,
        sampleCount: Int = 2000,
    ) {
        val sampleRate = 8000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val dataSize = sampleCount * channels * (bitsPerSample / 8)
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // PCM chunk size
        buffer.putShort(1) // AudioFormat = 1 (PCM)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * (bitsPerSample / 8)).toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        for (i in 0 until sampleCount) {
            buffer.putShort(((i % 64) * 200).toShort())
        }

        Files.write(target.toPath(), buffer.array())
    }

    @Test
    fun `plays local track and cloned track from RAM even after underlying file is deleted on disk`(
        @TempDir tempDir: Path,
    ) {
        val props =
            BotProps().apply {
                cacheLocalFilesInMemory = true
                maxTotalCacheMb = 32
                maxCachedFileMb = 16
            }
        val c = LocalFileMemoryCache(props)
        cache = c
        val manager = DefaultAudioPlayerManager()
        apm = manager
        val sm = InMemoryLocalAudioSourceManager(c, props)
        manager.registerSourceManager(sm)

        val albumDir = Files.createDirectories(tempDir.resolve("Album [FLAC]"))
        val wavFile = albumDir.resolve("01 Boss Theme.wav").toFile()
        writeValidWavFile(wavFile, sampleCount = 4000)

        // Verify quoted path + [FLAC] brackets + #t= fragment parsing
        val parsed =
            LocalPathInputUtils.parsePlayIdentifier(
                "[Boss, v:65] \"${wavFile.absolutePath}#t=1s\"",
            )
        assertNotNull(parsed)
        assertEquals("[Boss, v:65]", parsed!!.queueLabel)
        assertEquals(wavFile.absolutePath, parsed.source)
        assertEquals(1000L, parsed.startOffsetMs)

        val loaded = sm.loadItem(manager, AudioReference("\"${wavFile.absolutePath}\"", null)) as AudioTrack
        assertTrue(loaded is InMemoryLocalAudioTrack)

        // Pin & prefetch into RAM
        c.bindPlayerCallbacks(snapshotProvider = { mapOf(1L to listOf(loaded)) }, stateNotifier = {})
        c.reconcileAndPrefetch()

        val deadline = System.currentTimeMillis() + 5000L
        while (c.trackCacheState(loaded) != "MEMORY" && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        assertEquals("MEMORY", c.trackCacheState(loaded))

        // Delete the file from disk completely!
        assertTrue(wavFile.delete())
        assertTrue(!wavFile.exists())

        // Play the track and verify frames are produced strictly from RAM
        val audioPlayer = manager.createPlayer()
        audioPlayer.playTrack(loaded)

        val frameBuf = ByteBuffer.allocate(4096)
        val frame = MutableAudioFrame().apply { setBuffer(frameBuf) }
        var gotFrame = false
        val playDeadline = System.currentTimeMillis() + 5000L
        while (System.currentTimeMillis() < playDeadline) {
            frameBuf.clear()
            if (audioPlayer.provide(frame)) {
                gotFrame = true
                break
            }
            Thread.sleep(20L)
        }
        assertTrue(gotFrame, "Expected decoded audio frame strictly from in-memory ByteArray after file deletion")
        assertEquals(PlaybackSource.MEMORY, (loaded as InMemoryLocalAudioTrack).activePlaybackSource)

        // Verify NowPlayingCommand embed does not crash on local path URI and reports local (memory)
        val embed = NowPlayingCommand(c).buildEmbed(loaded)
        assertEquals("Source: local (memory)", embed.footer?.text)

        // Verify makeClone() returns InMemoryLocalAudioTrack and also plays from RAM with file still deleted
        val clone = loaded.makeClone()
        assertTrue(clone is InMemoryLocalAudioTrack)
        assertNull((clone as InMemoryLocalAudioTrack).activePlaybackSource)

        audioPlayer.playTrack(clone)
        var gotCloneFrame = false
        val cloneDeadline = System.currentTimeMillis() + 5000L
        while (System.currentTimeMillis() < cloneDeadline) {
            frameBuf.clear()
            if (audioPlayer.provide(frame)) {
                gotCloneFrame = true
                break
            }
            Thread.sleep(20L)
        }
        assertTrue(gotCloneFrame, "Expected cloned track to play from RAM")
        assertEquals(PlaybackSource.MEMORY, clone.activePlaybackSource)
        audioPlayer.destroy()
    }

    @Test
    fun `preloads cold Track 1 into RAM before playback by default and supports opt-out disk cold start`(
        @TempDir tempDir: Path,
    ) {
        val defaultProps =
            BotProps().apply {
                cacheLocalFilesInMemory = true
                preloadActiveTrackIntoMemory = true
                maxTotalCacheMb = 32
                maxCachedFileMb = 16
            }
        val c1 = LocalFileMemoryCache(defaultProps)
        cache = c1
        val manager1 = DefaultAudioPlayerManager()
        apm = manager1
        val sm1 = InMemoryLocalAudioSourceManager(c1, defaultProps)
        manager1.registerSourceManager(sm1)

        val coldWav = tempDir.resolve("cold-first-track.wav").toFile()
        writeValidWavFile(coldWav, sampleCount = 4000)

        // Play cold WITHOUT calling reconcileAndPrefetch() beforehand (simulating Track #1 race)
        val track1 = sm1.loadItem(manager1, AudioReference(coldWav.absolutePath, null)) as InMemoryLocalAudioTrack
        val player1 = manager1.createPlayer()
        player1.playTrack(track1)

        val frameBuf = ByteBuffer.allocate(4096)
        val frame = MutableAudioFrame().apply { setBuffer(frameBuf) }
        var gotFrame = false
        val deadline = System.currentTimeMillis() + 5000L
        while (System.currentTimeMillis() < deadline) {
            frameBuf.clear()
            if (player1.provide(frame)) {
                gotFrame = true
                break
            }
            Thread.sleep(20L)
        }
        assertTrue(gotFrame)
        assertEquals(PlaybackSource.MEMORY, track1.activePlaybackSource, "Cold Track #1 should preload into RAM first by default")
        assertEquals("MEMORY", c1.trackCacheState(track1))
        player1.destroy()
        sm1.shutdown()
        c1.destroy()

        // Opt-out (preloadActiveTrackIntoMemory = false): cold Track #1 streams from DISK immediately on pass #1
        val optOutProps =
            BotProps().apply {
                cacheLocalFilesInMemory = true
                preloadActiveTrackIntoMemory = false
                maxTotalCacheMb = 32
                maxCachedFileMb = 16
            }
        val c2 = LocalFileMemoryCache(optOutProps)
        cache = c2
        val sm2 = InMemoryLocalAudioSourceManager(c2, optOutProps)
        val (_, source2) = c2.openPlaybackStream(coldWav).also { it.first.close() }
        assertEquals(PlaybackSource.DISK, source2, "Opting out of preloadActiveTrackIntoMemory should fall back to DISK on cold start")
        sm2.shutdown()
    }

    @Test
    fun `recursively scans folders in natural alphanumeric order, ignores non-audio and corrupt files, and enforces maxFolderTracks`(
        @TempDir tempDir: Path,
    ) {
        val props =
            BotProps().apply {
                recursiveFolderScan = true
                maxFolderTracks = 3
            }
        val c = LocalFileMemoryCache(props)
        cache = c
        val manager = DefaultAudioPlayerManager()
        apm = manager
        val sm = InMemoryLocalAudioSourceManager(c, props)

        val sub = Files.createDirectories(tempDir.resolve("Dungeon"))
        val nested = Files.createDirectories(sub.resolve("Level 2"))
        val hidden = Files.createDirectories(sub.resolve(".hidden"))

        // Create files out of lexicographical order ("2" vs "10") + non-audio + corrupt .wav + hidden file
        writeValidWavFile(sub.resolve("10 - Boss.wav").toFile())
        writeValidWavFile(sub.resolve("2 - Entrance.wav").toFile())
        writeValidWavFile(sub.resolve("1 - Overture.wav").toFile())
        writeValidWavFile(nested.resolve("1 - Deep Cavern.wav").toFile())
        writeValidWavFile(hidden.resolve("secret.wav").toFile())
        Files.writeString(sub.resolve("cover.jpg"), "not an image")
        Files.writeString(sub.resolve("0 - Corrupt.wav"), "corrupt-header-not-riff")

        // Temporarily allow up to 10 tracks to verify corrupt skip + natural sort across subfolders
        props.maxFolderTracks = 10
        val playlist = sm.loadItem(manager, AudioReference(sub.toString(), null)) as AudioPlaylist
        assertEquals("Dungeon", playlist.name)
        assertEquals(
            listOf("1 - Overture", "2 - Entrance", "10 - Boss", "1 - Deep Cavern"),
            playlist.tracks.map { it.info.title },
        )

        // Verify natural sort happens BEFORE maxFolderTracks truncation (so "1", "2", "10" beat "Level 2")
        // Note "0 - Corrupt.wav" is included in candidate limit of 3 -> 2 valid tracks returned in order
        props.maxFolderTracks = 3
        val capped = sm.loadItem(manager, AudioReference(sub.toString(), null)) as AudioPlaylist
        assertEquals(listOf("1 - Overture", "2 - Entrance"), capped.tracks.map { it.info.title })

        // Verify remote URL returns null cleanly without throwing InvalidPathException
        assertNull(sm.loadItem(manager, AudioReference("https://www.youtube.com/watch?v=dQw4w9WgXcQ", null)))

        // Verify track encode/decode preserves InMemoryLocalAudioTrack
        val firstTrack = playlist.tracks.first()
        val baos = ByteArrayOutputStream()
        sm.encodeTrack(firstTrack, DataOutputStream(baos))
        val decoded =
            sm.decodeTrack(
                firstTrack.info,
                java.io.DataInputStream(java.io.ByteArrayInputStream(baos.toByteArray())),
            )
        assertTrue(decoded is InMemoryLocalAudioTrack)
    }

    @Test
    fun `enforces owner-only Discord local and UNC path guardrails while allowing owner and rejecting device paths`() {
        val props =
            BotProps().apply {
                ownerId = "owner-123"
                restrictDiscordLocalPathsToOwner = true
                allowUncPaths = true
            }

        // Owner in Discord: both local drive and UNC share paths are allowed
        assertNull(
            LocalPathInputUtils.checkPathAccess(
                "Z:\\Music\\Tavern",
                props,
                isDiscordInvoker = true,
                invokerId = "owner-123",
            ),
        )
        assertNull(
            LocalPathInputUtils.checkPathAccess(
                "\\\\nas\\music\\tavern.flac",
                props,
                isDiscordInvoker = true,
                invokerId = "owner-123",
            ),
        )

        // Ukulele-Remote API (isDiscordInvoker = false): both local drive and UNC share paths are allowed
        assertNull(
            LocalPathInputUtils.checkPathAccess(
                "Z:\\Music\\Tavern",
                props,
                isDiscordInvoker = false,
                invokerId = null,
            ),
        )
        assertNull(
            LocalPathInputUtils.checkPathAccess(
                "\\\\nas\\music\\tavern.flac",
                props,
                isDiscordInvoker = false,
                invokerId = null,
            ),
        )

        // Non-owner in Discord: blocked from both local paths and UNC paths, but allowed remote URLs
        assertNotNull(
            LocalPathInputUtils.checkPathAccess(
                "C:\\Users\\secret",
                props,
                isDiscordInvoker = true,
                invokerId = "rando-999",
            ),
        )
        assertNotNull(
            LocalPathInputUtils.checkPathAccess(
                "\\\\attacker\\share\\leak.mp3",
                props,
                isDiscordInvoker = true,
                invokerId = "rando-999",
            ),
        )
        assertNull(
            LocalPathInputUtils.checkPathAccess(
                "https://youtu.be/abc123",
                props,
                isDiscordInvoker = true,
                invokerId = "rando-999",
            ),
        )

        // Windows device namespace paths are always rejected even for owner/API
        assertNotNull(
            LocalPathInputUtils.checkPathAccess(
                "\\\\.\\PhysicalDrive0",
                props,
                isDiscordInvoker = false,
                invokerId = null,
            ),
        )

        val c = LocalFileMemoryCache(props)
        cache = c
        val manager = DefaultAudioPlayerManager()
        apm = manager
        val sm = InMemoryLocalAudioSourceManager(c, props)
        assertThrows(FriendlyException::class.java) {
            sm.loadItem(manager, AudioReference("\\\\.\\PhysicalDrive0", null))
        }
    }
}
