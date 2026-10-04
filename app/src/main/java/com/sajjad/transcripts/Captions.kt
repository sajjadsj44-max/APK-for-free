package com.sajjad.transcripts

import org.json.JSONArray
import org.json.JSONObject

data class Segment(val start: Double, val end: Double, val text: String)

data class Track(val key: String, val formats: JSONArray, val kind: String)

object Captions {

    private val TAG = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")
    private val NUM_ENTITY = Regex("&#(x?[0-9a-fA-F]+);")
    private val TS = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})")

    fun cleanText(s: String?): String {
        var t = (s ?: "").replace(TAG, "")
        t = NUM_ENTITY.replace(t) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x") || v.startsWith("X")) v.substring(1).toIntOrNull(16) else v.toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&amp;", "&").replace("\u200b", "")
        return t.replace(SPACES, " ").trim()
    }

    private fun seconds(m: MatchResult): Double {
        val g = m.groupValues
        val h = g[1].toIntOrNull() ?: 0
        return h * 3600 + g[2].toInt() * 60 + g[3].toInt() + g[4].padEnd(3, '0').toInt() / 1000.0
    }

    /** WebVTT or SRT. Drops YouTube's rolling duplicate lines. */
    fun parseVttSrt(text: String): List<Segment> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        class Cue(val start: Double, val end: Double, val body: MutableList<String> = mutableListOf())
        val cues = mutableListOf<Cue>()
        var cur: Cue? = null
        for (i in lines.indices) {
            val line = lines[i]
            if (line.contains("-->")) {
                val left = line.substringBefore("-->")
                val right = line.substringAfter("-->")
                val ma = TS.find(left)
                val mb = TS.find(right)
                if (ma != null && mb != null) {
                    cur = Cue(seconds(ma), seconds(mb)).also { cues.add(it) }
                    continue
                }
            }
            val next = if (i + 1 < lines.size) lines[i + 1] else ""
            if (next.contains("-->") && line.isNotBlank()) continue   // cue number / id
            cur?.body?.add(line)
        }
        val out = mutableListOf<Segment>()
        var prev: List<String> = emptyList()
        for (c in cues) {
            val body = c.body.map { cleanText(it) }.filter { it.isNotEmpty() }
            val fresh = body.filter { it !in prev }
            prev = body
            if (fresh.isNotEmpty()) out.add(Segment(c.start, c.end, fresh.joinToString(" ")))
        }
        return out
    }

    fun parseJson3(text: String): List<Segment> {
        val events = JSONObject(text).optJSONArray("events") ?: return emptyList()
        val out = mutableListOf<Segment>()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val segs = ev.optJSONArray("segs") ?: continue
            val sb = StringBuilder()
            for (j in 0 until segs.length()) sb.append(segs.optJSONObject(j)?.optString("utf8", "") ?: "")
            val t = cleanText(sb.toString().replace("\n", " "))
            if (t.isEmpty()) continue
            val start = ev.optLong("tStartMs", 0) / 1000.0
            out.add(Segment(start, start + ev.optLong("dDurationMs", 0) / 1000.0, t))
        }
        return out
    }

    fun parse(ext: String, raw: String): List<Segment> =
        if (ext == "json3") parseJson3(raw) else parseVttSrt(raw)

    private fun langMatch(key: String, lang: String): Boolean {
        val k = key.lowercase()
        return k == lang || k.startsWith("$lang-") || k.startsWith(lang)
    }

    private fun tracks(obj: JSONObject?, skipLiveChat: Boolean): LinkedHashMap<String, JSONArray> {
        val map = LinkedHashMap<String, JSONArray>()
        if (obj == null) return map
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (skipLiveChat && k == "live_chat") continue
            val arr = obj.optJSONArray(k)
            if (arr != null && arr.length() > 0) map[k] = arr
        }
        return map
    }

    /** Creator subtitles first, then auto captions in the original language.
     *  Never picks machine-translated tracks unless that language was asked for. */
    fun pickTrack(info: JSONObject, lang: String?): Track? {
        val manual = tracks(info.optJSONObject("subtitles"), true)
        val auto = tracks(info.optJSONObject("automatic_captions"), false)
        val vidLang = info.optString("language", "").lowercase().ifEmpty { null }

        if (!lang.isNullOrEmpty()) {
            manual.entries.firstOrNull { langMatch(it.key, lang) }?.let { return Track(it.key, it.value, "uploaded") }
            for (k in listOf("$lang-orig", lang)) auto[k]?.let { return Track(k, it, "auto") }
            return null
        }
        if (manual.isNotEmpty()) {
            for (want in listOfNotNull(vidLang, "en")) {
                manual.entries.firstOrNull { langMatch(it.key, want) }?.let { return Track(it.key, it.value, "uploaded") }
            }
            val first = manual.entries.first()
            return Track(first.key, first.value, "uploaded")
        }
        auto.keys.firstOrNull { it.endsWith("-orig") }?.let { return Track(it, auto.getValue(it), "auto") }
        for (want in listOfNotNull(vidLang, "en")) auto[want]?.let { return Track(want, it, "auto") }
        return null
    }

    /** Best format we can parse: json3, then vtt, then srt. Returns (ext, url). */
    fun pickFormat(formats: JSONArray): Pair<String, String>? {
        val byExt = HashMap<String, String>()
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val url = f.optString("url", "")
            val ext = f.optString("ext", "")
            if (url.isNotEmpty() && ext.isNotEmpty() && ext !in byExt) byExt[ext] = url
        }
        for (ext in listOf("json3", "vtt", "srt")) byExt[ext]?.let { return ext to it }
        return null
    }
}
