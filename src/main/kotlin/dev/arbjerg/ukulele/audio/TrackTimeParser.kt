package dev.arbjerg.ukulele.audio

import java.util.regex.Pattern

data class TrackStartOffset(
    val offsetMs: Long,
) {
    /**
     * YoutubeAudioTrack in youtube-source invokes JsonBrowser.parse(userData.toString()) whenever userData != null.
     * Returning "{}" ensures full compatibility with YoutubeAudioTrack without Jackson parse warnings.
     */
    override fun toString(): String = "{}"
}

object TrackTimeParser {
    private val colonPattern: Pattern = Pattern.compile("^(\\d+)(?::([0-5]?\\d))?(?::([0-5]?\\d))?$")
    private val unitPattern: Pattern =
        Pattern.compile(
            "^(?:(\\d+)[hH])?(?:(\\d+)[mM])?(?:(\\d+)[sS])?$",
        )
    private val bracketBlockPattern: Pattern = Pattern.compile("^\\s*\\[([^\\]]+)\\].*$")
    private val labelTimePattern: Pattern =
        Pattern.compile(
            "(?:^|[,;\\s])(?:t|start)\\s*:\\s*([0-9hmsHMS:]+)",
            Pattern.CASE_INSENSITIVE,
        )
    private val fragmentTimePattern: Pattern =
        Pattern.compile(
            "#(?:t|start)=([0-9hmsHMS:]+)$",
            Pattern.CASE_INSENSITIVE,
        )
    private val queryTimePattern: Pattern =
        Pattern.compile(
            "[?&](?:t|start)=([0-9hmsHMS:]+)(?=[&#]|$)",
            Pattern.CASE_INSENSITIVE,
        )

    /**
     * Parses a time string into milliseconds.
     * Supports:
     * - Colon-separated format: `ss`, `mm:ss`, `hh:mm:ss` (e.g. `45`, `1:30`, `01:02:03`)
     * - Unit format: `[Xh][Ym][Zs]` (e.g. `90s`, `1m30s`, `1h2m3s`)
     */
    fun parseTimeOffsetMs(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val colonMatcher = colonPattern.matcher(trimmed)
        if (colonMatcher.find()) {
            val g1 = colonMatcher.group(1)?.toLongOrNull() ?: return null
            val g2 = colonMatcher.group(2)?.toLongOrNull()
            val g3 = colonMatcher.group(3)?.toLongOrNull()
            val totalSeconds =
                when {
                    g2 != null && g3 != null -> g1 * 3600L + g2 * 60L + g3
                    g2 != null -> g1 * 60L + g2
                    else -> g1
                }
            return totalSeconds * 1000L
        }

        val unitMatcher = unitPattern.matcher(trimmed)
        if (unitMatcher.find()) {
            val hStr = unitMatcher.group(1)
            val mStr = unitMatcher.group(2)
            val sStr = unitMatcher.group(3)
            if (hStr == null && mStr == null && sStr == null) return null
            val hours = hStr?.toLongOrNull() ?: 0L
            val minutes = mStr?.toLongOrNull() ?: 0L
            val seconds = sStr?.toLongOrNull() ?: 0L
            return (hours * 3600L + minutes * 60L + seconds) * 1000L
        }

        return null
    }

    /**
     * Extracts `t:<time>` or `start:<time>` from a leading `[...]` queue label block.
     * Examples: `[Boss, v:65, t:1:30]`, `[t:45s] Song`, `[start:2:00]`.
     */
    fun parseLabelOffsetMs(titleOrLabel: String): Long? {
        val bracketMatcher = bracketBlockPattern.matcher(titleOrLabel)
        if (!bracketMatcher.find()) return null
        val insideBrackets = bracketMatcher.group(1) ?: return null
        val matcher = labelTimePattern.matcher(insideBrackets)
        if (!matcher.find()) return null
        val rawTime = matcher.group(1) ?: return null
        return parseTimeOffsetMs(rawTime)?.takeIf { it >= 0L }
    }

    /**
     * Extracts a clean loadable source string and an optional start offset (in ms) from a URL or file path.
     * - Strips `#t=...` / `#start=...` fragments from all sources so local files and HTTP URLs open cleanly.
     * - Parses `?t=...` / `&t=...` / `&start=...` query parameters (and strips trailing `?t=...` from local file paths).
     */
    fun extractSourceAndOffset(rawSource: String): Pair<String, Long?> {
        var cleaned = rawSource.trim()
        var offsetMs: Long? = null

        val fragMatcher = fragmentTimePattern.matcher(cleaned)
        if (fragMatcher.find()) {
            val rawTime = fragMatcher.group(1)
            if (rawTime != null) {
                offsetMs = parseTimeOffsetMs(rawTime)?.takeIf { it > 0L }
            }
            cleaned = cleaned.substring(0, fragMatcher.start())
        }

        val queryMatcher = queryTimePattern.matcher(cleaned)
        if (queryMatcher.find()) {
            val rawTime = queryMatcher.group(1)
            if (offsetMs == null && rawTime != null) {
                offsetMs = parseTimeOffsetMs(rawTime)?.takeIf { it > 0L }
            }
            val isHttp = cleaned.startsWith("http://", ignoreCase = true) || cleaned.startsWith("https://", ignoreCase = true)
            if (!isHttp && cleaned[queryMatcher.start()] == '?' && queryMatcher.end() == cleaned.length) {
                cleaned = cleaned.substring(0, queryMatcher.start())
            }
        }

        return cleaned to offsetMs
    }
}
