package dev.arbjerg.ukulele.audio.local

import dev.arbjerg.ukulele.audio.TrackTimeParser
import dev.arbjerg.ukulele.config.BotProps
import java.io.File
import java.math.BigInteger
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import java.util.regex.Pattern

data class ParsedPlayIdentifier(
    val queueLabel: String,
    val source: String,
    val startOffsetMs: Long?,
)

object LocalPathInputUtils {
    private val nonGreedyLabelPattern: Pattern = Pattern.compile("^\\s*(\\[[^\\]]*])?\\s*(.+)$")
    private val remoteSchemePattern: Pattern = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]{1,}://.*$")
    private val searchPrefixPattern: Pattern = Pattern.compile("^(?:ytsearch|ytmsearch|scsearch):.*$", Pattern.CASE_INSENSITIVE)
    private val windowsDrivePattern: Pattern = Pattern.compile("^[a-zA-Z]:[\\\\/].*$")
    private val isWindows: Boolean = File.separatorChar == '\\'

    val supportedAudioExtensions: Set<String> =
        setOf(
            "mp3",
            "flac",
            "ogg",
            "opus",
            "wav",
            "m4a",
            "aac",
            "mp4",
            "webm",
            "mkv",
        )

    val ignoredDirectoryNames: Set<String> =
        setOf(
            "\$recycle.bin",
            "system volume information",
            "node_modules",
            "appdata",
        )

    fun stripWrappingQuotesAndAngles(raw: String): String {
        var result = raw.trim()
        while (result.length >= 2) {
            if (result.startsWith("<") && result.endsWith(">")) {
                result = result.substring(1, result.length - 1).trim()
            } else if (result.startsWith("\"") && result.endsWith("\"")) {
                result = result.substring(1, result.length - 1).trim()
            } else {
                break
            }
        }
        return result
    }

    /**
     * Parses `[Optional Label, v:50, t:1:30] "C:\Path\Album [FLAC]\01.flac#t=30s"` using a non-greedy
     * leading bracket matcher, stripping wrapping quotes/angles before extracting `#t=` / `?t=` offsets.
     */
    fun parsePlayIdentifier(rawIdentifier: String): ParsedPlayIdentifier? {
        val trimmed = rawIdentifier.trim()
        if (trimmed.isEmpty()) return null

        val unquotedWhole = stripWrappingQuotesAndAngles(trimmed)
        if (unquotedWhole.isEmpty()) return null

        // If the entire input (without splitting a leading [...] block) directly resolves to an existing
        // path on disk (e.g. a relative path literally starting with "[OST] Song.mp3"), keep it intact.
        val wholePathWithoutFragment = TrackTimeParser.extractSourceAndOffset(unquotedWhole).first
        if (trimmed.startsWith("[") && existingFileOrDir(wholePathWithoutFragment) != null) {
            val (cleanSource, offsetMs) = TrackTimeParser.extractSourceAndOffset(unquotedWhole)
            return ParsedPlayIdentifier(queueLabel = "", source = cleanSource, startOffsetMs = offsetMs)
        }

        val matcher = nonGreedyLabelPattern.matcher(trimmed)
        if (!matcher.find()) return null

        val queueLabel = matcher.group(1)?.trim() ?: ""
        val rawSourcePart = matcher.group(2)?.trim() ?: return null
        val unquotedSource = stripWrappingQuotesAndAngles(rawSourcePart)
        val (cleanSource, urlOffsetMs) = TrackTimeParser.extractSourceAndOffset(unquotedSource)
        if (cleanSource.isEmpty()) return null

        val startOffsetMs = TrackTimeParser.parseLabelOffsetMs(queueLabel) ?: urlOffsetMs
        return ParsedPlayIdentifier(
            queueLabel = queueLabel,
            source = cleanSource,
            startOffsetMs = startOffsetMs,
        )
    }

    fun isRemoteUrlOrSearch(source: String): Boolean {
        val cleaned = stripWrappingQuotesAndAngles(source)
        return remoteSchemePattern.matcher(cleaned).matches() || searchPrefixPattern.matcher(cleaned).matches()
    }

    fun isUncPath(source: String): Boolean {
        val cleaned = stripWrappingQuotesAndAngles(source)
        return (cleaned.startsWith("\\\\") && !isWindowsDeviceNamespace(cleaned)) ||
            cleaned.startsWith("//") ||
            cleaned.startsWith("\\\\?\\UNC\\", ignoreCase = true)
    }

    fun isWindowsDeviceNamespace(source: String): Boolean {
        val cleaned = stripWrappingQuotesAndAngles(source)
        if (cleaned.startsWith("\\\\.\\")) return true
        if (cleaned.startsWith("\\\\?\\")) {
            val rest = cleaned.substring(4)
            val isExtendedDrive = windowsDrivePattern.matcher(rest).matches()
            val isExtendedUnc = rest.startsWith("UNC\\", ignoreCase = true)
            return !isExtendedDrive && !isExtendedUnc
        }
        return false
    }

    fun looksLikeLocalOrUncPath(source: String): Boolean {
        val cleaned = stripWrappingQuotesAndAngles(source)
        if (cleaned.isEmpty() || isRemoteUrlOrSearch(cleaned)) return false
        if (isWindowsDeviceNamespace(cleaned) || isUncPath(cleaned) || windowsDrivePattern.matcher(cleaned).matches()) {
            return true
        }
        if (cleaned.startsWith("./") || cleaned.startsWith(".\\") || cleaned.startsWith("../") || cleaned.startsWith("..\\") ||
            cleaned.startsWith("/")
        ) {
            return true
        }
        return existingFileOrDir(cleaned) != null
    }

    /**
     * Checks local/UNC path access policy. Returns null when allowed, or a human-readable denial reason.
     */
    fun checkPathAccess(
        source: String,
        botProps: BotProps,
        isDiscordInvoker: Boolean,
        invokerId: String?,
    ): String? {
        val cleaned = stripWrappingQuotesAndAngles(source)
        if (isRemoteUrlOrSearch(cleaned)) return null

        if (isWindowsDeviceNamespace(cleaned)) {
            return "Windows device namespace paths are not permitted."
        }

        if (isDiscordInvoker && botProps.restrictDiscordLocalPathsToOwner && botProps.ownerId.isNotBlank() &&
            invokerId != botProps.ownerId && looksLikeLocalOrUncPath(cleaned)
        ) {
            return "Local and network file paths are restricted to the bot owner."
        }

        if (isUncPath(cleaned) && !botProps.allowUncPaths && !isUnderAllowedRoots(cleaned, botProps.allowedLocalPaths)) {
            return "UNC network share paths are disabled in configuration."
        }

        if (botProps.allowedLocalPaths.isNotEmpty() && looksLikeLocalOrUncPath(cleaned) &&
            !isUnderAllowedRoots(cleaned, botProps.allowedLocalPaths)
        ) {
            return "Path is outside configured allowedLocalPaths."
        }

        return null
    }

    fun isUnderAllowedRoots(
        rawPath: String,
        allowedRoots: List<String>,
    ): Boolean {
        if (allowedRoots.isEmpty()) return true
        val candidate = toNormalizedPathOrNull(rawPath) ?: return false
        return allowedRoots.any { rootStr ->
            val root = toNormalizedPathOrNull(rootStr) ?: return@any false
            if (isWindows) {
                val candLower = candidate.toString().lowercase(Locale.ROOT)
                val rootLower = root.toString().lowercase(Locale.ROOT).trimEnd('\\', '/')
                candLower == rootLower || candLower.startsWith("$rootLower\\")
            } else {
                candidate.startsWith(root)
            }
        }
    }

    fun toNormalizedPathOrNull(rawPath: String): Path? {
        val cleaned = stripWrappingQuotesAndAngles(rawPath)
        if (cleaned.isEmpty() || isRemoteUrlOrSearch(cleaned) || isWindowsDeviceNamespace(cleaned)) return null
        return try {
            Path.of(cleaned).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun normalizeCacheKey(rawPath: String): String? {
        val normalized = toNormalizedPathOrNull(rawPath)?.toString() ?: return null
        return if (isWindows) normalized.lowercase(Locale.ROOT) else normalized
    }

    private fun existingFileOrDir(rawPath: String): File? {
        val path = toNormalizedPathOrNull(rawPath) ?: return null
        val file = path.toFile()
        return if (file.exists()) file else null
    }

    val naturalStringComparator: Comparator<String> =
        Comparator { a, b ->
            var ia = 0
            var ib = 0
            while (ia < a.length && ib < b.length) {
                val ca = a[ia]
                val cb = b[ib]
                if (ca.isDigit() && cb.isDigit()) {
                    val startA = ia
                    while (ia < a.length && a[ia].isDigit()) ia++
                    val startB = ib
                    while (ib < b.length && b[ib].isDigit()) ib++
                    val numA = BigInteger(a.substring(startA, ia))
                    val numB = BigInteger(b.substring(startB, ib))
                    val cmp = numA.compareTo(numB)
                    if (cmp != 0) return@Comparator cmp
                    val lenCmp = (ia - startA).compareTo(ib - startB)
                    if (lenCmp != 0) return@Comparator lenCmp
                } else {
                    val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (cmp != 0) return@Comparator cmp
                    ia++
                    ib++
                }
            }
            a.length.compareTo(b.length)
        }

    fun naturalRelativePathComparator(root: Path): Comparator<Path> =
        Comparator { p1, p2 ->
            val r1 = root.relativize(p1)
            val r2 = root.relativize(p2)
            val minCount = minOf(r1.nameCount, r2.nameCount)
            for (i in 0 until minCount) {
                val s1 = r1.getName(i).toString()
                val s2 = r2.getName(i).toString()
                val cmp = naturalStringComparator.compare(s1, s2)
                if (cmp != 0) return@Comparator cmp
            }
            r1.nameCount.compareTo(r2.nameCount)
        }
}
