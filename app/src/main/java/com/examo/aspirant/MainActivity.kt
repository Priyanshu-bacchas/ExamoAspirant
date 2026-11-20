package com.examo.aspirant

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.view.View
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var splashSheet: ExamoSplashView? = null
    private var authSheetOpen = false
    private var popupWebView: WebView? = null
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility: Int = 0
    private var pendingDownload: DownloadInfo? = null

    private data class DownloadInfo(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?
    )

    companion object {
        private const val FILE_CHOOSER_REQUEST = 4101
        private const val STORAGE_PERMISSION_REQUEST = 4102
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(248, 249, 252)
        window.navigationBarColor = Color.rgb(17, 24, 39)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        // Keep the welcome screen as the permanent background layer.
        val splash = ExamoSplashView(this)
        splashSheet = splash
        root.addView(splash, FrameLayout.LayoutParams(-1, -1))

        // The login/signup WebView is a separate sheet above the welcome screen.
        // It stays laid out and loaded, but starts just below the visible screen.
        webView = createWebView()
        webView.visibility = View.INVISIBLE
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        root.post {
            webView.translationY = root.height.toFloat()
        }

        if (savedInstanceState == null) {
            webView.loadUrl(getString(R.string.examo_website_url))
        } else {
            val restored = webView.restoreState(savedInstanceState)
            if (restored == null) webView.loadUrl(getString(R.string.examo_website_url))
        }

        var swipeStartY = 0f
        var draggingAuthSheet = false
        splash.setSwipeTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    webView.animate().cancel()
                    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    webView.visibility = View.VISIBLE
                    webView.translationY = root.height.toFloat()
                    swipeStartY = event.rawY
                    draggingAuthSheet = true
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (draggingAuthSheet) {
                        val delta = event.rawY - swipeStartY
                        // Finger moving upward pulls the login/signup sheet over
                        // the fixed welcome screen.
                        webView.translationY = (root.height.toFloat() + delta)
                            .coerceIn(0f, root.height.toFloat())
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (draggingAuthSheet) {
                        val movedUp = swipeStartY - event.rawY
                        draggingAuthSheet = false
                        if (movedUp > dp(55)) {
                            authSheetOpen = true
                            webView.animate().cancel()
                            webView.animate()
                                .translationY(0f)
                                .setDuration(300)
                                .withLayer()
                                .withEndAction { webView.setLayerType(View.LAYER_TYPE_NONE, null) }
                                .start()
                        } else {
                            webView.animate().cancel()
                            webView.animate()
                                .translationY(root.height.toFloat())
                                .setDuration(220)
                                .withEndAction {
                                    webView.visibility = View.INVISIBLE
                                    webView.translationY = root.height.toFloat()
                                    webView.setLayerType(View.LAYER_TYPE_NONE, null)
                                    authSheetOpen = false
                                }
                                .start()
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createWebView(): WebView {
        return WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.mediaPlaybackRequiresUserGesture = false

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(object {
                @JavascriptInterface
                fun showWelcome() {
                    runOnUiThread { showWelcomeSplash() }
                }
            }, "ExamoSwipeBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return false
                    if ((scheme == "http" || scheme == "https") && isPdfUrl(uri)) {
                        openPdf(uri, view?.settings?.userAgentString)
                        return true
                    }
                    if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript") return false
                    return openExternal(uri)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    installMobileNavigationFix(view)
                    hideGoogleSignInInApp(view)
                    installLoginSwipeBorder(view)
                }
            }

            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(url, userAgent, contentDisposition, mimeType)
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = callback
                    return try {
                        val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                        startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                        true
                    } catch (_: Exception) {
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = null
                        false
                    }
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null || customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    originalSystemUiVisibility = window.decorView.systemUiVisibility
                    webView.visibility = View.GONE
                    root.addView(view, FrameLayout.LayoutParams(-1, -1))
                    window.decorView.systemUiVisibility =
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                }

                override fun onHideCustomView() {
                    hideCustomView()
                }
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                    val popup = createPopupWebView()
                    popupWebView = popup
                    root.addView(popup, FrameLayout.LayoutParams(-1, -1))
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    if (window != null && window == popupWebView) {
                        root.removeView(window)
                        window.destroy()
                        popupWebView = null
                    }
                }

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    Toast.makeText(this@MainActivity, message ?: "", Toast.LENGTH_LONG).show()
                    result?.confirm()
                    return true
                }
            }
        }
    }

    private fun installLoginSwipeBorder(view: WebView?) {
        val script = """
            (function () {
              function addHandle() {
                var shell = document.querySelector('.ex-shell');
                if (!shell) return;
                if (getComputedStyle(shell).position === 'static') shell.style.position = 'relative';
                var bar = shell.querySelector('.examo-native-swipe-border');
                if (!bar) {
                  bar = document.createElement('div');
                  bar.className = 'examo-native-swipe-border';
                  bar.setAttribute('role', 'button');
                  bar.setAttribute('aria-label', 'Swipe down to return to welcome screen');
                  // Large transparent touch target, with a slim inset blue grip.
                  // This keeps the reference look but is much easier to grab.
                  bar.style.cssText = 'position:absolute;left:0;right:0;top:0;height:46px;background:transparent;border-radius:26px 26px 0 0;z-index:10020;touch-action:none;cursor:ns-resize';
                  var grip = document.createElement('div');
                  grip.style.cssText = 'position:absolute;left:14px;right:14px;top:10px;height:6px;background:#2455ad;border-radius:999px;box-shadow:0 2px 4px rgba(20,50,110,.22);pointer-events:none';
                  bar.appendChild(grip);
                  shell.appendChild(bar);
                }
                var brand = shell.querySelector('.ex-mobile-brand');
                if (brand) {
                  brand.style.marginTop = '10px';
                  brand.style.marginBottom = '12px';
                }
                var tabs = shell.querySelector('.ex-main-tabs');
                if (tabs) tabs.style.marginTop = '-8px';
                if (bar.dataset.dragBound === '1') return;
                bar.dataset.dragBound = '1';
                var startY = 0, dragging = false, lastY = 0;
                bar.addEventListener('touchstart', function(e) {
                  if (!e.touches || !e.touches.length) return;
                  startY = e.touches[0].clientY; lastY = startY; dragging = true;
                  shell.style.transition = 'none';
                }, {passive:true});
                bar.addEventListener('touchmove', function(e) {
                  if (!dragging || !e.touches || !e.touches.length) return;
                  lastY = e.touches[0].clientY;
                  var dy = Math.max(0, Math.min(lastY - startY, window.innerHeight * .72));
                  shell.style.transform = 'translate3d(0,' + dy + 'px,0)';
                  if (e.cancelable) e.preventDefault();
                }, {passive:false});
                function finish() {
                  if (!dragging) return;
                  dragging = false;
                  var dy = Math.max(0, lastY - startY);
                  shell.style.willChange = 'transform';
                  shell.style.transition = 'transform 320ms cubic-bezier(.22,.61,.36,1)';
                  if (dy > 70 && window.ExamoSwipeBridge) {
                    shell.style.transform = 'translate3d(0,' + window.innerHeight + 'px,0)';
                    setTimeout(function() {
                      shell.style.transform = '';
                      window.ExamoSwipeBridge.showWelcome();
                    }, 320);
                  } else {
                    shell.style.transform = 'translate3d(0,0,0)';
                    setTimeout(function(){ shell.style.transition = ''; shell.style.willChange = ''; }, 340);
                  }
                }
                bar.addEventListener('touchend', finish, {passive:true});
                bar.addEventListener('touchcancel', finish, {passive:true});
                bar.addEventListener('pointerdown', function(e) {
                  if (e.pointerType === 'touch') return;
                  startY = lastY = e.clientY; dragging = true;
                  shell.style.transition = 'none';
                  bar.setPointerCapture(e.pointerId);
                });
                bar.addEventListener('pointermove', function(e) {
                  if (!dragging || e.pointerType === 'touch') return;
                  lastY = e.clientY;
                  var dy = Math.max(0, Math.min(lastY - startY, window.innerHeight * .72));
                  shell.style.transform = 'translate3d(0,' + dy + 'px,0)';
                });
                bar.addEventListener('pointerup', finish);
                bar.addEventListener('pointercancel', finish);
              }
              addHandle();
              if (!window.__examoSwipeBorderObserver) {
                window.__examoSwipeBorderObserver = new MutationObserver(addHandle);
                window.__examoSwipeBorderObserver.observe(document.documentElement, {childList:true,subtree:true});
              }
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun showWelcomeSplash() {
        // Dismiss only the login/signup sheet; never remove or animate the
        // welcome screen underneath it.
        if (!::webView.isInitialized) return
        webView.animate().cancel()
        webView.visibility = View.VISIBLE
        webView.animate()
            .translationY(root.height.toFloat())
            .setDuration(300)
            .withLayer()
            .withEndAction {
                webView.visibility = View.INVISIBLE
                webView.translationY = root.height.toFloat()
                authSheetOpen = false
            }
            .start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPopupWebView(): WebView {
        return WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.mediaPlaybackRequiresUserGesture = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return false
                    if ((scheme == "http" || scheme == "https") && isPdfUrl(uri)) {
                        openPdf(uri, view?.settings?.userAgentString)
                        popupWebView?.let { popup ->
                            popup.post {
                                root.removeView(popup)
                                popup.destroy()
                                if (popupWebView === popup) popupWebView = null
                            }
                        }
                        return true
                    }
                    return if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript") false else openExternal(uri)
                }
            }
            webChromeClient = WebChromeClient()
        }
    }

    private fun openExternal(uri: Uri): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun isPdfUrl(uri: Uri): Boolean {
        val path = (uri.lastPathSegment ?: uri.toString()).lowercase()
        return path.endsWith(".pdf") || path.contains(".pdf/")
    }

    private fun openPdf(uri: Uri, userAgent: String? = webView.settings.userAgentString) {
        Toast.makeText(this, "Opening PDF...", Toast.LENGTH_SHORT).show()
        activityScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val connection = (URL(uri.toString()).openConnection() as HttpURLConnection)
                    connection.connectTimeout = 20000
                    connection.readTimeout = 45000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("Accept", "application/pdf,*/*")
                    userAgent?.let { connection.setRequestProperty("User-Agent", it) }
                    CookieManager.getInstance().getCookie(uri.toString())?.let {
                        connection.setRequestProperty("Cookie", it)
                    }
                    try {
                        val status = connection.responseCode
                        if (status !in 200..299) throw IllegalStateException("PDF request failed (HTTP $status).")
                        val file = File(cacheDir, "pdf_" + System.currentTimeMillis() + ".pdf")
                        connection.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                        if (file.length() < 5 || file.inputStream().use { input ->
                            val header = ByteArray(5); input.read(header); String(header, Charsets.US_ASCII) != "%PDF-"
                        }) {
                            file.delete()
                            throw IllegalStateException("The server did not return a valid PDF. Please sign in again and retry.")
                        }
                        file
                    } finally {
                        connection.disconnect()
                    }
                }
                startActivity(PdfViewerActivity.createIntent(this@MainActivity, file.absolutePath, file.name))
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, error.message ?: "Could not open PDF.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun enqueueDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val info = DownloadInfo(url, userAgent, contentDisposition, mimeType)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = info
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST)
            return
        }
        startDownload(info)
    }

    private fun startDownload(info: DownloadInfo) {
        try {
            val filename = URLUtil.guessFileName(info.url, info.contentDisposition, info.mimeType)
            val request = DownloadManager.Request(Uri.parse(info.url)).apply {
                setTitle(filename)
                setDescription("Downloading from Examo")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                info.mimeType?.let { setMimeType(it) }
                info.userAgent?.let { addRequestHeader("User-Agent", it) }
                CookieManager.getInstance().getCookie(info.url)?.let { addRequestHeader("Cookie", it) }
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Download started: " + filename, Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Could not download this file.", Toast.LENGTH_LONG).show()
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        webView.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = originalSystemUiVisibility
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val callback = fileChooserCallback
            fileChooserCallback = null
            val results = if (resultCode == RESULT_OK && data != null) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else null
            callback?.onReceiveValue(results)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            val info = pendingDownload
            pendingDownload = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && info != null) {
                startDownload(info)
            } else {
                Toast.makeText(this, "Storage permission is required to save this download.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hideGoogleSignInInApp(view: WebView?) {
        val script = """
            (function () {
              function hideGoogleButton() {
                document.querySelectorAll('button,a,[role="button"]').forEach(function (el) {
                  var label = (el.innerText || el.textContent || '').trim().toLowerCase();
                  if (label.indexOf('continue with google') !== -1 || label === 'google') {
                    el.style.setProperty('display', 'none', 'important');
                    el.setAttribute('aria-hidden', 'true');
                  }
                });
              }
              hideGoogleButton();
              if (!window.__examoHideGoogleObserver) {
                window.__examoHideGoogleObserver = new MutationObserver(hideGoogleButton);
                window.__examoHideGoogleObserver.observe(document.documentElement, {childList:true,subtree:true});
              }
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun installMobileNavigationFix(view: WebView?) {
        val script = """
            (function () {
              if (window.__examoAndroidNavInstalled) return;
              window.__examoAndroidNavInstalled = true;

              var css = document.createElement('style');
              css.id = 'examo-android-nav-style';
              css.textContent = '@media(max-width:600px){' +
                '.sidebar{position:fixed!important;left:0!important;top:0!important;bottom:0!important;width:min(82vw,300px)!important;max-width:300px!important;min-height:100dvh!important;height:100dvh!important;overflow-y:auto!important;overflow-x:hidden!important;transform:translate3d(-105%,0,0)!important;transition:transform .25s ease!important;z-index:10002!important;visibility:visible!important;pointer-events:auto!important;-webkit-overflow-scrolling:touch!important;display:flex!important;touch-action:pan-y!important}' +
                '.sidebar.examo-mobile-open{transform:translate3d(0,0,0)!important}' +
                '.main-content{width:100%!important;margin-left:0!important;min-width:0!important}' +
                '.mobile-menu{display:flex!important;align-items:center!important;justify-content:center!important;width:56px!important;height:56px!important;min-width:56px!important;min-height:56px!important;cursor:pointer!important;touch-action:manipulation!important;position:relative!important;z-index:10003!important;border-radius:10px!important;pointer-events:auto!important;user-select:none!important;-webkit-tap-highlight-color:transparent!important}' +
                '.mobile-menu svg{pointer-events:none!important}' +
                '.examo-mobile-backdrop{position:fixed!important;inset:0!important;background:rgba(15,23,42,.50)!important;z-index:10001!important;display:none!important;touch-action:manipulation!important}' +
                '.examo-mobile-backdrop.open{display:block!important}' +
                '.topbar{position:sticky!important;top:0!important;z-index:1000!important}.content-area{min-width:0!important}' +
                // Native-only mobile presentation: keep the website DOM/actions,
                // but render each table row as a readable stacked card in this WebView.
                /* Consistent Examo mobile theme across every section inside Android WebView. */
                'html,body{background:#e8edf3!important}' +
                '.app-layout,.main-content,.content-area,.page{background:linear-gradient(145deg,#edf0f4 0%,#e5eaf1 100%)!important}' +
                '.topbar{background:linear-gradient(105deg,#19385f 0%,#10243e 100%)!important;color:#f8fafc!important;border-bottom:1px solid rgba(255,255,255,.12)!important}' +
                '.topbar .breadcrumb,.topbar .breadcrumb span{color:#f8fafc!important;opacity:1!important}' +
                '.topbar .breadcrumb strong{color:#ffffff!important;opacity:1!important;font-weight:750!important}' +
                '.topbar .nt-bell{background:#ffffff!important;color:#173253!important;border:1px solid rgba(255,255,255,.85)!important;box-shadow:0 3px 8px rgba(7,20,40,.15)!important}' +
                '.topbar .nt-bell svg{stroke:#173253!important;color:#173253!important;opacity:1!important}' +
                '.topbar .nt-bell-badge{background:#dc2626!important;color:#ffffff!important;border:2px solid #173253!important}' +
                '.topbar .mobile-menu svg{stroke:#ffffff!important}' +
                '.topbar svg{stroke:#f8fafc!important}' +
                '.table-container{width:100%!important;max-width:100%!important;overflow:visible!important;background:transparent!important}' +
                'table.data-table{display:block!important;width:100%!important;min-width:0!important;border-collapse:separate!important;border-spacing:0!important;background:transparent!important}' +
                'table.data-table thead{display:none!important}' +
                'table.data-table tbody{display:block!important;width:100%!important}' +
                'table.data-table tbody tr.examo-mobile-record{display:block!important;width:100%!important;margin:0 0 15px!important;padding:15px 16px!important;background:linear-gradient(145deg,#e9edf2 0%,#dfe5ed 100%)!important;border:1px solid rgba(255,255,255,.96)!important;border-radius:17px!important;box-shadow:0 9px 17px rgba(31,48,73,.14),inset 0 1px 0 rgba(255,255,255,.9)!important;overflow:hidden!important}' +
                'table.data-table tbody td{display:grid!important;grid-template-columns:minmax(88px,36%) minmax(0,1fr)!important;align-items:start!important;gap:10px!important;width:100%!important;min-width:0!important;padding:9px 0!important;border:0!important;border-bottom:1px solid rgba(91,108,130,.20)!important;background:transparent!important;color:#3e4959!important;font-size:14px!important;line-height:1.42!important;white-space:normal!important;overflow-wrap:anywhere!important;vertical-align:middle!important}' +
                'table.data-table tbody td.examo-mobile-actions{display:flex!important;align-items:center!important;justify-content:flex-start!important;flex-direction:row!important;flex-wrap:nowrap!important;gap:8px!important;padding:12px 0 0!important;border-bottom:0!important}' +
                'table.data-table tbody td.examo-mobile-actions .row-actions{display:flex!important;align-items:center!important;justify-content:flex-start!important;flex-direction:row!important;flex-wrap:nowrap!important;gap:7px!important;width:auto!important}' +
                'table.data-table tbody td.examo-mobile-actions button{flex:0 0 auto!important;max-width:none!important}' +
                'table.data-table tbody td:before{content:attr(data-label);display:block!important;color:#8290a5!important;font-size:10px!important;font-weight:750!important;letter-spacing:.045em!important;text-transform:uppercase!important;line-height:1.4!important}' +
                'table.data-table tbody td.examo-mobile-title{display:block!important;padding:0 0 11px!important;margin:0 0 5px!important;border-bottom:1px solid #e9edf4!important;color:#172033!important;font-size:16px!important;font-weight:750!important;line-height:1.35!important}' +
                'table.data-table tbody td.examo-mobile-title:before{display:none!important;content:none!important}' +
                'table.data-table tbody td.examo-mobile-actions{display:flex!important;align-items:center!important;justify-content:flex-start!important;flex-wrap:wrap!important;gap:8px!important;padding:12px 0 0!important;border-bottom:0!important}' +
                'table.data-table tbody td.examo-mobile-actions:before{display:none!important;content:none!important}' +
                'table.data-table tbody td.examo-mobile-actions .row-actions{display:flex!important;flex-wrap:wrap!important;gap:8px!important}' +
                'table.data-table tbody td.examo-mobile-actions button,table.data-table tbody td.examo-mobile-actions a{max-width:100%!important}' +
                'table.data-table tbody tr:last-child{margin-bottom:0!important}' +
                '.data-card{min-width:0!important;max-width:100%!important;overflow:visible!important}' +
                '.page,.content-area,.main-content{min-width:0!important;max-width:100%!important;overflow-x:hidden!important}' +
                '.jobs-chip{display:inline-block!important;margin:5px 0 0!important;white-space:normal!important}' +
                // Keep Latest Jobs / Admit Card / Results in one compact row.
                '.jobs-tabs{display:flex!important;flex-wrap:nowrap!important;align-items:center!important;gap:6px!important;width:100%!important;max-width:100%!important;overflow-x:auto!important;overflow-y:hidden!important;white-space:nowrap!important;padding:2px 0 8px!important;scrollbar-width:none!important}' +
                '.jobs-tabs::-webkit-scrollbar{display:none!important}' +
                '.jobs-tab{display:inline-flex!important;flex:1 1 0!important;min-width:0!important;max-width:none!important;align-items:center!important;justify-content:center!important;gap:5px!important;padding:9px 7px!important;font-size:12px!important;line-height:1.1!important;white-space:nowrap!important;overflow:hidden!important;text-overflow:ellipsis!important}' +
                '.jobs-tab svg{width:15px!important;height:15px!important;min-width:15px!important;flex:0 0 15px!important}' +
                // Jobs / Admit Card / Results: title, paired metadata, then actions.
                'table.examo-mobile-job-table tbody tr.examo-mobile-record{display:grid!important;grid-template-columns:minmax(0,1fr) minmax(0,1fr)!important;column-gap:14px!important;row-gap:0!important;padding:14px 16px!important}' +
                'table.examo-mobile-job-table tbody td{display:flex!important;flex-direction:column!important;align-items:flex-start!important;justify-content:center!important;min-width:0!important;gap:4px!important;padding:9px 0!important;border-bottom:0!important;font-size:13px!important;line-height:1.4!important}' +
                'table.examo-mobile-job-table tbody td.examo-mobile-title{grid-column:1/-1!important;display:block!important;margin:0 0 5px!important;padding:0 0 12px!important;border-bottom:1px solid #e7ebf2!important;font-size:17px!important;font-weight:750!important;line-height:1.3!important;color:#172033!important}' +
                'table.examo-mobile-job-table tbody td.examo-mobile-date{display:flex!important;flex-direction:column!important;align-items:flex-start!important;justify-content:flex-start!important;padding:8px 0 12px!important;border-bottom:1px solid #edf0f5!important}' +
                'table.examo-mobile-job-table tbody td.examo-mobile-date:before{display:block!important;content:attr(data-label)!important;color:#8491a5!important;font-size:10px!important;font-weight:750!important;letter-spacing:.045em!important;text-transform:uppercase!important}' +
                'table.examo-mobile-job-table tbody td.examo-mobile-status{display:flex!important;flex-direction:column!important;align-items:flex-start!important;justify-content:flex-start!important;padding:8px 0 12px!important;border-bottom:1px solid #edf0f5!important}' +
                'table.examo-mobile-job-table tbody td.examo-mobile-status:before{display:block!important;content:attr(data-label)!important;color:#8491a5!important;font-size:10px!important;font-weight:750!important;letter-spacing:.045em!important;text-transform:uppercase!important}' +
                'table.examo-mobile-job-table tbody td.examo-mobile-details{grid-column:1!important;display:flex!important;align-items:flex-start!important;justify-content:center!important;flex-direction:row!important;flex-wrap:wrap!important;padding:13px 0 0!important;border-bottom:0!important}' +
                'table.examo-mobile-job-table tbody td.examo-mobile-actions{grid-column:2!important;display:flex!important;align-items:flex-end!important;justify-content:center!important;flex-direction:row!important;flex-wrap:wrap!important;padding:13px 0 0!important;border-bottom:0!important}' +
                'table.examo-mobile-job-table tbody td.examo-mobile-details:before,table.examo-mobile-job-table tbody td.examo-mobile-actions:before{display:none!important;content:none!important}' +
                'table.examo-mobile-job-table .jobs-video-btn,table.examo-mobile-job-table .jobs-add-btn,table.examo-mobile-job-table .jobs-open-btn{max-width:100%!important;justify-content:center!important;padding:7px 10px!important;font-size:11px!important;line-height:1.25!important;white-space:normal!important;text-align:center!important}' +
                'table.examo-mobile-job-table .jobs-chip{font-size:10px!important;white-space:normal!important}' +
                /* Latest Jobs: title; Start/Last Date; Watch and Added directly below the matching dates. */
                'table.examo-mobile-latest-jobs-table tbody tr.examo-mobile-record{display:grid!important;grid-template-columns:minmax(0,1fr) minmax(0,1fr)!important;grid-template-rows:auto auto auto!important;column-gap:14px!important;row-gap:0!important;align-items:stretch!important;padding:15px 16px 14px!important}' +
                'table.examo-mobile-latest-jobs-table tbody tr.examo-mobile-record>td{min-width:0!important;width:auto!important;padding:9px 0!important;border:0!important;background:transparent!important;color:#3e4959!important;font-size:13px!important;line-height:1.4!important}' +
                'table.examo-mobile-latest-jobs-table tbody tr.examo-mobile-record>td.examo-mobile-title{grid-column:1/-1!important;grid-row:1!important;display:block!important;padding:0 0 12px!important;margin:0 0 7px!important;border-bottom:1px solid rgba(91,108,130,.24)!important;font-size:18px!important;line-height:1.3!important;color:#172033!important}' +
                'table.examo-mobile-latest-jobs-table tbody tr.examo-mobile-record>td.examo-mobile-date:nth-child(2){grid-column:1!important;grid-row:2!important;display:flex!important;flex-direction:column!important;align-items:flex-start!important;gap:5px!important;padding:8px 0 13px!important;border-bottom:1px solid rgba(91,108,130,.18)!important}' +
                'table.examo-mobile-latest-jobs-table tbody tr.examo-mobile-record>td.examo-mobile-date:nth-child(3){grid-column:2!important;grid-row:2!important;display:flex!important;flex-direction:column!important;align-items:flex-end!important;justify-content:flex-start!important;text-align:right!important;gap:5px!important;padding:8px 0 13px!important;border-bottom:1px solid rgba(91,108,130,.18)!important}' +
                'table.examo-mobile-latest-jobs-table tbody tr.examo-mobile-record>td:nth-child(2):before,table.examo-mobile-latest-jobs-table tbody tr.examo-mobile-record>td:nth-child(3):before{display:block!important;content:attr(data-label)!important;color:#8491a5!important;font-size:10px!important;font-weight:750!important;letter-spacing:.045em!important;text-transform:uppercase!important}' +
                'table.examo-mobile-latest-jobs-table tbody tr.examo-mobile-record>td.examo-mobile-details{grid-column:1!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:flex-start!important;padding:13px 0 0!important;border:0!important}' +
                'table.examo-mobile-latest-jobs-table tbody tr.examo-mobile-record>td.examo-mobile-actions{grid-column:2!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:flex-end!important;padding:13px 0 0!important;border:0!important}' +
                'table.examo-mobile-latest-jobs-table tbody tr.examo-mobile-record>td.examo-mobile-details:before,table.examo-mobile-latest-jobs-table tbody tr.examo-mobile-record>td.examo-mobile-actions:before{display:none!important;content:none!important}' +
                'table.examo-mobile-latest-jobs-table tbody tr.examo-mobile-record>td.examo-mobile-actions .jobs-add-btn{max-width:100%!important;white-space:nowrap!important}' +
                /* Admit Cards / Results: date and status, then Watch and Open in the same columns below. */
                'table.examo-mobile-job-posts-table tbody tr.examo-mobile-record{display:grid!important;grid-template-columns:minmax(0,1fr) minmax(0,1fr)!important;grid-template-rows:auto auto auto!important;column-gap:14px!important;row-gap:0!important;align-items:stretch!important;padding:15px 16px 14px!important}' +
                'table.examo-mobile-job-posts-table tbody tr.examo-mobile-record>td{min-width:0!important;width:auto!important;padding:9px 0!important;border:0!important;background:transparent!important;color:#3e4959!important;font-size:13px!important;line-height:1.4!important}' +
                'table.examo-mobile-job-posts-table tbody tr.examo-mobile-record>td.examo-mobile-title{grid-column:1/-1!important;grid-row:1!important;display:block!important;padding:0 0 12px!important;margin:0 0 7px!important;border-bottom:1px solid rgba(91,108,130,.24)!important;font-size:18px!important;line-height:1.3!important;color:#172033!important}' +
                'table.examo-mobile-job-posts-table tbody tr.examo-mobile-record>td.examo-mobile-date{grid-column:1!important;grid-row:2!important;display:flex!important;flex-direction:column!important;align-items:flex-start!important;gap:5px!important;padding:8px 0 13px!important;border-bottom:1px solid rgba(91,108,130,.18)!important}' +
                'table.examo-mobile-job-posts-table tbody tr.examo-mobile-record>td.examo-mobile-status{grid-column:2!important;grid-row:2!important;display:flex!important;flex-direction:column!important;align-items:flex-end!important;justify-content:flex-start!important;text-align:right!important;gap:5px!important;padding:8px 0 13px!important;border-bottom:1px solid rgba(91,108,130,.18)!important}' +
                'table.examo-mobile-job-posts-table tbody tr.examo-mobile-record>td.examo-mobile-date:before,table.examo-mobile-job-posts-table tbody tr.examo-mobile-record>td.examo-mobile-status:before{display:block!important;content:attr(data-label)!important;color:#8491a5!important;font-size:10px!important;font-weight:750!important;letter-spacing:.045em!important;text-transform:uppercase!important}' +
                'table.examo-mobile-job-posts-table tbody tr.examo-mobile-record>td.examo-mobile-details{grid-column:1!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:flex-start!important;padding:13px 0 0!important;border:0!important}' +
                'table.examo-mobile-job-posts-table tbody tr.examo-mobile-record>td.examo-mobile-actions{grid-column:2!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:flex-end!important;padding:13px 0 0!important;border:0!important}' +
                'table.examo-mobile-job-posts-table tbody tr.examo-mobile-record>td.examo-mobile-details:before,table.examo-mobile-job-posts-table tbody tr.examo-mobile-record>td.examo-mobile-actions:before{display:none!important;content:none!important}' +
                'table.examo-mobile-job-posts-table .jobs-video-btn,table.examo-mobile-job-posts-table .jobs-open-btn{max-width:100%!important;white-space:nowrap!important}' +
                // Exam Forms: card surface uses the reference grey-blue gradient;
                // original table cells and button elements/colors remain intact.
                '.examo-mobile-exam-form-wrapper{background:transparent!important;border:0!important;box-shadow:none!important;padding:0!important;overflow:visible!important}' +
                '.examo-mobile-exam-form-wrapper .table-container{background:transparent!important;border:0!important;box-shadow:none!important;padding:0!important;overflow:visible!important}' +
                'table.examo-mobile-exam-form-table{display:block!important;width:100%!important;background:transparent!important;border:0!important}' +
                'table.examo-mobile-exam-form-table tbody{display:block!important;width:100%!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record{display:grid!important;grid-template-columns:minmax(64px,1fr) minmax(78px,1.2fr) minmax(36px,.55fr) minmax(74px,1fr)!important;grid-template-rows:auto auto auto!important;column-gap:5px!important;row-gap:0!important;align-items:center!important;width:100%!important;margin:0 0 16px!important;padding:15px 13px 14px!important;background:linear-gradient(145deg,#e9edf2 0%,#dfe5ed 100%)!important;border:1px solid rgba(255,255,255,.96)!important;border-radius:17px!important;box-shadow:0 9px 17px rgba(31,48,73,.14),inset 0 1px 0 rgba(255,255,255,.9)!important;overflow:hidden!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td{display:flex!important;position:relative!important;align-items:center!important;justify-content:flex-start!important;min-width:0!important;width:auto!important;gap:4px!important;padding:0!important;margin:0!important;border:0!important;background:transparent!important;color:#3e4959!important;font-size:14px!important;line-height:1.35!important;white-space:normal!important;overflow-wrap:anywhere!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td.examo-mobile-title{grid-column:1/-1!important;grid-row:1!important;display:block!important;padding:0 0 12px!important;margin:0 0 11px!important;border-bottom:1px solid rgba(91,108,130,.24)!important;color:#080d16!important;font-size:20px!important;font-weight:750!important;line-height:1.25!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td.examo-mobile-title:before{display:none!important;content:none!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(2){grid-column:1/3!important;grid-row:2!important;display:flex!important;flex-direction:row!important;align-items:baseline!important;justify-content:flex-start!important;gap:4px!important;padding:1px 0 16px!important;white-space:nowrap!important;font-size:13px!important;font-weight:650!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(3){grid-column:3/5!important;grid-row:2!important;display:flex!important;flex-direction:row!important;align-items:baseline!important;justify-content:flex-start!important;gap:4px!important;padding:1px 0 16px!important;white-space:nowrap!important;font-size:13px!important;font-weight:650!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(2):before,table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(3):before{display:inline!important;content:attr(data-label) ": "!important;color:#505c6c!important;font-size:12px!important;font-weight:500!important;letter-spacing:0!important;text-transform:none!important;white-space:nowrap!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(4){grid-column:1!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:flex-start!important;padding:0!important;white-space:nowrap!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-exam-form-table td:nth-child(5){grid-column:2!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:center!important;padding:0!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(5){grid-column:2!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:center!important;padding:0!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(6){grid-column:3!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:center!important;padding:0!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(7){grid-column:4!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:flex-end!important;padding:0!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-exam-form-table td:nth-child(n+4):before{display:none!important;content:none!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(n+4):before{display:none!important;content:none!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(5)>*{max-width:100%!important;white-space:nowrap!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(6)>*{white-space:nowrap!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(7) .row-actions{display:flex!important;align-items:center!important;justify-content:flex-end!important;flex-direction:row!important;flex-wrap:nowrap!important;gap:6px!important;width:auto!important;white-space:nowrap!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(7) .row-actions button{display:inline-flex!important;align-items:center!important;justify-content:center!important;flex:0 0 34px!important;width:34px!important;min-width:34px!important;height:36px!important;min-height:36px!important;padding:5px!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(7) .row-actions svg{width:17px!important;height:17px!important}' +
                /* Help page: Android-only reference styling and mobile content order. */
                '.hs-page{display:flex!important;flex-direction:column!important;gap:0!important;align-items:stretch!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;color:#172033!important}' +
                '.hs-main,.hs-side{display:contents!important}' +
                '.hs-hero{order:1!important;width:100%!important;margin:0 0 12px!important;padding:13px 12px!important;text-align:center!important;background:linear-gradient(145deg,#eaf2fc 0%,#e2ebf7 100%)!important;border:1px solid #cbd8e8!important;border-radius:13px!important;box-shadow:inset 0 1px 0 rgba(255,255,255,.85)!important}' +
                '.hs-hero-title{gap:6px!important;align-items:center!important;justify-content:center!important}' +
                '.hs-hero-icon{color:#174777!important;display:inline-flex!important;flex:0 0 auto!important}' +
                '.hs-hero-icon svg{width:21px!important;height:21px!important}' +
                '.hs-hero h1{margin:0!important;color:#101827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:21px!important;line-height:1.15!important;font-weight:750!important;letter-spacing:-.35px!important}' +
                '.hs-search{width:100%!important;max-width:none!important;height:39px!important;margin:10px 0 0!important;padding:3px 4px 3px 12px!important;background:#fff!important;border:1px solid #c7d1de!important;border-radius:999px!important;box-shadow:0 1px 2px rgba(15,35,60,.05)!important}' +
                '.hs-search input{min-width:0!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:13px!important;color:#263449!important}' +
                '.hs-search input::placeholder{color:#657286!important;opacity:1!important}' +
                '.hs-search button{width:29px!important;height:29px!important;min-width:29px!important;background:#0751bd!important;color:#fff!important;box-shadow:0 1px 3px rgba(10,40,80,.2)!important}' +
                '.hs-search button svg{width:16px!important;height:16px!important}' +
                '.hs-side>h2{order:2!important;margin:0 0 2px!important;color:#111827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:17px!important;line-height:1.18!important;font-weight:750!important}' +
                '.hs-side>.hs-side-text{order:3!important;margin:0 0 8px!important;color:#344256!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;line-height:1.35!important}' +
                '.hs-side>.hs-contact{order:4!important;display:grid!important;grid-template-columns:minmax(0,1fr) minmax(0,1fr)!important;grid-template-areas:"title title" "name subject" "email message" "submit submit" "notice notice"!important;gap:7px 8px!important;width:100%!important;margin:0 0 9px!important;padding:10px 11px!important;background:linear-gradient(145deg,#ffffff 0%,#f6f8fb 100%)!important;border:1px solid #d1dae5!important;border-radius:13px!important;box-shadow:0 3px 10px rgba(24,43,70,.07)!important}' +
                '.hs-contact h3{grid-area:title!important;margin:0!important;color:#111827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:16px!important;line-height:1.2!important;font-weight:750!important}' +
                '.hs-contact label{display:grid!important;align-content:start!important;gap:3px!important;min-width:0!important;margin:0!important;color:#111827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;line-height:1.15!important;font-weight:700!important}' +
                '.hs-contact label:nth-of-type(1){grid-area:name!important}' +
                '.hs-contact label:nth-of-type(2){grid-area:email!important}' +
                '.hs-contact label:nth-of-type(3){grid-area:subject!important}' +
                '.hs-contact label:nth-of-type(4){grid-area:message!important}' +
                '.hs-contact input,.hs-contact textarea{box-sizing:border-box!important;width:100%!important;min-width:0!important;min-height:30px!important;padding:5px 7px!important;border:1px solid #bdc8d5!important;border-radius:7px!important;background:#fff!important;color:#1c2737!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;line-height:1.25!important;box-shadow:inset 0 1px 2px rgba(15,35,60,.035)!important}' +
                '.hs-contact input:disabled{opacity:1!important;color:#263449!important;-webkit-text-fill-color:#263449!important;background:#fff!important}' +
                '.hs-contact textarea{height:52px!important;min-height:52px!important;resize:vertical!important}' +
                '.hs-contact button.hs-btn{grid-area:submit!important;width:100%!important;min-height:33px!important;padding:6px 10px!important;border:1px solid #0646b4!important;border-radius:7px!important;background:linear-gradient(100deg,#0846c5,#0753d1)!important;color:#fff!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:13px!important;font-weight:700!important;box-shadow:0 2px 4px rgba(8,55,130,.2)!important}' +
                '.hs-contact .hs-success,.hs-contact>div[style]{grid-area:notice!important;font-size:11px!important;padding:7px 8px!important}' +
                '.hs-side>.hs-contact-links{order:5!important;display:grid!important;grid-template-columns:minmax(0,1fr) minmax(0,1fr)!important;align-items:stretch!important;gap:7px!important;width:100%!important;margin:0 0 9px!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:11px!important}' +
                '.hs-contact-links>div,.hs-contact-links>.hs-chat-open{min-width:0!important;display:grid!important;grid-template-columns:19px minmax(0,1fr)!important;column-gap:6px!important;align-content:center!important;align-items:center!important;padding:7px 8px!important;border-radius:10px!important}' +
                '.hs-contact-links>div{background:transparent!important;color:#173e6b!important}' +
                '.hs-contact-links>div>svg,.hs-contact-links>.hs-chat-open>svg{width:17px!important;height:17px!important;grid-row:1/3!important;align-self:center!important;color:#174777!important}' +
                '.hs-contact-links strong{min-width:0!important;color:#111827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;line-height:1.12!important;font-weight:750!important;overflow-wrap:anywhere!important}' +
                '.hs-contact-links span{grid-column:2!important;min-width:0!important;color:#28374b!important;font-size:11px!important;line-height:1.15!important;overflow-wrap:anywhere!important}' +
                '.hs-contact-links>.hs-chat-open{border:1px solid #b8c9e0!important;background:linear-gradient(145deg,#edf4ff,#e4edf9)!important;color:#174777!important;text-align:left!important;box-shadow:inset 0 1px 0 rgba(255,255,255,.8)!important;font:inherit!important}' +
                '.hs-side>.hs-link-btn{display:none!important}' +
                '.hs-main>.hs-heading:nth-of-type(1){order:7!important;margin:8px 0 7px!important;color:#111827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:16px!important;line-height:1.2!important;font-weight:750!important}' +
                '.hs-card-grid{order:8!important;display:grid!important;grid-template-columns:repeat(3,minmax(0,1fr))!important;gap:8px!important;width:100%!important;align-items:stretch!important;margin:0 0 12px!important}' +
                '.hs-faq-card{min-width:0!important;min-height:172px!important;background:linear-gradient(160deg,#ffffff 0%,#f5f7fa 100%)!important;border:1px solid #d0d9e4!important;border-radius:11px!important;box-shadow:0 2px 7px rgba(22,42,68,.045)!important;overflow:hidden!important}' +
                '.hs-faq-card-head{min-height:39px!important;display:flex!important;align-items:center!important;justify-content:space-between!important;gap:3px!important;padding:7px 8px!important;color:#111827!important}' +
                '.hs-faq-card-head.mint{background:linear-gradient(110deg,#d9f0ea,#cde9e2)!important}' +
                '.hs-faq-card-head.lavender{background:linear-gradient(110deg,#e9e3f6,#ddd7ef)!important}' +
                '.hs-faq-card-head.sky{background:linear-gradient(110deg,#deebf7,#d3e2f1)!important}' +
                '.hs-faq-card-head h3{min-width:0!important;margin:0!important;color:#111827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;line-height:1.12!important;font-weight:750!important;overflow-wrap:anywhere!important}' +
                '.hs-faq-card-head svg{flex:0 0 auto!important;width:19px!important;height:19px!important;color:#111827!important}' +
                '.hs-faq-card ul{display:grid!important;gap:5px!important;margin:0!important;padding:7px 8px 9px!important;list-style:none!important}' +
                '.hs-faq-card li{min-width:0!important;margin:0!important;padding:0!important}' +
                '.hs-faq-card li button{display:block!important;width:100%!important;padding:0!important;border:0!important;background:transparent!important;color:#1c2b3e!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:11px!important;line-height:1.28!important;text-align:left!important;text-decoration:underline!important;overflow-wrap:anywhere!important}' +
                '.hs-main>.hs-heading:nth-of-type(2){order:9!important;margin:8px 0 7px!important;padding:9px 10px!important;border:1px solid #cbd6e4!important;border-bottom:0!important;border-radius:12px 12px 0 0!important;background:linear-gradient(110deg,#e9e7f5,#e2eaf4)!important;color:#172033!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:15px!important;line-height:1.2!important;font-weight:750!important}' +
                '.hs-accordion{order:10!important;width:100%!important;margin:0 0 12px!important;background:#142239!important;border:1px solid #293a53!important;border-radius:0 0 11px 11px!important;overflow:hidden!important;box-shadow:0 4px 10px rgba(15,30,50,.1)!important}' +
                '.hs-acc-item{background:#142239!important}' +
                '.hs-acc-item+.hs-acc-item{border-top:1px solid rgba(225,235,248,.22)!important}' +
                '.hs-acc-head{display:flex!important;align-items:center!important;justify-content:space-between!important;gap:10px!important;width:100%!important;padding:9px 10px!important;background:#142239!important;color:#fff!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;line-height:1.22!important;font-weight:650!important;text-align:left!important}' +
                '.hs-acc-head span{min-width:0!important;overflow-wrap:anywhere!important}' +
                '.hs-acc-head svg{flex:0 0 auto!important;width:15px!important;height:15px!important;color:#fff!important;stroke:#fff!important}' +
                '.hs-acc-item.open .hs-acc-head{background:#1b304c!important;color:#fff!important}' +
                '.hs-acc-body{padding:9px 11px 12px!important;background:#e6edf6!important;color:#29384c!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;line-height:1.45!important}' +
                '.hs-empty{padding:14px!important;background:#f4f7fb!important;color:#536277!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important}' +
                '.hbot{font-family:"Roboto Condensed","Arial Narrow",sans-serif!important}' +
                '.hbot-head{background:linear-gradient(110deg,#19385f,#10243e)!important}' +
                /* Subscription page: same Examo mobile typography, navy + soft blue-grey theme. */
                '.examo-mobile-subscription-page{display:flex!important;flex-direction:column!important;align-items:stretch!important;gap:0!important;width:100%!important;min-width:0!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;color:#172033!important}' +
                '.examo-mobile-subscription-page .hs-main,.examo-mobile-subscription-page .hs-side{display:contents!important}' +
                '.examo-mobile-subscription-page .hs-hero{order:1!important;width:100%!important;margin:0 0 13px!important;padding:14px 12px!important;background:linear-gradient(145deg,#eaf2fc 0%,#e2ebf7 100%)!important;border:1px solid #cbd8e8!important;border-radius:14px!important;box-shadow:inset 0 1px 0 rgba(255,255,255,.9)!important;text-align:center!important}' +
                '.examo-mobile-subscription-page .hs-hero h1{margin:0!important;color:#101827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:21px!important;line-height:1.18!important;font-weight:750!important;letter-spacing:-.3px!important}' +
                '.examo-mobile-subscription-page .hs-search{width:100%!important;max-width:none!important;height:39px!important;margin:10px 0 0!important;padding:3px 4px 3px 12px!important;background:#fff!important;border:1px solid #c7d1de!important;border-radius:999px!important;box-shadow:0 1px 2px rgba(15,35,60,.05)!important}' +
                '.examo-mobile-subscription-page .hs-search input{min-width:0!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:13px!important;color:#263449!important}' +
                '.examo-mobile-subscription-page .hs-search button{width:30px!important;height:30px!important;min-width:30px!important;background:#0751bd!important;color:#fff!important}' +
                '.examo-mobile-subscription-page .hs-side>h2{order:2!important;margin:4px 0 9px!important;color:#111827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:19px!important;line-height:1.2!important;font-weight:750!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1){order:3!important;display:grid!important;gap:8px!important;width:100%!important;margin:0 0 14px!important;padding:13px 14px!important;background:linear-gradient(145deg,#ffffff 0%,#e9eef5 100%)!important;border:1px solid rgba(255,255,255,.95)!important;border-radius:15px!important;box-shadow:0 7px 15px rgba(31,48,73,.13),inset 0 1px 0 rgba(255,255,255,.9)!important}' +
                '.examo-mobile-subscription-page .hs-panel h3{margin:0!important;color:#111827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:16px!important;line-height:1.25!important;font-weight:750!important}' +
                '.examo-mobile-subscription-page .hs-panel-row{display:flex!important;align-items:center!important;justify-content:space-between!important;gap:8px!important}' +
                '.examo-mobile-subscription-page .hs-panel p{margin:0!important;color:#344054!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:13px!important;line-height:1.35!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-panel p strong{color:#151d2a!important;font-weight:750!important}' +
                '.examo-mobile-subscription-page .hs-active{color:#168044!important;font-weight:750!important}' +
                '.examo-mobile-subscription-page .hs-status-icon{color:#21834d!important}' +
                '.examo-mobile-subscription-page .hs-status-icon svg{width:20px!important;height:20px!important}' +
                '.examo-mobile-subscription-page .hs-panel hr{width:100%!important;margin:2px 0!important;border:0!important;border-top:1px solid rgba(91,108,130,.24)!important}' +
                '.examo-mobile-subscription-page .hs-panel>.hs-btn{width:100%!important;min-height:38px!important;margin:1px 0!important;padding:8px 10px!important;border:1px solid #0646b4!important;border-radius:9px!important;background:linear-gradient(100deg,#0846c5,#0753d1)!important;color:#fff!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:13px!important;font-weight:750!important;box-shadow:0 3px 7px rgba(8,55,130,.22)!important}' +
                '.examo-mobile-subscription-page .hs-panel-row:nth-of-type(2){align-items:center!important}' +
                '.examo-mobile-subscription-page .hs-panel-row:nth-of-type(2) h3{font-size:14px!important}' +
                '.examo-mobile-subscription-page .hs-payments{display:grid!important;gap:7px!important;margin:0!important;padding:0!important;list-style:none!important}' +
                '.examo-mobile-subscription-page .hs-payments li{display:flex!important;align-items:center!important;justify-content:space-between!important;gap:8px!important;min-width:0!important;padding-top:7px!important;border-top:1px solid rgba(91,108,130,.18)!important;color:#263449!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;line-height:1.3!important}' +
                '.examo-mobile-subscription-page .hs-payments li span{min-width:0!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-payments .hs-btn,.examo-mobile-subscription-page .hs-btn-sm{flex:0 0 auto!important;width:auto!important;min-height:34px!important;padding:6px 10px!important;border:1px solid #0646b4!important;border-radius:8px!important;background:linear-gradient(100deg,#0846c5,#0753d1)!important;color:#fff!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;font-weight:700!important;white-space:nowrap!important;box-shadow:0 2px 5px rgba(8,55,130,.2)!important}' +
                '.examo-mobile-subscription-page .hs-main>.hs-success-top{order:4!important;margin:0 0 9px!important;padding:8px 10px!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important}' +
                '.examo-mobile-subscription-page .hs-main>.hs-heading{order:5!important;margin:1px 0 8px!important;color:#111827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:19px!important;line-height:1.2!important;font-weight:750!important}' +
                '.examo-mobile-subscription-page .hs-card-grid{order:6!important;display:grid!important;grid-template-columns:repeat(3,minmax(0,1fr))!important;align-items:stretch!important;gap:8px!important;width:100%!important;margin:0 0 13px!important}' +
                '.examo-mobile-subscription-page .hs-plan-card{display:flex!important;flex-direction:column!important;min-width:0!important;min-height:250px!important;background:linear-gradient(160deg,#ffffff 0%,#f5f7fa 100%)!important;border:1px solid #d0d9e4!important;border-radius:12px!important;box-shadow:0 4px 11px rgba(22,42,68,.09)!important;overflow:hidden!important}' +
                '.examo-mobile-subscription-page .hs-plan-card.current{border:2px solid #2359a5!important;box-shadow:0 5px 12px rgba(24,65,130,.14)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head{display:flex!important;align-items:center!important;justify-content:space-between!important;gap:3px!important;min-height:41px!important;padding:7px 8px!important;color:#111827!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head.mint{background:linear-gradient(110deg,#d9f0ea,#cde9e2)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head.lavender{background:linear-gradient(110deg,#e9e3f6,#ddd7ef)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head.sky{background:linear-gradient(110deg,#deebf7,#d3e2f1)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head h3{min-width:0!important;margin:0!important;color:#111827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:13px!important;line-height:1.14!important;font-weight:750!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head svg{flex:0 0 auto!important;width:19px!important;height:19px!important;color:#111827!important}' +
                '.examo-mobile-subscription-page .hs-plan-body{display:flex!important;flex:1!important;flex-direction:column!important;min-width:0!important;padding:10px 9px 11px!important;gap:0!important}' +
                '.examo-mobile-subscription-page .hs-plan-label{display:block!important;margin:0!important;color:#172033!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;font-weight:750!important}' +
                '.examo-mobile-subscription-page .hs-plan-features{display:grid!important;gap:4px!important;margin:6px 0 11px!important;padding-left:15px!important;color:#243c5e!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:11px!important;line-height:1.28!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-plan-price{margin-top:auto!important;color:#111827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:17px!important;line-height:1.2!important;font-weight:800!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-plan-sub{min-height:26px!important;margin:3px 0 8px!important;color:#445166!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:11px!important;line-height:1.25!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-btn{width:100%!important;min-height:34px!important;padding:6px 5px!important;border:1px solid #0646b4!important;border-radius:8px!important;background:linear-gradient(100deg,#0846c5,#0753d1)!important;color:#fff!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:11px!important;line-height:1.2!important;font-weight:750!important;white-space:normal!important;box-shadow:0 2px 5px rgba(8,55,130,.18)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-btn:disabled{border-color:#9aa6b5!important;background:#8f9aaa!important;color:#f8fafc!important;box-shadow:none!important;opacity:1!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(2){order:7!important;display:grid!important;gap:7px!important;width:100%!important;margin:0 0 12px!important;padding:12px 14px!important;background:linear-gradient(145deg,#ffffff 0%,#e9eef5 100%)!important;border:1px solid #d1dae5!important;border-radius:14px!important;box-shadow:0 4px 10px rgba(24,43,70,.08)!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(2) h3{font-size:16px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(2) .hs-link-btn{display:inline-flex!important;align-self:flex-start!important;color:#174777!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:14px!important;font-weight:750!important}' +
                '.examo-mobile-subscription-page .hs-empty{order:6!important;padding:12px!important;background:#f4f7fb!important;border:1px dashed #cbd6e4!important;border-radius:10px!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important}' +
                '@media(max-width:360px){.examo-mobile-subscription-page .hs-card-grid{gap:6px!important}.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head{padding:6px!important}.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head h3{font-size:11px!important}.examo-mobile-subscription-page .hs-plan-body{padding:8px 7px 9px!important}.examo-mobile-subscription-page .hs-plan-features{font-size:10px!important}.examo-mobile-subscription-page .hs-plan-price{font-size:15px!important}.examo-mobile-subscription-page .hs-plan-card .hs-btn{font-size:10px!important}}' +
                '@media(max-width:360px){.hs-card-grid{gap:6px!important}.hs-faq-card-head{padding:6px!important}.hs-faq-card-head h3{font-size:11px!important}.hs-faq-card ul{padding:6px!important}.hs-faq-card li button{font-size:10px!important}.hs-contact{gap:6px!important;padding:9px!important}.hs-contact-links{gap:5px!important}}' +
                '@media(max-width:360px){table.examo-mobile-exam-form-table tbody tr.examo-mobile-record{grid-template-columns:minmax(58px,.9fr) minmax(72px,1.2fr) minmax(32px,.5fr) minmax(70px,1fr)!important;padding:13px 10px 12px!important}table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(2),table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(3){font-size:12px!important}table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(2):before,table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(3):before{font-size:11px!important}}' +

                /* Subscription mobile redesign: full-width, readable plan cards. */
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1){padding:15px!important;gap:9px!important;background:linear-gradient(145deg,#edf1f6 0%,#e2e8f0 100%)!important;border:1px solid rgba(255,255,255,.98)!important;border-radius:16px!important;box-shadow:0 8px 16px rgba(31,48,73,.12),inset 0 1px 0 rgba(255,255,255,.95)!important}' +
                '.examo-mobile-subscription-page .hs-panel-row:first-child h3{font-size:17px!important;letter-spacing:-.15px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>p{font-size:14px!important;line-height:1.4!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>p strong{font-weight:750!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>hr{border-top:1px solid rgba(91,108,130,.22)!important;margin:3px 0!important}' +
                '.examo-mobile-subscription-page .hs-payments li{padding:9px 10px!important;background:rgba(255,255,255,.58)!important;border:1px solid rgba(196,207,221,.75)!important;border-radius:10px!important}' +
                '.examo-mobile-subscription-page .hs-payments li span{font-size:13px!important;font-weight:600!important;color:#334155!important}' +
                '.examo-mobile-subscription-page .hs-main>.hs-heading{margin:3px 0 10px!important;font-size:20px!important;letter-spacing:-.2px!important}' +
                '.examo-mobile-subscription-page .hs-card-grid{display:grid!important;grid-template-columns:minmax(0,1fr)!important;gap:12px!important;width:100%!important;align-items:stretch!important;margin:0 0 14px!important}' +
                '.examo-mobile-subscription-page .hs-plan-card{display:flex!important;flex-direction:column!important;width:100%!important;min-width:0!important;min-height:0!important;height:auto!important;background:linear-gradient(145deg,#edf1f6 0%,#e1e7ef 100%)!important;border:1px solid rgba(255,255,255,.98)!important;border-radius:15px!important;box-shadow:0 7px 15px rgba(31,48,73,.11),inset 0 1px 0 rgba(255,255,255,.95)!important;overflow:hidden!important}' +
                '.examo-mobile-subscription-page .hs-plan-card.current{border:2px solid #285b9e!important;box-shadow:0 7px 16px rgba(30,75,135,.16)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head{display:flex!important;flex-direction:row!important;align-items:center!important;justify-content:space-between!important;gap:10px!important;min-height:50px!important;padding:11px 15px!important;border-bottom:1px solid rgba(80,97,122,.10)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head.mint{background:linear-gradient(100deg,#d9f0e9 0%,#c7e7df 100%)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head.lavender{background:linear-gradient(100deg,#e8e2f5 0%,#dcd4ef 100%)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head.sky{background:linear-gradient(100deg,#deebf7 0%,#cfe0f1 100%)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head h3{min-width:0!important;margin:0!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:17px!important;line-height:1.2!important;font-weight:750!important;letter-spacing:-.15px!important;color:#152238!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head svg{width:23px!important;height:23px!important;flex:0 0 auto!important;color:#193b64!important}' +
                '.examo-mobile-subscription-page .hs-plan-body{display:grid!important;grid-template-columns:minmax(0,1fr) minmax(126px,39%)!important;grid-template-areas:"label label" "features features" "price button" "sub button"!important;column-gap:14px!important;row-gap:0!important;align-items:center!important;min-width:0!important;padding:13px 15px 14px!important}' +
                '.examo-mobile-subscription-page .hs-plan-label{grid-area:label!important;margin:0 0 4px!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:14px!important;line-height:1.2!important;font-weight:750!important;color:#1a2739!important}' +
                '.examo-mobile-subscription-page .hs-plan-features{grid-area:features!important;display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr))!important;column-gap:20px!important;row-gap:6px!important;margin:4px 0 13px!important;padding:0 0 0 19px!important;list-style:disc!important;color:#263e5e!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:14px!important;line-height:1.35!important}' +
                '.examo-mobile-subscription-page .hs-plan-features li{min-width:0!important;padding:0!important;margin:0!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-plan-price{grid-area:price!important;align-self:end!important;margin:0!important;color:#101827!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:22px!important;line-height:1.15!important;font-weight:800!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-plan-sub{grid-area:sub!important;align-self:start!important;min-height:0!important;margin:3px 0 0!important;color:#536176!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:13px!important;line-height:1.28!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-btn{grid-area:button!important;align-self:center!important;justify-self:stretch!important;width:100%!important;min-width:0!important;min-height:42px!important;margin:4px 0!important;padding:8px 10px!important;border-radius:9px!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:13px!important;line-height:1.25!important;font-weight:750!important;white-space:normal!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(2){padding:14px 15px!important;margin-top:0!important;border-radius:15px!important;background:linear-gradient(145deg,#edf1f6 0%,#e2e8f0 100%)!important;border:1px solid rgba(255,255,255,.98)!important;box-shadow:0 6px 14px rgba(31,48,73,.10)!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(2) h3{font-size:17px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(2) .hs-panel-text{font-size:14px!important;line-height:1.4!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(2) .hs-link-btn{font-size:14px!important}' +
                '@media(max-width:380px){.examo-mobile-subscription-page .hs-plan-body{grid-template-columns:minmax(0,1fr) minmax(112px,40%)!important;column-gap:9px!important;padding:12px!important}.examo-mobile-subscription-page .hs-plan-features{column-gap:11px!important;font-size:13px!important}.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head{padding:10px 12px!important}.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head h3{font-size:16px!important}}' +
                '@media(max-width:330px){.examo-mobile-subscription-page .hs-plan-body{grid-template-columns:minmax(0,1fr)!important;grid-template-areas:"label" "features" "price" "sub" "button"!important}.examo-mobile-subscription-page .hs-plan-card .hs-btn{margin-top:12px!important}.examo-mobile-subscription-page .hs-plan-features{grid-template-columns:minmax(0,1fr)!important}}' +
                /* Final dashboard polish: left-aligned welcome, stacked cap/books, compact first-screen fit. */
                '.examo-mobile-dashboard-page .dashboard-heading{min-height:76px!important;margin:0 0 8px!important;padding:8px 11px!important;align-items:center!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading>div{flex:0 1 78%!important;width:78%!important;max-width:78%!important;min-width:0!important;margin:0 auto 0 0!important;padding:0!important;transform:none!important;text-align:left!important;align-self:center!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading .eyebrow{margin:0 0 4px!important;font-size:9px!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading h1{font-size:16px!important;line-height:1.15!important;letter-spacing:-.2px!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading p:not(.eyebrow){margin-top:3px!important;font-size:9.5px!important;line-height:1.18!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading:before{content:"🎓"!important;position:absolute!important;right:47px!important;top:7px!important;bottom:auto!important;z-index:0!important;font-size:23px!important;line-height:1!important;letter-spacing:0!important;filter:drop-shadow(0 2px 2px rgba(30,70,130,.12))}' +
                '.examo-mobile-dashboard-page .dashboard-heading:after{content:"📚"!important;position:absolute!important;right:8px!important;bottom:7px!important;top:auto!important;z-index:0!important;font-size:27px!important;line-height:1!important;letter-spacing:0!important;filter:drop-shadow(0 2px 2px rgba(30,70,130,.12))}' +
                '.examo-mobile-dashboard-page .stats-grid{margin-bottom:8px!important;padding:4px 2px!important;border-radius:13px!important}' +
                '.examo-mobile-dashboard-page .stat-card{gap:2px!important;padding:3px 1px!important}' +
                '.examo-mobile-dashboard-page .stat-icon{width:27px!important;height:27px!important;min-width:27px!important;margin-bottom:0!important}' +
                '.examo-mobile-dashboard-page .stat-icon svg{width:16px!important;height:16px!important}' +
                '.examo-mobile-dashboard-page .stat-card span{min-height:18px!important;font-size:8.5px!important}' +
                '.examo-mobile-dashboard-page .stat-card strong{font-size:17px!important}' +
                '.examo-mobile-dashboard-page .stat-card small{font-size:7.8px!important;margin-top:1px!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid{gap:7px!important}' +
                '.examo-mobile-dashboard-page .dashboard-card{border-radius:12px!important}' +
                '.examo-mobile-dashboard-page .card-heading{padding:7px 7px!important;gap:3px!important}' +
                '.examo-mobile-dashboard-page .card-heading h2{font-size:11px!important}' +
                '.examo-mobile-dashboard-page .card-heading p{font-size:8px!important;margin-top:2px!important}' +
                '.examo-mobile-dashboard-page .text-button{font-size:8px!important}' +
                '.examo-mobile-dashboard-page .text-button svg{width:10px!important;height:10px!important}' +
                '.examo-mobile-dashboard-page .deadline-item{gap:4px!important;padding:6px 6px!important}' +
                '.examo-mobile-dashboard-page .deadline-item strong{font-size:8px!important;line-height:1.15!important}' +
                '.examo-mobile-dashboard-page .deadline-item>div>span{font-size:7.5px!important;margin-top:2px!important}' +
                '.examo-mobile-dashboard-page .status-badge{font-size:7px!important;padding:3px 5px!important}' +
                '.examo-mobile-dashboard-page .dashboard-empty{min-height:54px!important;padding:7px 5px!important;font-size:8.5px!important}' +
                '.examo-mobile-dashboard-page .calendar{padding:5px 5px 6px!important}' +
                '.examo-mobile-dashboard-page .calendar-weekdays span{padding:2px 0!important;font-size:7px!important}' +
                '.examo-mobile-dashboard-page .calendar-day{min-height:17px!important;font-size:7.5px!important}' +
                '.examo-mobile-dashboard-page .calendar-legend{margin-top:5px!important;padding:5px 6px!important;font-size:7.5px!important}' +
                '.examo-mobile-dashboard-page .schedule-list{padding:4px 6px!important}' +
                '.examo-mobile-dashboard-page .schedule-item{gap:5px!important;padding:6px 5px!important}' +
                '.examo-mobile-dashboard-page .schedule-date{min-width:29px!important;padding:4px 2px!important;font-size:8.5px!important}' +
                '.examo-mobile-dashboard-page .schedule-item strong{font-size:9px!important}' +
                '.examo-mobile-dashboard-page .schedule-item span{font-size:7.5px!important;margin-top:2px!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card{margin-top:8px!important;padding:8px 8px!important;border-radius:13px!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card h2{font-size:11px!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card p{font-size:8px!important;margin-top:2px!important}' +
                '.examo-mobile-dashboard-page .quick-actions{margin-top:6px!important;gap:5px!important}' +
                '.examo-mobile-dashboard-page .quick-actions button{min-height:31px!important;padding:4px 3px!important;font-size:7.5px!important;gap:3px!important}' +
                '.examo-mobile-dashboard-page .quick-actions button svg{width:12px!important;height:12px!important}' +
                '@media(max-height:820px) and (max-width:600px){.examo-mobile-dashboard-page .dashboard-heading{min-height:70px!important}.examo-mobile-dashboard-page .stats-grid{margin-bottom:6px!important}.examo-mobile-dashboard-page .dashboard-grid{gap:6px!important}.examo-mobile-dashboard-page .dashboard-empty{min-height:44px!important}.examo-mobile-dashboard-page .calendar{padding-top:4px!important;padding-bottom:4px!important}.examo-mobile-dashboard-page .calendar-day{min-height:15px!important}.examo-mobile-dashboard-page .quick-actions-card{margin-top:6px!important;padding:7px!important}.examo-mobile-dashboard-page .quick-actions button{min-height:29px!important}}' +

                /* Dashboard fallback: target the unique dashboard classes directly,
                   so the two-column layout does not depend on a JS scope class. */
                '.dashboard-grid{display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:10px!important;width:100%!important;min-width:0!important;align-items:stretch!important}' +
                '.dashboard-grid>.dashboard-card{grid-column:auto!important;grid-row:auto!important;min-width:0!important;width:auto!important;max-width:100%!important;overflow:hidden!important}' +
                '.dashboard-grid>.dashboard-card:nth-child(3),.dashboard-grid>.dashboard-card:nth-child(4){grid-column:auto!important}' +
                '.quick-actions-card{display:block!important;width:100%!important;max-width:100%!important;min-width:0!important;margin-top:11px!important}' +
                '.quick-actions{display:grid!important;grid-template-columns:repeat(3,minmax(0,1fr))!important;width:100%!important;gap:6px!important}' +
                '.quick-actions button{min-width:0!important;justify-content:center!important;white-space:nowrap!important}' +
                '@media(max-width:360px){.dashboard-grid{grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:8px!important}.dashboard-grid>.dashboard-card{min-width:0!important}.dashboard-grid .card-heading{padding:8px 7px!important}.dashboard-grid .card-heading h2{font-size:11px!important}.dashboard-grid .card-heading p{font-size:8px!important}.dashboard-grid .text-button{font-size:8px!important}.dashboard-grid .deadline-item{gap:4px!important;padding:7px 6px!important}.dashboard-grid .deadline-item strong{font-size:8px!important}.dashboard-grid .deadline-item>div>span{font-size:7.5px!important}.dashboard-grid .status-badge{font-size:7px!important;padding:3px 5px!important}.dashboard-grid .calendar{padding:7px 5px!important}.dashboard-grid .calendar-day{min-height:19px!important;font-size:7.5px!important}.quick-actions button{font-size:8px!important}}' +

                /* Dashboard: compact Examo study-overview layout, native WebView only. */
                '.examo-mobile-dashboard-page{width:100%!important;max-width:100%!important;min-width:0!important;padding:0!important;color:#15284a!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading{position:relative!important;display:flex!important;align-items:center!important;justify-content:flex-start!important;min-height:91px!important;margin:0 0 10px!important;padding:11px 14px!important;overflow:hidden!important;background:linear-gradient(112deg,#edf5ff 0%,#e4efff 62%,#cfe3ff 100%)!important;border:1px solid #c4dbfb!important;border-radius:15px!important;box-shadow:0 4px 10px rgba(34,82,145,.09)!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading>div{position:relative!important;z-index:1!important;width:100%!important;max-width:79%!important;min-width:0!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading:after{content:"🎓📚"!important;position:absolute!important;right:10px!important;bottom:12px!important;z-index:0!important;font-size:32px!important;line-height:1!important;letter-spacing:-8px!important;white-space:nowrap!important;filter:drop-shadow(0 3px 2px rgba(30,70,130,.12))}' +
                '.examo-mobile-dashboard-page .dashboard-heading .eyebrow{margin:0 0 5px!important;color:#1762d5!important;font-size:10px!important;font-weight:500!important;line-height:1.2!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading h1{margin:0!important;color:#10264a!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:18px!important;font-weight:600!important;line-height:1.2!important;letter-spacing:-.25px!important;white-space:normal!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading p:not(.eyebrow){margin:5px 0 0!important;color:#536b91!important;font-size:11px!important;font-weight:400!important;line-height:1.25!important}' +
                '.examo-mobile-dashboard-page .stats-grid{display:grid!important;grid-template-columns:repeat(4,minmax(0,1fr))!important;gap:0!important;width:100%!important;margin:0 0 11px!important;padding:7px 3px!important;background:linear-gradient(145deg,#ffffff 0%,#f5f9ff 100%)!important;border:1px solid #d1e0f3!important;border-radius:15px!important;box-shadow:0 5px 12px rgba(30,68,120,.09)!important}' +
                '.examo-mobile-dashboard-page .stat-card{display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;gap:3px!important;min-width:0!important;margin:0!important;padding:5px 2px!important;border:0!important;border-right:1px solid #cfdef1!important;border-radius:0!important;background:transparent!important;box-shadow:none!important;text-align:center!important}' +
                '.examo-mobile-dashboard-page .stat-card:last-child{border-right:0!important}' +
                '.examo-mobile-dashboard-page .stat-icon{display:flex!important;align-items:center!important;justify-content:center!important;width:32px!important;height:32px!important;min-width:32px!important;margin:0 0 1px!important;border-radius:50%!important}' +
                '.examo-mobile-dashboard-page .stat-icon svg{width:18px!important;height:18px!important}' +
                '.examo-mobile-dashboard-page .stat-card>div:last-child{display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;min-width:0!important;width:100%!important}' +
                '.examo-mobile-dashboard-page .stat-card span{display:block!important;min-height:22px!important;margin:0!important;color:#233956!important;font-size:9px!important;font-weight:500!important;line-height:1.12!important;text-align:center!important;white-space:normal!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-dashboard-page .stat-card strong{display:block!important;margin:0!important;color:#13284a!important;font-size:19px!important;font-weight:700!important;line-height:1.08!important}' +
                '.examo-mobile-dashboard-page .stat-card small{display:block!important;margin:2px 0 0!important;color:#637b9e!important;font-size:8.5px!important;font-weight:400!important;line-height:1.12!important;text-align:center!important;white-space:normal!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid{display:grid!important;grid-template-columns:minmax(0,1fr) minmax(0,1fr)!important;gap:10px!important;width:100%!important;min-width:0!important;align-items:stretch!important}' +
                '.examo-mobile-dashboard-page .dashboard-card{display:flex!important;flex-direction:column!important;min-width:0!important;max-width:100%!important;overflow:hidden!important;background:linear-gradient(145deg,#ffffff 0%,#f7faff 100%)!important;border:1px solid #d5e2f3!important;border-radius:14px!important;box-shadow:0 5px 12px rgba(35,70,120,.09)!important}' +
                '.examo-mobile-dashboard-page .dashboard-card:nth-child(2){background:linear-gradient(145deg,#ffffff 0%,#f6f7ff 100%)!important;border-color:#dcdcf4!important}' +
                '.examo-mobile-dashboard-page .dashboard-card:nth-child(3),.examo-mobile-dashboard-page .dashboard-card:nth-child(4){grid-column:auto!important}' +
                '.examo-mobile-dashboard-page .card-heading{display:flex!important;align-items:center!important;justify-content:space-between!important;gap:5px!important;min-width:0!important;padding:10px 9px!important;border-bottom:1px solid #e3ebf6!important}' +
                '.examo-mobile-dashboard-page .card-heading>div{min-width:0!important}' +
                '.examo-mobile-dashboard-page .card-heading h2{margin:0!important;color:#172c4c!important;font-size:12px!important;font-weight:600!important;line-height:1.2!important;letter-spacing:-.1px!important}' +
                '.examo-mobile-dashboard-page .card-heading p{margin:4px 0 0!important;color:#647b9c!important;font-size:9px!important;font-weight:400!important;line-height:1.2!important}' +
                '.examo-mobile-dashboard-page .card-heading>svg{width:17px!important;height:17px!important;flex:0 0 auto!important;color:#193b64!important}' +
                '.examo-mobile-dashboard-page .text-button{display:inline-flex!important;align-items:center!important;justify-content:center!important;gap:2px!important;min-width:0!important;padding:2px 0!important;color:#0960e8!important;font-size:9px!important;font-weight:600!important;white-space:nowrap!important}' +
                '.examo-mobile-dashboard-page .text-button svg{width:12px!important;height:12px!important}' +
                '.examo-mobile-dashboard-page .exam-list,.examo-mobile-dashboard-page .form-deadline-list,.examo-mobile-dashboard-page .schedule-list{min-width:0!important}' +
                '.examo-mobile-dashboard-page .exam-list-item,.examo-mobile-dashboard-page .deadline-item{display:flex!important;align-items:center!important;gap:6px!important;min-width:0!important;padding:8px 8px!important;border-bottom:1px solid #e8eef7!important}' +
                '.examo-mobile-dashboard-page .list-icon{display:none!important}' +
                '.examo-mobile-dashboard-page .list-main,.examo-mobile-dashboard-page .deadline-item>div{flex:1!important;min-width:0!important}' +
                '.examo-mobile-dashboard-page .list-main strong,.examo-mobile-dashboard-page .deadline-item strong{display:block!important;color:#213855!important;font-size:9.5px!important;font-weight:600!important;line-height:1.2!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-dashboard-page .list-main span,.examo-mobile-dashboard-page .deadline-item>div>span{display:block!important;margin:3px 0 0!important;color:#667e9e!important;font-size:8.5px!important;font-weight:400!important;line-height:1.15!important}' +
                '.examo-mobile-dashboard-page .small-status,.examo-mobile-dashboard-page .status-badge{flex:0 0 auto!important;padding:4px 7px!important;border-radius:999px!important;font-size:8px!important;font-weight:500!important;white-space:nowrap!important}' +
                '.examo-mobile-dashboard-page .dashboard-empty{display:flex!important;align-items:center!important;justify-content:center!important;min-height:78px!important;padding:12px 8px!important;color:#637b9e!important;font-size:10px!important;font-weight:400!important;text-align:center!important}' +
                '.examo-mobile-dashboard-page .calendar{min-width:0!important;padding:8px 7px 9px!important}' +
                '.examo-mobile-dashboard-page .calendar-weekdays,.examo-mobile-dashboard-page .calendar-days{grid-template-columns:repeat(7,minmax(0,1fr))!important;gap:1px!important}' +
                '.examo-mobile-dashboard-page .calendar-weekdays span{padding:3px 0!important;color:#7186a4!important;font-size:8px!important;font-weight:500!important}' +
                '.examo-mobile-dashboard-page .calendar-day{min-height:22px!important;width:100%!important;border-radius:50%!important;color:#344c6d!important;font-size:8.5px!important;font-weight:400!important}' +
                '.examo-mobile-dashboard-page .calendar-day.today{background:#1764ed!important;color:#fff!important;font-weight:600!important}' +
                '.examo-mobile-dashboard-page .calendar-legend{margin-top:8px!important;padding:6px 7px!important;border-radius:8px!important;background:#edf4ff!important;color:#577196!important;font-size:8.5px!important;font-weight:400!important}' +
                '.examo-mobile-dashboard-page .calendar-legend span{width:6px!important;height:6px!important;flex:0 0 6px!important;background:#1764ed!important}' +
                '.examo-mobile-dashboard-page .schedule-list{padding:6px 8px!important}' +
                '.examo-mobile-dashboard-page .schedule-item{display:flex!important;align-items:center!important;gap:7px!important;padding:9px 7px!important;border:0!important;border-radius:9px!important;background:#edf4ff!important}' +
                '.examo-mobile-dashboard-page .schedule-date{display:flex!important;align-items:center!important;justify-content:center!important;min-width:34px!important;padding:5px 3px!important;border-right:1px solid #c7d9f2!important;color:#1261df!important;font-size:10px!important;font-weight:600!important;line-height:1.15!important}' +
                '.examo-mobile-dashboard-page .schedule-item>div:last-child{min-width:0!important}' +
                '.examo-mobile-dashboard-page .schedule-item strong{display:block!important;color:#1b3457!important;font-size:10px!important;font-weight:600!important;line-height:1.2!important}' +
                '.examo-mobile-dashboard-page .schedule-item span{display:block!important;margin:4px 0 0!important;color:#627b9d!important;font-size:8.5px!important;font-weight:400!important;line-height:1.15!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card{display:block!important;min-width:0!important;margin:11px 0 0!important;padding:11px 10px!important;background:linear-gradient(145deg,#edf4ff 0%,#e4eefc 100%)!important;border:1px solid #cbdcf4!important;border-radius:15px!important;box-shadow:0 5px 12px rgba(35,70,120,.09)!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card h2{margin:0!important;color:#1a355c!important;font-size:13px!important;font-weight:600!important;line-height:1.2!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card p{margin:3px 0 0!important;color:#637b9e!important;font-size:9px!important;font-weight:400!important;line-height:1.2!important}' +
                '.examo-mobile-dashboard-page .quick-actions{display:grid!important;grid-template-columns:repeat(3,minmax(0,1fr))!important;gap:6px!important;width:100%!important;margin:9px 0 0!important}' +
                '.examo-mobile-dashboard-page .quick-actions button{display:flex!important;align-items:center!important;justify-content:center!important;gap:4px!important;min-width:0!important;min-height:36px!important;padding:6px 4px!important;border:1px solid #c8d8f0!important;border-radius:9px!important;background:#fff!important;color:#253e60!important;font-size:8.5px!important;font-weight:500!important;line-height:1.15!important;white-space:nowrap!important;box-shadow:0 2px 4px rgba(25,60,110,.05)!important}' +
                '.examo-mobile-dashboard-page .quick-actions button svg{width:14px!important;height:14px!important;flex:0 0 auto!important;color:#173d6c!important}' +
                '.examo-mobile-dashboard-page .quick-icon{width:14px!important;height:14px!important;flex:0 0 14px!important;font-size:10px!important}' +
                '.examo-mobile-dashboard-page .error-box{margin-bottom:9px!important;padding:8px 10px!important;font-size:10px!important}' +
                '@media(max-width:360px){.examo-mobile-dashboard-page .dashboard-heading{min-height:84px!important;padding:9px 11px!important}.examo-mobile-dashboard-page .dashboard-heading>div{max-width:70%!important}.examo-mobile-dashboard-page .dashboard-heading h1{font-size:16px!important}.examo-mobile-dashboard-page .dashboard-heading p:not(.eyebrow){font-size:10px!important}.examo-mobile-dashboard-page .stat-card span{font-size:8px!important}.examo-mobile-dashboard-page .stat-card small{font-size:7.5px!important}.examo-mobile-dashboard-page .dashboard-grid{grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:8px!important}.examo-mobile-dashboard-page .dashboard-card{min-width:0!important}.examo-mobile-dashboard-page .card-heading{padding:8px 7px!important}.examo-mobile-dashboard-page .card-heading h2{font-size:11px!important}.examo-mobile-dashboard-page .card-heading p{font-size:8px!important}.examo-mobile-dashboard-page .text-button{font-size:8px!important}.examo-mobile-dashboard-page .deadline-item{gap:4px!important;padding:7px 6px!important}.examo-mobile-dashboard-page .deadline-item strong{font-size:8px!important}.examo-mobile-dashboard-page .deadline-item>div>span{font-size:7.5px!important}.examo-mobile-dashboard-page .status-badge{font-size:7px!important;padding:3px 5px!important}.examo-mobile-dashboard-page .calendar{padding:7px 5px!important}.examo-mobile-dashboard-page .calendar-day{min-height:19px!important;font-size:7.5px!important}.examo-mobile-dashboard-page .quick-actions button{font-size:8px!important}}' +

                /* Final override layer: ensure these rules win over earlier
                   dashboard defaults in the native WebView stylesheet. */
                '.examo-mobile-dashboard-page{zoom:.88!important;width:113.64%!important;max-width:none!important;min-width:0!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading{min-height:66px!important;margin:0 0 7px!important;padding:6px 8px!important;align-items:center!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading>div{flex:0 1 76%!important;width:76%!important;max-width:76%!important;min-width:0!important;margin:0 auto 0 0!important;padding:0!important;text-align:left!important;transform:none!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading .eyebrow{margin:0 0 3px!important;font-size:9px!important;line-height:1.1!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading h1{font-size:16px!important;font-weight:600!important;line-height:1.12!important;letter-spacing:-.2px!important;margin:0!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading p:not(.eyebrow){margin:3px 0 0!important;font-size:9px!important;line-height:1.15!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading:before{content:"🎓"!important;position:absolute!important;display:block!important;right:43px!important;top:4px!important;bottom:auto!important;z-index:2!important;font-size:21px!important;line-height:1!important;letter-spacing:0!important;filter:drop-shadow(0 2px 1px rgba(20,50,90,.12))}' +
                '.examo-mobile-dashboard-page .dashboard-heading:after{content:"📚"!important;position:absolute!important;display:block!important;right:5px!important;top:auto!important;bottom:4px!important;z-index:1!important;font-size:25px!important;line-height:1!important;letter-spacing:0!important;filter:drop-shadow(0 2px 1px rgba(20,50,90,.12))}' +
                '.examo-mobile-dashboard-page .stats-grid{margin:0 0 7px!important;padding:3px 2px!important}' +
                '.examo-mobile-dashboard-page .stat-card{gap:1px!important;padding:2px 1px!important}' +
                '.examo-mobile-dashboard-page .stat-icon{width:25px!important;height:25px!important;min-width:25px!important;margin:0!important}' +
                '.examo-mobile-dashboard-page .stat-icon svg{width:15px!important;height:15px!important}' +
                '.examo-mobile-dashboard-page .stat-card span{min-height:16px!important;font-size:8px!important;line-height:1.05!important}' +
                '.examo-mobile-dashboard-page .stat-card strong{font-size:16px!important;line-height:1!important}' +
                '.examo-mobile-dashboard-page .stat-card small{margin-top:0!important;font-size:7.3px!important;line-height:1.05!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid{gap:6px!important;align-items:stretch!important}' +
                '.examo-mobile-dashboard-page .dashboard-card{border-radius:11px!important}' +
                '.examo-mobile-dashboard-page .card-heading{padding:6px 6px!important;gap:2px!important}' +
                '.examo-mobile-dashboard-page .card-heading h2{font-size:10px!important;line-height:1.12!important}' +
                '.examo-mobile-dashboard-page .card-heading p{font-size:7.2px!important;line-height:1.12!important;margin-top:2px!important}' +
                '.examo-mobile-dashboard-page .text-button{font-size:7.5px!important;gap:1px!important}' +
                '.examo-mobile-dashboard-page .text-button svg{width:9px!important;height:9px!important}' +
                '.examo-mobile-dashboard-page .deadline-item{padding:5px 5px!important;gap:3px!important}' +
                '.examo-mobile-dashboard-page .deadline-item strong{font-size:7.3px!important;line-height:1.1!important}' +
                '.examo-mobile-dashboard-page .deadline-item>div>span{font-size:7px!important;margin-top:1px!important}' +
                '.examo-mobile-dashboard-page .status-badge{padding:2px 4px!important;font-size:6.5px!important}' +
                '.examo-mobile-dashboard-page .dashboard-empty{min-height:40px!important;padding:5px 4px!important;font-size:8px!important}' +
                '.examo-mobile-dashboard-page .calendar{padding:4px 4px 5px!important}' +
                '.examo-mobile-dashboard-page .calendar-weekdays,.examo-mobile-dashboard-page .calendar-days{gap:0!important}' +
                '.examo-mobile-dashboard-page .calendar-weekdays span{padding:1px 0!important;font-size:6.8px!important}' +
                '.examo-mobile-dashboard-page .calendar-day{min-height:14px!important;font-size:7px!important}' +
                '.examo-mobile-dashboard-page .calendar-legend{margin-top:4px!important;padding:4px 5px!important;font-size:7px!important}' +
                '.examo-mobile-dashboard-page .schedule-list{padding:3px 5px!important}' +
                '.examo-mobile-dashboard-page .schedule-item{padding:5px 4px!important;gap:4px!important}' +
                '.examo-mobile-dashboard-page .schedule-date{min-width:27px!important;padding:3px 1px!important;font-size:8px!important}' +
                '.examo-mobile-dashboard-page .schedule-item strong{font-size:8.5px!important}' +
                '.examo-mobile-dashboard-page .schedule-item span{font-size:7px!important;margin-top:1px!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card{margin:7px 0 0!important;padding:7px!important;border-radius:11px!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card h2{font-size:10px!important;line-height:1.1!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card p{margin-top:2px!important;font-size:7.5px!important;line-height:1.1!important}' +
                '.examo-mobile-dashboard-page .quick-actions{margin-top:5px!important;gap:4px!important}' +
                '.examo-mobile-dashboard-page .quick-actions button{min-height:27px!important;padding:3px 2px!important;font-size:7px!important;line-height:1.05!important;gap:2px!important}' +
                '.examo-mobile-dashboard-page .quick-actions button svg{width:11px!important;height:11px!important}' +
                '.examo-mobile-dashboard-page .quick-icon{width:11px!important;height:11px!important;flex:0 0 11px!important;font-size:8px!important}' +
                '@media(max-width:360px){.examo-mobile-dashboard-page{zoom:.84!important;width:119.05%!important}.examo-mobile-dashboard-page .dashboard-heading>div{max-width:73%!important;width:73%!important}.examo-mobile-dashboard-page .dashboard-grid{gap:5px!important}}' +
                '@media(max-height:860px) and (max-width:600px){.examo-mobile-dashboard-page{zoom:.84!important;width:119.05%!important}}' +

                /* Correct final mobile layout: never expand beyond the device viewport.
                   Earlier zoom + 113% width caused the right edge to be clipped. */
                '.examo-mobile-dashboard-page{zoom:1!important;width:100%!important;max-width:100%!important;min-width:0!important;margin:0!important;box-sizing:border-box!important;overflow-x:hidden!important}' +
                '.examo-mobile-dashboard-page *{box-sizing:border-box!important;min-width:0}' +
                '.examo-mobile-dashboard-page .dashboard-heading{width:100%!important;max-width:100%!important;min-height:72px!important;margin:0 0 7px!important;padding:7px 9px!important;overflow:hidden!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading>div{width:75%!important;max-width:75%!important;flex:0 1 75%!important;margin:0 auto 0 0!important;padding:0!important;text-align:left!important;transform:none!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading .eyebrow{font-size:9px!important;margin:0 0 3px!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading h1{font-size:15px!important;line-height:1.13!important;font-weight:600!important;margin:0!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading p:not(.eyebrow){font-size:9px!important;line-height:1.16!important;margin:3px 0 0!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading:before{content:"🎓"!important;position:absolute!important;display:block!important;right:49px!important;top:5px!important;bottom:auto!important;font-size:21px!important;line-height:1!important;letter-spacing:0!important;z-index:2!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading:after{content:"📚"!important;position:absolute!important;display:block!important;right:7px!important;top:auto!important;bottom:5px!important;font-size:24px!important;line-height:1!important;letter-spacing:0!important;z-index:1!important}' +
                '.examo-mobile-dashboard-page .stats-grid{display:grid!important;grid-template-columns:repeat(4,minmax(0,1fr))!important;width:100%!important;max-width:100%!important;margin:0 0 7px!important;padding:4px 2px!important;gap:0!important}' +
                '.examo-mobile-dashboard-page .stat-card{display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;gap:2px!important;padding:3px 1px!important;min-width:0!important;overflow:hidden!important}' +
                '.examo-mobile-dashboard-page .stat-icon{width:25px!important;height:25px!important;min-width:25px!important;margin:0!important}' +
                '.examo-mobile-dashboard-page .stat-icon svg{width:15px!important;height:15px!important}' +
                '.examo-mobile-dashboard-page .stat-card span{min-height:17px!important;font-size:8px!important;line-height:1.1!important;white-space:normal!important}' +
                '.examo-mobile-dashboard-page .stat-card strong{font-size:16px!important;line-height:1.05!important}' +
                '.examo-mobile-dashboard-page .stat-card small{font-size:7.5px!important;line-height:1.1!important;margin:0!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid{display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:7px!important;width:100%!important;max-width:100%!important;min-width:0!important;margin:0!important;align-items:stretch!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid>.dashboard-card{width:100%!important;max-width:100%!important;min-width:0!important;grid-column:auto!important;overflow:hidden!important;border-radius:12px!important}' +
                '.examo-mobile-dashboard-page .card-heading{display:flex!important;align-items:flex-start!important;justify-content:space-between!important;gap:3px!important;padding:7px 6px!important}' +
                '.examo-mobile-dashboard-page .card-heading h2{font-size:10px!important;line-height:1.15!important;font-weight:600!important}' +
                '.examo-mobile-dashboard-page .card-heading p{font-size:7.5px!important;line-height:1.13!important;margin:2px 0 0!important}' +
                '.examo-mobile-dashboard-page .text-button{font-size:7.5px!important;gap:1px!important;white-space:nowrap!important}' +
                '.examo-mobile-dashboard-page .text-button svg{width:9px!important;height:9px!important}' +
                '.examo-mobile-dashboard-page .deadline-item{padding:5px 5px!important;gap:3px!important}' +
                '.examo-mobile-dashboard-page .deadline-item strong{font-size:7.2px!important;line-height:1.12!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-dashboard-page .deadline-item>div>span{font-size:7px!important;line-height:1.1!important;margin-top:2px!important}' +
                '.examo-mobile-dashboard-page .status-badge{font-size:6.8px!important;padding:2px 4px!important}' +
                '.examo-mobile-dashboard-page .dashboard-empty{min-height:42px!important;padding:6px 4px!important;font-size:8px!important}' +
                '.examo-mobile-dashboard-page .calendar{padding:5px 4px!important}' +
                '.examo-mobile-dashboard-page .calendar-weekdays,.examo-mobile-dashboard-page .calendar-days{grid-template-columns:repeat(7,minmax(0,1fr))!important;gap:0!important}' +
                '.examo-mobile-dashboard-page .calendar-weekdays span{font-size:7px!important;padding:2px 0!important}' +
                '.examo-mobile-dashboard-page .calendar-day{min-height:16px!important;font-size:7.3px!important}' +
                '.examo-mobile-dashboard-page .calendar-legend{margin-top:5px!important;padding:4px 5px!important;font-size:7.2px!important}' +
                '.examo-mobile-dashboard-page .schedule-list{padding:4px 5px!important}' +
                '.examo-mobile-dashboard-page .schedule-item{padding:5px 4px!important;gap:4px!important}' +
                '.examo-mobile-dashboard-page .schedule-date{min-width:27px!important;padding:3px 1px!important;font-size:8px!important}' +
                '.examo-mobile-dashboard-page .schedule-item strong{font-size:8.5px!important}' +
                '.examo-mobile-dashboard-page .schedule-item span{font-size:7px!important;margin-top:2px!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card{display:block!important;width:100%!important;max-width:100%!important;min-width:0!important;margin:7px 0 0!important;padding:8px!important;border-radius:12px!important;overflow:hidden!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card h2{font-size:10px!important;line-height:1.15!important;font-weight:600!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card p{font-size:7.5px!important;margin:2px 0 0!important;line-height:1.15!important}' +
                '.examo-mobile-dashboard-page .quick-actions{display:grid!important;grid-template-columns:repeat(3,minmax(0,1fr))!important;width:100%!important;max-width:100%!important;min-width:0!important;gap:5px!important;margin:6px 0 0!important}' +
                '.examo-mobile-dashboard-page .quick-actions button{display:flex!important;align-items:center!important;justify-content:center!important;min-width:0!important;width:100%!important;min-height:30px!important;padding:4px 2px!important;gap:3px!important;font-size:7.5px!important;line-height:1.1!important;white-space:nowrap!important;overflow:hidden!important}' +
                '.examo-mobile-dashboard-page .quick-actions button svg{width:12px!important;height:12px!important;flex:0 0 12px!important}' +
                '.examo-mobile-dashboard-page .quick-icon{width:12px!important;height:12px!important;flex:0 0 12px!important}' +
                '@media(max-width:360px){.examo-mobile-dashboard-page .dashboard-heading>div{width:72%!important;max-width:72%!important;flex-basis:72%!important}.examo-mobile-dashboard-page .dashboard-grid{gap:5px!important}.examo-mobile-dashboard-page .card-heading h2{font-size:9.5px!important}.examo-mobile-dashboard-page .deadline-item strong{font-size:6.8px!important}.examo-mobile-dashboard-page .quick-actions button{font-size:6.8px!important;gap:2px!important}}' +
                '@media(max-height:800px) and (max-width:600px){.examo-mobile-dashboard-page .dashboard-heading{min-height:65px!important}.examo-mobile-dashboard-page .stats-grid{margin-bottom:5px!important}.examo-mobile-dashboard-page .dashboard-grid{gap:5px!important}.examo-mobile-dashboard-page .calendar-day{min-height:14px!important}.examo-mobile-dashboard-page .quick-actions-card{margin-top:5px!important;padding:6px!important}.examo-mobile-dashboard-page .quick-actions button{min-height:27px!important}}' +
                '}';
              (document.head || document.documentElement).appendChild(css);

              function decorateDashboard() {
                var dashboardHeading = document.querySelector('.dashboard-heading');
                var dashboardGrid = document.querySelector('.dashboard-grid');
                if (!dashboardHeading || !dashboardGrid) return;
                var dashboardPage = dashboardHeading.closest('.page') || dashboardGrid.parentElement;
                if (dashboardPage) dashboardPage.classList.add('examo-mobile-dashboard-page');
                // Set the mobile layout inline as a fallback for WebViews whose
                // responsive viewport/class scoping differs between devices.
                if (window.innerWidth <= 600) {
                  dashboardGrid.style.setProperty('display','grid','important');
                  dashboardGrid.style.setProperty('grid-template-columns','repeat(2,minmax(0,1fr))','important');
                  dashboardGrid.style.setProperty('gap','7px','important');
                  var headingContent = dashboardHeading.querySelector('div');
                  if (headingContent) {
                    headingContent.style.setProperty('margin','0 auto 0 0','important');
                    headingContent.style.setProperty('padding','0','important');
                    headingContent.style.setProperty('transform','none','important');
                    headingContent.style.setProperty('text-align','left','important');
                  }
                  dashboardGrid.style.setProperty('width','100%','important');
                  dashboardGrid.style.setProperty('min-width','0','important');
                  Array.prototype.forEach.call(dashboardGrid.children,function(card) {
                    card.style.setProperty('grid-column','auto','important');
                    card.style.setProperty('min-width','0','important');
                    card.style.setProperty('width','auto','important');
                  });
                  var quick = document.querySelector('.quick-actions');
                  if (quick) {
                    quick.style.setProperty('display','grid','important');
                    quick.style.setProperty('grid-template-columns','repeat(3,minmax(0,1fr))','important');
                    quick.style.setProperty('width','100%','important');
                    quick.style.setProperty('gap','6px','important');
                  }
                }
              }

              function decorateDataTables() {
                if (window.innerWidth > 600) return;
                var subscriptionPlan = document.querySelector('.hs-page .hs-plan-card');
                if (subscriptionPlan) {
                  var subscriptionPage = subscriptionPlan.closest('.hs-page');
                  if (subscriptionPage) subscriptionPage.classList.add('examo-mobile-subscription-page');
                }
                document.querySelectorAll('table.data-table').forEach(function(table) {
                  var headers = Array.from(table.querySelectorAll('thead th')).map(function(th) {
                    return (th.innerText || th.textContent || '').trim();
                  });
                  if (!headers.length) return;

                  var normalized = headers.map(function(h) { return h.toLowerCase().replace(/\\s+/g, ' ').trim(); });
                  var isJobRecordTable = normalized[0] === 'exam name' &&
                    (normalized.indexOf('start date') >= 0 || normalized.indexOf('date') >= 0);
                  var isLatestJobsTable = normalized[0] === 'exam name' &&
                    normalized.indexOf('start date') >= 0 &&
                    normalized.indexOf('last date') >= 0 &&
                    normalized.indexOf('details') >= 0;
                  var isJobPostsTable = normalized[0] === 'exam name' &&
                    normalized.indexOf('date') >= 0 &&
                    normalized.indexOf('status') >= 0 &&
                    normalized.indexOf('details') >= 0;
                  if (isLatestJobsTable) table.classList.add('examo-mobile-latest-jobs-table');
                  if (isJobPostsTable) table.classList.add('examo-mobile-job-posts-table');
                  var isExamFormsTable = normalized[0] === 'exam name' &&
                    normalized.indexOf('start date') >= 0 &&
                    normalized.indexOf('end date') >= 0 &&
                    normalized.indexOf('status') >= 0 &&
                    normalized.indexOf('details') >= 0 &&
                    normalized.indexOf('link') >= 0;
                  if (isJobRecordTable) table.classList.add('examo-mobile-job-table');
                  if (isExamFormsTable) {
                    table.classList.add('examo-mobile-exam-form-table');
                    var cardWrapper = table.closest('.data-card');
                    if (cardWrapper) cardWrapper.classList.add('examo-mobile-exam-form-wrapper');
                  }

                  table.querySelectorAll('tbody tr').forEach(function(row) {
                    row.classList.add('examo-mobile-record');
                    var cells = Array.from(row.children).filter(function(el) {
                      return el.tagName === 'TD';
                    });
                    var titleAssigned = false;
                    cells.forEach(function(cell, index) {
                      var label = headers[index] || '';
                      var labelKey = normalized[index] || '';
                      cell.setAttribute('data-label', label);
                      if (cell.classList.contains('select-column')) return;
                      if (!titleAssigned && /^(exam name|name|subject|student|user|job|title)$/i.test(label)) {
                        cell.classList.add('examo-mobile-title');
                        titleAssigned = true;
                      }
                      if (isJobRecordTable) {
                        if (/^(start date|last date|date)$/i.test(labelKey)) cell.classList.add('examo-mobile-date');
                        if (labelKey === 'status') cell.classList.add('examo-mobile-status');
                        if (labelKey === 'details') cell.classList.add('examo-mobile-details');
                      }
                      if (isExamFormsTable) {
                        if (/^(start date|end date)$/i.test(labelKey)) cell.classList.add('examo-mobile-date');
                        if (labelKey === 'status') cell.classList.add('examo-mobile-status');
                        if (labelKey === 'details') cell.classList.add('examo-mobile-details');
                        if (labelKey === 'link') cell.classList.add('examo-mobile-link');
                      }
                      if (/^(actions?|action)$/i.test(labelKey) || cell.classList.contains('actions-column')) {
                        cell.classList.add('examo-mobile-actions');
                      }
                    });
                    if (!titleAssigned) {
                      var first = cells.find(function(cell) { return !cell.classList.contains('select-column'); });
                      if (first) first.classList.add('examo-mobile-title');
                    }
                  });
                  table.classList.add('examo-mobile-card-table');
                });
              }

              function getSidebar() { return document.querySelector('.app-layout .sidebar') || document.querySelector('.sidebar'); }
              function getBackdrop() {
                var el = document.querySelector('.examo-mobile-backdrop');
                if (!el) {
                  el = document.createElement('div');
                  el.className = 'examo-mobile-backdrop';
                  document.body.appendChild(el);
                  el.addEventListener('click', closeMenu);
                }
                return el;
              }
              function openMenu() {
                var side = getSidebar();
                if (!side) return;
                side.classList.add('examo-mobile-open');
                side.style.setProperty('transform','translate3d(0,0,0)','important');
                var back = getBackdrop();
                back.classList.add('open');
                back.style.setProperty('display','block','important');
                document.body.style.overflow = 'hidden';
                var menu = document.querySelector('.mobile-menu');
                if (menu) menu.setAttribute('aria-expanded','true');
              }
              function closeMenu() {
                var side = getSidebar();
                if (side) {
                  side.classList.remove('examo-mobile-open');
                  side.style.setProperty('transform','translate3d(-105%,0,0)','important');
                }
                var back = document.querySelector('.examo-mobile-backdrop');
                if (back) {
                  back.classList.remove('open');
                  back.style.setProperty('display','none','important');
                }
                document.body.style.overflow = '';
                var menu = document.querySelector('.mobile-menu');
                if (menu) menu.setAttribute('aria-expanded','false');
              }
              function bindMenu() {
                var menu = document.querySelector('.mobile-menu');
                if (!menu || menu.dataset.examoClickBound === '1') return;
                menu.dataset.examoClickBound = '1';
                menu.setAttribute('role','button');
                menu.setAttribute('aria-label','Open navigation menu');
                menu.setAttribute('aria-expanded','false');
                menu.setAttribute('tabindex','0');
                menu.style.setProperty('pointer-events','auto','important');
                menu.onclick = function(e) {
                  e.preventDefault();
                  e.stopPropagation();
                  var side = getSidebar();
                  if (side && side.classList.contains('examo-mobile-open')) closeMenu();
                  else openMenu();
                };
                menu.onkeydown = function(e) {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    var side = getSidebar();
                    if (side && side.classList.contains('examo-mobile-open')) closeMenu();
                    else openMenu();
                  }
                };
              }

              function bindSidebarSwipe() {
                var side = getSidebar();
                if (!side || side.dataset.examoSwipeCloseBound === '1') return;
                side.dataset.examoSwipeCloseBound = '1';
                var startX = 0, startY = 0, lastX = 0, lastY = 0;
                var horizontal = false;
                side.addEventListener('touchstart', function(e) {
                  if (!e.touches || !e.touches.length || !side.classList.contains('examo-mobile-open')) return;
                  startX = lastX = e.touches[0].clientX;
                  startY = lastY = e.touches[0].clientY;
                  horizontal = false;
                }, {passive:true});
                side.addEventListener('touchmove', function(e) {
                  if (!e.touches || !e.touches.length || !side.classList.contains('examo-mobile-open')) return;
                  lastX = e.touches[0].clientX;
                  lastY = e.touches[0].clientY;
                  var dx = lastX - startX, dy = lastY - startY;
                  if (dx < -14 && Math.abs(dx) > Math.abs(dy) * 1.25) horizontal = true;
                  if (horizontal && e.cancelable) e.preventDefault();
                }, {passive:false});
                side.addEventListener('touchend', function() {
                  if (!side.classList.contains('examo-mobile-open')) return;
                  var dx = lastX - startX, dy = lastY - startY;
                  if (horizontal && dx < -64 && Math.abs(dx) > Math.abs(dy) * 1.25) {
                    window.__examoIgnoreTapUntil = Date.now() + 500;
                    closeMenu();
                  }
                  horizontal = false;
                }, {passive:true});
                side.addEventListener('touchcancel', function(){ horizontal = false; }, {passive:true});
              }

              document.addEventListener('click', function(e) {
                if (window.__examoIgnoreTapUntil && Date.now() < window.__examoIgnoreTapUntil) {
                  e.preventDefault(); e.stopPropagation();
                  if (e.stopImmediatePropagation) e.stopImmediatePropagation();
                  return;
                }
                var item = e.target && e.target.closest
                  ? e.target.closest('.sidebar-item,.sidebar-subitem') : null;
                if (!item) return;
                // Student is a disclosure/dropdown row, not a navigation route.
                // Its aria-expanded click must keep the sidebar open.
                if (item.classList.contains('sidebar-item') &&
                    (item.hasAttribute('aria-expanded') || item.querySelector('.sidebar-chevron'))) return;
                closeMenu();
              }, true);
              document.addEventListener('keydown', function(e) {
                if (e.key === 'Escape') closeMenu();
              });
              window.addEventListener('resize', function() {
                if (window.innerWidth > 600) closeMenu();
              });
              bindMenu();
              bindSidebarSwipe();
              decorateDashboard();
              if (!window.__examoAndroidNavObserver) {
                window.__examoAndroidNavObserver = new MutationObserver(function() {
                  bindMenu();
                  bindSidebarSwipe();
                  decorateDashboard();
                  decorateDataTables();
                });
                window.__examoAndroidNavObserver.observe(document.documentElement,{childList:true,subtree:true});
              }
              decorateDataTables();
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) { hideCustomView(); return }
        if (authSheetOpen) { showWelcomeSplash(); return }
        val popup = popupWebView
        if (popup != null) {
            root.removeView(popup)
            popup.destroy()
            popupWebView = null
        } else if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (customView != null) hideCustomView()
        activityScope.cancel()
        popupWebView?.destroy()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
