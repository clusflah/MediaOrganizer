package com.example.mediaorganizer.parser

/**
 * 从影视文件名中解析出片名、年份、季集等信息。
 * 例：老友记.1994.S02E03.2160p.Dolby.BluRay.DC.PROPER.x265.AC3.EVO.mp4
 *   -> title=老友记, year=1994, season=2, episode=3
 */
data class ParsedMedia(
    val rawName: String,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeEnd: Int? = null,      // 连集，如 S01E01E02
    val extension: String? = null,
    val resolution: String? = null,
    val source: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val releaseGroup: String? = null,
) {
    val isEpisode: Boolean get() = episode != null

    /** 中文名、英文名分开，用于 TMDB 依次搜索 */
    val cjkTitle: String? get() =
        Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}0-9：:·\s]+""")
            .findAll(title).map { it.value.trim() }
            .firstOrNull { it.any { c -> Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN } }

    val latinTitle: String? get() =
        Regex("""[A-Za-z][A-Za-z0-9'&:!\s-]*""").findAll(title)
            .map { it.value.trim() }.filter { it.length > 1 }.maxByOrNull { it.length }
}

object MediaNameParser {

    val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "ts", "m2ts", "rmvb", "rm", "mov", "wmv", "iso", "flv", "webm", "mpg", "mpeg"
    )

    private val SEASON_EPISODE = listOf(
        Regex("""(?i)(?<![A-Za-z0-9])S(\d{1,2})[ ._-]?E[Pp]?(\d{1,4})(?:[ ._-]?E[Pp]?(\d{1,4}))?(?![0-9])"""),
        Regex("""(?i)(?<![A-Za-z0-9])season[ ._-]?(\d{1,2})[ ._-]?episode[ ._-]?(\d{1,4})(?![0-9])"""),
        Regex("""(?i)(?<![A-Za-z0-9])se?(\d{1,2})\.ep?(\d{1,4})(?![0-9])"""),
        Regex("""(?<![0-9A-Za-z])(\d{1,2})x(\d{2,3})(?![0-9])"""),
        Regex("""第\s*(\d{1,2})\s*季.{0,10}?第\s*(\d{1,4})\s*[集话話]"""),
    )

    // 只有集数没有季（默认第 1 季）
    private val EPISODE_ONLY = listOf(
        Regex("""(?i)(?<![A-Za-z0-9])EP?(\d{1,4})(?![0-9])"""),
        Regex("""第\s*(\d{1,4})\s*[集话話]"""),
        Regex("""\s-\s(\d{2,4})(?![0-9])(?:v\d)?"""),   // 动漫：[字幕组] 名称 - 03 [1080p]
    )

    private val YEAR = Regex("""(?<![0-9])(19[0-9]{2}|20[0-9]{2})(?![0-9])""")
    private val RESOLUTION = Regex("""(?i)(?<![A-Za-z0-9])(480p|576p|720p|1080[pi]|2160p|4k|8k)(?![A-Za-z0-9])""")
    private val SOURCE = Regex("""(?i)(?<![A-Za-z0-9])(blu-?ray|bdrip|brrip|remux|web-?dl|webrip|web|hdtv|dvdrip|hdrip|uhd)(?![A-Za-z0-9])""")
    private val VIDEO_CODEC = Regex("""(?i)(?<![A-Za-z0-9])(x26[45]|h\.?26[45]|hevc|avc|av1)(?![A-Za-z0-9])""")
    private val AUDIO_CODEC = Regex("""(?i)(?<![A-Za-z0-9])(aac|e?ac3|dts(?:-hd)?(?:[ .]?ma)?|truehd|atmos|flac|ddp?[ .]?[257]\.[01])(?![A-Za-z0-9])""")
    private val OTHER_TAGS = Regex(
        """(?i)(?<![A-Za-z0-9])(proper|repack|dc|extended|uncut|unrated|imax|remastered|dolby|vision|dovi|dv|hdr10\+?|hdr|sdr|10bit|8bit|complete|multi|chs|cht|gb|big5)(?![A-Za-z0-9])"""
    )
    private val LEADING_BRACKET = Regex("""^\s*[\[【][^\]】]*[\]】]\s*""")
    private val TRAILING_GROUP = Regex("""-([A-Za-z0-9]+)$""")

    fun parse(fileName: String): ParsedMedia {
        // 1. 拆扩展名
        val dot = fileName.lastIndexOf('.')
        val ext = if (dot > 0) fileName.substring(dot + 1).lowercase() else null
        var name = if (ext != null && ext in VIDEO_EXTENSIONS) fileName.substring(0, dot) else fileName

        // 2. 开头的 [字幕组] / 结尾的 -发布组
        var group: String? = null
        LEADING_BRACKET.find(name)?.let {
            group = it.value.trim().trim('[', ']', '【', '】')
            name = name.substring(it.range.last + 1)
        }
        TRAILING_GROUP.find(name)?.let { m ->
            // 避免把片名里的连字符当发布组：前面必须已有质量标签
            if (RESOLUTION.containsMatchIn(name) || SOURCE.containsMatchIn(name) || VIDEO_CODEC.containsMatchIn(name)) {
                group = m.groupValues[1]
                name = name.substring(0, m.range.first)
            }
        }

        // 3. 季集
        var season: Int? = null; var episode: Int? = null; var episodeEnd: Int? = null
        var seStart: Int? = null
        for (re in SEASON_EPISODE) {
            val m = re.find(name) ?: continue
            if (m.range.first == 0) continue
            season = m.groupValues[1].toInt()
            episode = m.groupValues[2].toInt()
            episodeEnd = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toInt()
            seStart = m.range.first
            break
        }
        if (episode == null) {
            for (re in EPISODE_ONLY) {
                val m = re.find(name) ?: continue
                if (m.range.first == 0) continue
                season = 1
                episode = m.groupValues[1].toInt()
                seStart = m.range.first
                break
            }
        }

        // 4. 质量标签
        val res = RESOLUTION.find(name)
        val src = SOURCE.find(name)
        val vc = VIDEO_CODEC.find(name)
        val ac = AUDIO_CODEC.find(name)
        val other = OTHER_TAGS.findAll(name).firstOrNull { it.range.first > 0 && cleanTitle(name.substring(0, it.range.first)).isNotEmpty() }

        // 5. 年份：取第一个前面已有片名的年份（应对《2012》这类片名）；
        //    还没到的年份不算（「银翼杀手2049 (2017)」里的 2049 是片名）
        val maxYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1
        val yearMatch = YEAR.findAll(name).firstOrNull {
            it.value.toInt() <= maxYear && cleanTitle(name.substring(0, it.range.first)).isNotEmpty()
        }

        // 6. 片名 = 第一个“切分点”之前的部分
        val cut = listOfNotNull(
            seStart, yearMatch?.range?.first, res?.range?.first, src?.range?.first,
            vc?.range?.first, ac?.range?.first, other?.range?.first,
        ).filter { it > 0 }.minOrNull() ?: name.length

        val title = cleanTitle(name.substring(0, cut)).ifEmpty { cleanTitle(name) }

        return ParsedMedia(
            rawName = fileName,
            title = title,
            year = yearMatch?.value?.toInt(),
            season = season,
            episode = episode,
            episodeEnd = episodeEnd,
            extension = ext,
            resolution = res?.value,
            source = src?.value,
            videoCodec = vc?.value,
            audioCodec = ac?.value,
            releaseGroup = group,
        )
    }

    private fun cleanTitle(s: String): String =
        s.replace(Regex("""[._]+"""), " ")
            .replace(Regex("""[\[\]【】()（）]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–')
}
