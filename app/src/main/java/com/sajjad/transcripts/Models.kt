package com.sajjad.transcripts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ModelInfo(val id: String, val label: String, val file: String)

object Models {
    // Quantized whisper.cpp models from the official repository
    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    val all = listOf(
        ModelInfo("tiny", "Tiny (fastest, rough)", "ggml-tiny-q5_1.bin"),
        ModelInfo("base", "Base (balanced)", "ggml-base-q5_1.bin"),
        ModelInfo("small", "Small (accurate, slow)", "ggml-small-q5_1.bin"),
    )

    fun get(id: String): ModelInfo = all.firstOrNull { it.id == id } ?: all[1]

    private fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }

    fun file(ctx: Context, m: ModelInfo) = File(dir(ctx), m.file)

    fun isReady(ctx: Context, m: ModelInfo) = file(ctx, m).let { it.exists() && it.length() > 1_000_000 }

    fun listJson(ctx: Context): String {
        val arr = JSONArray()
        for (m in all) {
            val f = file(ctx, m)
            arr.put(JSONObject()
                .put("id", m.id)
                .put("label", m.label)
                .put("ready", isReady(ctx, m))
                .put("mb", if (f.exists()) f.length() / 1_048_576 else 0))
        }
        return arr.toString()
    }

    fun delete(ctx: Context, id: String) {
        file(ctx, get(id)).delete()
    }

    /** Downloads the model if missing. [onProgress] gets 0-100, or -1 when the size is unknown. */
    fun ensure(ctx: Context, m: ModelInfo, isCancelled: () -> Boolean, onProgress: (Int, Long) -> Unit): File {
        val target = file(ctx, m)
        if (isReady(ctx, m)) return target
        val part = File(target.path + ".part")
        val conn = URL(BASE_URL + m.file).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Transcripts-Android")
        try {
            if (conn.responseCode !in 200..299) throw IOException("Model download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -2
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw InterruptedException("Stopped")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; onProgress(pct, total) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Model download was incomplete. Try again.")
            if (!part.renameTo(target)) throw IOException("Could not save the model file")
            return target
        } finally {
            conn.disconnect()
            if (part.exists() && !target.exists()) part.delete()
        }
    }
}
