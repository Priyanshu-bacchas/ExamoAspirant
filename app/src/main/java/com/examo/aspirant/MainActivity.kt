package com.examo.aspirant

import android.annotation.SuppressLint
import android.app.Activity
import org.json.JSONObject
import java.io.IOException
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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.WindowManager
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
    private var pendingOtpOverlay: View? = null
    private var pendingOtpEmailShown: String? = null
    private var pendingOtpInput: EditText? = null
    private var pendingOtpStatus: TextView? = null
    private var pendingOtpVerifyButton: Button? = null
    private var pendingOtpRestartButton: Button? = null
    private var pendingOtpCodeToVerify: String? = null
    private var mainWebViewPageReady = false

    // Recover the login OTP step if Android recreates the WebView while Gmail is open.
    // Only the destination email, remember preference and creation time are stored;
    // passwords and OTP values are never persisted.
    private val authRecoveryPrefs by lazy {
        getSharedPreferences("examo_auth_recovery", MODE_PRIVATE)
    }
    private var loginOtpVerificationInProgress = false

    private data class PendingLoginOtp(
        val email: String,
        val remember: Boolean,
        val startedAt: Long,
        val apiBaseUrl: String
    )

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

        // Check pending login BEFORE building the welcome/login layers.
        // If Android killed this Activity while Gmail was open, the OTP WebView
        // must be foreground-visible from the first frame (not hidden below splash).
        val pendingAtLaunch = getPendingLoginOtp()
        authSheetOpen = pendingAtLaunch != null

        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        // Keep the welcome screen underneath the WebView for normal launches.
        val splash = ExamoSplashView(this)
        splashSheet = splash
        root.addView(splash, FrameLayout.LayoutParams(-1, -1))

        webView = createWebView()
        webView.visibility = if (pendingAtLaunch != null) View.VISIBLE else View.INVISIBLE
        webView.translationY = 0f
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        if (pendingAtLaunch != null) {
            showNativePendingLoginOtpScreen(pendingAtLaunch)
        }

        root.post {
            if (pendingAtLaunch != null) {
                // Keep the pending OTP page in front. Never run the default
                // "hide WebView below welcome screen" animation for this launch.
                webView.visibility = View.VISIBLE
                webView.translationY = 0f
                authSheetOpen = true
            } else {
                webView.translationY = root.height.toFloat()
            }
        }

        val savedExamoUrl = authRecoveryPrefs.getString("last_examo_url", null)
        val launchUrl = if (pendingAtLaunch != null && isExamoWebsiteUrl(savedExamoUrl)) {
            savedExamoUrl!!
        } else {
            getString(R.string.examo_website_url)
        }

        if (savedInstanceState == null) {
            webView.loadUrl(launchUrl)
        } else {
            val restored = webView.restoreState(savedInstanceState)
            if (restored == null) webView.loadUrl(launchUrl)
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
                    runOnUiThread {
                        if (getPendingLoginOtp() != null) {
                            Toast.makeText(
                                this@MainActivity,
                                "Enter the login code to complete verification.",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            showWelcomeSplash()
                        }
                    }
                }
            }, "ExamoSwipeBridge")

            addJavascriptInterface(object {
                @JavascriptInterface
                fun savePendingLoginOtp(
                    email: String?,
                    remember: Boolean,
                    apiBaseUrl: String?
                ) {
                    val normalizedEmail = email?.trim()?.lowercase().orEmpty()
                    if (normalizedEmail.isBlank() || !normalizedEmail.contains("@")) return

                    val candidateApiBase = apiBaseUrl?.trim()?.trimEnd('/').orEmpty()
                    val parsedApiBase = Uri.parse(candidateApiBase)
                    val oldApiBase = authRecoveryPrefs.getString("login_otp_api_base", null)
                        ?.trim()
                        ?.trimEnd('/')
                        .orEmpty()
                    val parsedOldApiBase = Uri.parse(oldApiBase)

                    val safeApiBase = when {
                        parsedApiBase.scheme.equals("https", ignoreCase = true) &&
                            !parsedApiBase.host.isNullOrBlank() -> candidateApiBase
                        parsedOldApiBase.scheme.equals("https", ignoreCase = true) &&
                            !parsedOldApiBase.host.isNullOrBlank() -> oldApiBase
                        else -> getString(R.string.examo_api_url).trimEnd('/')
                    }

                    val oldEmail = authRecoveryPrefs.getString("login_otp_email", null)
                    val oldStartedAt = authRecoveryPrefs.getLong("login_otp_started_at", 0L)
                    val startedAt = if (oldEmail == normalizedEmail && oldStartedAt > 0L) {
                        oldStartedAt
                    } else {
                        System.currentTimeMillis()
                    }

                    // Persist the exact API host used by the login request.
                    // The WebView build can have a different API base from the APK resource.
                    authRecoveryPrefs.edit()
                        .putString("login_otp_email", normalizedEmail)
                        .putString("login_otp_api_base", safeApiBase)
                        .putBoolean("login_otp_remember", remember)
                        .putLong("login_otp_started_at", startedAt)
                        .commit()

                    runOnUiThread {
                        getPendingLoginOtp()?.let { showNativePendingLoginOtpScreen(it) }
                    }
                }

                @JavascriptInterface
                fun clearPendingLoginOtp() {
                    clearSavedLoginOtp()
                    runOnUiThread { removeNativePendingLoginOtpScreen() }
                }

                @JavascriptInterface
                fun verifyPendingLoginOtp(email: String?, otp: String?) {
                    runOnUiThread {
                        handlePendingLoginOtpVerification(email, otp)
                    }
                }

                @JavascriptInterface
                fun completePendingLoginOtp(token: String?, userJson: String?) {
                    runOnUiThread {
                        val pending = getPendingLoginOtp()
                        if (pending == null || token.isNullOrBlank()) {
                            loginOtpVerificationInProgress = false
                            setOtpRecoveryStatus(
                                "Login verification expired or the server did not return a token. Please retry.",
                                isError = true,
                                expired = pending == null
                            )
                            return@runOnUiThread
                        }

                        val safeUser = userJson?.takeIf { it.isNotBlank() } ?: "{}"
                        val storage = if (pending.remember) "localStorage" else "sessionStorage"
                        val otherStorage = if (pending.remember) "sessionStorage" else "localStorage"
                        val sessionScript = """
                            (function () {
                              try {
                                ${storage}.setItem('examo_token', ${JSONObject.quote(token)});
                                ${storage}.setItem('examo_user', ${JSONObject.quote(safeUser)});
                                ${otherStorage}.removeItem('examo_token');
                                ${otherStorage}.removeItem('examo_user');
                                return true;
                              } catch (error) {
                                return false;
                              }
                            })();
                        """.trimIndent()

                        webView.evaluateJavascript(sessionScript) { result ->
                            if (result == "true") {
                                clearSavedLoginOtp()
                                loginOtpVerificationInProgress = false
                                pendingOtpCodeToVerify = null
                                removeNativePendingLoginOtpScreen()
                                mainWebViewPageReady = false
                                webView.visibility = View.VISIBLE
                                webView.translationY = 0f
                                authSheetOpen = true
                                webView.reload()
                            } else {
                                loginOtpVerificationInProgress = false
                                setOtpRecoveryStatus(
                                    "Could not save your login session. Please try again.",
                                    isError = true
                                )
                            }
                        }
                    }
                }

                @JavascriptInterface
                fun failPendingLoginOtp(message: String?, expired: Boolean) {
                    runOnUiThread {
                        loginOtpVerificationInProgress = false
                        setOtpRecoveryStatus(
                            message?.take(300)?.ifBlank { null }
                                ?: "Could not verify your code. Please try again.",
                            isError = true,
                            expired = expired
                        )
                    }
                }

                @JavascriptInterface
                fun restartPendingLogin() {
                    runOnUiThread {
                        clearSavedLoginOtp()
                        loginOtpVerificationInProgress = false
                        pendingOtpCodeToVerify = null
                        removeNativePendingLoginOtpScreen()
                        webView.visibility = View.VISIBLE
                        webView.translationY = 0f
                        authSheetOpen = true
                        webView.loadUrl(getString(R.string.examo_website_url))
                    }
                }
            }, "ExamoAuthBridge")

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

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (view == webView) mainWebViewPageReady = false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view == webView) {
                        mainWebViewPageReady = true
                        pendingOtpCodeToVerify?.let { code ->
                            pendingOtpCodeToVerify = null
                            getPendingLoginOtp()?.let { handlePendingLoginOtpVerification(it.email, code) }
                        }
                    }
                    persistLastExamoUrl(url)
                    installMobileNavigationFix(view)
                    hideGoogleSignInInApp(view)
                    installLoginSwipeBorder(view)
                    installLoginOtpRecovery(view)
                    installAndroidHelpRedesign(view)
                    restorePendingLoginOtpIfNeeded(view, url)
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    if (view == null) return true
                    if (view == webView) {
                        runOnUiThread { recreateMainWebViewAfterRendererGone(view) }
                    } else {
                        runOnUiThread {
                            root.removeView(view)
                            view.destroy()
                            if (popupWebView === view) popupWebView = null
                        }
                    }
                    return true
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

    // Android-only Help page redesign. The website and its APIs remain unchanged.
    // This is injected only into the main WebView and only while the Help page is active.
    // Android-only Help page redesign. The live website remains unchanged.
    private fun installAndroidHelpRedesign(view: WebView?) {
        if (view == null || !::webView.isInitialized || view !== webView) return

        val script = """
            (function () {
              var subscriptionBooksImageSrc = __EXAMO_SUBSCRIPTION_BOOKS_URL__;
              if (window.__examoAndroidHelpBooted) {
                window.__examoApplyAndroidHelp && window.__examoApplyAndroidHelp();
                return;
              }
              window.__examoAndroidHelpBooted = true;

              var style = document.createElement('style');
              style.id = 'examo-android-help-style';
              style.textContent = [
                'body.examo-android-help-mode{background:#edf3fc!important}',
                'body.examo-android-help-mode .content-area{padding:14px 12px 26px!important;background:linear-gradient(180deg,#f3f7ff 0%,#eaf1ff 100%)!important;min-width:0!important;overflow-x:hidden!important}',
                'body.examo-android-help-mode .hs-page{display:block!important;width:100%!important;max-width:100%!important;margin:0!important;min-width:0!important}',
                'body.examo-android-help-mode .hs-main,body.examo-android-help-mode .hs-side{display:none!important}',
                'body.examo-android-help-mode .topbar .breadcrumb strong{font-size:14px!important}',
                '#examo-android-help-redesign{width:100%;max-width:760px;margin:0 auto;display:flex;flex-direction:column;gap:14px;color:#14213c;font-family:inherit}',
                '#examo-android-help-redesign *{box-sizing:border-box}',
                '.eah-hero{position:relative;display:block;width:100%;height:auto;min-height:0;overflow:hidden;border-radius:19px;border:1px solid #d8e4fb;background:transparent!important;box-shadow:0 7px 16px rgba(33,75,141,.09);line-height:0;padding:0!important}',
                '.eah-hero-canvas{position:relative;z-index:0;display:block;width:100%;height:auto;max-width:100%;margin:0;padding:0;border:0;background:transparent}',
                '.eah-hero:after{content:"";position:absolute;z-index:1;left:0;top:0;bottom:0;width:73%;pointer-events:none;background:linear-gradient(90deg,rgba(224,235,255,.99) 0%,rgba(229,234,255,.98) 66%,rgba(231,231,255,.92) 82%,rgba(231,231,255,0) 100%)}',
                '.eah-hero-copy{position:absolute;z-index:2;left:4.5%;top:13%;width:59%;height:78%;display:flex;flex-direction:column;justify-content:center;align-items:flex-start;pointer-events:none}',
                '.eah-hero-kicker{margin:0 0 3px;color:#075bd8;font-size:clamp(17px,5.4vw,23px);font-weight:900;font-style:italic;line-height:1.06;letter-spacing:-.035em;text-shadow:0 0 0 #075bd8}',
                '.eah-hero-headline{margin:0;color:#101b36;font-size:clamp(14px,4.3vw,19px);font-weight:900;font-style:italic;line-height:1.1;letter-spacing:-.04em;white-space:nowrap;text-shadow:0 0 0 #101b36}',
                '.eah-hero-headline em{color:#075bd8;font-style:italic}',
                '.eah-hero-description{margin:7px 0 0;color:#162745;font-size:clamp(8px,2.75vw,11px);font-weight:650;line-height:1.32;letter-spacing:-.01em;text-shadow:0 0 0 #162745;max-width:100%}',
                '.eah-hero-fallback{min-height:148px;background:linear-gradient(110deg,#dceaff 0%,#e3e7ff 60%,#eee6ff 100%)!important;display:flex;align-items:center;padding:20px 17px;line-height:normal}',
                '.eah-hero-fallback-content{max-width:78%;color:#12264a}',
                '.eah-hero-fallback-content small{display:block;color:#1762cf;font-size:12px;font-weight:850;letter-spacing:.08em;margin-bottom:7px}',
                '.eah-hero-fallback-content strong{display:block;font-size:23px;line-height:1.1;font-weight:850}',
                '.eah-hero-fallback-content strong em{font-style:normal;color:#1765dc}',
                '.eah-hero-fallback-content p{margin:9px 0 0;color:#435a7b;font-size:13px;line-height:1.4}',
                '.eah-section-title{margin:2px 2px -5px;color:#14284b;font-weight:850;font-size:20px;line-height:1.2;display:flex;align-items:center;gap:9px}',
                '.eah-section-title:before{content:"";width:4px;height:23px;border-radius:8px;background:#2469df;display:block}',
                '.eah-categories{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:9px}',
                '.eah-category{min-width:0;min-height:112px;padding:12px 10px 11px;text-align:left;border:1px solid rgba(255,255,255,.9);border-radius:17px;box-shadow:0 4px 10px rgba(34,65,116,.06);display:flex;flex-direction:column;align-items:flex-start;gap:5px;color:#14213c;cursor:pointer;position:relative;overflow:hidden;-webkit-tap-highlight-color:transparent}',
                '.eah-category:after{content:"›";position:absolute;right:9px;top:49px;font-size:25px;line-height:1;color:#14213c;font-weight:400}',
                '.eah-category:active{transform:scale(.985)}',
                '.eah-category-icon{width:32px;height:32px;display:flex;align-items:center;justify-content:center;margin-bottom:1px}',
                '.eah-category-icon svg{width:31px;height:31px;stroke:currentColor;fill:none;stroke-width:1.9;stroke-linecap:round;stroke-linejoin:round}',
                '.eah-category strong{font-size:12px;line-height:1.2;font-weight:850;padding-right:13px}',
                '.eah-category small{font-size:10px;line-height:1.3;color:#43536b;max-width:95%;padding-right:8px}',
                '.eah-user{background:linear-gradient(145deg,#dff2ff,#e9f4ff);color:#1593e8;border-color:#cde8ff}',
                '.eah-account{background:linear-gradient(145deg,#eee7ff,#f0ebff);color:#8250ed;border-color:#e2d8ff}',
                '.eah-exam{background:linear-gradient(145deg,#fff0e5,#fff6ee);color:#f28a20;border-color:#ffe1cb}',
                '.eah-jobs{background:linear-gradient(145deg,#e1f8eb,#e9f9ef);color:#12a34a;border-color:#cef1dc}',
                '.eah-docs{background:linear-gradient(145deg,#ffe8f0,#fff0f5);color:#ed3d75;border-color:#ffd8e5}',
                '.eah-tech{background:linear-gradient(145deg,#e2f0ff,#edf6ff);color:#1769de;border-color:#cfe5ff}',
                '.eah-category strong,.eah-category small{color:#14213c}',
                '.eah-panel{display:none;background:#fff;border:1px solid #dce6f6;border-radius:16px;overflow:hidden;box-shadow:0 7px 18px rgba(34,65,116,.07)}',
                '.eah-panel.is-visible{display:block}',
                '.eah-panel-head{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:13px 15px;background:linear-gradient(100deg,#e8f2ff,#f1efff);border-bottom:1px solid #e0e8f5}',
                '.eah-panel-head strong{font-size:15px;color:#172b4d}',
                '.eah-panel-head small{display:block;margin-top:3px;color:#71819a;font-size:11px}',
                '.eah-panel-close{width:30px;height:30px;border:0;border-radius:50%;background:#fff;color:#405571;font-size:20px;line-height:1}',
                '.eah-faq-list{padding:7px 10px 10px;display:grid;gap:7px}',
                '.eah-faq-item{border:1px solid #e1e9f5;border-radius:11px;overflow:hidden;background:#fff}',
                '.eah-faq-question{width:100%;padding:11px 12px;border:0;background:#fff;display:flex;justify-content:space-between;align-items:center;gap:10px;text-align:left;color:#1b2d4a;font-size:12px;font-weight:750;line-height:1.35}',
                '.eah-faq-question span:last-child{font-size:17px;color:#42628b;flex:0 0 auto}',
                '.eah-faq-answer{display:none;padding:0 12px 12px;color:#536780;font-size:12px;line-height:1.55;white-space:pre-wrap}',
                '.eah-faq-item.open .eah-faq-answer{display:block}',
                '.eah-no-results{padding:16px;color:#63748c;font-size:12px;line-height:1.5}',
                '.eah-support-card{padding:0;background:rgba(255,255,255,.82);border:1px solid #d8e5fb;border-radius:18px;box-shadow:0 7px 17px rgba(34,65,116,.07);overflow:hidden}',
                '.eah-support-head{display:flex;align-items:center;gap:11px;padding:15px 15px 13px}',
                '.eah-support-head-icon{width:42px;height:42px;border-radius:13px;background:#e7f0ff;color:#1e65d2;display:flex;align-items:center;justify-content:center;flex:0 0 42px}',
                '.eah-support-head-icon svg{width:25px;height:25px;stroke:currentColor;fill:none;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}',
                '.eah-support-head strong{display:block;color:#172746;font-size:18px;line-height:1.2;font-weight:850}',
                '.eah-support-head p{margin:4px 0 0;color:#65758d;font-size:12px;line-height:1.4}',
                '.eah-support-actions{padding:0 10px 11px;display:grid;gap:9px}',
                '.eah-support-action{width:100%;min-height:66px;padding:11px 13px;display:flex;align-items:center;gap:12px;border:1px solid #e0e7f5;border-radius:14px;text-align:left;text-decoration:none;color:#182a49;box-shadow:0 4px 9px rgba(34,65,116,.06);cursor:pointer;-webkit-tap-highlight-color:transparent}',
                '.eah-support-action:active{transform:scale(.99)}',
                '.eah-chat-action{background:linear-gradient(105deg,#f0eaff,#f6f2ff);border-color:#e3d8ff}',
                '.eah-email-action{background:linear-gradient(105deg,#e8f2ff,#f2f7ff);border-color:#d4e4ff}',
                '.eah-action-icon{width:39px;height:39px;flex:0 0 39px;border-radius:12px;background:rgba(255,255,255,.8);display:flex;align-items:center;justify-content:center;color:#7544e7}',
                '.eah-email-action .eah-action-icon{color:#1767dc}',
                '.eah-action-icon svg{width:25px;height:25px;stroke:currentColor;fill:none;stroke-width:1.9;stroke-linecap:round;stroke-linejoin:round}',
                '.eah-action-copy{min-width:0;flex:1;display:grid;gap:3px}',
                '.eah-action-copy strong{font-size:13px;line-height:1.25;color:#172746;font-weight:850}',
                '.eah-action-copy small{font-size:11px;line-height:1.3;color:#6c7c92;overflow-wrap:anywhere}',
                '.eah-action-arrow{flex:0 0 auto;color:#334a6b;font-size:22px;line-height:1}',
                '@media(min-width:620px){.eah-categories{gap:12px}.eah-category{min-height:132px;padding:15px}.eah-category strong{font-size:14px}.eah-category small{font-size:12px}.eah-category-icon,.eah-category-icon svg{width:36px;height:36px}.eah-support-actions{padding:0 14px 14px}.eah-support-action{min-height:78px;padding:14px 17px}.eah-action-copy strong{font-size:15px}.eah-action-copy small{font-size:13px}}',
                '@media(max-width:360px){body.examo-android-help-mode .content-area{padding-left:9px!important;padding-right:9px!important}.eah-category{min-height:105px;padding:9px 7px}.eah-category strong{font-size:11px}.eah-category small{font-size:9px}}'
              ].join('');
              document.head.appendChild(style);

              var fallbackFaqs = [
                {question:'How to use this App?',answer:'Examo helps you track jobs, exam forms, preparation and results in one place. Open Jobs and use Add to ExamForm to start.'},
                {question:'What is Examo?',answer:'Examo is a student management app for government exam aspirants.'},
                {question:'How to add an exam form?',answer:'Open Jobs > Latest Jobs and click Add to ExamForm next to the exam you want.'},
                {question:'How to upload my documents?',answer:'Open Student > Documents and upload a JPG, PNG, WEBP or PDF file up to 10 MB.'},
                {question:'The page is not loading properly',answer:'Wait a moment and refresh. If the problem continues, contact support with a screenshot.'}
              ];
              window.__examoAndroidHelpAllFaqs = window.__examoAndroidHelpAllFaqs || fallbackFaqs.slice();
              window.__examoAndroidHelpFaqsReady = false;
              window.__examoAndroidHelpCategory = '';
              window.__examoAndroidHelpCaptureRunning = false;
              window.__examoAndroidHelpLastSignature = '';

              function makeMarkup() {
                return [
                  '<section class="eah-hero" role="img" aria-label="Need Help? We are here for you."><canvas class="eah-hero-canvas" width="382" height="116"></canvas><div class="eah-hero-copy"><div class="eah-hero-kicker">Need Help?</div><div class="eah-hero-headline">We’re Here for <em>You!</em></div><p class="eah-hero-description">Get quick answers, step-by-step guides<br>or contact our support team.</p></div></section>',
                  '<div class="eah-section-title">Quick Help</div>',
                  '<section class="eah-categories" aria-label="Help categories">',
                    '<button type="button" class="eah-category eah-user" data-category="User Guide"><span class="eah-category-icon"><svg viewBox="0 0 24 24"><path d="M3 5.5c3.2-1.4 6.2-.9 9 1.2v14c-2.8-2.1-5.8-2.6-9-1.2z"/><path d="M21 5.5c-3.2-1.4-6.2-.9-9 1.2v14c2.8-2.1 5.8-2.6 9-1.2z"/><path d="M6 9h3M6 12h3M15 9h3M15 12h3"/></svg></span><strong>User Guide</strong><small>Learn how to use Examo</small></button>',
                    '<button type="button" class="eah-category eah-account" data-category="Account Help"><span class="eah-category-icon"><svg viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="2.5"/><path d="M5.5 17c.7-2 2-3 3.5-3s2.8 1 3.5 3M15 9h3M15 13h3M15 17h3"/></svg></span><strong>Account Help</strong><small>Login, profile &amp; account issues</small></button>',
                    '<button type="button" class="eah-category eah-exam" data-category="Exam Forms"><span class="eah-category-icon"><svg viewBox="0 0 24 24"><path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h9"/><path d="M14 3v5h5M9 12h6M9 16h3"/><path d="m15.5 17.5 5-5 2 2-5 5-3 1z"/></svg></span><strong>Exam Forms</strong><small>Add, edit &amp; manage exam forms</small></button>',
                    '<button type="button" class="eah-category eah-jobs" data-category="Jobs &amp; Updates"><span class="eah-category-icon"><svg viewBox="0 0 24 24"><rect x="3" y="7" width="18" height="13" rx="2"/><path d="M8 7V5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M3 12h18M10 12v2h4v-2"/></svg></span><strong>Jobs &amp; Updates</strong><small>Jobs, admit card &amp; results</small></button>',
                    '<button type="button" class="eah-category eah-docs" data-category="Documents"><span class="eah-category-icon"><svg viewBox="0 0 24 24"><path d="M12 16V4M7 9l5-5 5 5"/><path d="M4 15v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4"/></svg></span><strong>Documents</strong><small>Upload &amp; manage your documents</small></button>',
                    '<button type="button" class="eah-category eah-tech" data-category="Technical Support"><span class="eah-category-icon"><svg viewBox="0 0 24 24"><path d="M12 8.2a3.8 3.8 0 1 0 0 7.6 3.8 3.8 0 0 0 0-7.6Z"/><path d="m19.4 13.4 1.1.9-1.5 2.6-1.4-.5a7.8 7.8 0 0 1-1.5.9l-.2 1.5h-3l-.3-1.5a7.8 7.8 0 0 1-1.5-.9l-1.4.5-1.5-2.6 1.1-.9a7.4 7.4 0 0 1 0-1.8l-1.1-.9 1.5-2.6 1.4.5a7.8 7.8 0 0 1 1.5-.9l.3-1.5h3l.2 1.5a7.8 7.8 0 0 1 1.5.9l1.4-.5 1.5 2.6-1.1.9a7.4 7.4 0 0 1 0 1.8Z"/></svg></span><strong>Technical Support</strong><small>App, website &amp; other issues</small></button>',
                  '</section>',
                  '<section class="eah-panel" aria-live="polite"><div class="eah-panel-head"><div><strong class="eah-panel-title">Help articles</strong><small class="eah-panel-subtitle"></small></div><button type="button" class="eah-panel-close" aria-label="Close">×</button></div><div class="eah-faq-list"></div></section>',
                  '<section class="eah-support-card">',
                    '<div class="eah-support-head"><span class="eah-support-head-icon"><svg viewBox="0 0 24 24"><path d="M4 13v-2a8 8 0 0 1 16 0v2"/><rect x="3" y="12" width="4" height="7" rx="2"/><rect x="17" y="12" width="4" height="7" rx="2"/><path d="M19 19c0 2-2 3-5 3h-1"/></svg></span><div><strong>Contact Support</strong><p>Send us your question and our support team will get back to you.</p></div></div>',
                    '<div class="eah-support-actions">',
                      '<button type="button" class="eah-support-action eah-chat-action"><span class="eah-action-icon"><svg viewBox="0 0 24 24"><path d="M20 11.5a7.5 7.5 0 0 1-7.5 7.5H6l-3 2v-5.5A7.5 7.5 0 1 1 20 11.5Z"/></svg></span><span class="eah-action-copy"><strong>Chat with Examo Assistant</strong><small>Instant answers, available 24/7</small></span><span class="eah-action-arrow">›</span></button>',
                      '<a class="eah-support-action eah-email-action" href="mailto:examoaspirant@gmail.com?subject=Examo%20Support"><span class="eah-action-icon"><svg viewBox="0 0 24 24"><rect x="2.5" y="4.5" width="19" height="15" rx="2"/><path d="m3.5 6 8.5 7 8.5-7"/></svg></span><span class="eah-action-copy"><strong>Email Support</strong><small>examoaspirant@gmail.com</small></span><span class="eah-action-arrow">›</span></a>',
                    '</div>',
                  '</section>'
                ].join('');
              }

              function normaliseQuestion(value) {
                return String(value || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim().replace(/\s+/g, ' ');
              }

              // Exact question-to-section mapping: a question is displayed in only its intended category.
              var questionGroups = {
                'User Guide': [
                  'How to use this App?',
                  'What is Examo?',
                  'How to set up my student profile?',
                  'What does the Dashboard show?',
                  'How to add subjects and schedules?',
                  'How do I track my exam preparation?',
                  'What are Notifications and reminders?'
                ],
                'Account Help': [
                  "I can't log in to my account",
                  'How to reset my password?',
                  'I did not receive the OTP / verification code',
                  'Why do I get a code every time I log in?',
                  'How does Continue with Google work?',
                  'Is my data and uploaded documents safe?',
                  'How do I change my email or mobile number?',
                  'How do I delete my account?',
                  'My account is blocked',
                  'How does the free trial work?',
                  'How much does Examo cost?',
                  'What payment methods are accepted?',
                  'How do I buy or renew a plan?',
                  'How can I change or cancel my plan?',
                  'How do I check when my plan ends?',
                  'How do I download my payment receipt?',
                  'My payment was deducted but my plan is not active'
                ],
                'Exam Forms': [
                  'How do I add an exam form?',
                  'How do I change my exam form status?',
                  'Can I add an exam form manually?',
                  "Why can't I add a job to my Exam Forms?"
                ],
                'Jobs & Updates': [
                  'What are the Jobs, Admit Card and Results tabs?',
                  'Where can I track my exam dates?',
                  'How to check my results?'
                ],
                'Documents': [
                  'How to upload my documents?',
                  'My document upload is failing'
                ],
                'Technical Support': [
                  'The page is not loading properly',
                  'How do I contact support?'
                ]
              };
              var questionToCategory = {};
              Object.keys(questionGroups).forEach(function (category) {
                questionGroups[category].forEach(function (question) {
                  questionToCategory[normaliseQuestion(question)] = category;
                });
              });

              function normaliseFaq(item) {
                return {
                  question: String(item.question || item.Question || '').trim().replace(/website/gi, 'App'),
                  answer: String(item.answer || item.Answer || item.content || '').trim().replace(/website/gi, 'App')
                };
              }

              function itemsForCategory(name) {
                var allowed = questionGroups[name] || [];
                var byQuestion = {};
                (window.__examoAndroidHelpAllFaqs || []).forEach(function (raw) {
                  var faq = normaliseFaq(raw);
                  var key = normaliseQuestion(faq.question);
                  if (key) byQuestion[key] = faq;
                });
                return allowed.map(function (question) {
                  return byQuestion[normaliseQuestion(question)];
                }).filter(Boolean);
              }

              function renderPanel(title, subtitle, items, emptyText, isLoading) {
                var root = document.getElementById('examo-android-help-redesign');
                if (!root) return;
                var panel = root.querySelector('.eah-panel');
                var list = panel.querySelector('.eah-faq-list');
                panel.querySelector('.eah-panel-title').textContent = title;
                panel.querySelector('.eah-panel-subtitle').textContent = subtitle || '';
                list.innerHTML = '';
                panel.classList.add('is-visible');

                if (isLoading) {
                  var loading = document.createElement('div');
                  loading.className = 'eah-no-results';
                  loading.textContent = 'Loading questions...';
                  list.appendChild(loading);
                  return;
                }
                if (!items || !items.length) {
                  var empty = document.createElement('div');
                  empty.className = 'eah-no-results';
                  empty.textContent = emptyText || 'No questions are available in this section yet.';
                  list.appendChild(empty);
                  return;
                }

                items.forEach(function (raw) {
                  var item = normaliseFaq(raw);
                  if (!item.question) return;
                  var wrap = document.createElement('article');
                  wrap.className = 'eah-faq-item';
                  var question = document.createElement('button');
                  question.type = 'button';
                  question.className = 'eah-faq-question';
                  var label = document.createElement('span');
                  label.textContent = item.question;
                  var chevron = document.createElement('span');
                  chevron.textContent = '⌄';
                  question.appendChild(label);
                  question.appendChild(chevron);
                  var answer = document.createElement('div');
                  answer.className = 'eah-faq-answer';
                  answer.textContent = item.answer || 'Please contact Examo Support for more help with this question.';
                  question.addEventListener('click', function () {
                    var willOpen = !wrap.classList.contains('open');
                    Array.prototype.forEach.call(list.querySelectorAll('.eah-faq-item.open'), function (node) {
                      node.classList.remove('open');
                    });
                    if (willOpen) wrap.classList.add('open');
                  });
                  wrap.appendChild(question);
                  wrap.appendChild(answer);
                  list.appendChild(wrap);
                });
              }

              function openCategory(name) {
                window.__examoAndroidHelpCategory = name;
                var matches = itemsForCategory(name);
                renderPanel(name + ' – FAQs', 'Questions related to ' + name + '.', matches,
                  'No FAQs are available in this category yet.', !window.__examoAndroidHelpFaqsReady);
                var panel = document.querySelector('#examo-android-help-redesign .eah-panel');
                if (panel) panel.scrollIntoView({behavior:'smooth', block:'nearest'});
              }

              async function captureFaqs() {
                if (window.__examoAndroidHelpCaptureRunning) return;
                var buttons = Array.prototype.slice.call(document.querySelectorAll('.hs-accordion .hs-acc-head'));
                if (!buttons.length) return;
                window.__examoAndroidHelpCaptureRunning = true;
                var originalOpen = document.querySelector('.hs-accordion .hs-acc-item.open .hs-acc-head');
                var originalQuestion = originalOpen ? (originalOpen.querySelector('span') || originalOpen).innerText.trim() : '';
                var collected = [];
                try {
                  for (var i = 0; i < buttons.length; i++) {
                    var questionText = (buttons[i].querySelector('span') || buttons[i]).innerText.trim();
                    if (!questionText) continue;
                    var current = Array.prototype.slice.call(document.querySelectorAll('.hs-accordion .hs-acc-head')).find(function (b) {
                      return (b.querySelector('span') || b).innerText.trim() === questionText;
                    });
                    if (!current) continue;
                    var currentItem = current.closest('.hs-acc-item');
                    if (!currentItem || !currentItem.querySelector('.hs-acc-body')) {
                      current.click();
                      await new Promise(function (resolve) { window.setTimeout(resolve, 95); });
                    }
                    current = Array.prototype.slice.call(document.querySelectorAll('.hs-accordion .hs-acc-head')).find(function (b) {
                      return (b.querySelector('span') || b).innerText.trim() === questionText;
                    });
                    currentItem = current ? current.closest('.hs-acc-item') : null;
                    var answerNode = currentItem ? currentItem.querySelector('.hs-acc-body') : null;
                    collected.push({question:questionText,answer:answerNode ? answerNode.innerText.trim() : ''});
                  }
                  if (originalQuestion) {
                    var restore = Array.prototype.slice.call(document.querySelectorAll('.hs-accordion .hs-acc-head')).find(function (b) {
                      return (b.querySelector('span') || b).innerText.trim() === originalQuestion;
                    });
                    if (restore && !restore.closest('.hs-acc-item').classList.contains('open')) restore.click();
                  }
                } finally {
                  if (collected.length) {
                    window.__examoAndroidHelpAllFaqs = collected;
                    window.__examoAndroidHelpFaqsReady = true;
                  }
                  window.__examoAndroidHelpCaptureRunning = false;
                  refreshPanelAfterCapture();
                }
              }

              function refreshPanelAfterCapture() {
                var name = window.__examoAndroidHelpCategory;
                if (!name) return;
                renderPanel(name + ' – FAQs', 'Questions related to ' + name + '.', itemsForCategory(name),
                  'No FAQs are available in this category yet.', !window.__examoAndroidHelpFaqsReady);
              }

              var captureTimer = null;
              function queueFaqCapture() {
                if (window.__examoAndroidHelpCaptureRunning) return;
                window.clearTimeout(captureTimer);
                captureTimer = window.setTimeout(captureFaqs, 450);
              }

              function installScreen(page) {
                if (!page) return;
                document.body.classList.add('examo-android-help-mode');
                var crumb = document.querySelector('.topbar .breadcrumb strong');
                if (crumb && crumb.textContent.trim() !== 'Help & Support') crumb.textContent = 'Help & Support';
                page.style.display = 'block';
                page.style.width = '100%';
                page.style.maxWidth = '100%';
                page.style.margin = '0';
                window.__examoAndroidHelpStyledPage = page;
                var main = page.querySelector('.hs-main');
                var side = page.querySelector('.hs-side');
                if (main) main.style.display = 'none';
                if (side) side.style.display = 'none';

                if (!document.getElementById('examo-android-help-redesign')) {
                  var root = document.createElement('div');
                  root.id = 'examo-android-help-redesign';
                  root.innerHTML = makeMarkup();
                  page.insertAdjacentElement('afterend', root);

                  var bannerHero = root.querySelector('.eah-hero');
                  if (bannerHero) {
                    var bannerUrl = __EXAMO_HELP_BANNER_SRC__;
                    var bannerCanvas = bannerHero.querySelector('.eah-hero-canvas');
                    var bannerContext = bannerCanvas ? bannerCanvas.getContext('2d') : null;
                    var bannerLoader = new Image();
                    bannerLoader.onload = function () {
                      if (!bannerCanvas || !bannerContext) return;
                      // Draw the complete source image to its exact 382x116 canvas.
                      // The canvas itself determines the rendered height; no trailing area is created.
                      bannerContext.clearRect(0, 0, 382, 116);
                      bannerContext.drawImage(bannerLoader, 0, 0, 382, 116);
                    };
                    bannerLoader.onerror = function () {
                      bannerHero.classList.add('eah-hero-fallback');
                      bannerHero.innerHTML = '<div class="eah-hero-fallback-content"><small>NEED HELP?</small><strong>We are Here for <em>You!</em></strong><p>Get quick answers, step-by-step guides or contact our support team.</p></div>';
                    };
                    if (bannerUrl) {
                      bannerLoader.src = bannerUrl;
                    } else {
                      bannerLoader.onerror();
                    }
                  }

                  root.querySelectorAll('.eah-category').forEach(function (button) {
                    button.addEventListener('click', function () {
                      openCategory(button.getAttribute('data-category'));
                    });
                  });
                  root.querySelector('.eah-panel-close').addEventListener('click', function () {
                    root.querySelector('.eah-panel').classList.remove('is-visible');
                    window.__examoAndroidHelpCategory = '';
                  });
                  root.querySelector('.eah-chat-action').addEventListener('click', function () {
                    var button = document.querySelector('.hs-side .hs-chat-open');
                    if (button) button.click();
                  });
                  window.setTimeout(queueFaqCapture, 900);
                }
              }

              function removeScreen() {
                var root = document.getElementById('examo-android-help-redesign');
                var page = window.__examoAndroidHelpStyledPage || null;
                if (root) root.remove();
                if (root || document.body.classList.contains('examo-android-help-mode')) {
                  document.body.classList.remove('examo-android-help-mode');
                  if (page && page.isConnected) {
                    page.style.display = '';
                    page.style.width = '';
                    page.style.maxWidth = '';
                    page.style.margin = '';
                    var main = page.querySelector('.hs-main');
                    var side = page.querySelector('.hs-side');
                    if (main) main.style.display = '';
                    if (side) side.style.display = '';
                  }
                  window.__examoAndroidHelpStyledPage = null;
                }
              }

              function apply() {
                var page = document.querySelector('.hs-page');
                var helpHero = page ? page.querySelector('.hs-hero') : null;
                var helpCategoryCards = page ? page.querySelector('.hs-card-grid') : null;
                var helpFaqAccordion = page ? page.querySelector('.hs-accordion') : null;
                // Only the Help page has the Featured FAQ cards and FAQ accordion.
                // Subscription shares .hs-page/.hs-hero styles, so never transform it.
                if (page && helpHero && helpCategoryCards && helpFaqAccordion) {
                  installScreen(page);
                } else {
                  removeScreen();
                }
              }

              window.__examoApplyAndroidHelp = apply;
              apply();

              var observer = new MutationObserver(function () {
                apply();
                var faqHeadings = document.querySelectorAll('.hs-accordion .hs-acc-head');
                var signature = Array.prototype.map.call(faqHeadings, function (b) {
                  return (b.querySelector('span') || b).innerText.trim();
                }).join('|');
                if (signature && signature !== window.__examoAndroidHelpLastSignature && !window.__examoAndroidHelpCaptureRunning) {
                  window.__examoAndroidHelpLastSignature = signature;
                  queueFaqCapture();
                }
              });
              observer.observe(document.body, {childList:true,subtree:true});
            })();
        """.trimIndent()

        val bannerDataUrl = runCatching {
            val bitmap = android.graphics.BitmapFactory.decodeResource(
                resources,
                R.drawable.examo_help_banner
            ) ?: throw IllegalStateException("Help banner drawable could not be decoded")
            val pngBytes = java.io.ByteArrayOutputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
            bitmap.recycle()
            "data:image/png;base64," + android.util.Base64.encodeToString(
                pngBytes,
                android.util.Base64.NO_WRAP
            )
        }.onFailure { error ->
            android.util.Log.e("ExamoHelp", "Unable to prepare Help banner image", error)
        }.getOrDefault("")

        // Inject the bundled drawable as a data URI. WebView cannot reliably
        // resolve the drawable-nodpi file URL on all Android versions.
        val safeBannerSource = JSONObject.quote(bannerDataUrl)
        view.evaluateJavascript(
            script.replace("__EXAMO_HELP_BANNER_SRC__", safeBannerSource),
            null
        )
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
                  var downCue = document.createElement('div');
                  downCue.textContent = '↓';
                  downCue.setAttribute('aria-hidden', 'true');
                  downCue.style.cssText = 'position:absolute;left:50%;top:17px;transform:translateX(-50%);font:700 18px/20px Arial,sans-serif;color:#2455ad;text-shadow:0 1px 2px rgba(20,50,110,.16);pointer-events:none;animation:examoSwipeDown 1.05s ease-in-out infinite';
                  bar.appendChild(downCue);
                  if (!document.getElementById('examo-swipe-down-style')) {
                    var cueStyle = document.createElement('style');
                    cueStyle.id = 'examo-swipe-down-style';
                    cueStyle.textContent = '@keyframes examoSwipeDown{0%,100%{transform:translate(-50%,0);opacity:.68}50%{transform:translate(-50%,4px);opacity:1}}';
                    document.head.appendChild(cueStyle);
                  }
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
        val subscriptionBooksDataUrl = runCatching {
            val bytes = resources.openRawResource(R.drawable.examo_subscription_books).use { it.readBytes() }
            "data:image/webp;base64," + android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.NO_WRAP
            )
        }.onFailure { error ->
            android.util.Log.e("ExamoSubscription", "Unable to load subscription books illustration", error)
        }.getOrDefault("")
        val safeBooksUrl = JSONObject.quote(subscriptionBooksDataUrl)
        view?.evaluateJavascript(
            script.replace("__EXAMO_SUBSCRIPTION_BOOKS_URL__", safeBooksUrl),
            null
        )
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


    private fun installLoginOtpRecovery(view: WebView?) {
        val script = """
            (function () {
              if (window.__examoLoginOtpRecoveryInstalled) return;
              window.__examoLoginOtpRecoveryInstalled = true;

              // Capture the backend's OTP-required response directly. This is
              // more reliable than waiting for React to render the OTP heading.
              if (!window.__examoLoginOtpFetchWrapped) {
                window.__examoLoginOtpFetchWrapped = true;
                var originalFetch = window.fetch.bind(window);
                window.fetch = function (input, init) {
                  var requestUrl = typeof input === 'string' ? input :
                    (input && input.url) || '';
                  var isLoginRequest = String(requestUrl).toLowerCase().indexOf('/auth/login') !== -1;
                  var rememberInput = document.querySelector('form.ex-form input[type="checkbox"]');
                  var remember = rememberInput ? !!rememberInput.checked : true;
                  var loginApiBaseUrl = '';

                  if (isLoginRequest) {
                    try {
                      var absoluteLoginUrl = new URL(String(requestUrl), window.location.href);
                      var loginPath = absoluteLoginUrl.pathname.replace(/\/+$/, '');
                      var loginSuffix = loginPath.toLowerCase().lastIndexOf('/auth/login');
                      if (loginSuffix >= 0) {
                        loginApiBaseUrl =
                          absoluteLoginUrl.origin + loginPath.substring(0, loginSuffix);
                        window.__examoApiBaseUrl = loginApiBaseUrl;
                      }
                    } catch (urlError) {}
                  }

                  return originalFetch(input, init).then(function (response) {
                    if (isLoginRequest && response.status === 403) {
                      response.clone().json().then(function (data) {
                        if (data && data.code === 'LOGIN_OTP_REQUIRED' &&
                            data.email && window.ExamoAuthBridge) {
                          window.ExamoAuthBridge.savePendingLoginOtp(
                            data.email,
                            remember,
                            loginApiBaseUrl
                          );
                        }
                      }).catch(function () {});
                    }
                    return response;
                  });
                };
              }

              function inspectLoginOtp() {
                try {
                  var heading = Array.from(document.querySelectorAll('form.ex-form h2'))
                    .find(function (node) {
                      return (node.textContent || '').trim().toLowerCase() === 'confirm your login';
                    });

                  if (heading) {
                    var form = heading.closest('form');
                    var emailNode = form && form.querySelector('.ex-sub b');
                    var email = emailNode ? (emailNode.textContent || '').trim() : '';
                    var rememberInput = document.querySelector('form.ex-form input[type="checkbox"]');
                    var remember = rememberInput ? !!rememberInput.checked : true;

                    if (email && window.ExamoAuthBridge) {
                      window.ExamoAuthBridge.savePendingLoginOtp(
                        email,
                        remember,
                        window.__examoApiBaseUrl || ''
                      );
                    }
                    return;
                  }

                  var token = localStorage.getItem('examo_token') ||
                    sessionStorage.getItem('examo_token');
                  if (token && window.ExamoAuthBridge) {
                    window.ExamoAuthBridge.clearPendingLoginOtp();
                  }
                } catch (error) {
                  // A storage or DOM timing issue must not interrupt the login page.
                }
              }

              inspectLoginOtp();
              if (window.__examoLoginOtpRecoveryObserver) {
                window.__examoLoginOtpRecoveryObserver.disconnect();
              }
              window.__examoLoginOtpRecoveryObserver = new MutationObserver(inspectLoginOtp);
              window.__examoLoginOtpRecoveryObserver.observe(document.documentElement, {
                childList: true,
                subtree: true,
                characterData: true
              });
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun getPendingLoginOtp(): PendingLoginOtp? {
        val email = authRecoveryPrefs.getString("login_otp_email", null)
            ?.trim()
            .orEmpty()
        val startedAt = authRecoveryPrefs.getLong("login_otp_started_at", 0L)

        // Keep the verification page until the user explicitly restarts or
        // the login succeeds. The backend enforces the OTP's 10-minute validity;
        // local UI recovery must not discard the page while Gmail is open.
        if (email.isBlank() || startedAt <= 0L ||
            System.currentTimeMillis() - startedAt > 24 * 60 * 60 * 1000L
        ) {
            clearSavedLoginOtp()
            return null
        }

        val apiBaseUrl = authRecoveryPrefs.getString("login_otp_api_base", null)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { candidate ->
                val parsed = Uri.parse(candidate)
                parsed.scheme.equals("https", ignoreCase = true) &&
                    !parsed.host.isNullOrBlank()
            }
            ?: getString(R.string.examo_api_url).trimEnd('/')

        return PendingLoginOtp(
            email = email,
            remember = authRecoveryPrefs.getBoolean("login_otp_remember", true),
            startedAt = startedAt,
            apiBaseUrl = apiBaseUrl
        )
    }

    private fun clearSavedLoginOtp() {
        authRecoveryPrefs.edit()
            .remove("login_otp_email")
            .remove("login_otp_api_base")
            .remove("login_otp_remember")
            .remove("login_otp_started_at")
            .apply()
    }

    private fun isExamoWebsiteUrl(url: String?): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
        return host == "examoaspirants.co.in" || host == "www.examoaspirants.co.in"
    }

    private fun persistLastExamoUrl(url: String?) {
        if (!isExamoWebsiteUrl(url)) return

        val safeUrl = if (getPendingLoginOtp() != null) {
            getString(R.string.examo_website_url)
        } else {
            runCatching {
                Uri.parse(url!!).buildUpon().clearQuery().fragment(null).build().toString()
            }.getOrDefault(getString(R.string.examo_website_url))
        }

        authRecoveryPrefs.edit()
            .putString("last_examo_url", safeUrl)
            .apply()
    }

    private fun recreateMainWebViewAfterRendererGone(crashedView: WebView) {
        if (isFinishing || isDestroyed || !::root.isInitialized) return

        val pending = getPendingLoginOtp()
        val childIndex = root.indexOfChild(crashedView).coerceAtLeast(0)
        val layoutParams = crashedView.layoutParams
            ?: FrameLayout.LayoutParams(-1, -1)

        runCatching { root.removeView(crashedView) }
        runCatching { crashedView.destroy() }

        val replacement = createWebView()
        mainWebViewPageReady = false
        webView = replacement
        root.addView(replacement, childIndex, layoutParams)

        val shouldShowAuth = pending != null || authSheetOpen
        replacement.visibility = if (shouldShowAuth) View.VISIBLE else View.INVISIBLE
        replacement.translationY = if (shouldShowAuth) 0f else root.height.toFloat()
        if (pending != null) authSheetOpen = true

        val savedUrl = authRecoveryPrefs.getString("last_examo_url", null)
        val targetUrl = if (pending != null) {
            getString(R.string.examo_website_url)
        } else if (isExamoWebsiteUrl(savedUrl)) {
            savedUrl!!
        } else {
            getString(R.string.examo_website_url)
        }
        replacement.loadUrl(targetUrl)
    }



    private fun restorePendingLoginOtpIfNeeded(view: WebView?, url: String?) {
        val pending = getPendingLoginOtp() ?: return

        // Do not depend on the WebView URL/DOM. The OTP screen is a native
        // Activity overlay and stays visible even if the page navigates/reloads.
        if (::webView.isInitialized) {
            webView.animate().cancel()
            webView.visibility = View.VISIBLE
            webView.translationY = 0f
        }
        authSheetOpen = true
        showNativePendingLoginOtpScreen(pending)
    }

    private fun showNativePendingLoginOtpScreen(pending: PendingLoginOtp) {
        if (!::root.isInitialized || isFinishing || isDestroyed) return

        val existing = pendingOtpOverlay
        if (existing != null && existing.parent === root &&
            pendingOtpEmailShown.equals(pending.email, ignoreCase = true)
        ) {
            existing.visibility = View.VISIBLE
            existing.bringToFront()
            return
        }

        removeNativePendingLoginOtpScreen()

        fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = dp(radiusDp).toFloat()
                if (strokeColor != null) setStroke(dp(1), strokeColor)
            }
        }

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(239, 244, 252))
            isClickable = true
            isFocusable = true
            elevation = dp(18).toFloat()
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(18), dp(20), dp(18), dp(20))
        }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(26))
            background = rounded(Color.WHITE, 22, Color.rgb(226, 232, 240))
            elevation = dp(5).toFloat()
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "E"
            textSize = 27f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(27, 42, 82), 13)
        }
        brandRow.addView(logo, LinearLayout.LayoutParams(dp(52), dp(52)))

        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val brandName = TextView(this).apply {
            text = "Examo"
            textSize = 25f
            setTextColor(Color.rgb(27, 42, 82))
            typeface = Typeface.DEFAULT_BOLD
        }
        val brandSub = TextView(this).apply {
            text = "Student Management"
            textSize = 12f
            setTextColor(Color.rgb(100, 116, 139))
        }
        brandText.addView(brandName)
        brandText.addView(brandSub)
        brandRow.addView(brandText)

        val title = TextView(this).apply {
            text = "Confirm Your Login"
            textSize = 25f
            setTextColor(Color.rgb(15, 23, 42))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(10))
        }
        val subtitle = TextView(this).apply {
            text = "Enter the 6-digit code sent to\n" + pending.email
            textSize = 15f
            setTextColor(Color.rgb(100, 116, 139))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        val label = TextView(this).apply {
            text = "Verification Code"
            textSize = 14f
            setTextColor(Color.rgb(30, 41, 59))
            typeface = Typeface.DEFAULT_BOLD
        }
        val input = EditText(this).apply {
            hint = "------"
            textSize = 24f
            letterSpacing = 0.28f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(6))
            setTextColor(Color.rgb(15, 23, 42))
            setHintTextColor(Color.rgb(148, 163, 184))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.rgb(244, 247, 252), 10, Color.rgb(211, 219, 232))
        }

        val status = TextView(this).apply {
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(254, 242, 242), 9, Color.rgb(254, 202, 202))
            visibility = View.GONE
        }

        val verify = Button(this).apply {
            text = "Verify & Login"
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(35, 74, 150), 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val help = TextView(this).apply {
            text = "You can switch to Gmail to read your code. Return to Examo from Recent Apps; this verification screen will stay here."
            textSize = 13f
            setTextColor(Color.rgb(100, 116, 139))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }

        val restart = Button(this).apply {
            text = "Start login again"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.rgb(27, 95, 214))
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            visibility = View.GONE
        }

        card.addView(brandRow, LinearLayout.LayoutParams(-2, -2))
        card.addView(title, LinearLayout.LayoutParams(-1, -2))
        card.addView(subtitle, LinearLayout.LayoutParams(-1, -2))
        card.addView(label, LinearLayout.LayoutParams(-1, -2))
        card.addView(input, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(8) })
        card.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        card.addView(verify, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(18) })
        card.addView(help, LinearLayout.LayoutParams(-1, -2))
        card.addView(restart, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        center.addView(card, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(center, FrameLayout.LayoutParams(-1, -1))
        overlay.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        pendingOtpOverlay = overlay
        pendingOtpEmailShown = pending.email
        pendingOtpInput = input
        pendingOtpStatus = status
        pendingOtpVerifyButton = verify
        pendingOtpRestartButton = restart

        verify.setOnClickListener {
            val code = input.text?.toString()?.trim().orEmpty()
            handlePendingLoginOtpVerification(pending.email, code)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                verify.performClick()
                true
            } else false
        }
        restart.setOnClickListener {
            clearSavedLoginOtp()
            loginOtpVerificationInProgress = false
            pendingOtpCodeToVerify = null
            removeNativePendingLoginOtpScreen()
            if (::webView.isInitialized) {
                webView.visibility = View.VISIBLE
                webView.translationY = 0f
                authSheetOpen = true
                webView.loadUrl(getString(R.string.examo_website_url))
            }
        }

        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.bringToFront()
    }

    private fun removeNativePendingLoginOtpScreen() {
        pendingOtpOverlay?.let { view ->
            if (view.parent === root) root.removeView(view)
        }
        pendingOtpOverlay = null
        pendingOtpEmailShown = null
        pendingOtpInput = null
        pendingOtpStatus = null
        pendingOtpVerifyButton = null
        pendingOtpRestartButton = null
    }

    private fun setOtpRecoveryStatus(
        message: String,
        isError: Boolean,
        expired: Boolean = false,
        busy: Boolean = false
    ) {
        pendingOtpStatus?.let { status ->
            status.text = message
            status.setTextColor(
                if (isError) Color.rgb(185, 28, 28) else Color.rgb(29, 78, 216)
            )
            status.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9).toFloat()
                setColor(if (isError) Color.rgb(254, 242, 242) else Color.rgb(239, 246, 255))
                setStroke(
                    dp(1),
                    if (isError) Color.rgb(254, 202, 202) else Color.rgb(191, 219, 254)
                )
            }
            status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        }

        pendingOtpVerifyButton?.let { button ->
            button.isEnabled = !busy
            button.text = if (busy) "Verifying..." else "Verify & Login"
        }
        pendingOtpRestartButton?.visibility = if (expired) View.VISIBLE else View.GONE

        val script = """
            (function () {
              if (window.showExamoOtpStatus) {
                window.showExamoOtpStatus(
                  ${JSONObject.quote(message)},
                  ${isError},
                  ${expired},
                  ${busy}
                );
              }
            })();
        """.trimIndent()
        if (::webView.isInitialized) webView.evaluateJavascript(script, null)
    }


    private fun handlePendingLoginOtpVerification(email: String?, otp: String?) {
        if (loginOtpVerificationInProgress) return

        val pending = getPendingLoginOtp()
        if (pending == null) {
            setOtpRecoveryStatus(
                "Your login code has expired. Please start login again.",
                isError = true,
                expired = true
            )
            return
        }

        if (!email.isNullOrBlank() &&
            !email.trim().equals(pending.email, ignoreCase = true)
        ) {
            setOtpRecoveryStatus(
                "This login request does not match the pending account. Please reopen Examo login.",
                isError = true
            )
            return
        }

        val code = otp?.trim().orEmpty()
        if (!code.matches(Regex("^\\d{6}$"))) {
            setOtpRecoveryStatus("Enter the 6-digit code.", isError = true)
            return
        }

        if (!mainWebViewPageReady) {
            pendingOtpCodeToVerify = code
            setOtpRecoveryStatus(
                "Connecting securely to Examo...",
                isError = false,
                busy = true
            )
            return
        }

        loginOtpVerificationInProgress = true
        setOtpRecoveryStatus(
            "Verifying your login securely...",
            isError = false,
            busy = true
        )

        // Use the same browser fetch stack, API origin and CORS path used by
        // Auth.jsx for normal login. This avoids the empty native HTTP 200 body.
        val endpointJson = JSONObject.quote(
            "${pending.apiBaseUrl.trimEnd('/')}/Auth/verify-login-otp"
        )
        val emailJson = JSONObject.quote(pending.email)
        val otpJson = JSONObject.quote(code)
        val script = """
            (async function () {
              try {
                const response = await fetch(${endpointJson}, {
                  method: 'POST',
                  headers: {
                    'Accept': 'application/json',
                    'Content-Type': 'application/json'
                  },
                  body: JSON.stringify({
                    email: ${emailJson},
                    otp: ${otpJson}
                  })
                });

                const responseText = await response.text();
                let data = {};
                if (responseText && responseText.trim()) {
                  try {
                    data = JSON.parse(responseText);
                  } catch (parseError) {
                    throw new Error('The login server returned an unreadable response (HTTP ' + response.status + ').');
                  }
                } else if (response.ok) {
                  throw new Error('The login server returned an empty response (HTTP ' + response.status + '). Please retry.');
                }

                if (!response.ok) {
                  throw new Error(
                    data.message || data.Message || data.title ||
                    ('OTP verification failed (HTTP ' + response.status + ').')
                  );
                }

                const nested = data.data || data.Data || {};
                const token = data.token || data.Token ||
                  data.accessToken || data.AccessToken ||
                  nested.token || nested.Token ||
                  nested.accessToken || nested.AccessToken;

                const user = data.user || data.User ||
                  nested.user || nested.User || nested || {};

                if (!token) {
                  throw new Error('The server did not return a login token. Please retry.');
                }

                window.ExamoAuthBridge.completePendingLoginOtp(
                  String(token),
                  JSON.stringify(user)
                );
              } catch (error) {
                const message = (error && error.message) ||
                  'Could not verify your code. Please check your connection and retry.';
                const expired = /expired|too many wrong attempts/i.test(message);
                window.ExamoAuthBridge.failPendingLoginOtp(message, expired);
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
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
              var dashboardCapBooksSvg = `
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 180 130">
                  <defs>
                    <linearGradient id="capTop" x1="0" y1="0" x2="1" y2="1">
                      <stop offset="0" stop-color="#244f91"/><stop offset=".55" stop-color="#102f66"/><stop offset="1" stop-color="#071d43"/>
                    </linearGradient>
                    <linearGradient id="redBook" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0" stop-color="#ff7770"/><stop offset="1" stop-color="#d93243"/>
                    </linearGradient>
                    <linearGradient id="goldBook" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0" stop-color="#ffe16c"/><stop offset="1" stop-color="#f49a16"/>
                    </linearGradient>
                    <linearGradient id="blueBook" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0" stop-color="#3d9cff"/><stop offset="1" stop-color="#0755c9"/>
                    </linearGradient>
                    <linearGradient id="pages" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#e8f0fc"/>
                    </linearGradient>
                    <filter id="shadow" x="-20%" y="-20%" width="140%" height="160%">
                      <feGaussianBlur stdDeviation="3"/>
                    </filter>
                  </defs>
                  <ellipse cx="91" cy="119" rx="68" ry="6" fill="#446a9d" opacity=".18" filter="url(#shadow)"/>
                  <g stroke-linejoin="round">
                    <!-- bottom blue volume -->
                    <path d="M19 99 Q19 94 26 93 L145 87 Q153 87 158 93 L161 104 Q162 109 155 111 L39 121 Q30 122 25 116 Z" fill="url(#blueBook)"/>
                    <path d="M25 103 L145 97 Q151 97 156 101 L155 108 L39 118 Q31 119 27 114 Z" fill="url(#pages)"/>
                    <path d="M28 114 Q33 121 42 120 L157 110" fill="none" stroke="#0647aa" stroke-width="2"/>
                    <path d="M38 105 L145 100" stroke="#d7e7ff" stroke-width="1.2" opacity=".9"/>
                    <!-- middle golden volume -->
                    <path d="M24 77 Q23 72 31 71 L145 65 Q153 65 158 71 L161 81 Q162 86 155 88 L42 98 Q32 99 28 93 Z" fill="url(#goldBook)"/>
                    <path d="M30 81 L145 75 Q152 75 157 79 L155 85 L42 95 Q34 96 30 91 Z" fill="url(#pages)"/>
                    <path d="M30 91 Q35 98 44 97 L156 87" fill="none" stroke="#e68a0b" stroke-width="2"/>
                    <path d="M43 82 L145 77" stroke="#fff5ce" stroke-width="1.2" opacity=".9"/>
                    <!-- top red volume -->
                    <path d="M31 55 Q30 50 38 49 L145 44 Q152 44 157 50 L160 60 Q160 65 153 67 L47 76 Q38 77 34 71 Z" fill="url(#redBook)"/>
                    <path d="M36 59 L145 54 Q152 54 156 58 L154 64 L47 73 Q40 74 36 69 Z" fill="url(#pages)"/>
                    <path d="M36 69 Q41 76 49 75 L155 66" fill="none" stroke="#cf2638" stroke-width="2"/>
                    <path d="M49 60 L145 56" stroke="#fff0ee" stroke-width="1.2" opacity=".9"/>
                    <!-- graduation cap base -->
                    <path d="M48 32 L90 43 L132 31 L129 43 Q91 65 52 43 Z" fill="#102e62"/>
                    <path d="M48 32 L90 43 L132 31" fill="none" stroke="#315a91" stroke-width="1.4"/>
                    <!-- cap mortarboard -->
                    <path d="M17 27 L87 5 L163 27 L89 52 Z" fill="url(#capTop)" stroke="#315b96" stroke-width="1.5"/>
                    <path d="M27 27 L87 9 L153 27 L89 48 Z" fill="#1a3d79" opacity=".7"/>
                    <path d="M137 22 L148 57" stroke="#f7bd32" stroke-width="3" stroke-linecap="round" fill="none"/>
                    <circle cx="137" cy="22" r="3" fill="#ffd45b"/>
                    <path d="M148 56 Q151 61 149 66 L146 72 Q143 75 140 72 L138 65 Q137 60 141 56 Z" fill="#f7b92f"/>
                    <path d="M142 58 L144 70 M146 58 L145 70" stroke="#ffe08a" stroke-width="1.2" stroke-linecap="round"/>
                    <!-- glints -->
                    <path d="M22 14 L26 8 M13 19 L7 16 M35 10 L37 5" stroke="#2b83ff" stroke-width="2.3" stroke-linecap="round"/>
                  </g>
                </svg>`;
              var dashboardCapBooksUrl = 'url("data:image/svg+xml;charset=UTF-8,' + encodeURIComponent(dashboardCapBooksSvg) + '")';
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
                /* Premium mobile Subscription UI – presentation only; preserve all existing handlers. */
                '.examo-mobile-subscription-page{display:flex!important;flex-direction:column!important;align-items:stretch!important;gap:0!important;width:100%!important;min-width:0!important;padding-bottom:14px!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;color:#101b39!important;background:transparent!important}' +
                '.examo-mobile-subscription-page .hs-main,.examo-mobile-subscription-page .hs-side{display:contents!important}' +
                '.examo-mobile-subscription-page .hs-hero{position:relative!important;isolation:isolate!important;order:1!important;display:block!important;width:100%!important;min-width:0!important;margin:0 0 13px!important;padding:47px 13px 11px!important;overflow:hidden!important;text-align:left!important;background:linear-gradient(118deg,#e7edff 0%,#eee8ff 58%,#dfdcff 100%)!important;border:1px solid #d4d8ff!important;border-radius:18px!important;box-shadow:0 5px 13px rgba(70,68,160,.13),inset 0 1px 0 rgba(255,255,255,.95)!important}' +
                '.examo-mobile-subscription-page .hs-hero:before{content:"♛  Premium Plans";position:absolute;z-index:3;top:10px;left:13px;display:inline-flex;align-items:center;min-height:25px;padding:4px 11px;border-radius:999px;background:linear-gradient(100deg,#33208e,#4b24b5);color:#fff;font-family:"Roboto Condensed","Arial Narrow",sans-serif;font-size:12px;font-weight:800;letter-spacing:.1px;box-shadow:0 2px 5px rgba(48,29,130,.18)}' +
                '.examo-mobile-subscription-page .hs-hero-title{position:relative!important;z-index:2!important;display:flex!important;align-items:center!important;justify-content:space-between!important;gap:3px!important;min-height:97px!important;margin:0!important;text-align:left!important}' +
                '.examo-mobile-subscription-page .hs-hero-title h1{position:relative!important;z-index:2!important;flex:1 1 64%!important;width:64%!important;max-width:66%!important;margin:0!important;color:#111d43!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:clamp(20px,5.4vw,24px)!important;line-height:1.02!important;font-weight:900!important;letter-spacing:-.65px!important;text-align:left!important}' +
                '.examo-mobile-subscription-page .hs-hero-title h1:after{content:"Choose a plan that fits your learning goals.";display:block!important;margin:7px 0 0!important;color:#1f2d50!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:clamp(10px,2.9vw,12px)!important;line-height:1.28!important;font-weight:500!important;letter-spacing:0!important}' +
                '.examo-mobile-subscription-page .hs-hero-title:after{content:none!important;display:none!important}' +
                '.examo-mobile-subscription-page .hs-hero-title .examo-subscription-books-image{display:block!important;flex:0 0 34%!important;width:34%!important;max-width:34%!important;height:108px!important;min-width:0!important;object-fit:contain!important;object-position:center!important;align-self:center!important;position:relative!important;z-index:3!important;margin:-2px -1px 0 0!important;padding:0!important;border:0!important;background:transparent!important}' +
                '.examo-mobile-subscription-page .hs-search{position:relative!important;z-index:2!important;display:flex!important;align-items:center!important;width:100%!important;max-width:none!important;height:40px!important;margin:4px 0 0!important;padding:3px 4px 3px 12px!important;background:#fff!important;border:1px solid #d5d8fa!important;border-radius:999px!important;box-shadow:0 3px 8px rgba(57,48,129,.11)!important}' +
                '.examo-mobile-subscription-page .hs-search input{flex:1 1 auto!important;min-width:0!important;height:32px!important;padding:0 4px!important;border:0!important;outline:0!important;background:transparent!important;color:#28385b!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important}' +
                '.examo-mobile-subscription-page .hs-search input::placeholder{color:#71809a!important;opacity:1!important}' +
                '.examo-mobile-subscription-page .hs-search button{display:flex!important;align-items:center!important;justify-content:center!important;flex:0 0 32px!important;width:32px!important;height:32px!important;min-width:32px!important;padding:0!important;background:linear-gradient(135deg,#6b21d8,#4b19b5)!important;color:#fff!important;border:0!important;border-radius:50%!important}' +
                '.examo-mobile-subscription-page .hs-side>h2{order:2!important;position:relative!important;margin:2px 0 7px!important;padding-left:11px!important;color:#111b3c!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:16px!important;line-height:1.2!important;font-weight:850!important;letter-spacing:-.2px!important}' +
                '.examo-mobile-subscription-page .hs-side>h2:before,.examo-mobile-subscription-page .hs-main>.hs-heading:before{content:"";position:absolute;left:0;top:1px;bottom:1px;width:4px;border-radius:4px;background:linear-gradient(180deg,#7c3aed,#4f19c8)}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1){order:3!important;position:relative!important;display:grid!important;gap:8px!important;width:100%!important;min-width:0!important;margin:0 0 14px!important;padding:12px!important;background:linear-gradient(145deg,#ffffff 0%,#f1efff 100%)!important;border:1px solid #d8d8f6!important;border-radius:16px!important;box-shadow:0 5px 13px rgba(54,50,128,.10),inset 0 1px 0 rgba(255,255,255,.95)!important}' +
                '.examo-mobile-subscription-page .hs-panel h3{margin:0!important;color:#111b3c!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:15px!important;line-height:1.2!important;font-weight:850!important;letter-spacing:-.15px!important}' +
                '.examo-mobile-subscription-page .hs-panel-row{display:flex!important;align-items:center!important;justify-content:space-between!important;gap:8px!important;min-width:0!important}' +
                '.examo-mobile-subscription-page .hs-panel-row:first-child{min-height:35px!important;padding-left:45px!important;position:relative!important}' +
                '.examo-mobile-subscription-page .hs-panel-row:first-child:before{content:"♛";position:absolute;left:0;top:0;width:34px;height:34px;display:flex;align-items:center;justify-content:center;border-radius:10px;background:linear-gradient(135deg,#7626dc,#4511b4);color:#fff3a7;font-size:20px;box-shadow:0 3px 7px rgba(71,25,166,.2)}' +
                '.examo-mobile-subscription-page .hs-panel-row:first-child h3{font-size:16px!important}' +
                '.examo-mobile-subscription-page .hs-status-icon{display:inline-flex!important;align-items:center!important;justify-content:center!important;min-width:19px!important;color:#16a34a!important}' +
                '.examo-mobile-subscription-page .hs-status-icon svg{width:19px!important;height:19px!important}' +
                '.examo-mobile-subscription-page .hs-panel>p{margin:0!important;color:#263653!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;line-height:1.3!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-panel>p strong{color:#14213f!important;font-weight:850!important}' +
                '.examo-mobile-subscription-page .hs-panel .hs-active{color:#168343!important;font-weight:850!important}' +
                '.examo-mobile-subscription-page .hs-panel hr{width:100%!important;margin:2px 0!important;border:0!important;border-top:1px solid rgba(87,79,157,.17)!important}' +
                '.examo-mobile-subscription-page .hs-panel>.hs-btn{display:flex!important;align-items:center!important;justify-content:center!important;width:100%!important;min-height:37px!important;margin:1px 0!important;padding:7px 10px!important;border:0!important;border-radius:9px!important;background:linear-gradient(100deg,#6720d3,#4c17bd)!important;color:#fff!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;line-height:1.2!important;font-weight:850!important;box-shadow:0 3px 7px rgba(85,26,174,.2)!important}' +
                '.examo-mobile-subscription-page .hs-panel-row:nth-of-type(2){min-height:29px!important;padding:5px 8px!important;border-radius:8px!important;background:linear-gradient(100deg,#eeebff,#e4e2ff)!important}' +
                '.examo-mobile-subscription-page .hs-panel-row:nth-of-type(2) h3{font-size:12px!important}' +
                '.examo-mobile-subscription-page .hs-panel-row:nth-of-type(2)>svg{width:19px!important;height:19px!important;color:#5540a3!important}' +
                '.examo-mobile-subscription-page .hs-payments{display:grid!important;gap:5px!important;margin:0!important;padding:0!important;list-style:none!important}' +
                '.examo-mobile-subscription-page .hs-payments li{display:flex!important;align-items:center!important;justify-content:space-between!important;gap:7px!important;min-width:0!important;padding:5px 8px!important;background:rgba(255,255,255,.8)!important;border:1px solid #dedafa!important;border-radius:9px!important;color:#293956!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:11px!important;line-height:1.25!important}' +
                '.examo-mobile-subscription-page .hs-payments li span{flex:1 1 auto!important;min-width:0!important;overflow-wrap:anywhere!important;font-size:11px!important;color:#293956!important}' +
                '.examo-mobile-subscription-page .hs-payments .hs-btn,.examo-mobile-subscription-page .hs-btn-sm{display:inline-flex!important;align-items:center!important;justify-content:center!important;flex:0 0 auto!important;width:auto!important;min-width:82px!important;min-height:29px!important;padding:5px 9px!important;border:1px solid #7727dd!important;border-radius:8px!important;background:#fff!important;color:#6421c9!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:11px!important;font-weight:850!important;white-space:nowrap!important;box-shadow:none!important}' +
                '.examo-mobile-subscription-page .hs-main>.hs-success-top{order:4!important;margin:0 0 8px!important;padding:8px 10px!important;border-radius:9px!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:11px!important}' +
                '.examo-mobile-subscription-page .hs-main>.hs-heading{order:5!important;position:relative!important;margin:2px 0 8px!important;padding-left:12px!important;color:#111b3c!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:18px!important;line-height:1.2!important;font-weight:850!important;letter-spacing:-.2px!important}' +
                '.examo-mobile-subscription-page .hs-card-grid{order:6!important;display:grid!important;grid-template-columns:minmax(0,1fr)!important;gap:10px!important;width:100%!important;min-width:0!important;align-items:stretch!important;margin:0 0 13px!important}' +
                '.examo-mobile-subscription-page .hs-plan-card{display:flex!important;flex-direction:column!important;width:100%!important;min-width:0!important;min-height:0!important;height:auto!important;background:linear-gradient(145deg,#ffffff 0%,#f8fbff 100%)!important;border:1px solid #d9e0ef!important;border-radius:14px!important;box-shadow:0 4px 10px rgba(36,45,91,.08),inset 0 1px 0 rgba(255,255,255,.95)!important;overflow:hidden!important}' +
                '.examo-mobile-subscription-page .hs-plan-card.current{border-width:2px!important;box-shadow:0 5px 12px rgba(80,39,174,.12)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(1){border-color:#c9ebc9!important;background:linear-gradient(145deg,#f4fff2 0%,#ffffff 100%)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(2){border-color:#d6a2ff!important;background:linear-gradient(145deg,#fbf5ff 0%,#ffffff 100%)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(3){border-color:#ffd49a!important;background:linear-gradient(145deg,#fffaf0 0%,#ffffff 100%)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head{position:relative!important;display:flex!important;flex-direction:row!important;align-items:center!important;justify-content:space-between!important;gap:7px!important;min-height:42px!important;padding:8px 12px!important;border-bottom:1px solid rgba(57,70,104,.08)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(1) .hs-faq-card-head{background:linear-gradient(100deg,#ddf7d9 0%,#e9fbe7 100%)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(2) .hs-faq-card-head{background:linear-gradient(100deg,#f0e1ff 0%,#e9d9ff 100%)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(3) .hs-faq-card-head{background:linear-gradient(100deg,#ffedd0 0%,#ffe1bb 100%)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head h3{flex:1 1 auto!important;min-width:0!important;margin:0!important;color:#111c3b!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:15px!important;line-height:1.18!important;font-weight:900!important;letter-spacing:-.2px!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head svg{display:none!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head:before{display:inline-flex!important;align-items:center!important;justify-content:center!important;flex:0 0 22px!important;width:22px!important;height:22px!important;font-size:19px!important;line-height:1!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(1) .hs-faq-card-head:before{content:"🌿";}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(2) .hs-faq-card-head:before{content:"⚡";}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(3) .hs-faq-card-head:before{content:"🏆";}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head:after{display:inline-flex!important;align-items:center!important;justify-content:center!important;flex:0 0 auto!important;min-height:21px!important;padding:3px 8px!important;border-radius:999px!important;color:#fff!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:10px!important;line-height:1!important;font-weight:900!important;white-space:nowrap!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(1) .hs-faq-card-head:after{content:"Free";background:linear-gradient(100deg,#1aa83c,#0b9230)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(2) .hs-faq-card-head:after{content:"★ Most Popular";background:linear-gradient(100deg,#7c20df,#5b14ca)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(3) .hs-faq-card-head:after{content:"♛ Best Value";background:linear-gradient(100deg,#f43e77,#e91553)!important}' +
                '.examo-mobile-subscription-page .hs-plan-body{display:grid!important;grid-template-columns:minmax(0,1fr) minmax(112px,39%)!important;grid-template-areas:"features features" "price button" "sub button"!important;column-gap:10px!important;row-gap:0!important;align-items:center!important;min-width:0!important;padding:9px 12px 11px!important}' +
                '.examo-mobile-subscription-page .hs-plan-label{display:none!important}' +
                '.examo-mobile-subscription-page .hs-plan-features{grid-area:features!important;display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr))!important;column-gap:11px!important;row-gap:4px!important;margin:0 0 9px!important;padding:0!important;list-style:none!important;color:#263b59!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:11px!important;line-height:1.25!important}' +
                '.examo-mobile-subscription-page .hs-plan-features li{position:relative!important;min-width:0!important;margin:0!important;padding:0 0 0 14px!important;list-style:none!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-plan-features li:before{content:"✓";position:absolute;left:0;top:0;color:#1d9a43;font-size:12px;font-weight:950;line-height:1.1}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(2) .hs-plan-features li:before{color:#6920cb}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(3) .hs-plan-features li:before{color:#c27a12}' +
                '.examo-mobile-subscription-page .hs-plan-price{grid-area:price!important;align-self:end!important;margin:0!important;color:#101a39!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:21px!important;line-height:1.1!important;font-weight:950!important;letter-spacing:-.35px!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-plan-sub{grid-area:sub!important;align-self:start!important;min-height:0!important;margin:2px 0 0!important;color:#53627b!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:11px!important;line-height:1.22!important;overflow-wrap:anywhere!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(1) .hs-plan-price{font-size:17px!important;color:#117b31!important}' +
                '.examo-mobile-subscription-page .hs-plan-card .hs-btn{grid-area:button!important;align-self:center!important;justify-self:stretch!important;display:inline-flex!important;align-items:center!important;justify-content:center!important;width:100%!important;min-width:0!important;min-height:36px!important;margin:3px 0!important;padding:6px 8px!important;border:0!important;border-radius:9px!important;background:linear-gradient(100deg,#6720d3,#4c17bd)!important;color:#fff!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:11px!important;line-height:1.18!important;font-weight:900!important;white-space:normal!important;overflow-wrap:anywhere!important;box-shadow:0 3px 7px rgba(82,26,168,.19)!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(1) .hs-btn:disabled{background:#99a3b0!important;color:#fff!important;box-shadow:none!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(2) .hs-btn{background:linear-gradient(100deg,#9b8bca,#8a7ab9)!important;color:#fff!important}' +
                '.examo-mobile-subscription-page .hs-plan-card:nth-child(3) .hs-btn{background:linear-gradient(100deg,#ff9f00,#fa165f)!important;color:#fff!important;box-shadow:0 3px 7px rgba(245,93,37,.20)!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(2){order:7!important;position:relative!important;display:grid!important;grid-template-columns:40px minmax(0,1fr)!important;grid-template-areas:"icon title" "icon text" "icon link"!important;align-items:center!important;column-gap:10px!important;row-gap:4px!important;width:100%!important;min-width:0!important;margin:0 0 10px!important;padding:12px!important;background:linear-gradient(110deg,#f8faff 0%,#eef3ff 100%)!important;border:1px solid #cbd8ff!important;border-radius:14px!important;box-shadow:0 4px 10px rgba(55,76,145,.09)!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(2):before{content:"🎧";grid-area:icon;display:flex;align-items:center;justify-content:center;width:40px;height:40px;border-radius:11px;background:#e8e8ff;color:#5921c8;font-size:25px}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(2) h3{grid-area:title!important;font-size:14px!important;line-height:1.2!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(2) .hs-panel-text{grid-area:text!important;margin:0!important;font-size:11px!important;line-height:1.3!important;color:#4a5b79!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(2) .hs-link-btn{grid-area:link!important;display:inline-flex!important;align-items:center!important;align-self:start!important;margin-top:1px!important;padding:0!important;color:#5d1fc8!important;font-family:"Roboto Condensed","Arial Narrow",sans-serif!important;font-size:12px!important;font-weight:900!important}' +
                '@media(max-width:380px){.examo-mobile-subscription-page .hs-hero{padding:44px 10px 9px!important}.examo-mobile-subscription-page .hs-hero-title{min-height:91px!important}.examo-mobile-subscription-page .hs-hero-title:after{flex-basis:32%!important;width:32%!important;height:91px!important}.examo-mobile-subscription-page .hs-hero-title h1{font-size:20px!important;max-width:66%!important}.examo-mobile-subscription-page .hs-hero-title h1:after{font-size:10px!important}.examo-mobile-subscription-page .hs-plan-body{grid-template-columns:minmax(0,1fr) minmax(104px,38%)!important;column-gap:7px!important;padding:8px 10px 10px!important}.examo-mobile-subscription-page .hs-plan-features{column-gap:7px!important;font-size:10.5px!important}.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head{padding:8px 10px!important}.examo-mobile-subscription-page .hs-plan-card .hs-faq-card-head h3{font-size:14px!important}.examo-mobile-subscription-page .hs-plan-card .hs-btn{font-size:10px!important}}' +
                '@media(max-width:330px){.examo-mobile-subscription-page .hs-hero-title:after{flex-basis:29%!important;width:29%!important;height:82px!important}.examo-mobile-subscription-page .hs-hero-title h1{max-width:70%!important;font-size:18px!important}.examo-mobile-subscription-page .hs-plan-body{grid-template-columns:minmax(0,1fr)!important;grid-template-areas:"features" "price" "sub" "button"!important}.examo-mobile-subscription-page .hs-plan-card .hs-btn{margin-top:8px!important}.examo-mobile-subscription-page .hs-plan-features{grid-template-columns:minmax(0,1fr)!important}}' +
                /* Subscription details: compact the card's vertical footprint while retaining readable text. */
                '.examo-mobile-subscription-page .hs-side>h2{font-size:19px!important;line-height:1.2!important;margin:2px 0 6px!important;padding-left:11px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1){position:relative!important;display:grid!important;grid-template-columns:minmax(0,1fr)!important;gap:4px!important;margin:0 0 10px!important;padding:10px!important;border-radius:15px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>.hs-panel-row:first-child{min-height:31px!important;padding-left:38px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>.hs-panel-row:first-child:before{width:29px!important;height:29px!important;border-radius:9px!important;font-size:18px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>.hs-panel-row:first-child h3{font-size:16px!important;line-height:1.15!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>.hs-panel-row:first-child .hs-status-icon svg{width:18px!important;height:18px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>p{font-size:13px!important;line-height:1.2!important;margin:0!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>p:first-of-type{position:absolute!important;top:14px!important;right:42px!important;z-index:2!important;display:inline-flex!important;align-items:center!important;gap:3px!important;min-height:20px!important;padding:3px 7px!important;border-radius:999px!important;background:#e0f7e8!important;color:#168343!important;font-size:12px!important;line-height:1!important;white-space:nowrap!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>p:first-of-type strong{display:none!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>p:first-of-type .hs-active{color:#168343!important;font-size:12px!important;font-weight:850!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>p:nth-of-type(2),.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>p:nth-of-type(3){min-height:18px!important;display:flex!important;align-items:center!important;gap:4px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>hr{margin:1px 0!important;border-top:1px solid rgba(87,79,157,.16)!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>.hs-btn{min-height:34px!important;margin:0!important;padding:6px 9px!important;font-size:13px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>.hs-panel-row:nth-of-type(2){min-height:26px!important;padding:4px 8px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>.hs-panel-row:nth-of-type(2) h3{font-size:13px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1) .hs-payments{gap:3px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1) .hs-payments li{min-height:33px!important;padding:4px 7px!important;font-size:12px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1) .hs-payments li span{font-size:12px!important}' +
                '.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1) .hs-payments .hs-btn-sm{min-height:27px!important;padding:4px 8px!important;font-size:11px!important}' +
                '@media(max-width:380px){.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1){padding:9px!important;gap:3px!important}.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>.hs-panel-row:first-child{min-height:29px!important}.examo-mobile-subscription-page .hs-side>.hs-panel:nth-of-type(1)>p:first-of-type{right:38px!important}.examo-mobile-subscription-page .hs-side>h2{font-size:18px!important}}' +
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

                /* Dashboard: fixed Quick Actions dock + slightly larger readable sections. */
                '.examo-mobile-dashboard-page{padding-bottom:112px!important;overflow-x:hidden!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card{position:fixed!important;left:12px!important;right:12px!important;bottom:8px!important;z-index:10010!important;width:auto!important;max-width:none!important;min-width:0!important;margin:0!important;padding:9px 9px!important;border-radius:14px!important;border:1px solid #c6d9f4!important;background:linear-gradient(145deg,rgba(239,246,255,.98) 0%,rgba(224,237,255,.98) 100%)!important;box-shadow:0 7px 20px rgba(23,58,108,.18),0 1px 3px rgba(23,58,108,.12)!important;backdrop-filter:blur(12px)!important;-webkit-backdrop-filter:blur(12px)!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card h2{font-size:11px!important;line-height:1.2!important;font-weight:600!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card p{font-size:8.5px!important;line-height:1.2!important;margin-top:3px!important}' +
                '.examo-mobile-dashboard-page .quick-actions{display:grid!important;grid-template-columns:repeat(3,minmax(0,1fr))!important;width:100%!important;max-width:100%!important;min-width:0!important;gap:6px!important;margin:7px 0 0!important}' +
                '.examo-mobile-dashboard-page .quick-actions button{min-width:0!important;width:100%!important;min-height:33px!important;padding:5px 3px!important;gap:3px!important;font-size:8px!important;line-height:1.1!important;white-space:nowrap!important;border-radius:9px!important}' +
                '.examo-mobile-dashboard-page .quick-actions button svg{width:13px!important;height:13px!important;flex:0 0 13px!important}' +
                '.examo-mobile-dashboard-page .quick-icon{width:13px!important;height:13px!important;flex:0 0 13px!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading{min-height:76px!important;margin-bottom:8px!important;padding:8px 10px!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading h1{font-size:16px!important;line-height:1.16!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading p:not(.eyebrow){font-size:9.5px!important;line-height:1.2!important}' +
                '.examo-mobile-dashboard-page .stats-grid{margin-bottom:9px!important;padding:5px 3px!important}' +
                '.examo-mobile-dashboard-page .stat-card{gap:3px!important;padding:4px 2px!important}' +
                '.examo-mobile-dashboard-page .stat-icon{width:28px!important;height:28px!important;min-width:28px!important}' +
                '.examo-mobile-dashboard-page .stat-icon svg{width:17px!important;height:17px!important}' +
                '.examo-mobile-dashboard-page .stat-card span{min-height:18px!important;font-size:8.5px!important;line-height:1.12!important}' +
                '.examo-mobile-dashboard-page .stat-card strong{font-size:17px!important;line-height:1.05!important}' +
                '.examo-mobile-dashboard-page .stat-card small{font-size:8px!important;line-height:1.12!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid{gap:8px!important}' +
                '.examo-mobile-dashboard-page .dashboard-card{border-radius:13px!important}' +
                '.examo-mobile-dashboard-page .card-heading{padding:8px 7px!important;gap:4px!important}' +
                '.examo-mobile-dashboard-page .card-heading h2{font-size:10.5px!important;line-height:1.18!important}' +
                '.examo-mobile-dashboard-page .card-heading p{font-size:8px!important;line-height:1.16!important;margin-top:3px!important}' +
                '.examo-mobile-dashboard-page .text-button{font-size:8px!important}' +
                '.examo-mobile-dashboard-page .deadline-item{padding:6px 6px!important;gap:4px!important}' +
                '.examo-mobile-dashboard-page .deadline-item strong{font-size:7.6px!important;line-height:1.15!important}' +
                '.examo-mobile-dashboard-page .deadline-item>div>span{font-size:7.4px!important;line-height:1.13!important}' +
                '.examo-mobile-dashboard-page .status-badge{font-size:7px!important;padding:3px 5px!important}' +
                '.examo-mobile-dashboard-page .dashboard-empty{min-height:48px!important;font-size:8.5px!important}' +
                '.examo-mobile-dashboard-page .calendar{padding:6px 5px!important}' +
                '.examo-mobile-dashboard-page .calendar-weekdays span{font-size:7.5px!important;padding:2px 0!important}' +
                '.examo-mobile-dashboard-page .calendar-day{min-height:17px!important;font-size:7.8px!important}' +
                '.examo-mobile-dashboard-page .calendar-legend{margin-top:6px!important;padding:5px 6px!important;font-size:7.6px!important}' +
                '.examo-mobile-dashboard-page .schedule-list{padding:5px 6px!important}' +
                '.examo-mobile-dashboard-page .schedule-item{padding:6px 5px!important;gap:5px!important}' +
                '.examo-mobile-dashboard-page .schedule-date{min-width:29px!important;font-size:8.5px!important}' +
                '.examo-mobile-dashboard-page .schedule-item strong{font-size:9px!important}' +
                '.examo-mobile-dashboard-page .schedule-item span{font-size:7.5px!important}' +
                '@media(max-width:360px){.examo-mobile-dashboard-page{padding-bottom:106px!important}.examo-mobile-dashboard-page .quick-actions-card{left:8px!important;right:8px!important;bottom:6px!important;padding:8px!important}.examo-mobile-dashboard-page .quick-actions{gap:4px!important}.examo-mobile-dashboard-page .quick-actions button{font-size:7.3px!important;min-height:31px!important}.examo-mobile-dashboard-page .dashboard-grid{gap:6px!important}}' +

                /* One clean graduation-cap + stacked-books illustration, matching the supplied reference. */
                '.examo-mobile-dashboard-page .dashboard-heading{position:relative!important;isolation:isolate!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading>div{width:67%!important;max-width:67%!important;flex:0 1 67%!important;position:relative!important;z-index:3!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading:before{display:none!important;content:none!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading:after{content:""!important;display:block!important;position:absolute!important;right:4px!important;top:50%!important;bottom:auto!important;transform:translateY(-50%)!important;width:106px!important;height:78px!important;z-index:2!important;background-image:' + dashboardCapBooksUrl + '!important;background-repeat:no-repeat!important;background-position:center!important;background-size:contain!important;filter:drop-shadow(0 3px 4px rgba(24,64,115,.16))!important;pointer-events:none!important}' +

                /* Stable dashboard card dimensions. More records must not grow cards. */
                '.examo-mobile-dashboard-page .dashboard-grid{grid-template-columns:repeat(2,minmax(0,1fr))!important;grid-template-rows:repeat(2,clamp(205px,26vh,230px))!important;grid-auto-rows:clamp(205px,26vh,230px)!important;align-items:stretch!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid>.dashboard-card{display:flex!important;flex-direction:column!important;height:100%!important;min-height:0!important;max-height:100%!important;overflow:hidden!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid>.dashboard-card .card-heading{flex:0 0 auto!important;min-height:47px!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid>.dashboard-card .exam-list,.examo-mobile-dashboard-page .dashboard-grid>.dashboard-card .form-deadline-list,.examo-mobile-dashboard-page .dashboard-grid>.dashboard-card .schedule-list{flex:1 1 auto!important;min-height:0!important;max-height:100%!important;overflow:hidden!important}' +
                '.examo-mobile-dashboard-page .exam-list>.exam-list-item:nth-child(n+4),.examo-mobile-dashboard-page .form-deadline-list>.deadline-item:nth-child(n+4),.examo-mobile-dashboard-page .schedule-list>.schedule-item:nth-child(n+4){display:none!important}' +
                '.examo-mobile-dashboard-page .exam-list-item,.examo-mobile-dashboard-page .deadline-item,.examo-mobile-dashboard-page .schedule-item{flex:0 0 auto!important;min-height:0!important}' +
                '.examo-mobile-dashboard-page .deadline-item{padding:6px 6px!important}' +
                '.examo-mobile-dashboard-page .deadline-item strong{font-size:7.4px!important;line-height:1.16!important}' +
                '.examo-mobile-dashboard-page .deadline-item>div>span{font-size:7.1px!important;line-height:1.13!important}' +
                '.examo-mobile-dashboard-page .schedule-item{padding:6px 5px!important}' +
                '.examo-mobile-dashboard-page .schedule-item strong{font-size:8.8px!important}' +
                '.examo-mobile-dashboard-page .schedule-item span{font-size:7.4px!important}' +
                '.examo-mobile-dashboard-page .calendar{flex:1 1 auto!important;min-height:0!important;overflow:hidden!important;padding:5px 4px!important}' +
                '.examo-mobile-dashboard-page .calendar-days{grid-auto-rows:minmax(0,1fr)!important}' +
                '@media(max-width:360px){.examo-mobile-dashboard-page .dashboard-heading>div{width:64%!important;max-width:64%!important;flex-basis:64%!important}.examo-mobile-dashboard-page .dashboard-heading:after{width:96px!important;height:72px!important;right:2px!important}.examo-mobile-dashboard-page .dashboard-grid{grid-template-rows:repeat(2,clamp(195px,25vh,220px))!important;grid-auto-rows:clamp(195px,25vh,220px)!important;gap:6px!important}}' +

                /* Accurate cap-on-books art and stable 3-record dashboard panels. */
                '.examo-mobile-dashboard-page .dashboard-heading:before{content:none!important;display:none!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading:after{content:""!important;display:block!important;position:absolute!important;right:5px!important;top:50%!important;bottom:auto!important;transform:translateY(-50%)!important;width:88px!important;height:80px!important;background-image:url("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAL4AAACvCAMAAAB0IJSRAAAB4FBMVEWZJQoCGGIDGWxcGhbcXRMKKY0lV87l3toSHVzQl20SL5btalUpDSDkpo/64SOuThEBFWynEwHsoBr5aQCVredhj/DQJQp0CgMHJosCdf+oblVhWY+qUwghQJ4CBSICCCngFQ8LCRn//wCyTgTUYgFbWkzQEAG0xuj//6qpmTeuj4fdYgFLeen92kgEHJbVup3exqnUBgft1bITMtagBwiujF3/rAALTu2yrqulBgWNdoj///8A//92dgD1iwcBNtjXtpYBMczXm3XYwqUDMsgAamo7RmkTT+NLPUNoX1+acmS/v3//f38AqgAAqv8+lP1RJD54AwBWJU94WzlpdKGqAFW4RgO/nX//AP/HeU30iAAAAAAUNo7669MMKHL88twCFEv5FhIAJ7ACR/EACjILV/oAN8/7lwLxCQj89eLNAgL4hwG1AQL6pQr2dwHUSQDvZwEDG2kYZPsIKYrkWQD7tgkka/vYVAMpdPoAGov8yAv7ty381RAAO+n7Nyz6IhwAAH6PAQAAAVMcQ6nv1rYAAP/7xy/6KSPryqgCED77Rzb/AAAiSq0ADkETMHsALMczhPsAVaqvOALnw5gROKpyBAD7aFAAATUtVrj51TLVZQEAAKoLBBjbtYzku4/7WUZ1EhITAAAAoHRSTlP6Jlr896L7/ev82f33/P38nRTwCP397gdmAv39E/dhpZ0mAVOd/SH9A/39Yvn8HXSeWe8iX/0DlQme/QEBApxYoowkdccC/Vz9EQoEAgMD/UWQlv1EA4NcAaFLAPz9/P38/Pz8/Pz8/Pz9/Pz8/Pz8/Pv9/Pz8/Pz8/P38/P39/AL8B/z9Afz7/fz8Af38/Pz9A/z9+/38Dv38/AP9+/39SqxXiwAAGu9JREFUeNrtnYdfG1e2xwUIkAGDBQ64QIxLnE1sZxM7TjYvm7LZvm9f72+EKpKAYVQYjUVHFhYYyzYuJNg4tv/Vd84tM/dOkUYgp7zP3o9D6Pr+zvmdc8sMUkD5WY/A3/D/H+LXHp+Gt79+/PhniE/Z2Xj8uPKzwn+BEa+e6u6bmOjuPrVP5FR+JviPGfve5ubmHr7Z7D7VQb5Q/cnjVxF0v7N78/7mZgSGrsMbUNDHFLQ/Ce3Dr5C4d3b3bd6/H7GGHqUKujvRRqfbrCDQRvZ9ZKdxlwZLQh+zUUflp4VfeVxDKoy7k12QAMq4gmrtJ4JfoTyn+vbub05EGoxolNgo0tfNCqH2o+NXHv/aZI80GdHowyiVEGGF0IZ+egT8GmeHWp1oBh95SOCjVAGM7k7spLUjFkLgaOyd3Xt+2DH4Fj6Mh5HIHig48owQOBz7aRL37ohbo3Ev3YiEz5Nw1DntEPjV07TBA/pEJOKXntSumwJzRqj9APhVNjmBZzYj/ofuSo9DBxcxBYeYEQItsyud4JmW2LFuH0YbDKuUlRabUaA1djKx+mPXdQitzntmQ3xTQcuFEPDb4GncbQuaBvB/OHNzePi9MyGdhP7hw6YCaCGcIApOV9uIT9k7yILGb7HefL66ugxj60wU+X3QcwlQCIrvxV3A58TqP+4k9jcHV3EsG8bWkB7xj28qwFL+fXMFgSbsp1uYWC36d5aXVwevXRsEfk3700OoAT3agoTIHp/TmuUg0HRy6uzb8z05cfwhY3l1dZDQZ9WcNvzeO++E2HTr20XWrFxpHb9Gdn0wsbbY4Cn+v2magc43NDWnllUDpDwffO+9d/5givChQPehIODNDjtWvutrdYSGtaymQeSBfqirKzCUy2pgJxDBElH05yLdakbuk3LAe2LdPETc+fosNBRUgT1XKHdNP5ienh5ADUEVckITcYZoKPorBKag4gO/1kHZI4dnJ/zxOBB3dQ1MP4jHQ/BR/MGDBzsDoKGsZrkGkgg/GiAJfeTEpRl+lUxOE5v3NyciRxsPMbo2NBBBEjF0AGYyBDM11aDDJqfDJf726FdJk9yMHH14TVaYCKKBmMnSECo2EqFvbob2m0S/pnRGNjfbwS7JcK7ZQugmWhAHWBDPiZmGPDWgHYvFZvhKR6TnfuRNDd2ZB9BACgLMxAvi5jshWYGOPwnfWQw18X4Fgn+/p+fZm1EAq8+o7uGlaaaBdNet994pCuzRInwLKI2/3cT7FeUE/sD9Z8+e3Z94IwI8jE29RDWo2vLg4Hsh5hmiLk7GuWadp6p0UtlUweYPhs+tRDUcaMvXtv6kPySfmiYt98GDgbebz7oVJVQ0s3b/Wbtt1BjfKocu1bimheI709Nxpunc2/uKH/zzobjgO6Jgoo34yN98uRPvUpcHh6bjOxh2ZD/PZqTms+7+22DEotm+IpugoF1J0HHHrjfd90bj013asjEAnufsStX/ku382+figoTIxLN22qiRg2iLROqgYQxBHVD2/RYWzPtMwYO4MIO000Z6Y/YHUKo700OGNnTu/NihNouWAigE00agoB1J0HXdJQEmO6vVLkPLjSi1/UNuFqtkhnj7XMhmo3YosPOzFSpBJ7U6cO78JcAfU8aOsFWvVqmCAarAtFFPz1EWFjobNnYadbIQIsV60TBy48oXRztpqFIbnRt4wFJQNBU82zxC/2H4Oo87m1cx7mR2GlMuaog/cvSDkqqjGRWPWgiEPiLFnTV49pgjBP+iMt6WYyquYJoIYDbSaSEcYWURNdHjJjvt7+NtxZdLGZsRVaHTQtg8JDuzDG6F2eTER9vxFb5VtjWjw9kI18AWOwxbgxxXxjVDvdgO7zsUnJ/GpVTRpsB/NxLY4xQ+Hj8vz6wjSviN4MOUVu2Mk0fdiRctCXrEZyFY7DzwiF/slBc1X7w5fIXhywqKfvqpHjXZ8Vd8z+njxRM/FH5VOVGMT1uD2Yiq8FQA238Pdlf8C0o4a6hXlE/ajl9BfJHfUsAONBylvLe5FxHYwTTffy//fDSkSEeAdYJ/6Q3gK/uhqOhbu42KrBBYP8W7ekTD2wLP8W1nIIA//IbwO6J6MW6LP1UQF0sZbUSM1ISd4csnaHVl7M3gV5QO3XTxAyeIVMr3J2TDf+/CPo2HoFF761HOE/y2l25V6dRFJocGsRDgf43jzn8iGrXjk+iPj7Q/+p3QQwhV0TzemPZSULQCP+058DfpjtZDzVNvM/4L5QTiyyeRbhKogqbsFn5Foh9RDTV48Q3c09C9p7ufz0hM37MzmunmA5OlS6fHI8oVcmUmeyk8Um8zfp+Ar5tH+eSgb8euwGvs7Ej48aIutp4RZcRgV1UvQSLaiF9TOiIWvi5ei3BJgQc6HfDeNPmP4Iu1e0H5cnV18NrzVUPLjjfhD7RcuW74goCdJujxHWsQBQRft/BxzmJXVdXslSbNszX8DqVzz8TXdfvVIHpWHPdCF/stTQA3j9h6EH+ZeEdT1Xbjdwv4zqtZvA/tuMzJ9sHsw/HrY2F2LnIBmr4BxsdrwuNtxa8p3ZsRL3zOb8+ALe5SAiAF5DBMp5dOxsJ11niw9eTU4FhbG+d/j/WZ+Lr75USeAJYBd3JLQJxFv/ivH38cJo8RDo+EL6nkqnCw6ZLfP369Hv4PpRppiC8lwN94QPGjXXNz84HPPqbhvlQuH5TLQ0PjzXbrPvFJVuF/HzfBPwQ/w789Pz83RyUoV7JgnYODciE4dvS+P1YPUwUfTY7OdO35wo9GW+KP6gOAjwMlBIL8do7cxaOVbr1OZ+3LgL4NIzEfYvi6963KLSVgB0u3OP0tw0cFo9ry8nPKf6l+uX5IfED/3EJf397eSCQSc/NdxYb4Fn/RDz1Z78cHugT8+VGgv7a6in0/+A/AEW4ZH9AvCOiMPUH4vx2AWdI+abng+/DPNF2RAvxtE39uPr+6/Pw54kPvnMR+VK+3hF//q4m+TtDJYPwzKCDuI/q+wg9bMAy9YJ25+YPngL9M78QaTSQmL+NU7B9/jKBPIrrJbtIzAV070ab40ebOQXYBfo6MHER/leKro4n1e9uTYS9+F/y6cnny3uvX94AdqLFgbfiJmRmWAnqS4InfKPw7hF2wzZw5shR/WQPz5+HB19cnw5/XfeLX65c37gVffweDSkg4wm8q6Bqgf1jTKv409Qxnt8jn4NfOaavLqww/W56ZSSS21ycvu/vfBV+ZRL6N9Xv3Xn/XTzSQurUrmMExf7trYCf0kBxFOehd8XcouhV2C5wM+M3GMsXPAv7BHD7W9vpHim/8z8xfBHm7911/f7+pwTZmSBJufzswEA9F7ROXE5+QC+gyOD7iBjToUZy0YL1Moh+cQ47E+vZlV/sEXAo3PD8zI/7KdUwD5AEkJDwkoIauAVyghaIu8d/ZMcm/FcmFR0nQTgGdeRS8g7Mu8b46SvAT65NK3W/phj8L4AOIIoiG70gaPLKAeUARXQMD31sHUvAuYONtea7gCRagdVplJBKjg4aEj98xs51w7T4ejXPs4pmhQAAfTEoEy0PQLQ0zTASmAnQIY97NKJx8G2qMRJ1/fi4v4GfVUfojie2P/OLD6kz5y7FXL48dOwMi7Bq4l0gaZhKJhFcyPIb1bdgd7lFH0k+TL8+VEZ94Hza72VEmeXvSN76i/EL5eurO3adPn76iGmQzoVHRSx5WSlBZQpTxrfR1Rm7/aYKfk/DzLGmJFvFjsdjUFGgAEa+Ogwi7BpKH77iGGfc8uAxil3sN2oAK+IaJX2amS4y2sGT7hfLLKTKICKqBmMldA/FSorkGj6CLnsNZi+FnET/Ia71FfBJ8S8LUHRTxChJxkyRC9hJvS9sNyFnQN7y+g9HPGRZ+FhZth8S3wLkGeL/3zh0vM0E9kJJ+TdqIm13WNxokhnetOZi1iABjleCrrGkdNvqAPRuzaWB5cJgJazpI62HbskvQw+nOVoWcMGsZq5qJnw1Q/pnPWirdD2djMSv49vdpHiARL51mol4iMxyJ+fZGk4oQ4efmR1eNZRs+idDHStg3/q+UDwlxbLaBhphZEC+dZkqQ+ehecD3RCjzgw6wFi00Lf4hNemH/+HVl/HqMYc7iiFlWwg9kDb0enSkBbl8PBltgR/yDQQ3xNYKvqtk8pQ+M1X17/6/1b+7EJGQmQsCWPmB5YBXBzQRFi0aCHUPjuVmAn58fRnyD4QP/Ad1BunrHHT/8jdn2Z2UNAnfMkYgpUwQ1EwjYWId1UtCJ70JO2GFoq1l0jmYsqxh9lTb+zy6M+e08kKXw17NSyxQi7fCSoGF2Vpwh+mHhDZvl9dfrTaNuws/PQ78X8EnnvH37X5RwuO5zu6J8NDk6ZeFbdpmyJ2LKIw/UTE+PzUH4sXU2dLsEPx9YNbRBdM4yw8/e/jbw5MplIPvkgq+97iTsG47f7SWMNg2zsgQpD7FZOWNQ0v1QvjhpbXg7Roafn38yqEHdwk7RxH/yJF8uFS5dCSvOA1s7fn3kk8t0Y7v++vjxXrFg3azkpsGSOHV3dIZU7/ZMIuGBPm8bB4NZg+CvGoiv5bL5fLlQyKnqMKTgQr0R/ggeiIbn2EZ3Y2O7v//43SlHGqakCjYliNM0+6B/Bvb3sNzxyY6NB20Dbd/Ch9DnYO2W1bThK3b+gHwTljI2cuXLJ3R/wt2KGnq5BnnicnjJ1Ek1zB6D5okzmC90/ORtUrkEX8sRfO0AIp/NGivGysrKl5flK70B+WL2l8PDK0Z2KAA7vDlxb7RtWolqsPIwa2+t4jw9e5zgb6z7Qoed8reBVbA+4GMBqIaqagdaEOK+MpxdubaC/PKJeUCk/9+VrS3D0HCZWn4SuO3QAGk47mKlqZg4PYj1ELtLTJ/YboYOYQf2rkAgiNbHf9D+KX42uEJGVlvBP4S8ItVvQIz98PLW860t+C4cau5g6ImVhhm+grTKoVlrhUwg/txcYsOT3UQPBJ5Aixk1VlVoOfBPW1ZVI5fD6K+sDOO1OlWDWVhTR8T4BwTjj6jwTcYWDoNKIBoCgdvzcho2BCuJXpImAFIbx018PPl2Q5+n6HnoL/ly+QAKFv4tQ9kSfFUrl3MaudCYzeYwsNold3xYpgUpM0p4/hyygD+DVjoo0zSIFU26klUOchq4BsSfwY3qvAf6bTAMBB3ASwXojrnC8AHULPxDBZqROwjmy7ksGzlcBGVzl4WFf0DsOxcPaIsiXcoACc/taRCttMGsJJa0bU03+x3BT8y5owcIOmHPkfaSzR7kg+CXVVVd1Q6G1aEn+QNOhAx4ZC5daZc7z/h/XioVsM2qUhosDeUhq6LJ1MBL2ioHIRWxqW08e5Pwudl51Dk6+AMLVFMLxEdDkPEh+HLBhAHwAnl7yQMfk/KP4/9zLAgutCQQDVgNzEo5ZiWrHDbkkhaWP/0YfFi4CSf4ltmJYXIYXEoOFTo8rGVzhXI5nydJKZOvW6OQy+bwUvsFj1n3i/C48s3Ll7De7Q+W+WxnpUG0klgOVklLGmZ7EyT4ePhpQ8/nGTonZ92FNG2oglIJa0Gmh8ooAH4uKJx22tc8F+rjL+/AwE2srIGngVY0zcOQs6Sphl6sh7vrCQz8HNvEksaOUSdoiD4soZsWB1A+RHjAL0FucoUG+MrnsMsla3Zc8XpoELuSWdLyQmP9dX8/vQw2z8xOok6iSvyyIkfdhKcuF4clplDG1JQa4cMm3XIvargL+7/+YLDMHpdYaYVNDmJJ271krtGg1gF9CNndgi6R2+EFdBx5/F8j8+ARibj7Yxqus3IoS12psQZy4YWhlzk6QG8J5Db0xvAEv1QQ75Jxwf+t28kIZOGuo6TpHM2tJK00qGWIY0oCOh0aZ5fxG5KTes7n4Hd5Nk7zfE2a/Pm714mVnjINBe+SBg35JwFKXjCtvrUlkWc9457LubCXcOTzBUjjlcb4NPoxaRcuLO1FL9G2TaxksM5KNQh8mmEPugu8G3tBZi+VcTrKl/Jh90WDdTyI1KKEmOOQDfPw8rhpJUdJ82HIRdrU7t7oOJeNlsr50qS4Yg64Hw9KG1hpVSaX9FOhpK0Z7hrsGgzcOtjJbezudeqOTififD4wJl6g9ur7/AQnFhPOd2IOCVP2ciCJ0Aj71rUVzRs961qnNrMT9jwfo+VSPhCufeF90lC7cfov14UN66ysQZQgrG3I7AAagpaXDJHfj10cQZfRkT6ff9L1zwD52OtZMvD5hH51R9r9TcXkgwRRg1wNYCU84SQzHLRJA3JwTct6tkbX5ljyQM8jele8uKR3nxpTTrvi1xTlxh8/vTrltLztnDkmfU3YNfJyCJYZ/4oP+OboTwJdA4C+pCeTyUzmd6cs/oBIf+PT9EL6ZGxKvCQ0O+t+Nmi3kqjh7tPjQTULDWjlmtbI6YVCs7AHKXoIwo7sAL+WSqe+MvlF/Bs9i48WAV+whAPUrOiY5ykzUfAS/GNkta1htVmNSl4vi+SIHiJRhwHkmVQqlYaxkP4N57fwT5++kUovLC7euvrWu70cUOqeMa882JoSWbEey6HtV1Ya1ahHh+GGCRWtoEPY04tATsat9FesfgOcHeR8YMoDCSd7mQb7+YerBmdjvZNT8VxgxdMull9KNr9glSI68TqyI9VCOnXr0cKtW7cAP72Q+qBWsfDxb9ZufPVpZndtLYUS4DsgEW+dfLeXnzfJoY+5dFbzWhj7vmHAV7PDakNwh9VHSYMpmuQZjCcGHLjXgB/JcDD7BHjRforMyaWl5C7mgGaBp2HWmQZJjyUiZnWlYwy/YLOL6HTZMKO8wegWOgFnI51avEXhU+nMByTmiH9a+ePCo0WQlUplkvoSSEhm1kwJJA29ZhosatthuXBqRU+ocrCdVDXVK+hilQat3sjQezj6IkWnlkktIjsZp5QXPPodPQuLjx6hAvKVjL6E2TMloJNuvQUF3SushNwOB60ZDvbprwh+NueCLobcjr7HrI7kMDg6HZmFhRQbvyHFG0DrfEDTtLj4iCUhtZYBH0EaLAkL1Env9s7aG6t8oZFX8ewraJmFXLYgh9wl6rRKddkvFnpaGJkeE/93FW6emtJNSxYb5yNTAnfSrpiGNKSBV0NMOh2MCUsN/NoxGd/udKvB0CpNCn6h5Bh1ER4QejIEH2T2UPNT/A+66YQmSqBOyuzZnERar9WU7GYy5wfEz8HyjQdd8kswzxrMksPqgtdFcjb20ikgx7H7Af6lF5+2Ojq7+/Z2IdCscy4KaeBOEnuSmQZWDTG7BGicEPlcjhyXSX7JE6tbMxKz+qIQ9LQbOo4k9JyMA7+GPvqv4+//3dm+ZJI3f5KGRZuTkjxHZlN6t5fZvVfMwuwd8E6pRE4sG1UpDbqAnpaHyA7QyR6On5SiX+t48eeXd2C1ePPM2Ymk5XdWz6aGJQibvRoWeTUImzLiHdiD2arUA53AL9jZRfQeBp1JWfg1cc3z+fnjZN+BZ2vvn4U07O7yQC88emRpwDQU0UrmV1ljNSsaNMzexeCXcyUkLwnofBlgorsVqWyYFCem+LtgHIhvsiLj/73ydcxa8b5CJ03YnGT2JJIG+/TGlxmYhilYZxbypVypFJQWX3usSq0ibULOw27irwE5jN0+c9bl+L8U1gW99IiTpIEFGh61R+qre9RKUjUQK7178uqZbC5YKphtPSlNSB5F6mCX0Sl+hrbZZLc167rcPChKOOMoaLOvMivxxmqKSKfPlphhSNR32drLrNImQV/LOAdC6yb+KfJHjiL+b93OEsi9d3I1pAUrscZq60rwOX0gXhSXAWB1jxpNpZoGPbPLoHVO30df/sEefedNazwNpBrO7tmbEtcAv5dODms4diElSavBsPay6DKT2sgboNOqN4P/Qsb/lXjzoMuxFLsFkswNu4KVpMlhj2jAoSfNKvUb9YyHY6yxlNzlzq8pdvwP5Rsr5NuMYuah1Ct7Y12wrIRU5GHXLL+4Ll98eH036RhLTE53pebAr30Ym3LeLOW4qYrfAfn++2cn+qiVaNek69WFhZ4FtkHyVaTuXt9Luo0l6v+lbvMv2zn+49PKJ9LNg9K5lHy91qyGl+bkYDalBVLVC6SzOxujHd4t6HbDWENP7kKLCOGzGlfkgxLYOP7533sdB1Me9z/S7+plVjp2k1iJVEMa479IVDRu6YC+1gI5iX2x78SJzo6q+IzMPPpffdpz1X7yFxN2gI5yiIn3DtI5eg+ykAby9OKjf2qM7hr2vUboEPTOjn3hab4E/MqLT28t0PMp5x1fjvu9Zp0Xj3rp+eaZ+2t0awlb51Q65YGeaS3q4BcMOl1YvqhWHHdTPVZupCDjuHCccmv70lmC29EhT8PTV2czKbKCW1h04XbvL7tNg06eQ6BWrVbcb4R8oZzi0/0tugWZdb9tubGVUMLTs2t4oAT+WfAR80bsuuAXd3TL+x3JNWtPLh5POVqP/Y418aQZ3rv+fgbPM3C7ttYQvKFhlpaK4Bf6xA2VaqNXh6Lep5td2jt4FtiZgni/l/2ubGdnnbp+lSx54DekMmutg/vwi/udhLDZNSUwI13lm1nn8WDMa3K4Du5J4y9Z6Mn4m0lFvxRDvEgbB92OTw5NiARcq6ytCRvyk729thtNY+4XLaiGk4CPS0wX/MZFKvnF77MvmNGvvXhBu2rnKSrBOugkxSCdiTjuQjU/ngX8FLmIkNn1111a9Yv3bagggWYBj02Su5KT7BJst/DzXW6M4mfW0rgz2t1tbHTJL7VK66/75LgwWqtSCeTkhxTDmpgG2lZdbrvm1cDxU425Kbo5H1X2D/eyYe4vnVGp2CSkvHuSkAcW/TWC78Mv7NnGXhz+Fc+8n6ehwopBrGerJ9mLgeckBo0zQ7aNTfyyz/xytBfMa/KqN0zCvqMYFqiTHNXQi/sVWEK7e4f6ha68KpU2vN5i8+coqbygTtp3OolP0MLUcBKWarglTbmgW4vGSrVNLxXp7xlioBheeEkgsxtb38WmbpFNe1JuONL6pW3oLT69kOkkcWYQexJY5yqeAgsHAnJ/qbJE/ij4opNcZgY8Mb+VYsHfNafSTnMqfROvsXuIV/oDEA8npTh9sk+aSt8M+iHx2eRGJJxnEpgGcmgIhiF+2W9h6fUD4ztmBnLojwUrWv3Noh8Vn0lgi9VT3X196JcOFvSK8gOMdry+Ll8m/b6jJi78fi74TEKltaX6Twr/Rxp/w/8xx/8BNlw7foj8K+MAAAAASUVORK5CYII=")!important;background-repeat:no-repeat!important;background-position:center!important;background-size:contain!important;filter:drop-shadow(0 2px 3px rgba(20,50,90,.12))!important;z-index:2!important;pointer-events:none!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading>div{width:67%!important;max-width:67%!important;flex-basis:67%!important;position:relative!important;z-index:3!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid{grid-template-rows:clamp(190px,26vh,220px) clamp(190px,26vh,220px)!important;grid-auto-rows:clamp(190px,26vh,220px)!important;align-items:stretch!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid>.dashboard-card{height:100%!important;min-height:0!important;max-height:100%!important;display:flex!important;flex-direction:column!important;overflow:hidden!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid>.dashboard-card .card-heading{flex:0 0 auto!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid>.dashboard-card .exam-list,.examo-mobile-dashboard-page .dashboard-grid>.dashboard-card .form-deadline-list,.examo-mobile-dashboard-page .dashboard-grid>.dashboard-card .schedule-list{flex:1 1 auto!important;min-height:0!important;max-height:100%!important;overflow:hidden!important}' +
                '.examo-mobile-dashboard-page .exam-list .exam-list-item:nth-child(n+4),.examo-mobile-dashboard-page .form-deadline-list .deadline-item:nth-child(n+4),.examo-mobile-dashboard-page .schedule-list .schedule-item:nth-child(n+4){display:none!important}' +
                '.examo-mobile-dashboard-page .exam-list .exam-list-item,.examo-mobile-dashboard-page .form-deadline-list .deadline-item,.examo-mobile-dashboard-page .schedule-list .schedule-item{min-height:0!important;max-height:48px!important;padding-top:5px!important;padding-bottom:5px!important;overflow:hidden!important}' +
                '.examo-mobile-dashboard-page .exam-list .list-main strong,.examo-mobile-dashboard-page .form-deadline-list .deadline-item strong,.examo-mobile-dashboard-page .schedule-list .schedule-item strong{display:-webkit-box!important;-webkit-box-orient:vertical!important;-webkit-line-clamp:2!important;overflow:hidden!important;overflow-wrap:anywhere!important;line-height:1.12!important}' +
                '.examo-mobile-dashboard-page .exam-list .list-main span,.examo-mobile-dashboard-page .form-deadline-list .deadline-item>div>span,.examo-mobile-dashboard-page .schedule-list .schedule-item span{display:block!important;white-space:nowrap!important;overflow:hidden!important;text-overflow:ellipsis!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid>.dashboard-card .dashboard-empty{flex:1 1 auto!important;min-height:0!important;display:flex!important;align-items:center!important;justify-content:center!important}' +
                '@media(max-width:360px){.examo-mobile-dashboard-page .dashboard-grid{grid-template-rows:clamp(184px,25vh,208px) clamp(184px,25vh,208px)!important;grid-auto-rows:clamp(184px,25vh,208px)!important}.examo-mobile-dashboard-page .dashboard-heading:after{width:80px!important;height:74px!important;right:3px!important}.examo-mobile-dashboard-page .dashboard-heading>div{width:65%!important;max-width:65%!important;flex-basis:65%!important}}' +

                /* Quick Actions: keep it in document flow, never overlay the calendar/schedule. */
                '.examo-mobile-dashboard-page{padding-bottom:10px!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card{position:static!important;inset:auto!important;top:auto!important;right:auto!important;bottom:auto!important;left:auto!important;z-index:auto!important;transform:none!important;width:100%!important;max-width:100%!important;min-width:0!important;margin:9px 0 10px!important}' +
                '@media(max-width:360px){.examo-mobile-dashboard-page{padding-bottom:8px!important}.examo-mobile-dashboard-page .quick-actions-card{position:static!important;inset:auto!important;margin:8px 0 9px!important;width:100%!important}}' +

                /* Welcome title stays on one line; compact the vertical stack so Quick Actions fits. */
                '.examo-mobile-dashboard-page .dashboard-heading{min-height:66px!important;height:auto!important;padding:6px 9px!important;margin-bottom:8px!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading>div{width:74%!important;max-width:74%!important;flex:0 1 74%!important;min-width:0!important;margin:0 auto 0 0!important;align-self:center!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading h1{font-size:14px!important;line-height:1.12!important;letter-spacing:-.28px!important;white-space:nowrap!important;overflow:visible!important;text-overflow:clip!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading p:not(.eyebrow){margin-top:2px!important;font-size:9px!important;line-height:1.14!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading:after{width:94px!important;height:70px!important;right:3px!important}' +
                '.examo-mobile-dashboard-page .dashboard-grid{grid-template-rows:repeat(2,clamp(190px,24vh,210px))!important;grid-auto-rows:clamp(190px,24vh,210px)!important}' +
                '.examo-mobile-dashboard-page .quick-actions-card{margin-top:9px!important;margin-bottom:9px!important}' +
                '@media(max-width:360px){.examo-mobile-dashboard-page .dashboard-heading{min-height:62px!important;padding:5px 8px!important}.examo-mobile-dashboard-page .dashboard-heading>div{width:74%!important;max-width:74%!important;flex-basis:74%!important}.examo-mobile-dashboard-page .dashboard-heading h1{font-size:13.5px!important;letter-spacing:-.32px!important;white-space:nowrap!important}.examo-mobile-dashboard-page .dashboard-heading p:not(.eyebrow){font-size:8.5px!important;line-height:1.12!important}.examo-mobile-dashboard-page .dashboard-heading:after{width:88px!important;height:66px!important;right:2px!important}.examo-mobile-dashboard-page .dashboard-grid{grid-template-rows:repeat(2,clamp(184px,23vh,198px))!important;grid-auto-rows:clamp(184px,23vh,198px)!important}}' +

                /* Precise banner inset + slightly shorter stats strip; preserve page scale. */
                '.examo-mobile-dashboard-page .dashboard-heading{min-height:74px!important;height:auto!important;padding:7px 9px!important;margin-bottom:8px!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading:after{width:100px!important;height:72px!important;right:10px!important;top:50%!important;bottom:auto!important;transform:translateY(-50%)!important;background-size:contain!important}' +
                '.examo-mobile-dashboard-page .dashboard-heading>div{width:74%!important;max-width:74%!important;flex:0 1 74%!important}' +
                '.examo-mobile-dashboard-page .stats-grid{padding:0 3px!important;margin-bottom:9px!important}' +
                '.examo-mobile-dashboard-page .stat-card{padding:1px 2px!important;gap:1px!important}' +
                '.examo-mobile-dashboard-page .stat-icon{width:26px!important;height:26px!important;min-width:26px!important}' +
                '.examo-mobile-dashboard-page .stat-icon svg{width:16px!important;height:16px!important}' +
                '@media(max-width:360px){.examo-mobile-dashboard-page .dashboard-heading{min-height:70px!important;padding:6px 8px!important}.examo-mobile-dashboard-page .dashboard-heading:after{width:94px!important;height:68px!important;right:8px!important}.examo-mobile-dashboard-page .stats-grid{padding:0 2px!important}.examo-mobile-dashboard-page .stat-card{padding:1px 1px!important}.examo-mobile-dashboard-page .stat-icon{width:25px!important;height:25px!important;min-width:25px!important}}' +
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
                  if (subscriptionPage) {
                    subscriptionPage.classList.add('examo-mobile-subscription-page');
                    var subscriptionHeroTitle = subscriptionPage.querySelector('.hs-hero-title');
                    if (subscriptionHeroTitle && subscriptionBooksImageSrc &&
                        !subscriptionHeroTitle.querySelector('.examo-subscription-books-image')) {
                      var booksImage = document.createElement('img');
                      booksImage.className = 'examo-subscription-books-image';
                      booksImage.alt = 'Books with a graduation cap';
                      booksImage.decoding = 'async';
                      booksImage.loading = 'eager';
                      booksImage.src = subscriptionBooksImageSrc;
                      subscriptionHeroTitle.appendChild(booksImage);
                    }
                  }
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
        getPendingLoginOtp()?.let { pending ->
            showNativePendingLoginOtpScreen(pending)
            Toast.makeText(
                this,
                "Enter the 6-digit login code to complete verification.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
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
        if (::webView.isInitialized) {
            webView.onResume()

            // A pending OTP flow always wins over the welcome screen, including
            // when the Android task was recreated while Gmail was foregrounded.
            getPendingLoginOtp()?.let { pending ->
                webView.animate().cancel()
                webView.visibility = View.VISIBLE
                showNativePendingLoginOtpScreen(pending)
                webView.translationY = 0f
                authSheetOpen = true

                val retryDelays = longArrayOf(150L, 500L, 1200L, 2500L, 4000L)
                retryDelays.forEach { delay ->
                    webView.postDelayed({
                        if (!isFinishing && !isDestroyed &&
                            getPendingLoginOtp() != null
                        ) {
                            restorePendingLoginOtpIfNeeded(webView, webView.url)
                        }
                    }, delay)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::webView.isInitialized) getPendingLoginOtp()?.let { pending ->
            showNativePendingLoginOtpScreen(pending)
            webView.animate().cancel()
            webView.visibility = View.VISIBLE
            webView.translationY = 0f
            authSheetOpen = true
            webView.postDelayed({
                if (!isFinishing && !isDestroyed && getPendingLoginOtp() != null) {
                    restorePendingLoginOtpIfNeeded(webView, webView.url)
                }
            }, 150L)
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            persistLastExamoUrl(webView.url)
            webView.onPause()
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::webView.isInitialized) getPendingLoginOtp()?.let { pending ->
            showNativePendingLoginOtpScreen(pending)
            webView.animate().cancel()
            webView.visibility = View.VISIBLE
            webView.translationY = 0f
            authSheetOpen = true

            webView.postDelayed({
                if (!isFinishing && !isDestroyed && getPendingLoginOtp() != null) {
                    restorePendingLoginOtpIfNeeded(webView, webView.url)
                }
            }, 150L)
        }
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
