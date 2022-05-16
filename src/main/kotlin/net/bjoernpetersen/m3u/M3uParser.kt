package net.bjoernpetersen.m3u

import dto.response.content.ContentResponse
import dto.response.m3u8.*
import enumClasses.ContentType
import mu.KotlinLogging
import net.bjoernpetersen.m3u.model.*
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*
import kotlin.collections.HashMap
import kotlin.streams.asSequence

/**
 * Can be used to parse `.m3u` files.
 *
 * Accepts several input formats:
 *
 * - a file [path][Path]
 * - an [InputStreamReader]
 * - a string containing the content of an `.m3u` file
 */

object M3uParser {
    private const val COMMENT_START = '#'
    private const val EXTENDED_HEADER = "${COMMENT_START}EXTM3U"

    // Using group index instead of name, because Android doesn't support named group lookup
    private const val SECONDS = 1
    private const val KEY_VALUE_PAIRS = 2
    private const val TITLE = 3
    private const val EXTENDED_INFO =
        """${COMMENT_START}EXTINF:([-]?\d+)(.*),(.+)"""

    private val logger = KotlinLogging.logger { }

    private val infoRegex = Regex(EXTENDED_INFO)

    private val vodExtensions = listOf("mkv", "avi", "mp4", "mov", "wmv", "flv", "webm")

    private val seriesTitleRegex = Regex("[s](eason|ezona)?.{0,2}[0-9]{1,2}[^0-9].*[e](pisode|pizoda)?.{0,2}[0-9]{1,2}")
    private val seasonRegex = Regex("[s](eason|ezona)?.{0,2}[0-9]{1,2}[^0-9]")
    private val episodeRegex = Regex("[e](pisode|pizoda)?.{0,2}[0-9]{1,2}")
    private val numberRegex = Regex("[0-9]{1,2}")
    private val contentResponse = M3u8ContentResponse()

    /**
     * Parses the specified file.
     *
     * Comment lines and lines which can't be parsed are dropped.
     *
     * @param m3uFile a path to an .m3u file
     * @param charset the file's encoding, defaults to UTF-8
     * @return a list of all contained entries in order
     * @throws IOException if file can't be read
     * @throws IllegalArgumentException if file is not a regular file
     */
    @Throws(IOException::class)
    @JvmStatic
    @JvmOverloads
    fun parse(m3uFile: Path, charset: Charset = Charsets.UTF_8): List<M3uEntry> {
        require(Files.isRegularFile(m3uFile)) { "$m3uFile is not a file" }
        return parse(Files.lines(m3uFile, charset).asSequence(), m3uFile.parent)
    }

    /**
     * Parses the [InputStream] from the specified reader.
     *
     * Comment lines and lines which can't be parsed are dropped.
     *
     * @param m3uContentReader a reader reading the content of an `.m3u` file
     * @param baseDir a base dir for resolving relative paths
     * @return a list of all parsed entries in order
     */
    @JvmStatic
    @JvmOverloads
    fun parse(m3uContentReader: InputStreamReader, baseDir: Path? = null): List<M3uEntry> {
        return m3uContentReader.buffered().useLines { parse(it, baseDir) }
    }

    /**
     * Parses the specified content of a `.m3u` file.
     *
     * Comment lines and lines which can't be parsed are dropped.
     *
     * @param m3uContent the content of a `.m3u` file
     * @param baseDir a base dir for resolving relative paths
     * @return a list of all parsed entries in order
     */
    @JvmStatic
    @JvmOverloads
    fun parse(m3uContent: String, baseDir: Path? = null): List<M3uEntry> {
        return parse(m3uContent.lineSequence(), baseDir)
    }

    /**
     * Recursively resolves all playlist files contained as entries in the given list.
     *
     * Note that unresolvable playlist file entries will be dropped.
     *
     * @param entries a list of playlist entries
     * @param charset the encoding to be used to read nested playlist files, defaults to UTF-8
     */
    @JvmStatic
    @JvmOverloads
    fun resolveNestedPlaylists(
        entries: List<M3uEntry>,
        charset: Charset = Charsets.UTF_8
    ): List<M3uEntry> {
        return resolveRecursively(entries, charset)
    }

    @JvmStatic
    @JvmOverloads
    fun parseAndFormat(m3uContent: String, baseDir: Path? = null): M3u8ContentResponse {
        return parseAndFormat(m3uContent.lineSequence(), baseDir)
    }

    @JvmStatic
    @JvmOverloads
    fun parseAndFormat(m3uFile: Path, charset: Charset = Charsets.UTF_8): M3u8ContentResponse {
        return parseAndFormat(Files.lines(m3uFile, charset).asSequence(), m3uFile.parent)
    }

