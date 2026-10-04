package com.sajjad.transcripts

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var engine: Engine
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val pendingJs = mutableListOf<String>()
    private var pageReady = false
    private var sharedLink: String? = null
    private var pendingSave: Pair<String, Boolean>? = null

    companion object {
        private const val REQ_SAVE = 41
        private val LINK = Regex("https?://\\S+")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        engine = Engine(applicationContext) { ev -> send(ev) }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") {
                    openExternal(uri)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingJs.forEach { web.evaluateJavascript(it, null) }
                pendingJs.clear()
            }
        }

        sharedLink = linkFrom(intent)
        web.loadUrl("file:///android_asset/index.html")
        worker.execute { startup() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { send(JSONObject().put("type", "shared").put("url", it)) }
    }

    @Deprecated("Keeps transcription running when Back is pressed")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        engine.cancel()
        worker.shutdown()
        web.destroy()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE) return
        val save = pendingSave
        pendingSave = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || save == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                if (save.second) out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                out.write(save.first.toByteArray(Charsets.UTF_8))
            }
            toast("Saved")
        } catch (e: Exception) {
            toast("Couldn't save the file: ${e.message}")
        }
    }

    // ------------------------------------------------------------- helpers

    private fun startup() {
        try {
            engine.ensureInit()
            send(JSONObject().put("type", "ready").put("version", engine.downloaderVersion()))
            val r = engine.updateDownloader(false)
            if (r == "updated") {
                send(JSONObject().put("type", "update").put("text", "Downloader updated to ${engine.downloaderVersion()}"))
            }
        } catch (e: Throwable) {
            if (!engine.initialized) {
                send(JSONObject().put("type", "fatal").put("text", "The app couldn't start its downloader: ${Engine.friendly(e)}"))
            }
            // a failed background update is not fatal; the built-in version still works
        }
    }

    private fun send(ev: JSONObject) {
        val js = "window.onNative && window.onNative(" + ev.toString() + ")"
        runOnUiThread {
            if (pageReady) web.evaluateJavascript(js, null) else pendingJs.add(js)
        }
    }

    private fun linkFrom(i: Intent?): String? {
        if (i?.action != Intent.ACTION_SEND) return null
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return LINK.find(text)?.value
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast("No app can open this link")
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------- JavaScript bridge

    inner class Bridge {
        @JavascriptInterface
        fun start(job: String, url: String, opts: String) {
            worker.execute {
                try {
                    val result = engine.run(job, url, JSONObject(opts))
                    send(JSONObject().put("type", "done").put("job", job).put("result", result))
                } catch (e: Throwable) {
                    send(JSONObject().put("type", "error").put("job", job).put("text", Engine.friendly(e)))
                }
            }
        }

        @JavascriptInterface
        fun cancel() = engine.cancel()

        @JavascriptInterface
        fun takeShared(): String {
            val s = sharedLink ?: ""
            sharedLink = null
            return s
        }

        @JavascriptInterface
        fun models(): String = Models.listJson(applicationContext)

        @JavascriptInterface
        fun deleteModel(id: String) {
            Models.delete(applicationContext, id)
        }

        @JavascriptInterface
        fun info(): String = JSONObject()
            .put("version", engine.downloaderVersion())
            .put("ready", engine.initialized)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
            .put("cores", Runtime.getRuntime().availableProcessors())
            .put("app", packageManager.getPackageInfo(packageName, 0).versionName ?: "")
            .toString()

        @JavascriptInterface
        fun updateDownloader() {
            worker.execute {
                val text = try {
                    when (engine.updateDownloader(true)) {
                        "updated" -> "Downloader updated to ${engine.downloaderVersion()}"
                        else -> "Downloader is already up to date (${engine.downloaderVersion()})"
                    }
                } catch (e: Throwable) {
                    "Update failed: ${Engine.friendly(e)}"
                }
                send(JSONObject().put("type", "update").put("text", text))
            }
        }

        @JavascriptInterface
        fun keepAwake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Transcript", text))
                    if (Build.VERSION.SDK_INT < 33) toast("Copied")
                } catch (e: Exception) {
                    toast("Too long to copy. Use Save instead.")
                }
            }
        }

        @JavascriptInterface
        fun share(title: String, text: String) {
            runOnUiThread {
                val body = if (text.length > 90_000) text.take(90_000) + "\n\n[Shortened. Use Save .txt for the full transcript.]" else text
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, title)
                    .putExtra(Intent.EXTRA_TEXT, body)
                try {
                    startActivity(Intent.createChooser(send, "Share transcript"))
                } catch (e: Exception) {
                    toast("Couldn't open sharing: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun save(name: String, text: String, mime: String) {
            runOnUiThread {
                pendingSave = text to (mime == "text/plain")
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_TITLE, name)
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, REQ_SAVE)
                } catch (e: ActivityNotFoundException) {
                    pendingSave = null
                    toast("No file picker found on this phone")
                }
            }
        }
    }
}
