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
                '.table-container{width:100%!important;max-width:100%!important;overflow:visible!important}' +
                'table.data-table{display:block!important;width:100%!important;min-width:0!important;border-collapse:separate!important;border-spacing:0!important;background:transparent!important}' +
                'table.data-table thead{display:none!important}' +
                'table.data-table tbody{display:block!important;width:100%!important}' +
                'table.data-table tbody tr.examo-mobile-record{display:block!important;width:100%!important;margin:0 0 13px!important;padding:12px 14px!important;background:#fff!important;border:1px solid #e1e7f0!important;border-radius:14px!important;box-shadow:0 4px 14px rgba(20,38,70,.06)!important;overflow:hidden!important}' +
                'table.data-table tbody td{display:grid!important;grid-template-columns:minmax(88px,36%) minmax(0,1fr)!important;align-items:start!important;gap:10px!important;width:100%!important;min-width:0!important;padding:8px 0!important;border:0!important;border-bottom:1px solid #eef1f5!important;background:transparent!important;color:#344054!important;font-size:13px!important;line-height:1.4!important;white-space:normal!important;overflow-wrap:anywhere!important;vertical-align:middle!important}' +
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
                // Exam Forms: match reference card exactly — title, inline dates,
                // then Status + Watch + Open + Edit/Delete in ONE action row.
                '.examo-mobile-exam-form-wrapper{background:transparent!important;border:0!important;box-shadow:none!important;padding:0!important;overflow:visible!important}' +
                '.examo-mobile-exam-form-wrapper .table-container{background:transparent!important;border:0!important;box-shadow:none!important;padding:0!important;overflow:visible!important}' +
                'table.examo-mobile-exam-form-table{width:100%!important;display:block!important;background:transparent!important}' +
                'table.examo-mobile-exam-form-table tbody{display:block!important;width:100%!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record{display:grid!important;grid-template-columns:minmax(66px,.9fr) minmax(70px,1fr) minmax(38px,.55fr) minmax(64px,.9fr)!important;grid-template-rows:auto auto auto!important;column-gap:5px!important;row-gap:0!important;align-items:center!important;width:100%!important;margin:0 0 15px!important;padding:15px 16px 13px!important;background:linear-gradient(145deg,#ffffff 0%,#f1f4f8 100%)!important;border:1px solid #e0e5ed!important;border-radius:17px!important;box-shadow:0 7px 16px rgba(24,39,65,.13)!important;overflow:hidden!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td{display:flex!important;position:relative!important;align-items:center!important;justify-content:flex-start!important;min-width:0!important;width:auto!important;gap:4px!important;padding:0!important;margin:0!important;border:0!important;background:transparent!important;color:#344054!important;font-size:13px!important;line-height:1.3!important;white-space:normal!important;overflow-wrap:anywhere!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td.examo-mobile-title{grid-column:1/-1!important;grid-row:1!important;display:block!important;min-height:0!important;padding:0 0 12px!important;margin:0 0 10px!important;border-bottom:1px solid #e1e6ed!important;color:#111827!important;font-size:19px!important;font-weight:750!important;line-height:1.28!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td.examo-mobile-title:before{display:none!important;content:none!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(2){grid-column:1/3!important;grid-row:2!important;display:flex!important;flex-direction:row!important;align-items:baseline!important;justify-content:flex-start!important;gap:5px!important;padding:2px 0 15px!important;white-space:nowrap!important;font-size:13px!important;font-weight:650!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(3){grid-column:3/5!important;grid-row:2!important;display:flex!important;flex-direction:row!important;align-items:baseline!important;justify-content:flex-start!important;gap:5px!important;padding:2px 0 15px!important;white-space:nowrap!important;font-size:13px!important;font-weight:650!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(2):before,table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(3):before{display:inline!important;content:attr(data-label) ": "!important;color:#5e6878!important;font-size:12px!important;font-weight:500!important;letter-spacing:0!important;text-transform:none!important;white-space:nowrap!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(4){grid-column:1!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:flex-start!important;padding:2px 0 0!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(5){grid-column:2!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:center!important;padding:2px 0 0!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(6){grid-column:3!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:center!important;padding:2px 0 0!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(7){grid-column:4!important;grid-row:3!important;display:flex!important;align-items:center!important;justify-content:flex-end!important;padding:2px 0 0!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(n+4):before{display:none!important;content:none!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(5) button,table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(5) a{min-width:0!important;max-width:100%!important;padding:7px 8px!important;font-size:12px!important;line-height:1.2!important;white-space:nowrap!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(6) a{font-size:13px!important;font-weight:650!important;white-space:nowrap!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(7) .row-actions{display:flex!important;align-items:center!important;justify-content:flex-end!important;flex-wrap:nowrap!important;gap:5px!important;width:100%!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(7) button{display:inline-flex!important;align-items:center!important;justify-content:center!important;width:32px!important;min-width:32px!important;height:34px!important;min-height:34px!important;padding:5px!important}' +
                'table.examo-mobile-exam-form-table tbody tr.examo-mobile-record>td:nth-child(7) svg{width:16px!important;height:16px!important}' +
                '}';
              (document.head || document.documentElement).appendChild(css);

              function decorateDataTables() {
                if (window.innerWidth > 600) return;
                document.querySelectorAll('table.data-table').forEach(function(table) {
                  var headers = Array.from(table.querySelectorAll('thead th')).map(function(th) {
                    return (th.innerText || th.textContent || '').trim();
                  });
                  if (!headers.length) return;

                  var normalized = headers.map(function(h) { return h.toLowerCase().replace(/\\s+/g, ' ').trim(); });
                  var isJobRecordTable = normalized[0] === 'exam name' &&
                    (normalized.indexOf('start date') >= 0 || normalized.indexOf('date') >= 0);
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
              if (!window.__examoAndroidNavObserver) {
                window.__examoAndroidNavObserver = new MutationObserver(function() {
                  bindMenu();
                  bindSidebarSwipe();
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