    private fun parseAndFormat(lines: Sequence<String>, baseDir: Path?): M3u8ContentResponse {
        val filtered = lines
            .filterNot { it.isBlank() }
            .map { it.trimEnd() }
            .dropWhile { it == EXTENDED_HEADER }
            .iterator()

        if (!filtered.hasNext()) return contentResponse

        val entries = LinkedList<M3uEntry>()

        var currentLine: String
        var match: MatchResult? = null
        while (filtered.hasNext()) {
            currentLine = filtered.next()

            while (currentLine.startsWith(COMMENT_START)) {
                val newMatch = infoRegex.matchEntire(currentLine)
                if (newMatch != null) {
                    if (match != null) logger.debug { "Ignoring info line: ${match!!.value}" }
                    match = newMatch
                } else {
                    logger.debug { "Ignoring comment line $currentLine" }
                }

                if (filtered.hasNext()) currentLine = filtered.next()
                else return contentResponse
            }

            val entry = if (currentLine.startsWith(COMMENT_START)) continue
            else if (match == null) {
                parseSimple(currentLine, baseDir)
            } else {
                parseExtended(match, currentLine, baseDir)
            }

            match = null

            if (entry != null) entries.add(entry)
            else logger.warn("Ignored line $currentLine")
        }

        return contentResponse
    }

    // TODO: fix detekt issues
    @Suppress("NestedBlockDepth", "ReturnCount")
    private fun parse(lines: Sequence<String>, baseDir: Path?): List<M3uEntry> {
        val filtered = lines
            .filterNot { it.isBlank() }
            .map { it.trimEnd() }
            .dropWhile { it == EXTENDED_HEADER }
            .iterator()

        if (!filtered.hasNext()) return emptyList()

        val entries = LinkedList<M3uEntry>()

        var currentLine: String
        var match: MatchResult? = null
        while (filtered.hasNext()) {
            currentLine = filtered.next()

            while (currentLine.startsWith(COMMENT_START)) {
                val newMatch = infoRegex.matchEntire(currentLine)
                if (newMatch != null) {
                    if (match != null) logger.debug { "Ignoring info line: ${match!!.value}" }
                    match = newMatch
                } else {
                    logger.debug { "Ignoring comment line $currentLine" }
                }

                if (filtered.hasNext()) currentLine = filtered.next()
                else return entries
            }

            val entry = if (currentLine.startsWith(COMMENT_START)) continue
            else if (match == null) {
                parseSimple(currentLine, baseDir)
            } else {
                parseExtended(match, currentLine, baseDir)
            }

            match = null

            if (entry != null) entries.add(entry)
            else logger.warn("Ignored line $currentLine")
        }

        return entries
    }

