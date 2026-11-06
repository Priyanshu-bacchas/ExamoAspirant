package com.examo.aspirant

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var popupWebView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(248, 249, 252)
        window.navigationBarColor = Color.rgb(17, 24, 39)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

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

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return false
                    if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript") return false
                    return openExternal(uri)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    installMobileNavigationFix(view)
                }
            }

            webChromeClient = object : WebChromeClient() {
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPopupWebView(): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = WebViewClient()
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

    private fun installMobileNavigationFix(view: WebView?) {
        val script = """
            (function() {
              if (window.__examoAndroidNavInstalled) return;
              window.__examoAndroidNavInstalled = true;
              var style = document.createElement('style');
              style.id = 'examo-android-nav-style';
              style.textContent = '@media(max-width:600px){' +
                '.sidebar{width:min(82vw,300px)!important;max-width:300px!important;min-height:100dvh!important;overflow:hidden!important;transform:translateX(-105%)!important;transition:transform .25s ease!important;z-index:10001!important;box-shadow:8px 0 28px rgba(15,23,42,.22)!important}' +
                '.sidebar.examo-mobile-open{transform:translateX(0)!important}' +
                '.main-content{width:100%!important;margin-left:0!important}' +
                '.mobile-menu{display:flex!important;align-items:center;justify-content:center;width:42px;height:42px;cursor:pointer;border-radius:10px}' +
                '.mobile-menu:active{background:#eef2ff}' +
                '.examo-mobile-backdrop{position:fixed;inset:0;background:rgba(15,23,42,.48);z-index:10000;display:none}' +
                '.examo-mobile-backdrop.open{display:block}' +
                '.topbar{position:sticky;top:0;z-index:1000}.content-area{min-width:0}}';
              document.head.appendChild(style);
              var sidebar = document.querySelector('.sidebar');
              var menu = document.querySelector('.mobile-menu');
              if (!sidebar || !menu) return;
              var backdrop = document.createElement('div');
              backdrop.className = 'examo-mobile-backdrop';
              document.body.appendChild(backdrop);
              function closeMenu() {
                sidebar.classList.remove('examo-mobile-open');
                backdrop.classList.remove('open');
              }
              function toggleMenu() {
                var open = !sidebar.classList.contains('examo-mobile-open');
                sidebar.classList.toggle('examo-mobile-open', open);
                backdrop.classList.toggle('open', open);
              }
              menu.setAttribute('role','button');
              menu.setAttribute('aria-label','Open navigation menu');
              menu.setAttribute('tabindex','0');
              menu.addEventListener('click', toggleMenu);
              menu.addEventListener('keydown', function(e) {
                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleMenu(); }
              });
              backdrop.addEventListener('click', closeMenu);
              sidebar.addEventListener('click', function(e) {
                var item = e.target.closest('.sidebar-subitem, .sidebar-item');
                if (item && !/Student/.test(item.innerText || '')) closeMenu();
              });
              window.addEventListener('resize', function() {
                if (window.innerWidth > 600) closeMenu();
              });
              window.__examoAndroidCloseMenu = closeMenu;
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
        popupWebView?.destroy()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
