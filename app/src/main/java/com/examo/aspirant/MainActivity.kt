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

            // Firebase Google sign-in opens a child window. Keep the OAuth callback
            // in this child WebView so Firebase can message window.opener.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return false
                    return if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript") {
                        false
                    } else {
                        openExternal(uri)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView?) {
                    // Important: Firebase calls window.close() after the OAuth
                    // handler posts its result to the opener. Close only the
                    // popup and reveal the original login page.
                    if (window != null && window == popupWebView) {
                        root.removeView(window)
                        window.stopLoading()
                        window.destroy()
                        popupWebView = null
                        webView.visibility = View.VISIBLE
                        webView.requestFocus()
                        CookieManager.getInstance().flush()
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

              function sidebar(){ return document.querySelector('.sidebar'); }
              function backdrop(){
                var el=document.querySelector('.examo-mobile-backdrop');
                if(!el){
                  el=document.createElement('div');
                  el.className='examo-mobile-backdrop';
                  document.body.appendChild(el);
                  el.addEventListener('click',closeMenu);
                  el.addEventListener('touchend',function(e){e.preventDefault();closeMenu();},{passive:false});
                }
                return el;
              }
              function closeMenu(){
                var s=sidebar();
                if(s)s.classList.remove('examo-mobile-open');
                var b=document.querySelector('.examo-mobile-backdrop');
                if(b)b.classList.remove('open');
                document.body.style.overflow='';
              }
              function toggleMenu(){
                var s=sidebar();
                if(!s)return;
                var b=backdrop();
                var open=!s.classList.contains('examo-mobile-open');
                s.classList.toggle('examo-mobile-open',open);
                b.classList.toggle('open',open);
                document.body.style.overflow=open?'hidden':'';
              }

              var lastToggle=0;
              function handleMenuEvent(e){
                var target=e.target;
                var menu=target && target.closest ? target.closest('.mobile-menu') : null;
                if(!menu)return;
                var now=Date.now();
                if(now-lastToggle<450)return;
                lastToggle=now;
                e.preventDefault();
                e.stopPropagation();
                if(e.stopImmediatePropagation)e.stopImmediatePropagation();
                toggleMenu();
              }
              // Bind directly to the real React header node and rebind if React remounts it.
              function bindMenu(){
                var menu=document.querySelector('.mobile-menu');
                if(!menu || menu.dataset.examoAndroidBound==='1')return;
                menu.dataset.examoAndroidBound='1';
                menu.addEventListener('click',handleMenuEvent,true);
                menu.addEventListener('touchend',handleMenuEvent,{capture:true,passive:false});
                menu.addEventListener('pointerup',handleMenuEvent,true);
                menu.setAttribute('role','button');
                menu.setAttribute('aria-label','Open navigation menu');
                menu.setAttribute('tabindex','0');
              }
              document.addEventListener('click',handleMenuEvent,true);
              document.addEventListener('touchend',handleMenuEvent,{capture:true,passive:false});
              document.addEventListener('pointerup',handleMenuEvent,true);
              document.addEventListener('click',function(e){
                var item=e.target&&e.target.closest?e.target.closest('.sidebar-item,.sidebar-subitem'):null;
                if(item)closeMenu();
              },true);
              document.addEventListener('keydown',function(e){
                if(e.key==='Escape')closeMenu();
                if((e.key==='Enter'||e.key===' ') && document.activeElement && document.activeElement.classList.contains('mobile-menu')){
                  e.preventDefault();toggleMenu();
                }
              });
              window.addEventListener('resize',function(){if(window.innerWidth>600)closeMenu();});
              bindMenu();
              new MutationObserver(bindMenu).observe(document.documentElement,{childList:true,subtree:true});
              window.__examoAndroidCloseMenu=closeMenu;
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
