package com.sajjad.transcripts

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A message that is already written for the user. */
class UserError(message: String) : Exception(message)

class Engine(private val ctx: Context, private val emit: (JSONObject) -> Unit) {

    @Volatile private var cancelled = false
    @Volatile private var currentProcess: String? = null
    @Volatile var initialized = false
        private set
    private var loadedModel: String? = null
    private var whisperCtx = 0L
    private val prefs = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)

    companion object {
        private const val CHUNK_SECONDS = 300.0

        private val HINTS = listOf(
            Regex("private video|video unavailable|has been removed|not available|404", RegexOption.IGNORE_CASE) to
                "The video is private, deleted or not available in your region.",
            Regex("sign in|login|log in|age-restricted|confirm your age|cookies", RegexOption.IGNORE_CASE) to
                "This video needs a signed-in account, which the app can't use. Try another video.",
            Regex("unsupported url", RegexOption.IGNORE_CASE) to
                "This link type isn't supported. Share the video itself, not a profile or search page.",
            Regex("requested format is not available|no video formats", RegexOption.IGNORE_CASE) to
                "The site didn't offer the audio to this app. Tap “Update downloader” below and try again.",
            Regex("http error 403|forbidden|unable to extract|nsig|signature|javascript|js runtime", RegexOption.IGNORE_CASE) to
                "The site blocked the request or changed its page. Tap “Update downloader” below and try again.",
            Regex("http error 429|too many requests", RegexOption.IGNORE_CASE) to
                "Too many requests from your connection. Wait 10–15 minutes and try again.",
            Regex("unable to resolve host|failed to resolve|network is unreachable|timed out|connection reset", RegexOption.IGNORE_CASE) to
                "No internet connection, or it dropped. Check your connection and try again.",
        )

        /** Turns any error into one clear sentence for the user. */
        fun friendly(e: Throwable): String {
            if (e is UserError) return e.message ?: "Something went wrong."
            if (e is InterruptedException) return "Stopped."
            if (e is OutOfMemoryError) return "The phone ran out of memory. Close other apps or use the Tiny model."
            val raw = (e.message ?: e.toString()).replace(Regex("\u001B\\[[0-9;]*m"), "")
            val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val msg = (lines.lastOrNull { it.startsWith("ERROR:") } ?: lines.lastOrNull() ?: raw)
                .removePrefix("ERROR:").trim()
            for ((re, hint) in HINTS) if (re.containsMatchIn(msg)) return "$hint (Details: ${msg.take(200)})"
            return msg.take(400)
        }
    }

    // ------------------------------------------------------------- setup

    @Synchronized
    fun ensureInit() {
        if (initialized) return
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        initialized = true
    }

    fun downloaderVersion(): String =
        YoutubeDL.getInstance().versionName(ctx) ?: YoutubeDL.getInstance().version(ctx) ?: "built-in"

    /** Updates yt-dlp every 2 days (or now when [force]). Returns a short status. */
    fun updateDownloader(force: Boolean): String {
        ensureInit()
        val last = prefs.getLong("lastUpdate", 0L)
        if (!force && System.currentTimeMillis() - last < 2L * 24 * 3600 * 1000) return "skipped"
        val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
        prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
        return if (status == YoutubeDL.UpdateStatus.DONE) "updated" else "current"
    }

    fun cancel() {
        cancelled = true
        currentProcess?.let { id -> runCatching { YoutubeDL.getInstance().destroyProcessById(id) } }
        if (whisperCtx != 0L) runCatching { WhisperLib.nativeAbort(true) }
    }

    // ------------------------------------------------------------- helpers

    private fun checkCancel() {
        if (cancelled) throw InterruptedException("Stopped")
    }

    private fun status(job: String, text: String, pct: Int = -1) {
        emit(JSONObject().put("type", "status").put("job", job).put("text", text).put("pct", pct))
    }

    private fun str(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key, "")

    private fun jobDir(name: String) = File(ctx.cacheDir, "job/$name").apply { deleteRecursively(); mkdirs() }

    private fun ytdlp(req: YoutubeDLRequest, onProgress: ((Float, Long, String) -> Unit)? = null): YoutubeDLResponse {
        checkCancel()
        val id = "p" + System.nanoTime()
        currentProcess = id
        try {
            return YoutubeDL.getInstance().execute(req, id, onProgress)
        } catch (e: YoutubeDL.CanceledException) {
            throw InterruptedException("Stopped")
        } finally {
            currentProcess = null
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun threads(): Int = min(4, max(1, Runtime.getRuntime().availableProcessors() - 1))

    private fun segmentsJson(segs: List<Segment>): JSONArray {
        val arr = JSONArray()
        for (s in segs) arr.put(JSONObject()
            .put("start", Math.round(s.start * 100) / 100.0)
            .put("end", Math.round(s.end * 100) / 100.0)
            .put("text", s.text))
        return arr
    }

    // ------------------------------------------------------------- main job

    fun run(job: String, url: String, opts: JSONObject): JSONObject {
        cancelled = false
        val t0 = System.currentTimeMillis()
        val mode = opts.optString("mode", "auto")
        val lang = opts.optString("lang", "").trim().lowercase().ifEmpty { null }
        val model = Models.get(opts.optString("model", "base"))
        val translate = opts.optBoolean("translate", false)
        val notes = JSONArray()

        if (!initialized) status(job, "Preparing the app (first launch takes a minute)")
        ensureInit()

        status(job, "Reading video details")
        val infoReq = YoutubeDLRequest(url)
            .addOption("--dump-single-json")
            .addOption("--no-playlist")
            .addOption("--playlist-items", "1")
            .addOption("--socket-timeout", "30")
        val raw = ytdlp(infoReq).out
        val brace = raw.indexOf('{')
        if (brace < 0) throw UserError("Couldn't read this link. Check that it opens a single video.")
        var info = JSONObject(raw.substring(brace))
        info.optJSONArray("entries")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i)
                if (e != null) { info = e; break }
            }
        }

        val videoUrl = str(info, "webpage_url").ifEmpty { url }
        val result = JSONObject()
            .put("id", str(info, "id"))
            .put("title", str(info, "title").ifEmpty { url })
            .put("uploader", str(info, "uploader").ifEmpty { str(info, "channel") })
            .put("duration", info.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it })
            .put("platform", str(info, "extractor_key").ifEmpty { str(info, "extractor") })
            .put("url", videoUrl)

        // 1. Captions
        if ((mode == "auto" || mode == "captions") && !translate) {
            status(job, "Looking for captions")
            val cap = try {
                fetchCaptions(info, videoUrl, lang)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                notes.put("Captions exist but could not be read.")
                null
            }
            if (cap != null) {
                return result
                    .put("segments", segmentsJson(cap.first))
                    .put("language", cap.second.key.removeSuffix("-orig"))
                    .put("method", "captions")
                    .put("kind", cap.second.kind)
                    .put("notes", notes)
                    .put("seconds", (System.currentTimeMillis() - t0) / 1000.0)
            }
            if (mode == "captions") {
                throw UserError("This video has no captions in that language. Set Source to “Captions, else speech-to-text”.")
            }
            notes.put("No captions found, so the audio was transcribed.")
        }
        if (translate && mode != "speech") notes.put("Translation uses speech-to-text, so captions were skipped.")

        // 2. Speech-to-text
        val modelFile = Models.ensure(ctx, model, { cancelled }) { pct, _ ->
            status(job, if (pct >= 0) "Downloading speech model (${model.id}) $pct%" else "Downloading speech model (${model.id})", pct)
        }
        checkCancel()

        val dir = jobDir("audio")
        status(job, "Downloading audio")
        var lastPct = -1
        val audioReq = YoutubeDLRequest(videoUrl)
            .addOption("-f", "bestaudio/worst")
            .addOption("-x")
            .addOption("--audio-format", "wav")
            .addOption("--postprocessor-args", "ExtractAudio+ffmpeg_o:-ar 16000 -ac 1")
            .addOption("--no-playlist")
            .addOption("--socket-timeout", "30")
            .addOption("-o", File(dir, "audio.%(ext)s").absolutePath)
        ytdlp(audioReq) { p, _, _ ->
            val pct = p.toInt()
            if (pct >= 0 && pct != lastPct) {
                lastPct = pct
                status(job, "Downloading audio $pct%", pct)
            }
        }
        val wav = dir.listFiles()?.firstOrNull { it.extension.equals("wav", true) }
            ?: throw UserError("The audio download produced no file. Tap “Update downloader” and try again.")

        val (segs, language) = try {
            transcribe(job, wav, model, modelFile, lang, translate)
        } finally {
            dir.deleteRecursively()
        }
        if (segs.isEmpty()) notes.put("No speech was detected in the audio.")
        if (result.optDouble("duration", 0.0) <= 0.0 && segs.isNotEmpty()) result.put("duration", segs.last().end)

        return result
            .put("segments", segmentsJson(segs))
            .put("language", language)
            .put("method", "speech")
            .put("model", model.id)
            .put("device", "phone")
            .put("kind", JSONObject.NULL)
            .put("notes", notes)
            .put("seconds", (System.currentTimeMillis() - t0) / 1000.0)
    }

    private fun fetchCaptions(info: JSONObject, videoUrl: String, lang: String?): Pair<List<Segment>, Track>? {
        val track = Captions.pickTrack(info, lang) ?: return null
        val fmt = Captions.pickFormat(track.formats)
        if (fmt != null) {
            try {
                val segs = Captions.parse(fmt.first, httpGet(fmt.second))
                if (segs.isNotEmpty()) return segs to track
            } catch (e: Exception) {
                // fall through: let yt-dlp fetch it with the right headers
            }
        }
        checkCancel()
        val dir = jobDir("subs")
        val req = YoutubeDLRequest(videoUrl)
            .addOption("--skip-download")
            .addOption(if (track.kind == "uploaded") "--write-subs" else "--write-auto-subs")
            .addOption("--sub-langs", track.key)
            .addOption("--sub-format", "json3/vtt/srt/best")
            .addOption("--no-playlist")
            .addOption("-o", File(dir, "sub.%(ext)s").absolutePath)
        ytdlp(req)
        val file = dir.listFiles()?.firstOrNull {
            it.name.startsWith("sub.") && it.extension.lowercase() in setOf("json3", "vtt", "srt")
        } ?: return null
        val segs = Captions.parse(file.extension.lowercase(), file.readText())
        dir.deleteRecursively()
        return if (segs.isEmpty()) null else segs to track
    }

    private fun transcribe(
        job: String, wav: File, model: ModelInfo, modelFile: File, lang: String?, translate: Boolean
    ): Pair<List<Segment>, String> {
        if (loadedModel != model.id || whisperCtx == 0L) {
            if (whisperCtx != 0L) WhisperLib.nativeFree(whisperCtx)
            whisperCtx = 0L
            loadedModel = null
            status(job, "Loading speech model")
            whisperCtx = WhisperLib.nativeInit(modelFile.absolutePath)
            if (whisperCtx == 0L) {
                modelFile.delete()
                throw UserError("The speech model file was damaged and has been removed. Try again to download it fresh.")
            }
            loadedModel = model.id
        }
        WhisperLib.nativeAbort(false)

        val out = mutableListOf<Segment>()
        var language = lang ?: "auto"
        var detected: String? = null

        WavReader(wav).use { reader ->
            val total = reader.durationSeconds
            val parts = max(1, ceil(total / CHUNK_SECONDS).toInt())
            for (i in 0 until parts) {
                checkCancel()
                val from = i * CHUNK_SECONDS
                val samples = reader.read(from, CHUNK_SECONDS)
                if (samples.size < 8000) continue          // under half a second
                val label = if (parts > 1) "Transcribing part ${i + 1} of $parts" else "Transcribing"
                val poller = Thread {
                    try {
                        while (true) {
                            val p = WhisperLib.nativeProgress().coerceIn(0, 100)
                            status(job, "$label $p%", ((i + p / 100.0) / parts * 100).toInt())
                            Thread.sleep(1000)
                        }
                    } catch (_: InterruptedException) {
                    }
                }
                poller.start()
                val bytes = try {
                    WhisperLib.nativeTranscribe(whisperCtx, samples, language, translate, threads())
                } finally {
                    poller.interrupt()
                }
                if (bytes == null) {
                    if (cancelled) throw InterruptedException("Stopped")
                    throw UserError("Speech-to-text failed on part ${i + 1}. Try the Tiny model.")
                }
                val lines = String(bytes, Charsets.UTF_8).split("\n")
                if (detected == null) {
                    detected = lines.firstOrNull()?.trim()?.ifEmpty { null }
                    if (language == "auto" && detected != null && detected != "auto") language = detected!!
                }
                for (line in lines.drop(1)) {
                    val p = line.split("\t", limit = 3)
                    if (p.size < 3) continue
                    val text = p[2].trim()
                    if (text.isEmpty()) continue
                    val a = p[0].toLongOrNull() ?: continue
                    val b = p[1].toLongOrNull() ?: a
                    out.add(Segment(from + a / 1000.0, from + b / 1000.0, text))
                }
            }
        }
        return out to (if (translate) "en" else (detected ?: language))
    }
}