    private fun parseSimple(location: String, baseDir: Path?): M3uEntry? {
        return try {
            M3uEntry(MediaLocation(location, baseDir))
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "Could not parse as location: $location" }
            null
        }
    }

    private fun parseExtended(infoMatch: MatchResult, location: String, baseDir: Path?): M3uEntry? {
        val mediaLocation = try {
            MediaLocation(location, baseDir)
        } catch (e: IllegalArgumentException) {
            logger.warn(e) { "Could not parse as location: $location" }
            return null
        }
        val duration = infoMatch.groups[SECONDS]?.value?.toLong()
            ?.let { if (it < 0) null else it }
            ?.let { Duration.ofSeconds(it) }
        val title = infoMatch.groups[TITLE]?.value ?: ""
        return if (isVod(mediaLocation.toString())) {
            val seriesInfo = getSeriesInfo(title)
            if (seriesInfo != null) {
                val metadata = parseMetadata(infoMatch.groups[KEY_VALUE_PAIRS]?.value)
                val seriesEntry = M3uEntrySeries(mediaLocation, duration, title, metadata)
                addEpisodeToResponse(mediaLocation, duration, title, metadata, seriesInfo)
                seriesEntry
            } else {
                val metadata = parseMetadata(infoMatch.groups[KEY_VALUE_PAIRS]?.value)
                addMovieToResponse(mediaLocation, duration, title, metadata)
                M3uEntryMovie(mediaLocation, duration, title, metadata)
            }
        } else {
            val metadata = parseMetadata(infoMatch.groups[KEY_VALUE_PAIRS]?.value)
            addStreamToResponse(mediaLocation, duration, title, metadata)
            M3uEntryChannel(mediaLocation, duration, title, metadata)
        }
    }

    private fun parseMetadata(keyValues: String?): M3uMetadata {
        if (keyValues == null) {
            return M3uMetadata.empty()
        }

        val keyValuePattern = Regex("""([\w-_.]+)="(.*?)"( )?""")
        val valueByKey = HashMap<String, String>()
        for (match in keyValuePattern.findAll(keyValues.trim())) {
            val key = match.groups[1]!!.value
            val value = match.groups[2]?.value?.ifBlank { null }
            if (value == null) {
                logger.debug { "Ignoring blank value for key $key" }
                continue
            }
            val overwritten = valueByKey.put(key, value)
            if (overwritten != null) {
                logger.info {
                    "Overwrote value for duplicate metadata key $key: '$overwritten' -> '$value'"
                }
            }
        }

        return M3uMetadata(valueByKey)
    }

    private fun resolveRecursively(
        source: List<M3uEntry>,
        charset: Charset,
        result: MutableList<M3uEntry> = LinkedList()
    ): List<M3uEntry> {
        for (entry in source) {
            val location = entry.location
            if (location is MediaPath && location.isPlaylistPath) {
                resolveNestedPlaylist(location.path, charset, result)
            } else {
                result.add(entry)
            }
        }
        return result
    }

    private fun resolveNestedPlaylist(
        path: Path,
        charset: Charset,
        result: MutableList<M3uEntry>
    ) {
        if (!Files.isRegularFile(path)) {
            return
        }

        val parsed = try {
            parse(path, charset)
        } catch (e: IOException) {
            logger.warn(e) { "Could not parse nested playlist file: $path" }
            return
        }

        resolveRecursively(parsed, charset, result)
    }

    private fun isVod(url: String): Boolean {
        return vodExtensions.contains(url.split(".").last())
    }

    private fun addEpisodeToResponse(
        mediaLocation: MediaLocation,
        duration: Duration?,
        title: String,
        metadata: M3uMetadata,
        seriesInfo: Triple<String, Int, Int>
    ) {
        val episodeNum = seriesInfo.third
        val seasonNum = seriesInfo.second
        val seriesTitle = seriesInfo.first
        val episodeEntryResponse = M3u8EpisodeEntryResponse(
            stream_url = mediaLocation.toString(),
            episode_title = title,
            episode_id = null,
            episode_num = episodeNum,
            container_extension = null,
            season = seasonNum,
            duration_ts = null,
            duration = duration?.seconds.toString()
        )
        var seriesResponseIndex = contentResponse.series.indexOfFirst { it.name == seriesTitle }
        if (seriesResponseIndex < 0) {
            contentResponse.series.add(0,
                M3u8SeriesResponse(
                    num = null,
                    name = seriesTitle,
                    series_id = null,
                    cover = metadata.getOrDefault("tvg-logo", null),
                    plot = null,
                    cast = null,
                    director = null,
                    genre = null,
                    releaseDate = null,
                    last_modified = null,
                    rating = null,
                    rating_5based = null,
                    backdrop_path = listOf(),
                    youtube_trailer = null,
                    episode_run_time = null,
                    category_id = null,
                    episodes = mutableListOf(),
                )
            )
            seriesResponseIndex = 0
        }
        var seasonsCount = contentResponse.series[seriesResponseIndex].episodes.count()
        while (contentResponse.series[seriesResponseIndex].episodes.count() < seasonNum+1) {
            contentResponse.series[seriesResponseIndex].episodes.add(M3uSeasonResponse())
            seasonsCount+=1
        }
        if (seriesResponseIndex<0 || seasonNum<1){
            return
        }
        var episodesCount = contentResponse.series[seriesResponseIndex].episodes[seasonNum]?.episodes?.count()?:0
        while (episodeNum >= episodesCount) {
            contentResponse.series[seriesResponseIndex].episodes[seasonNum]?.episodes?.add(null)
            episodesCount += 1
        }
        contentResponse.series[seriesResponseIndex].episodes[seasonNum]?.episodes?.set(episodeNum, episodeEntryResponse)
    }

    private fun addMovieToResponse(
        mediaLocation: MediaLocation,
        duration: Duration?,
        title: String,
        metadata: M3uMetadata
    ) {
        contentResponse.movies.add(
            M3u8MoviesResponse(
                num = null,
                name = title,
                stream_type = null,
                stream_id = null,
                stream_icon = metadata.getOrDefault("tvg-logo", null),
                rating = null,
                rating_5based = null,
                added = null,
                category_id = null,
                container_extension = null,
                custom_sid = null,
                direct_source = null,
                genre = null,
                description = null,
                stream_url = mediaLocation.toString(),
                duration = duration?.seconds
            )
        )
    }

    private fun addStreamToResponse(
        mediaLocation: MediaLocation,
        duration: Duration?,
        title: String,
        metadata: M3uMetadata
    ) {
        contentResponse.liveStreams.add(
            M3u8LiveStreamsResponse(
                num = null,
                name = title,
                stream_type = null,
                stream_id = null,
                stream_icon = metadata.getOrDefault("tvg-logo", null),
                epg_channel_id = null,
                added = null,
                category_id = null,
                custom_sid = null,
                tv_archive = null,
                direct_source = null,
                tv_archive_duration = null,
                stream_url = mediaLocation.toString(),
                duration = duration?.seconds
            )
        )

    }

    private fun getSeriesInfo(title: String): Triple<String, Int, Int>? {
        val seriesInfo = seriesTitleRegex.find(title.lowercase(Locale.getDefault()))?.value ?: return null
        val seasonInfo = seasonRegex.find(seriesInfo)?.value ?: return null
        val episodeInfo = episodeRegex.find(seriesInfo)?.value ?: return null
        val seasonNumber = numberRegex.find(seasonInfo)?.value?.toInt() ?: return null
        val episodeNumber = numberRegex.find(episodeInfo)?.value?.toInt() ?: return null
        val seriesTitleRange = seriesTitleRegex.find(title.lowercase(Locale.getDefault()))?.range ?: return null
        val seriesTitle = title.replaceRange(seriesTitleRange.first, seriesTitleRange.last + 1, "")
        return Triple(seriesTitle, seasonNumber, episodeNumber)
    }
}
