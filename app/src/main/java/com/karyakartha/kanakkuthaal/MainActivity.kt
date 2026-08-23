package com.karyakartha.kanakkuthaal

import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature

/**
 * Hosts the EXISTING கணக்கு தாள் web app unchanged, inside a WebView. This
 * activity intentionally contains no app UI of its own beyond a tiny offline
 * banner — every screen the user sees is the original HTML/CSS/JS.
 *
 * By default it loads the live site (kanakku-thaal.netlify.app) directly,
 * so behavior is guaranteed identical to opening that link in a normal
 * mobile browser. A local, bundled copy under assets/www/ (served over
 * https://appassets.androidplatform.net via WebViewAssetLoader) is kept only
 * as a fallback for the rare case of a first-ever launch with zero internet,
 * before the site's own service worker has had a chance to cache anything.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var offlineBanner: LinearLayout
    private lateinit var assetLoader: WebViewAssetLoader

    private var backPressedOnce = false
    private var hasLoadedOnce = false
    private var pageLoadFailed = false

    /**
     * Loads the real, live site — not the locally-bundled copy. Serving
     * bundled files through WebViewAssetLoader's pseudo-origin
     * (appassets.androidplatform.net) is close to a real browser, but not
     * identical, and was implicated in a stubborn text-input bug that never
     * happened on the actual Netlify site. Pointing straight at the live URL
     * guarantees the app behaves exactly like "open this link in a browser",
     * which is what was actually asked for. sw.js still gives real offline
     * caching after the first successful visit, same as any PWA.
     */
    private val startUrl = "https://kanakku-thaal.netlify.app/index.html"
    private val localFallbackUrl = "https://appassets.androidplatform.net/assets/www/index.html"

    // Hosts the app itself already talks to (Supabase, CDNs, fonts, WhatsApp).
    // Anything else that ever asks to be a top-level navigation is treated as
    // "external" and handed off to a real browser/app instead of loading
    // inside our WebView.
    private val knownAppHosts = setOf(
        "kanakku-thaal.netlify.app",
        "appassets.androidplatform.net",
        "saqwrtwdoncrqygqwdgg.supabase.co",
        "cdn.jsdelivr.net",
        "cdnjs.cloudflare.com",
        "fonts.googleapis.com",
        "fonts.gstatic.com"
    )

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback
        fileUploadCallback = null
        if (callback == null) return@registerForActivityResult
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val uris = if (data.clipData != null) {
            (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri }.toTypedArray()
        } else {
            data.data?.let { arrayOf(it) } ?: arrayOf()
        }
        callback.onReceiveValue(uris)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        offlineBanner = findViewById(R.id.offlineBanner)
        findViewById<Button>(R.id.retryButton).setOnClickListener { reload() }

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        configureWebView()
        configureServiceWorker()
        configureBackNavigation()
        requestNotificationPermissionIfNeeded()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(startUrl)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun reload() {
        offlineBanner.visibility = View.GONE
        webView.visibility = View.VISIBLE
        if (isOnline() || hasLoadedOnce) {
            webView.reload()
        } else {
            webView.loadUrl(startUrl)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
            }
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        // Without this, taps on <input>/<textarea> fields can fail to bring
        // up the keyboard or register keystrokes at all — a well-known
        // WebView quirk where the view never actually takes input focus.
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus(View.FOCUS_DOWN)
        webView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                if (!v.hasFocus()) v.requestFocus()
            }
            false
        }

        // Keep the app's own responsive CSS in charge; don't let the OS
        // rescale text on top of it (matches "no unwanted zoom" requirement).
        settings.textZoom = 100

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Bridges the app's existing Blob-based export flows (Excel via
        // XLSX.writeFile, JSON backup via URL.createObjectURL) to real
        // Android file saving. See BLOB_DOWNLOAD_INTERCEPT_JS below for why
        // this is necessary — WebView can't save blob: URLs on its own.
        webView.addJavascriptInterface(DownloadBridge(), "AndroidDownloadBridge")

        webView.webViewClient = AppWebViewClient()
        webView.webChromeClient = AppWebChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    /**
     * Lets the existing service worker (sw.js) register and fetch through the
     * same asset loader used for normal navigation, so offline/PWA behavior
     * defined in the original project keeps working unmodified.
     */
    private fun configureServiceWorker() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
        val controller = ServiceWorkerControllerCompat.getInstance()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
            controller.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }
            })
        }
    }

    private inner class AppWebViewClient : WebViewClientCompat() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            return assetLoader.shouldInterceptRequest(request.url) ?: super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme ?: ""

            // Deep links the original app already relies on: WhatsApp, phone, email.
            if (scheme == "tel" || scheme == "mailto" || scheme == "sms" ||
                uri.host == "wa.me" || uri.host == "api.whatsapp.com"
            ) {
                return openExternally(uri)
            }

            if (scheme == "http" || scheme == "https") {
                if (uri.host != null && knownAppHosts.contains(uri.host)) {
                    // Part of the app's own origin/CDNs/backend — keep it inside the WebView.
                    return false
                }
                // Anything else the user taps (e.g. a link to an outside site) opens
                // in the system browser rather than hijacking the app's WebView.
                return openExternally(uri)
            }

            // Unknown custom scheme (e.g. an installed app's own deep link) — hand off to Android.
            return openExternally(uri)
        }

        private fun openExternally(uri: Uri): Boolean {
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, "இதைத் திறக்க பொருத்தமான ஆப் இல்லை", Toast.LENGTH_SHORT).show()
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            pageLoadFailed = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            if (pageLoadFailed) return // this onPageFinished belongs to the browser's own error page, not real content
            hasLoadedOnce = true
            offlineBanner.visibility = View.GONE
            webView.visibility = View.VISIBLE
            view.evaluateJavascript(BLOB_DOWNLOAD_INTERCEPT_JS, null)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceErrorCompat
        ) {
            super.onReceivedError(view, request, error)
            // Only react to a failed top-level page load, not a failed sub-resource
            // (e.g. one CDN script briefly unreachable shouldn't blank the whole app,
            // especially once the service worker has things cached).
            if (!request.isForMainFrame) return
            pageLoadFailed = true
            if (hasLoadedOnce) return
            if (isOnline()) {
                offlineBanner.visibility = View.VISIBLE
                webView.visibility = View.GONE
            } else {
                // No internet at all on the very first launch, before the
                // service worker has ever had a chance to cache anything —
                // fall back to the bundled local copy so the app still opens
                // with something instead of a blank/offline screen.
                view.loadUrl(localFallbackUrl)
            }
        }
    }

    private inner class AppWebChromeClient : WebChromeClient() {

        // The web app requests no camera/mic/location permissions today; if a
        // future page ever calls navigator.mediaDevices etc., deny by default
        // rather than silently granting hardware access.
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = filePathCallback
            return try {
                fileChooserLauncher.launch(fileChooserParams.createIntent())
                true
            } catch (e: ActivityNotFoundException) {
                fileUploadCallback = null
                false
            }
        }

        /**
         * The app calls `window.open(...)` in two situations:
         *  1. window.open('https://wa.me/...', '_blank') — a real link. We
         *     grab the eventual URL and hand it to a real app/browser.
         *  2. window.open('', '_blank', 'width=900,height=700') — the blank
         *     "print preview" popup used for PDF export. We give it a real
         *     WebView, whose window.print() is wired to Android's native
         *     Print framework (lets the user "Save as PDF" or print for real).
         */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            val popup = WebView(this@MainActivity)
            popup.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            popup.settings.javaScriptEnabled = true
            popup.settings.domStorageEnabled = true
            popup.settings.useWideViewPort = true
            popup.settings.loadWithOverviewMode = true
            popup.settings.textZoom = 100

            // The popup must actually be shown (not just held in memory) or
            // Android's print/PDF renderer has nothing properly laid out to
            // capture, and the user never sees the "print preview" the app
            // intends to show while it prepares the PDF.
            val popupDialog = Dialog(this@MainActivity, android.R.style.Theme_DeviceDefault_NoActionBar)
            popupDialog.setContentView(popup)
            popupDialog.setOnDismissListener { popup.destroy() }
            popupDialog.show()

            popup.addJavascriptInterface(PrintBridge(popup, popupDialog), "AndroidPrintBridge")
            popup.webViewClient = object : WebViewClientCompat() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.toString() != "about:blank") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(this@MainActivity, "இதைத் திறக்க பொருத்தமான ஆப் இல்லை", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                    return false
                }

                override fun onPageFinished(v: WebView, url: String?) {
                    super.onPageFinished(v, url)
                    v.evaluateJavascript(OVERRIDE_WINDOW_PRINT_JS, null)
                }
            }
            // Cover the case where the calling JS writes content and calls
            // print() before the async onPageFinished callback above fires.
            popup.evaluateJavascript(OVERRIDE_WINDOW_PRINT_JS, null)

            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }

    /** Lets the "print preview" popup's window.print() open Android's real print/Save-as-PDF dialog. */
    private inner class PrintBridge(private val target: WebView, private val popupDialog: Dialog) {
        private var printRequested = false

        @JavascriptInterface
        fun requestPrint() {
            if (printRequested) return
            printRequested = true
            runOnUiThread {
                try {
                    val printManager = getSystemService(PRINT_SERVICE) as PrintManager
                    val jobName = "KanakkuThaal_${System.currentTimeMillis()}"
                    val adapter = target.createPrintDocumentAdapter(jobName)
                    val job = printManager.print(jobName, adapter, PrintAttributes.Builder().build())
                    waitForPrintJobThenDismiss(job, popupDialog)
                } catch (e: Exception) {
                    Log.e("KanakkuThaal", "Print failed", e)
                    Toast.makeText(this@MainActivity, "அச்சிட முடியவில்லை", Toast.LENGTH_SHORT).show()
                    popupDialog.dismiss()
                }
            }
        }
    }

    /**
     * PDF/print rendering happens asynchronously after printManager.print()
     * returns — dismissing the preview popup right away (as an earlier
     * version of this did) can detach its WebView before Android's print
     * framework has actually finished capturing the content, producing a
     * broken or empty PDF. This keeps the popup alive until the job is
     * genuinely done, with a safety timeout so it can never get stuck.
     */
    private fun waitForPrintJobThenDismiss(job: android.print.PrintJob, dialog: Dialog) {
        val handler = android.os.Handler(mainLooper)
        var elapsedMs = 0
        val maxWaitMs = 60_000
        val checkIntervalMs = 400
        val checkRunnable = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                elapsedMs += checkIntervalMs
                if (job.isCompleted || job.isFailed || job.isCancelled || elapsedMs >= maxWaitMs) {
                    dialog.dismiss()
                } else {
                    handler.postDelayed(this, checkIntervalMs.toLong())
                }
            }
        }
        handler.postDelayed(checkRunnable, checkIntervalMs.toLong())
    }

    /** Receives Blob contents (Excel export, JSON backup) from the intercepted <a download> click and saves them for real. */
    private inner class DownloadBridge {
        @JavascriptInterface
        fun saveBase64(base64Data: String, fileName: String, mimeType: String) {
            runOnUiThread {
                try {
                    val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    val resolvedMime = resolveMimeType(fileName, mimeType)
                    val uri = saveBytesToDownloads(bytes, fileName, resolvedMime)
                    Toast.makeText(this@MainActivity, "பதிவிறக்கம் முடிந்தது: $fileName", Toast.LENGTH_SHORT).show()
                    notifyDownloadComplete(fileName, uri, resolvedMime)
                } catch (e: Exception) {
                    Log.e("KanakkuThaal", "saveBase64 failed", e)
                    Toast.makeText(this@MainActivity, "சேமிப்பு தோல்வியடைந்தது", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Downloads a file the web app generated (report/export/PDF) via the system DownloadManager. */
    private fun handleDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String?) {
        try {
            if (url.startsWith("data:")) {
                saveDataUri(url, contentDisposition, mimeType)
                return
            }
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setDescription(fileName)
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "பதிவிறக்கம் தொடங்கியது: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("KanakkuThaal", "Download failed", e)
            Toast.makeText(this, "பதிவிறக்கம் தோல்வியடைந்தது", Toast.LENGTH_SHORT).show()
        }
    }

    /** Some in-app export flows (e.g. client-side generated CSV/Excel) trigger a data: URI "download" instead of a real network request. */
    private fun saveDataUri(dataUri: String, contentDisposition: String, mimeType: String?) {
        try {
            val commaIndex = dataUri.indexOf(',')
            val meta = dataUri.substring(5, commaIndex)
            val base64 = meta.contains("base64")
            val payload = dataUri.substring(commaIndex + 1)
            val bytes = if (base64) {
                android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray(Charsets.UTF_8)
            }
            val rawMimeHint = mimeType ?: meta.substringBefore(';')
            val fileName = URLUtil.guessFileName(dataUri, contentDisposition, rawMimeHint)
            val guessedMime = resolveMimeType(fileName, rawMimeHint)
            val uri = saveBytesToDownloads(bytes, fileName, guessedMime)
            Toast.makeText(this, "பதிவிறக்கம் முடிந்தது: $fileName", Toast.LENGTH_SHORT).show()
            notifyDownloadComplete(fileName, uri, guessedMime)
        } catch (e: Exception) {
            Log.e("KanakkuThaal", "data: URI save failed", e)
            Toast.makeText(this, "சேமிப்பு தோல்வியடைந்தது", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The web app's own export code sometimes hands us a generic
     * "application/octet-stream" Blob type (this is normal — SheetJS's
     * XLSX.writeFile does this for browser-compatibility reasons). A generic
     * MIME type means Android can't tell apps like Google Sheets that the
     * file is actually an .xlsx, so they don't show up as an option to open
     * it. Preferring a well-known MIME type based on the file's own
     * extension fixes that without needing anything from the caller.
     */
    private fun resolveMimeType(fileName: String, reportedMime: String?): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val knownMimeByExtension = mapOf(
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xls" to "application/vnd.ms-excel",
            "csv" to "text/csv",
            "json" to "application/json",
            "pdf" to "application/pdf"
        )
        knownMimeByExtension[ext]?.let { return it }
        if (!reportedMime.isNullOrBlank() && reportedMime != "application/octet-stream") return reportedMime
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /**
     * Saves bytes to the public Downloads folder so they show up in the
     * device's Files app / Downloads app like any normal browser download —
     * via MediaStore on Android 10+, and a direct file write (with the
     * legacy storage permission) on older versions. Returns a Uri suitable
     * for viewing/sharing the saved file.
     */
    private fun saveBytesToDownloads(bytes: ByteArray, fileName: String, mimeType: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, fileName)
            file.outputStream().use { it.write(bytes) }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }
    }

    /**
     * Posts a "download complete" notification, same as a real browser would,
     * so saving Excel/backup files actually *feels* like a download instead
     * of silently succeeding with only a passing Toast.
     */
    private fun notifyDownloadComplete(fileName: String, uri: Uri, mimeType: String) {
        val channelId = "downloads"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "பதிவிறக்கங்கள்", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // user hasn't granted notification permission; the Toast is the fallback
        }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, fileName.hashCode(), viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("பதிவிறக்கம் முடிந்தது")
            .setContentText(fileName)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(fileName.hashCode(), notification)
    }

    /**
     * Smart back-button behavior, in priority order:
     *  1. Close an open modal/sheet/menu inside the web app (without touching its source).
     *  2. Otherwise, go back through WebView history.
     *  3. Otherwise, require a second back-press to exit.
     */
    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(CLOSE_OPEN_OVERLAY_JS) { resultJson ->
                    val closedSomething = resultJson == "true"
                    when {
                        closedSomething -> { /* handled inside the page */ }
                        webView.canGoBack() -> webView.goBack()
                        else -> confirmExit()
                    }
                }
            }
        })
    }

    private fun confirmExit() {
        if (backPressedOnce) {
            finish()
            return
        }
        backPressedOnce = true
        Toast.makeText(this, R.string.exit_confirm_message, Toast.LENGTH_SHORT).show()
        webView.postDelayed({ backPressedOnce = false }, 2000)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.requestFocus(View.FOCUS_DOWN)
        CookieManager.getInstance().flush()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) webView.requestFocus(View.FOCUS_DOWN)
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        // Matches the existing overlay/menu convention already used across
        // index.html (elements ending in "Overlay" or the fabMenu, toggled
        // via a CSS "open" class). No app code is modified — this only reads
        // and, if something is open, removes that class the same way the
        // page's own close*() functions already do.
        private const val CLOSE_OPEN_OVERLAY_JS = """
            (function() {
                var openEls = document.querySelectorAll(
                    '.overlay.open, [id${'$'}="Overlay"].open, #fabMenu.open, .sheet.open, .modal.open'
                );
                if (openEls.length > 0) {
                    openEls.forEach(function(el) { el.classList.remove('open'); });
                    return true;
                }
                return false;
            })();
        """

        // The app's Excel export (XLSX.writeFile) and JSON backup export both
        // build a Blob, turn it into a blob: URL, and click a hidden
        // <a download> to trigger a save — a pattern real browsers handle,
        // but a bare WebView can't (DownloadManager can't fetch blob: URLs,
        // and WebView doesn't surface these clicks as real downloads at all).
        // This patches HTMLAnchorElement.click so that specific pattern is
        // caught, read back into bytes, and handed to the native side —
        // without touching index.html/admin.html themselves. Every other
        // anchor click behaves exactly as before.
        private const val BLOB_DOWNLOAD_INTERCEPT_JS = """
            (function() {
                if (window.__kanakkuBlobHooked) return;
                window.__kanakkuBlobHooked = true;
                var originalClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    try {
                        var href = this.href || '';
                        var hasDownload = this.hasAttribute('download');
                        if (hasDownload && href.indexOf('blob:') === 0 && window.AndroidDownloadBridge) {
                            var fileName = this.getAttribute('download') || 'download';
                            var anchor = this;
                            fetch(href).then(function(res) { return res.blob(); }).then(function(blob) {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    var result = reader.result || '';
                                    var base64 = result.indexOf(',') >= 0 ? result.split(',')[1] : '';
                                    window.AndroidDownloadBridge.saveBase64(base64, fileName, blob.type || '');
                                };
                                reader.readAsDataURL(blob);
                            }).catch(function() {
                                originalClick.apply(anchor, []);
                            });
                            return;
                        }
                    } catch (e) { /* fall through to normal click */ }
                    return originalClick.apply(this, arguments);
                };
            })();
        """

        // Replaces window.print inside the blank print-preview popup the app
        // opens for PDF export, routing it to Android's native print system
        // instead of a no-op (WebView has no default UI for window.print()).
        // The app writes a fresh document into this popup via document.write()
        // *after* window.open() returns, which can wipe out a one-shot
        // override depending on exact WebView/Chromium timing — so this
        // reinstalls the hook every 150ms for a few seconds, comfortably
        // covering both the app's later document.write() and its own
        // `setTimeout(() => win.print(), 800)` auto-print call.
        private const val OVERRIDE_WINDOW_PRINT_JS = """
            (function() {
                function installPrintHook() {
                    window.print = function() {
                        if (window.AndroidPrintBridge) { window.AndroidPrintBridge.requestPrint(); }
                    };
                }
                installPrintHook();
                if (window.__kanakkuPrintHookTimer) return;
                var tries = 0;
                window.__kanakkuPrintHookTimer = setInterval(function() {
                    installPrintHook();
                    tries++;
                    if (tries > 40) { clearInterval(window.__kanakkuPrintHookTimer); }
                }, 150);
            })();
        """
    }
}
