package com.karyakartha.kanakkuthaal

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.webkit.CookieManager
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
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature

/**
 * Hosts the EXISTING கணக்கு தாள் web app (index.html / admin.html) unchanged,
 * inside a WebView. This activity intentionally contains no app UI of its own
 * beyond a tiny offline banner — every screen the user sees is the original
 * HTML/CSS/JS from the uploaded project, served as-is.
 *
 * Local assets are served over https://appassets.androidplatform.net using
 * WebViewAssetLoader (not file://) so that:
 *  - relative fetch()/XHR calls behave exactly as they do on a real https origin
 *  - the existing Supabase client and CDN <script> tags keep working unmodified
 *  - the existing service worker (sw.js) can register and run
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var offlineBanner: LinearLayout
    private lateinit var assetLoader: WebViewAssetLoader

    private var backPressedOnce = false
    private var hasLoadedOnce = false

    /** Entry point inside the bundled web app. Change if the project's home file differs. */
    private val startUrl = "https://appassets.androidplatform.net/assets/www/index.html"
    private val appOrigin = "https://appassets.androidplatform.net"

    // Hosts the app itself already talks to (Supabase, CDNs, fonts, WhatsApp).
    // Anything else that ever asks to be a top-level navigation is treated as
    // "external" and handed off to a real browser/app instead of loading
    // inside our WebView.
    private val knownAppHosts = setOf(
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
        settings.setSupportMultipleWindows(false)

        // Keep the app's own responsive CSS in charge; don't let the OS
        // rescale text on top of it (matches "no unwanted zoom" requirement).
        settings.textZoom = 100

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

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

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            hasLoadedOnce = true
            offlineBanner.visibility = View.GONE
            webView.visibility = View.VISIBLE
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
            if (request.isForMainFrame && !hasLoadedOnce) {
                offlineBanner.visibility = View.VISIBLE
                webView.visibility = View.GONE
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
            val guessedMime = mimeType ?: meta.substringBefore(';')
            val fileName = URLUtil.guessFileName(dataUri, contentDisposition, guessedMime)
            val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val outFile = java.io.File(downloadsDir, fileName)
            outFile.outputStream().use { it.write(bytes) }
            Toast.makeText(this, "சேமிக்கப்பட்டது: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("KanakkuThaal", "data: URI save failed", e)
            Toast.makeText(this, "சேமிப்பு தோல்வியடைந்தது", Toast.LENGTH_SHORT).show()
        }
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
        CookieManager.getInstance().flush()
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
    }
}
