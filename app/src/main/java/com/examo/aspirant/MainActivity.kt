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
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var popupWebView: WebView? = null
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var googleLoginInProgress = false
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
        webView = createWebView()
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (savedInstanceState == null) {
            webView.loadUrl(getString(R.string.examo_website_url))
        } else {
            val restored = webView.restoreState(savedInstanceState)
            if (restored == null) webView.loadUrl(getString(R.string.examo_website_url))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
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
            addJavascriptInterface(AndroidBridge(), "ExamoAndroid")

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
                    installNativeGoogleLoginHook(view)
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

    private inner class AndroidBridge {
        @JavascriptInterface
        fun googleSignIn() {
            runOnUiThread { startNativeGoogleSignIn() }
        }
    }

    private fun installNativeGoogleLoginHook(view: WebView?) {
        val script = """
            (function () {
              if (window.__examoNativeGoogleHook) return;
              window.__examoNativeGoogleHook = true;
              document.addEventListener('click', function (event) {
                var button = event.target && event.target.closest
                  ? event.target.closest('.ex-btn-outline,.btn-google') : null;
                if (!button || !window.ExamoAndroid) return;
                var label = (button.innerText || button.textContent || '').toLowerCase();
                if (label.indexOf('continue with google') === -1) return;
                event.preventDefault();
                event.stopPropagation();
                if (event.stopImmediatePropagation) event.stopImmediatePropagation();
                window.ExamoAndroid.googleSignIn();
              }, true);
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun startNativeGoogleSignIn() {
        if (googleLoginInProgress) return
        googleLoginInProgress = true
        Toast.makeText(this, "Choose your Google account", Toast.LENGTH_SHORT).show()
        activityScope.launch {
            try {
                val webClientId = getString(R.string.default_web_client_id)
                val googleOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build()

                val credentialManager = CredentialManager.create(this@MainActivity)
                val result = try {
                    credentialManager.getCredential(
                        this@MainActivity,
                        GetCredentialRequest.Builder().addCredentialOption(googleOption).build()
                    )
                } catch (_: androidx.credentials.exceptions.NoCredentialException) {
                    val signInOption = GetSignInWithGoogleOption.Builder(webClientId).build()
                    credentialManager.getCredential(
                        this@MainActivity,
                        GetCredentialRequest.Builder().addCredentialOption(signInOption).build()
                    )
                }
                val credential = result.credential

                if (credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    throw IllegalStateException("Google account selection did not return a valid ID token.")
                }

                val googleToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val firebaseCredential = GoogleAuthProvider.getCredential(googleToken, null)
                val authResult = FirebaseAuth.getInstance()
                    .signInWithCredential(firebaseCredential)
                    .await()

                val firebaseIdToken = authResult.user?.getIdToken(true)?.await()?.token
                    ?: throw IllegalStateException("Firebase did not return an ID token.")

                val response = withContext(Dispatchers.IO) {
                    callExamoBackend(firebaseIdToken, authResult.user?.displayName)
                }

                val token = response.optString("token")
                    .ifBlank { response.optString("Token") }
                    .ifBlank { response.optJSONObject("data")?.optString("token").orEmpty() }
                    .ifBlank { response.optJSONObject("Data")?.optString("Token").orEmpty() }

                if (token.isBlank() || token == "null") {
                    throw IllegalStateException("Examo backend did not return an app login token.")
                }

                val user = response.optJSONObject("user")
                    ?: response.optJSONObject("User")
                    ?: response.optJSONObject("data")?.optJSONObject("user")
                    ?: response.optJSONObject("Data")?.optJSONObject("User")
                    ?: JSONObject()
                        .put("fullName", authResult.user?.displayName ?: "Student")
                        .put("email", authResult.user?.email ?: "")

                val tokenLiteral = JSONObject.quote(token)
                val userLiteral = JSONObject.quote(user.toString())
                webView.post {
                    webView.evaluateJavascript(
                        "localStorage.setItem('examo_token',$tokenLiteral);" +
                            "localStorage.setItem('examo_user',$userLiteral);" +
                            "sessionStorage.removeItem('examo_token');" +
                            "sessionStorage.removeItem('examo_user');" +
                            "window.location.replace('/');",
                        null
                    )
                }
                Toast.makeText(this@MainActivity, "Google login successful", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                android.util.Log.e("ExamoApp", "Native Google sign-in failed", error)
                val message = when (error) {
                    is androidx.credentials.exceptions.NoCredentialException ->
                        "No Google account found on this device. Add a Google account in Android Settings."
                    is androidx.credentials.exceptions.GetCredentialException ->
                        error.message ?: "Google sign-in was cancelled or could not be completed."
                    else -> error.message ?: "Google sign-in failed. Please try again."
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } finally {
                googleLoginInProgress = false
            }
        }
    }

    private fun callExamoBackend(firebaseIdToken: String, fullName: String?): JSONObject {
        val connection = (URL(getString(R.string.examo_api_url) + "/Auth/firebase")
            .openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 25000
        connection.readTimeout = 30000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")

        try {
            val payload = JSONObject().put("idToken", firebaseIdToken)
            if (!fullName.isNullOrBlank()) payload.put("fullName", fullName)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = try {
                    JSONObject(body).optString("message").ifBlank { body }
                } catch (_: Exception) { body }
                throw IllegalStateException(message.ifBlank { "Examo login failed (HTTP $status)." })
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
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

    private fun installMobileNavigationFix(view: WebView?) {
        val script = """
            (function () {
              if (window.__examoAndroidNavInstalled) return;
              window.__examoAndroidNavInstalled = true;
              var css = document.createElement('style');
              css.id = 'examo-android-nav-style';
              css.textContent = '@media(max-width:600px){' +
                '.sidebar{position:fixed!important;left:0!important;top:0!important;bottom:0!important;width:min(82vw,300px)!important;max-width:300px!important;min-height:100dvh!important;height:100dvh!important;overflow-y:auto!important;overflow-x:hidden!important;transform:translate3d(-105%,0,0)!important;transition:transform .25s ease!important;z-index:10002!important;visibility:visible!important;pointer-events:auto!important;-webkit-overflow-scrolling:touch!important;display:flex!important}' +
                '.sidebar.examo-mobile-open{transform:translate3d(0,0,0)!important}' +
                '.main-content{width:100%!important;margin-left:0!important;min-width:0!important}' +
                '.mobile-menu{display:flex!important;align-items:center!important;justify-content:center!important;width:48px!important;height:48px!important;min-width:48px!important;cursor:pointer!important;touch-action:manipulation!important;position:relative!important;z-index:10003!important;border-radius:10px!important;pointer-events:auto!important;user-select:none!important;-webkit-tap-highlight-color:transparent!important}' +
                '.mobile-menu svg{pointer-events:none!important}' +
                '.examo-mobile-backdrop{position:fixed!important;inset:0!important;background:rgba(15,23,42,.50)!important;z-index:10001!important;display:none!important;touch-action:manipulation!important}' +
                '.examo-mobile-backdrop.open{display:block!important}' +
                '.topbar{position:sticky!important;top:0!important;z-index:1000!important}.content-area{min-width:0!important}}';
              (document.head || document.documentElement).appendChild(css);
              function sidebar(){return document.querySelector('.sidebar');}
              function backdrop(){
                var el=document.querySelector('.examo-mobile-backdrop');
                if(!el){el=document.createElement('div');el.className='examo-mobile-backdrop';document.body.appendChild(el);el.addEventListener('click',closeMenu);}
                return el;
              }
              function closeMenu(){
                var s=sidebar();if(s)s.classList.remove('examo-mobile-open');
                var b=document.querySelector('.examo-mobile-backdrop');if(b)b.classList.remove('open');
                document.body.style.overflow='';
              }
              function toggleMenu(){
                var s=sidebar();if(!s)return;var b=backdrop();
                var open=!s.classList.contains('examo-mobile-open');
                s.classList.toggle('examo-mobile-open',open);b.classList.toggle('open',open);
                document.body.style.overflow=open?'hidden':'';
              }
              var lastToggle=0;
              function handleMenuEvent(e){
                var menu=e.target&&e.target.closest?e.target.closest('.mobile-menu'):null;if(!menu)return;
                var now=Date.now();if(now-lastToggle<450)return;lastToggle=now;
                e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();toggleMenu();
              }
              function bindMenu(){
                var menu=document.querySelector('.mobile-menu');
                if(!menu||menu.dataset.examoAndroidBound==='1')return;
                menu.dataset.examoAndroidBound='1';
                menu.addEventListener('click',handleMenuEvent,true);
                menu.addEventListener('touchend',handleMenuEvent,{capture:true,passive:false});
                menu.addEventListener('pointerup',handleMenuEvent,true);
                menu.setAttribute('role','button');menu.setAttribute('aria-label','Open navigation menu');menu.setAttribute('tabindex','0');
              }
              document.addEventListener('click',handleMenuEvent,true);
              document.addEventListener('touchend',handleMenuEvent,{capture:true,passive:false});
              document.addEventListener('pointerup',handleMenuEvent,true);
              document.addEventListener('click',function(e){
                var item=e.target&&e.target.closest?e.target.closest('.sidebar-item,.sidebar-subitem'):null;if(item)closeMenu();
              },true);
              document.addEventListener('keydown',function(e){if(e.key==='Escape')closeMenu();});
              window.addEventListener('resize',function(){if(window.innerWidth>600)closeMenu();});
              bindMenu();new MutationObserver(bindMenu).observe(document.documentElement,{childList:true,subtree:true});
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
