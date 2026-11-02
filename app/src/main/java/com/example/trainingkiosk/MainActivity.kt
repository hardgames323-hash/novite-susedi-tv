package com.example.trainingkiosk

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {

    companion object {
        // ---- Конфигурация за локалната мрежа ----
        const val TARGET_URL = "https://zenoplay.to/tv-show/novite-sasedi-2007/"
        const val PORTAL_TITLE = "НОВИТЕ СЪСЕДИ"
        const val PRIMARY_SERVER_GROUP = "bg"
        val BLOCKED_SCRIPTS = listOf("disable-devtool", "doubleclick.net", "googlesyndication.com", "popads.net", "exoclick.com", "juicyads.com", "adsterra.com", "acscdn.com", "onclickalgo.com", "adtng.com", "simple-views")

        // ---- Вътрешни настройки ----
        private const val TAG = "TvArchive"
        private const val BRIDGE_NAME = "TVBridge"
        private const val EXIT_CONFIRM_WINDOW_MS = 2000L
        private const val TAP_DURATION_MS = 60L
        private const val MODE_BROWSE = "browse"
        private const val MODE_VIDEO = "video"
        private const val MODE_LOADING = "loading"
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    @Volatile
    private var jsMode = MODE_BROWSE
    private var showingError = false
    private var lastBackPressAt = 0L
    private var exitToast: Toast? = null

    private val injectionScript: String by lazy { buildInjectionScript() }

    // ------------------------------------------------------------------
    // Жизнен цикъл
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0B0B0F"))
        }
        rootLayout.addView(webView)
        setContentView(rootLayout)
        enterImmersiveMode()

        configureWebView()
        onBackPressedDispatcher.addCallback(this, backCallback)

        webView.loadUrl(TARGET_URL)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        enterImmersiveMode()
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        exitToast?.cancel()
        if (::webView.isInitialized) {
            try {
                webView.removeJavascriptInterface(BRIDGE_NAME)
                webView.stopLoading()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "WebView cleanup failed", e)
            }
        }
        super.onDestroy()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------------
    // WebView
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configureWebView() {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString AndroidTV-ArchiveClient/1.0"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.addJavascriptInterface(TvBridge(), BRIDGE_NAME)
        webView.webViewClient = TvWebViewClient()
        webView.webChromeClient = TvChromeClient()
        webView.requestFocus()
    }

    private inner class TvWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (BLOCKED_SCRIPTS.any { url.contains(it, ignoreCase = true) }) {
                Log.d(TAG, "Blocked: $url")
                return WebResourceResponse(
                    "application/javascript",
                    "utf-8",
                    ByteArrayInputStream(ByteArray(0))
                )
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Не позволяваме intent://, market:// и други схеми да изведат потребителя от приложението.
            val scheme = request.url.scheme?.lowercase()
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            jsMode = MODE_BROWSE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            if (showingError || url == null || url == "about:blank" || url.startsWith("data:")) return
            view.evaluateJavascript(injectionScript, null)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) showErrorPage(error.description?.toString())
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e(TAG, "WebView renderer gone (crash=${detail.didCrash()}), recreating activity")
            rootLayout.removeView(view)
            recreate()
            return true
        }
    }

    private inner class TvChromeClient : WebChromeClient() {

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean = false // блокира изскачащи прозорци

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            rootLayout.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            webView.visibility = View.INVISIBLE
            enterImmersiveMode()
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        // Премахва сивата икона "play" на Android WebView преди старта на видеото.
        override fun getDefaultVideoPoster(): Bitmap? =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private fun hideCustomView() {
        val view = customView ?: return
        rootLayout.removeView(view)
        customView = null
        webView.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.requestFocus()
    }

    private fun showErrorPage(description: String?) {
        showingError = true
        jsMode = MODE_BROWSE
        val details = TextUtils.htmlEncode(description ?: "Неизвестна грешка")
        val title = TextUtils.htmlEncode(PORTAL_TITLE)
        val html = """
            <!doctype html>
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html,body{margin:0;height:100%;background:#0b0b0f;color:#fff;
                font-family:Roboto,'Segoe UI',Arial,sans-serif;}
              .wrap{height:100%;display:flex;flex-direction:column;align-items:center;
                justify-content:center;text-align:center;padding:0 10vw;box-sizing:border-box;}
              .brand{font-size:1.6vw;letter-spacing:.3vw;font-weight:800;opacity:.6;margin-bottom:3vw;}
              .icon{width:6vw;height:6vw;border-radius:50%;border:.4vw solid #E50914;color:#E50914;
                font-size:3.4vw;font-weight:800;display:flex;align-items:center;justify-content:center;}
              h1{font-size:3vw;margin:2vw 0 1vw;}
              p{font-size:1.3vw;opacity:.65;margin:0;}
              .btn{margin-top:3vw;padding:1vw 3vw;border-radius:3vw;background:#E50914;
                font-size:1.4vw;font-weight:700;transform:scale(1.06);
                box-shadow:0 .8vw 2.4vw rgba(229,9,20,.45);}
            </style></head>
            <body><div class="wrap">
              <div class="brand">$title</div>
              <div class="icon">!</div>
              <h1>Няма връзка с архива</h1>
              <p>$details</p>
              <div class="btn">Натиснете OK за нов опит</div>
            </div></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun retryLoad() {
        showingError = false
        webView.loadUrl(TARGET_URL)
    }

    // ------------------------------------------------------------------
    // Хардуерна D-Pad навигация
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isFirstDown = isDown && event.repeatCount == 0

        // Видеото е в нативен цял екран (Fullscreen API на плейъра в iframe-а).
        customView?.let { fullscreenView ->
            if (isCenterKey(keyCode) || isPlayPauseKey(keyCode)) {
                if (isFirstDown) simulateTap(fullscreenView)
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (showingError) {
            if (isCenterKey(keyCode)) {
                if (isFirstDown) retryLoad()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        val command = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
            else -> if (isCenterKey(keyCode)) "ENTER" else null
        }
        if (command != null) {
            if (isDown && (command != "ENTER" || event.repeatCount == 0)) sendNav(command)
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (isFirstDown) {
                    if (jsMode == MODE_VIDEO) simulateTap(webView)
                    else if (keyCode != KeyEvent.KEYCODE_MEDIA_PAUSE) sendNav("ENTER")
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (isDown && jsMode == MODE_VIDEO) passKeyToWebView(KeyEvent.KEYCODE_DPAD_RIGHT)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (isDown && jsMode == MODE_VIDEO) passKeyToWebView(KeyEvent.KEYCODE_DPAD_LEFT)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (isFirstDown) webView.evaluateJavascript("window.tvNext&&window.tvNext(1);", null)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (isFirstDown) webView.evaluateJavascript("window.tvNext&&window.tvNext(-1);", null)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (isFirstDown && jsMode != MODE_BROWSE) {
                    webView.evaluateJavascript("window.tvBack&&window.tvBack();", null)
                }
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun isCenterKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A

    private fun isPlayPauseKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE

    private fun sendNav(command: String) {
        webView.evaluateJavascript("window.tvNav&&window.tvNav('$command');", null)
    }

    /** Симулира докосване (DOWN + UP) в центъра на подадения изглед — Play/Pause в iframe плейъра. */
    private fun simulateTap(target: View) {
        if (target.width == 0 || target.height == 0) return
        val x = target.width / 2f
        val y = target.height / 2f
        val downTime = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(down)
        down.recycle()

        target.postDelayed({
            val up = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                x,
                y,
                0
            )
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            target.dispatchTouchEvent(up)
            up.recycle()
        }, TAP_DURATION_MS)
    }

    /** Подава нативно клавишно събитие директно на WebView (заобикаля dispatchKeyEvent на Activity). */
    private fun passKeyToWebView(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        webView.dispatchKeyEvent(KeyEvent(now, now + 40, KeyEvent.ACTION_UP, keyCode, 0))
    }

    // ------------------------------------------------------------------
    // JS мост
    // ------------------------------------------------------------------

    private inner class TvBridge {

        @JavascriptInterface
        fun tapCenter() {
            runOnUiThread { simulateTap(customView ?: webView) }
        }

        @JavascriptInterface
        fun passKey(direction: String) {
            val keyCode = when (direction) {
                "UP" -> KeyEvent.KEYCODE_DPAD_UP
                "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
                "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
                "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
                else -> return
            }
            runOnUiThread { passKeyToWebView(keyCode) }
        }

        @JavascriptInterface
        fun onModeChanged(mode: String) {
            jsMode = mode
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "[js] $message")
        }
    }

    // ------------------------------------------------------------------
    // Бутон Назад
    // ------------------------------------------------------------------

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> hideCustomView()
                showingError -> confirmExit()
                else -> webView.evaluateJavascript(
                    "(function(){try{return window.tvBack?window.tvBack():'none';}catch(e){return 'none';}})()"
                ) { result ->
                    when (result?.trim('"')) {
                        "handled" -> Unit
                        "root" -> confirmExit()
                        else -> if (webView.canGoBack()) webView.goBack() else confirmExit()
                    }
                }
            }
        }
    }

    private fun confirmExit() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPressAt < EXIT_CONFIRM_WINDOW_MS) {
            exitToast?.cancel()
            finish()
        } else {
            lastBackPressAt = now
            exitToast?.cancel()
            exitToast = Toast.makeText(
                this,
                "Натиснете Назад още веднъж за изход",
                Toast.LENGTH_SHORT
            ).also { it.show() }
        }
    }

    // ------------------------------------------------------------------
    // Инжектиран интерфейс (CSS + JS)
    // ------------------------------------------------------------------

    private fun buildCss(): String = """
        #tv-app-ui, #tv-app-ui *, #tv-loading, #tv-loading *, #tv-osd, #tv-osd *,
        #tv-video-hint, #tv-toast { box-sizing: border-box; }

        html.tv-ui-on, html.tv-ui-on body { overflow: hidden !important; }
        html.tv-video-on, html.tv-video-on body { overflow: hidden !important; background: #000 !important; }
        html.tv-video-on body { visibility: hidden !important; }
        html.tv-video-on #tv-app-ui { visibility: hidden !important; }

        #tv-app-ui {
          position: fixed; top: 0; left: 0; right: 0; bottom: 0; z-index: 2147483000;
          display: flex; flex-direction: column; overflow: hidden;
          background: #0b0b0f; color: #fff; text-align: left;
          font-family: Roboto, 'Segoe UI', 'Helvetica Neue', Arial, sans-serif;
          font-size: 1vw; line-height: 1.35; letter-spacing: normal;
          -webkit-font-smoothing: antialiased; user-select: none; cursor: none;
        }
        .tv-backdrop {
          position: absolute; top: -6%; left: -6%; right: -6%; bottom: -6%; z-index: 0;
          background-size: cover; background-position: center;
          filter: blur(40px) saturate(1.3); opacity: .38;
        }
        .tv-vignette {
          position: absolute; top: 0; left: 0; right: 0; bottom: 0; z-index: 0;
          background:
            linear-gradient(180deg, rgba(11,11,15,.45) 0%, rgba(11,11,15,.82) 42%, #0b0b0f 72%),
            linear-gradient(90deg, rgba(11,11,15,.85) 0%, rgba(11,11,15,0) 65%);
        }
        .tv-header, .tv-hero, .tv-seasons, .tv-grid-wrap { position: relative; z-index: 1; }

        /* ---------- Хедър ---------- */
        .tv-header {
          display: flex; align-items: center; justify-content: space-between;
          padding: 2.2vw 4vw 0; flex: none;
        }
        .tv-brand { display: flex; align-items: center; }
        .tv-brand-mark {
          width: .45vw; height: 2.4vw; margin-right: 1vw; border-radius: .2vw;
          background: #E50914; box-shadow: 0 0 1.2vw rgba(229,9,20,.8);
        }
        .tv-brand-title { font-size: 1.8vw; font-weight: 800; letter-spacing: .25vw; text-transform: uppercase; }
        .tv-header-right { display: flex; align-items: center; }
        .tv-continue {
          display: flex; align-items: center; max-width: 36vw; margin-right: 2.2vw;
          padding: .55vw 1.6vw .55vw .6vw; border-radius: 3vw;
          background: rgba(255,255,255,.08); border: .15vw solid rgba(255,255,255,.14);
          transition: transform .25s ease, background .25s ease, color .25s ease, box-shadow .25s ease;
        }
        .tv-continue.is-hidden { display: none; }
        .tv-continue-icon {
          flex: none; width: 2.4vw; height: 2.4vw; margin-right: .9vw; padding-left: .2vw;
          border-radius: 50%; background: #E50914; color: #fff;
          display: flex; align-items: center; justify-content: center; font-size: 1vw;
        }
        .tv-continue-text { display: flex; flex-direction: column; min-width: 0; }
        .tv-continue-label { font-size: .75vw; opacity: .65; text-transform: uppercase; letter-spacing: .08vw; }
        .tv-continue-title {
          font-size: 1.1vw; font-weight: 700;
          white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
        }
        .tv-continue.is-focused {
          background: #fff; color: #000; border-color: #fff; transform: scale(1.06);
          box-shadow: 0 .8vw 2.4vw rgba(0,0,0,.6), 0 0 0 .2vw #E50914;
        }
        .tv-clock { display: flex; flex-direction: column; align-items: flex-end; }
        .tv-clock-time { font-size: 2.1vw; font-weight: 300; letter-spacing: .1vw; font-variant-numeric: tabular-nums; }
        .tv-clock-date { font-size: .85vw; opacity: .6; text-transform: capitalize; }

        /* ---------- Hero ---------- */
        .tv-hero { flex: none; padding: 2vw 4vw 1.2vw; min-height: 11.5vw; }
        .tv-hero-kicker {
          font-size: 1vw; font-weight: 700; color: #E50914;
          text-transform: uppercase; letter-spacing: .15vw;
        }
        .tv-hero-title {
          margin: .5vw 0 .4vw; max-width: 72vw;
          font-size: 3vw; font-weight: 800; line-height: 1.12;
          display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
          text-shadow: 0 .2vw 1vw rgba(0,0,0,.6);
        }
        .tv-hero-meta { font-size: 1.1vw; opacity: .7; }

        /* ---------- Лента с модули ---------- */
        .tv-seasons { flex: none; margin: 0 4vw; padding: .8vw 0 1.2vw; overflow: hidden; }
        .tv-seasons-track {
          position: relative; display: flex; width: max-content; padding: 0 .8vw;
          transition: transform .45s cubic-bezier(.2,.8,.2,1); will-change: transform;
        }
        .tv-season {
          flex: none; display: flex; align-items: baseline; white-space: nowrap;
          margin-right: 1vw; padding: .85vw 2vw; border-radius: 3vw;
          background: rgba(255,255,255,.06); border: .15vw solid transparent;
          color: rgba(255,255,255,.62);
          transition: transform .25s ease, background .25s ease, color .25s ease, box-shadow .25s ease, border-color .25s ease;
        }
        .tv-season-name { font-size: 1.3vw; font-weight: 700; }
        .tv-season-count { font-size: .85vw; margin-left: .7vw; opacity: .65; }
        .tv-season.is-active { color: #fff; background: rgba(255,255,255,.14); border-color: rgba(255,255,255,.28); }
        #tv-app-ui[data-zone="seasons"] .tv-season.is-active {
          background: #E50914; border-color: #E50914; transform: scale(1.08);
          box-shadow: 0 .6vw 2vw rgba(229,9,20,.45);
        }

        /* ---------- Решетка ---------- */
        .tv-grid-wrap {
          flex: 1; min-height: 0; overflow-x: hidden; overflow-y: auto;
          padding: 1.6vw 4vw 7vw; scrollbar-width: none;
        }
        .tv-grid-wrap::-webkit-scrollbar { display: none; }
        .tv-grid {
          display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));
          grid-gap: 2.6vw 1.8vw; gap: 2.6vw 1.8vw;
        }
        .tv-card {
          position: relative; transform-origin: center center;
          transition: transform .28s cubic-bezier(.2,.8,.2,1), opacity .28s ease;
        }
        #tv-app-ui[data-zone="grid"] .tv-card { opacity: .72; }
        .tv-thumb {
          position: relative; padding-top: 56.25%; overflow: hidden; border-radius: .7vw;
          background: linear-gradient(135deg, #2a2a36, #15151c);
          box-shadow: 0 .4vw 1.2vw rgba(0,0,0,.5);
          transition: box-shadow .28s ease;
        }
        .tv-thumb::after {
          content: ''; position: absolute; left: 0; right: 0; bottom: 0; height: 55%; z-index: 1;
          background: linear-gradient(0deg, rgba(0,0,0,.75), rgba(0,0,0,0));
        }
        .tv-thumb-img {
          position: absolute; top: 0; left: 0; width: 100%; height: 100%;
          max-width: none; margin: 0; object-fit: cover; transition: transform .6s ease;
        }
        .tv-thumb-fallback {
          position: absolute; top: 0; left: 0; right: 0; bottom: 0;
          display: flex; align-items: center; justify-content: center;
          font-size: 4.5vw; font-weight: 800; color: rgba(255,255,255,.12);
        }
        .tv-play {
          position: absolute; left: 50%; top: 50%; z-index: 2;
          width: 4vw; height: 4vw; margin: -2vw 0 0 -2vw; padding-left: .25vw;
          border-radius: 50%; background: rgba(229,9,20,.92); color: #fff;
          display: flex; align-items: center; justify-content: center; font-size: 1.5vw;
          box-shadow: 0 0 2vw rgba(229,9,20,.6);
          opacity: 0; transform: scale(.6); transition: opacity .28s ease, transform .28s ease;
        }
        .tv-badge {
          position: absolute; top: .7vw; left: .7vw; z-index: 2;
          padding: .3vw .7vw; border-radius: .3vw;
          font-size: .72vw; font-weight: 700; text-transform: uppercase; letter-spacing: .05vw; color: #fff;
        }
        .tv-badge-watched { background: rgba(0,0,0,.68); }
        .tv-badge-last { background: #E50914; animation: tvPulse 2s ease-in-out infinite; }
        .tv-progress {
          position: absolute; left: 0; right: 0; bottom: 0; z-index: 2;
          height: .35vw; background: rgba(255,255,255,.18); display: none;
        }
        .tv-progress-bar { display: block; width: 100%; height: 100%; background: #E50914; }
        .tv-card.is-watched .tv-progress, .tv-card.is-last .tv-progress { display: block; }
        .tv-card.is-watched:not(.is-last) .tv-progress-bar { background: rgba(229,9,20,.55); }
        .tv-meta { padding: .8vw .2vw 0; }
        .tv-ep {
          display: block; font-size: .78vw; font-weight: 700; color: rgba(255,255,255,.5);
          text-transform: uppercase; letter-spacing: .1vw;
        }
        .tv-card-title {
          margin-top: .25vw; font-size: 1.15vw; font-weight: 600; line-height: 1.3;
          color: rgba(255,255,255,.8);
          display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
        }
        #tv-app-ui[data-zone="grid"] .tv-card.is-focused { opacity: 1; transform: scale(1.06); z-index: 3; }
        #tv-app-ui[data-zone="grid"] .tv-card.is-focused .tv-thumb {
          box-shadow: 0 0 0 .25vw #E50914, 0 1.6vw 3.2vw rgba(0,0,0,.75), 0 0 3vw rgba(229,9,20,.35);
        }
        #tv-app-ui[data-zone="grid"] .tv-card.is-focused .tv-play { opacity: 1; transform: scale(1); }
        #tv-app-ui[data-zone="grid"] .tv-card.is-focused .tv-thumb-img { transform: scale(1.05); }
        #tv-app-ui[data-zone="grid"] .tv-card.is-focused .tv-card-title { color: #fff; }
        #tv-app-ui:not([data-zone="grid"]) .tv-card.is-focused .tv-thumb {
          box-shadow: 0 0 0 .15vw rgba(255,255,255,.35), 0 .4vw 1.2vw rgba(0,0,0,.5);
        }

        /* ---------- Подсказки ---------- */
        .tv-hints {
          position: absolute; left: 0; right: 0; bottom: 0; z-index: 4;
          display: flex; justify-content: flex-end; padding: 1.4vw 4vw 1.2vw;
          font-size: .85vw; color: rgba(255,255,255,.6);
          background: linear-gradient(0deg, #0b0b0f 35%, rgba(11,11,15,0));
        }
        .tv-hint { margin-left: 2vw; white-space: nowrap; }
        .tv-kbd {
          display: inline-block; margin-right: .45vw; padding: .12vw .5vw; border-radius: .3vw;
          border: .1vw solid rgba(255,255,255,.35); color: #fff; font-weight: 700;
        }

        /* ---------- Зареждане ---------- */
        #tv-loading {
          position: fixed; top: 0; left: 0; right: 0; bottom: 0; z-index: 2147483100;
          display: none; align-items: center; justify-content: center; overflow: hidden;
          background: #000; color: #fff; visibility: visible !important;
          font-family: Roboto, 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; line-height: 1.35;
        }
        #tv-loading.is-visible { display: flex; animation: tvFade .3s ease; }
        .tv-loading-bg {
          position: absolute; top: -6%; left: -6%; right: -6%; bottom: -6%;
          background-size: cover; background-position: center;
          filter: blur(30px) brightness(.32) saturate(1.2);
        }
        .tv-loading-box { position: relative; max-width: 62vw; text-align: center; }
        .tv-spinner {
          width: 5vw; height: 5vw; margin: 0 auto 2vw; border-radius: 50%;
          border: .45vw solid rgba(255,255,255,.12); border-top-color: #E50914;
          animation: tvSpin .9s linear infinite;
        }
        .tv-error-icon {
          display: none; width: 5vw; height: 5vw; margin: 0 auto 2vw; border-radius: 50%;
          border: .4vw solid #E50914; color: #E50914; font-size: 2.8vw; font-weight: 800;
          align-items: center; justify-content: center;
        }
        #tv-loading.is-error .tv-spinner { display: none; }
        #tv-loading.is-error .tv-error-icon { display: flex; }
        .tv-loading-kicker {
          font-size: 1vw; font-weight: 700; color: #E50914;
          text-transform: uppercase; letter-spacing: .15vw;
        }
        .tv-loading-title { margin: .6vw 0 1.2vw; font-size: 2.4vw; font-weight: 800; line-height: 1.15; }
        .tv-loading-status { min-height: 1.6vw; font-size: 1.15vw; opacity: .78; }
        #tv-loading.is-error .tv-loading-status { color: #ff6b6b; opacity: 1; }
        .tv-loading-hint { margin-top: 2.4vw; font-size: .9vw; opacity: .55; }

        /* ---------- OSD за смяна на сървър ---------- */
        #tv-osd {
          position: fixed; left: 0; right: 0; bottom: 0; z-index: 2147483200;
          padding: 3vw 4vw 2.6vw; color: #fff; visibility: visible !important;
          font-family: Roboto, 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; line-height: 1.35;
          background: linear-gradient(0deg, rgba(0,0,0,.94) 40%, rgba(0,0,0,0));
          transform: translateY(110%); transition: transform .35s cubic-bezier(.2,.8,.2,1);
        }
        #tv-osd.is-open { transform: translateY(0); }
        .tv-osd-title {
          margin-bottom: 1vw; font-size: 1vw; font-weight: 700; color: #E50914;
          text-transform: uppercase; letter-spacing: .15vw;
        }
        .tv-osd-list { display: flex; flex-wrap: wrap; }
        .tv-osd-item {
          margin: 0 1vw 1vw 0; padding: .8vw 1.8vw; border-radius: 3vw;
          background: rgba(255,255,255,.1); border: .15vw solid transparent;
          font-size: 1.15vw; font-weight: 600;
          transition: transform .2s ease, background .2s ease, color .2s ease;
        }
        .tv-osd-item.is-current { border-color: rgba(229,9,20,.85); }
        .tv-osd-item.is-focused { background: #fff; color: #000; transform: scale(1.08); }
        .tv-osd-hint { font-size: .85vw; opacity: .55; }

        /* ---------- Подсказка във видео режим и toast ---------- */
        #tv-video-hint {
          position: fixed; top: 2vw; left: 50%; z-index: 2147483150;
          transform: translateX(-50%); padding: .8vw 1.8vw; border-radius: 3vw;
          background: rgba(0,0,0,.72); color: #fff; white-space: nowrap;
          font-family: Roboto, 'Segoe UI', Arial, sans-serif; font-size: .95vw;
          opacity: 0; transition: opacity .4s ease; pointer-events: none; visibility: visible !important;
        }
        #tv-video-hint.is-visible { opacity: 1; }
        #tv-toast {
          position: fixed; bottom: 4vw; left: 50%; z-index: 2147483300;
          transform: translate(-50%, 2vw); padding: 1vw 2vw; border-radius: .5vw;
          background: rgba(20,20,26,.96); border-left: .35vw solid #E50914; color: #fff;
          font-family: Roboto, 'Segoe UI', Arial, sans-serif; font-size: 1.1vw;
          opacity: 0; transition: opacity .35s ease, transform .35s ease;
          pointer-events: none; visibility: visible !important;
        }
        #tv-toast.is-visible { opacity: 1; transform: translate(-50%, 0); }

        @keyframes tvSpin { to { transform: rotate(360deg); } }
        @keyframes tvFade { from { opacity: 0; } to { opacity: 1; } }
        @keyframes tvPulse {
          0%, 100% { box-shadow: 0 0 0 0 rgba(229,9,20,.6); }
          50% { box-shadow: 0 0 0 .5vw rgba(229,9,20,0); }
        }
    """.trimIndent()

    private fun buildInjectionScript(): String = """
        (function () {
          'use strict';
          if (window.__tvInjected) return;
          window.__tvInjected = true;

          var PORTAL_TITLE = ${JSONObject.quote(PORTAL_TITLE)};
          var PICKER_SEL = '.player-picker[data-player-group="${PRIMARY_SERVER_GROUP}"]';
          var MENU_SEL = PICKER_SEL + ' .player-picker-menu';
          var CSS_TEXT = ${JSONObject.quote(buildCss())};

          var LS_LAST = 'tvArchive.lastWatched';
          var LS_WATCHED = 'tvArchive.watched';
          var COLS = 4;
          var SCAN_ATTEMPTS = 24;
          var SCAN_INTERVAL = 500;
          var CARD_CLICK_DELAY = 400;
          var MENU_STABLE_MS = 3000;
          var SERVER_TIMEOUT = 20000;
          var IFRAME_TIMEOUT = 15000;
          var AUTOPLAY_DELAY = 2000;

          var noop = function () {};
          var Bridge = window.TVBridge || { tapCenter: noop, passKey: noop, onModeChanged: noop, log: noop };

          var S = {
            seasons: [], seasonIdx: 0, cardIdx: 0, zone: 'seasons', mode: 'browse',
            playing: null, servers: [], serverIdx: 0, autoTries: 0, menuSnapshot: '',
            osdOpen: false, osdIdx: 0, fsTouched: [], timers: [],
            observer: null, poll: null, watchGen: 0, fallback: false
          };
          var UI = {};

          // ---------------- Помощни функции ----------------
          function qs(sel, root) { return (root || document).querySelector(sel); }
          function qsa(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
          function el(tag, cls, text) {
            var e = document.createElement(tag);
            if (cls) e.className = cls;
            if (text != null) e.textContent = text;
            return e;
          }
          function clean(t) { return (t || '').replace(/\s+/g, ' ').trim(); }
          function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
          function pad(n) { return (n < 10 ? '0' : '') + n; }
          function absUrl(u) { if (!u) return ''; try { return new URL(u, location.href).href; } catch (e) { return u; } }
          function load(k, d) { try { var v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } }
          function save(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
          function log(m) { try { Bridge.log(String(m)); } catch (e) {} }
          function cssEsc(s) { return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/"/g, '\\"'); }
          function lectures(n) { return n + (n === 1 ? ' лекция' : ' лекции'); }
          function fmtDate(ts) {
            try { return new Date(ts).toLocaleDateString('bg-BG', { day: 'numeric', month: 'long' }); }
            catch (e) { return ''; }
          }
          function later(fn, ms) {
            var id = setTimeout(function () {
              S.timers = S.timers.filter(function (t) { return t !== id; });
              fn();
            }, ms);
            S.timers.push(id);
            return id;
          }
          function clearTimers() {
            S.timers.forEach(function (t) { clearTimeout(t); });
            S.timers = [];
          }
          function setMode(m) {
            S.mode = m;
            document.documentElement.setAttribute('data-tv-mode', m);
            try { Bridge.onModeChanged(m); } catch (e) {}
          }

          // ---------------- Наблюдение на DOM ----------------
          function stopWatchers() {
            S.watchGen++;
            if (S.observer) { S.observer.disconnect(); S.observer = null; }
            if (S.poll) { clearInterval(S.poll); S.poll = null; }
          }
          function watch(fn) {
            stopWatchers();
            var gen = S.watchGen;
            var done = false;
            var run = function () {
              if (done || gen !== S.watchGen) return;
              if (fn()) {
                done = true;
                if (gen === S.watchGen) stopWatchers();
              }
            };
            run();
            if (done || gen !== S.watchGen) return;
            var pending = false;
            S.observer = new MutationObserver(function () {
              if (pending) return;
              pending = true;
              setTimeout(function () { pending = false; run(); }, 60);
            });
            S.observer.observe(document.body, {
              childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'class', 'style']
            });
            S.poll = setInterval(run, 350);
          }

          // ---------------- Извличане на данни ----------------
          function imgSrc(img) {
            if (!img) return '';
            var cands = [
              img.getAttribute('data-src'), img.getAttribute('data-lazy-src'),
              img.getAttribute('data-original'), img.currentSrc, img.getAttribute('src')
            ];
            for (var i = 0; i < cands.length; i++) {
              if (cands[i] && cands[i].indexOf('data:') !== 0) return absUrl(cands[i]);
            }
            var ss = img.getAttribute('data-srcset') || img.getAttribute('srcset');
            if (ss) {
              var lastSrc = ss.split(',').pop().trim().split(/\s+/)[0];
              if (lastSrc) return absUrl(lastSrc);
            }
            return '';
          }
          function buildSeason(sid, title, block, idx) {
            var cards = qsa('.episode-card', block).map(function (card, j) {
              var strong = qs('strong', card);
              var t = clean(strong ? strong.textContent : card.textContent) || ('Лекция ' + (j + 1));
              return { index: j, title: t, img: imgSrc(qs('img', card)), key: sid + '#' + j + '#' + t };
            });
            return { id: sid, title: title || ('Модул ' + (idx + 1)), cards: cards };
          }
          function scan() {
            var result = [];
            var seen = {};
            qsa('.episode-season-tab').forEach(function (tab) {
              var sid = tab.getAttribute('data-season-id');
              if (!sid || seen[sid]) return;
              var block = document.getElementById(sid);
              if (!block) return;
              seen[sid] = true;
              result.push(buildSeason(sid, clean(tab.textContent), block, result.length));
            });
            qsa('section.season-block').forEach(function (block) {
              if (!block.id || seen[block.id]) return;
              seen[block.id] = true;
              result.push(buildSeason(block.id, '', block, result.length));
            });
            return result.filter(function (s) { return s.cards.length > 0; });
          }
          function liveTab(season) {
            return qs('.episode-season-tab[data-season-id="' + cssEsc(season.id) + '"]');
          }
          function liveCard(season, j) {
            var block = document.getElementById(season.id);
            return block ? qsa('.episode-card', block)[j] : null;
          }
          function resolveLast() {
            var last = load(LS_LAST, null);
            if (!last) return null;
            for (var i = 0; i < S.seasons.length; i++) {
              var s = S.seasons[i];
              if (s.id === last.seasonId && s.cards[last.index]) {
                var c = s.cards[last.index];
                return { s: i, c: last.index, title: c.title, img: c.img, seasonTitle: s.title };
              }
            }
            return null;
          }

          // ---------------- Изграждане на интерфейса ----------------
          function buildUI() {
            var style = el('style');
            style.id = 'tv-app-style';
            style.textContent = CSS_TEXT;
            (document.head || document.documentElement).appendChild(style);

            var root = el('div');
            root.id = 'tv-app-ui';
            root.innerHTML =
              '<div class="tv-backdrop"></div><div class="tv-vignette"></div>' +
              '<div class="tv-header">' +
                '<div class="tv-brand"><span class="tv-brand-mark"></span><span class="tv-brand-title"></span></div>' +
                '<div class="tv-header-right">' +
                  '<div class="tv-continue is-hidden"><span class="tv-continue-icon">&#9654;</span>' +
                    '<span class="tv-continue-text"><span class="tv-continue-label">Продължи от последната лекция</span>' +
                    '<span class="tv-continue-title"></span></span></div>' +
                  '<div class="tv-clock"><span class="tv-clock-time"></span><span class="tv-clock-date"></span></div>' +
                '</div>' +
              '</div>' +
              '<div class="tv-hero"><div class="tv-hero-kicker"></div><div class="tv-hero-title"></div><div class="tv-hero-meta"></div></div>' +
              '<div class="tv-seasons"><div class="tv-seasons-track"></div></div>' +
              '<div class="tv-grid-wrap"><div class="tv-grid"></div></div>' +
              '<div class="tv-hints"></div>';
            document.body.appendChild(root);

            var loading = el('div');
            loading.id = 'tv-loading';
            loading.innerHTML =
              '<div class="tv-loading-bg"></div>' +
              '<div class="tv-loading-box">' +
                '<div class="tv-spinner"></div><div class="tv-error-icon">!</div>' +
                '<div class="tv-loading-kicker"></div><div class="tv-loading-title"></div>' +
                '<div class="tv-loading-status"></div><div class="tv-loading-hint"></div>' +
              '</div>';
            document.body.appendChild(loading);

            var osd = el('div');
            osd.id = 'tv-osd';
            osd.innerHTML =
              '<div class="tv-osd-title">Видео сървър</div><div class="tv-osd-list"></div>' +
              '<div class="tv-osd-hint">&#9664; &#9654; Избор &nbsp;·&nbsp; OK Превключи &nbsp;·&nbsp; &#9650; &#9660; / НАЗАД Скрий</div>';
            document.body.appendChild(osd);

            var videoHint = el('div', null, 'OK Пауза / Пусни  ·  ◀ ▶ Превъртане  ·  ▲ Сървъри  ·  НАЗАД Към лекциите');
            videoHint.id = 'tv-video-hint';
            document.body.appendChild(videoHint);

            var toastEl = el('div');
            toastEl.id = 'tv-toast';
            document.body.appendChild(toastEl);

            UI.root = root;
            UI.backdrop = qs('.tv-backdrop', root);
            UI.cont = qs('.tv-continue', root);
            UI.contTitle = qs('.tv-continue-title', root);
            UI.clockTime = qs('.tv-clock-time', root);
            UI.clockDate = qs('.tv-clock-date', root);
            UI.heroKicker = qs('.tv-hero-kicker', root);
            UI.heroTitle = qs('.tv-hero-title', root);
            UI.heroMeta = qs('.tv-hero-meta', root);
            UI.seasonsNav = qs('.tv-seasons', root);
            UI.track = qs('.tv-seasons-track', root);
            UI.gridWrap = qs('.tv-grid-wrap', root);
            UI.grid = qs('.tv-grid', root);
            UI.hints = qs('.tv-hints', root);
            UI.loading = loading;
            UI.loadingBg = qs('.tv-loading-bg', loading);
            UI.loadingKicker = qs('.tv-loading-kicker', loading);
            UI.loadingTitle = qs('.tv-loading-title', loading);
            UI.loadingStatus = qs('.tv-loading-status', loading);
            UI.loadingHint = qs('.tv-loading-hint', loading);
            UI.osd = osd;
            UI.osdList = qs('.tv-osd-list', osd);
            UI.videoHint = videoHint;
            UI.toast = toastEl;
            UI.cards = [];

            qs('.tv-brand-title', root).textContent = PORTAL_TITLE;
            document.documentElement.classList.add('tv-ui-on');
          }

          function renderSeasons() {
            UI.track.innerHTML = '';
            S.seasons.forEach(function (s) {
              var b = el('div', 'tv-season');
              b.appendChild(el('span', 'tv-season-name', s.title));
              b.appendChild(el('span', 'tv-season-count', String(s.cards.length)));
              UI.track.appendChild(b);
            });
          }

          function renderGrid() {
            var season = S.seasons[S.seasonIdx];
            var watched = load(LS_WATCHED, {});
            var last = load(LS_LAST, null);
            var frag = document.createDocumentFragment();
            UI.grid.innerHTML = '';

            season.cards.forEach(function (c, j) {
              var card = el('div', 'tv-card');
              var thumb = el('div', 'tv-thumb');
              thumb.appendChild(el('div', 'tv-thumb-fallback', String(j + 1)));
              if (c.img) {
                var im = new Image();
                im.className = 'tv-thumb-img';
                im.loading = 'lazy';
                im.decoding = 'async';
                im.alt = '';
                im.onerror = function () { if (im.parentNode) im.parentNode.removeChild(im); };
                im.src = c.img;
                thumb.appendChild(im);
              }
              var isLast = !!(last && last.seasonId === season.id && last.index === j);
              if (isLast) {
                card.classList.add('is-last');
                thumb.appendChild(el('span', 'tv-badge tv-badge-last', 'Последно гледана'));
              } else if (watched[c.key]) {
                thumb.appendChild(el('span', 'tv-badge tv-badge-watched', '✓ Гледана'));
              }
              if (watched[c.key]) card.classList.add('is-watched');
              var prog = el('div', 'tv-progress');
              prog.appendChild(el('span', 'tv-progress-bar'));
              thumb.appendChild(prog);
              thumb.appendChild(el('div', 'tv-play', '▶'));
              card.appendChild(thumb);

              var meta = el('div', 'tv-meta');
              meta.appendChild(el('span', 'tv-ep', 'Лекция ' + (j + 1)));
              meta.appendChild(el('div', 'tv-card-title', c.title));
              card.appendChild(meta);
              frag.appendChild(card);
            });

            UI.grid.appendChild(frag);
            UI.cards = qsa('.tv-card', UI.grid);
          }

          function renderHints() {
            var map = {
              header: [['OK', 'Продължи гледането'], ['▼', 'Модули']],
              seasons: [['◀ ▶', 'Смяна на модул'], ['▼', 'Лекции'], ['▲', 'Продължи'], ['НАЗАД', 'Изход']],
              grid: [['◀ ▶ ▲ ▼', 'Избор'], ['OK', 'Пусни лекцията'], ['НАЗАД', 'Модули']]
            };
            var items = map[S.zone] || [];
            UI.hints.innerHTML = '';
            items.forEach(function (it) {
              var h = el('span', 'tv-hint');
              h.appendChild(el('span', 'tv-kbd', it[0]));
              h.appendChild(document.createTextNode(it[1]));
              UI.hints.appendChild(h);
            });
          }

          function setBackdrop(url) {
            if (UI.lastBackdrop === url) return;
            UI.lastBackdrop = url;
            UI.backdrop.style.backgroundImage = url ? 'url(' + JSON.stringify(url) + ')' : 'none';
          }

          function updateContinue() {
            var r = resolveLast();
            UI.cont.classList.toggle('is-hidden', !r);
            if (r) UI.contTitle.textContent = r.seasonTitle + ' · ' + r.title;
            if (!r && S.zone === 'header') S.zone = 'seasons';
          }

          function updateHero() {
            var season = S.seasons[S.seasonIdx];
            if (!season) return;
            var watched = load(LS_WATCHED, {});
            if (S.zone === 'grid') {
              var c = season.cards[S.cardIdx];
              UI.heroKicker.textContent = season.title + ' · Лекция ' + (S.cardIdx + 1) + ' от ' + season.cards.length;
              UI.heroTitle.textContent = c.title;
              UI.heroMeta.textContent = watched[c.key]
                ? 'Гледана на ' + fmtDate(watched[c.key]) + '  ·  OK за повторно гледане'
                : 'Натиснете OK за възпроизвеждане';
              setBackdrop(c.img);
            } else if (S.zone === 'header') {
              var r = resolveLast();
              UI.heroKicker.textContent = 'Продължете оттам, докъдето стигнахте';
              UI.heroTitle.textContent = r ? r.title : '';
              UI.heroMeta.textContent = r ? r.seasonTitle + ' · Лекция ' + (r.c + 1) : '';
              setBackdrop(r ? r.img : '');
            } else {
              var wc = season.cards.filter(function (x) { return watched[x.key]; }).length;
              UI.heroKicker.textContent = 'Модул ' + (S.seasonIdx + 1) + ' от ' + S.seasons.length;
              UI.heroTitle.textContent = season.title;
              UI.heroMeta.textContent = lectures(season.cards.length) + '  ·  ' + wc + ' гледани';
              var bg = season.cards[S.cardIdx] || season.cards[0];
              setBackdrop(bg ? bg.img : '');
            }
          }

          function centerSeason() {
            var nav = UI.seasonsNav, track = UI.track, active = track.children[S.seasonIdx];
            if (!active) return;
            var max = Math.max(0, track.offsetWidth - nav.clientWidth);
            var off = clamp(active.offsetLeft + active.offsetWidth / 2 - nav.clientWidth / 2, 0, max);
            track.style.transform = 'translate3d(' + (-off) + 'px,0,0)';
          }

          function scrollCardIntoView(instant) {
            var c = UI.cards[S.cardIdx];
            if (!c) return;
            var w = UI.gridWrap;
            var top = Math.max(0, c.offsetTop - (w.clientHeight - c.offsetHeight) / 2);
            if (instant) w.scrollTop = top;
            else w.scrollTo({ top: top, behavior: 'smooth' });
          }

          function refresh() {
            UI.root.setAttribute('data-zone', S.zone);
            var pills = UI.track.children;
            for (var i = 0; i < pills.length; i++) pills[i].classList.toggle('is-active', i === S.seasonIdx);
            UI.cards.forEach(function (c, j) { c.classList.toggle('is-focused', j === S.cardIdx); });
            UI.cont.classList.toggle('is-focused', S.zone === 'header');
            centerSeason();
            if (S.zone === 'grid') scrollCardIntoView(false);
            updateHero();
            renderHints();
          }

          function selectSeason(i) {
            if (i < 0 || i >= S.seasons.length || i === S.seasonIdx) return;
            S.seasonIdx = i;
            var last = load(LS_LAST, null);
            var season = S.seasons[i];
            S.cardIdx = (last && last.seasonId === season.id && season.cards[last.index]) ? last.index : 0;
            renderGrid();
            UI.gridWrap.scrollTop = 0;
          }

          function tick() {
            var d = new Date();
            UI.clockTime.textContent = pad(d.getHours()) + ':' + pad(d.getMinutes());
            try {
              UI.clockDate.textContent = d.toLocaleDateString('bg-BG', { weekday: 'long', day: 'numeric', month: 'long' });
            } catch (e) {}
          }

          function toast(msg) {
            if (!UI.toast) return;
            UI.toast.textContent = msg;
            UI.toast.classList.add('is-visible');
            clearTimeout(UI.toastTimer);
            UI.toastTimer = setTimeout(function () { UI.toast.classList.remove('is-visible'); }, 2800);
          }

          function flashVideoHint() {
            UI.videoHint.classList.add('is-visible');
            clearTimeout(UI.hintTimer);
            UI.hintTimer = setTimeout(hideVideoHint, 3800);
          }
          function hideVideoHint() {
            clearTimeout(UI.hintTimer);
            UI.videoHint.classList.remove('is-visible');
          }

          function renderOsd() {
            UI.osd.classList.toggle('is-open', S.osdOpen);
            UI.osdList.innerHTML = '';
            S.servers.forEach(function (srv, i) {
              var item = el('div', 'tv-osd-item', (i + 1) + '. ' + srv.label);
              if (i === S.serverIdx) item.classList.add('is-current');
              if (S.osdOpen && i === S.osdIdx) item.classList.add('is-focused');
              UI.osdList.appendChild(item);
            });
          }

          // ---------------- Екран за зареждане ----------------
          function showLoading() {
            var p = S.playing;
            var season = p && S.seasons[p.sIdx];
            var card = season && season.cards[p.cIdx];
            UI.loadingKicker.textContent = season ? season.title + ' · Лекция ' + (p.cIdx + 1) : '';
            UI.loadingTitle.textContent = card ? card.title : '';
            UI.loadingBg.style.backgroundImage = card && card.img ? 'url(' + JSON.stringify(card.img) + ')' : 'none';
            UI.loading.classList.remove('is-error');
            UI.loading.classList.add('is-visible');
            UI.loadingHint.textContent = '◀ ▶ Смяна на сървъра  ·  НАЗАД Отказ';
          }
          function hideLoading() {
            UI.loading.classList.remove('is-visible', 'is-error');
          }
          function setStatus(msg) {
            UI.loadingStatus.textContent = msg;
          }
          function loadingError(msg) {
            stopWatchers();
            UI.loading.classList.add('is-visible', 'is-error');
            setStatus(msg);
            UI.loadingHint.textContent = S.servers.length
              ? '◀ ▶ Друг сървър  ·  OK Нов опит  ·  НАЗАД Към лекциите'
              : 'OK Нов опит  ·  НАЗАД Към лекциите';
          }

          // ---------------- Плейър ----------------
          function menuHtml() {
            var m = qs(MENU_SEL);
            return m ? m.innerHTML : '';
          }
          function serverItems() {
            var menu = qs(MENU_SEL);
            if (!menu) return [];
            var items = qsa('button, a, [role="button"], [data-url], [data-src], [data-server], [data-player], [data-id]', menu);
            if (!items.length) items = qsa('li', menu);
            if (!items.length) items = Array.prototype.slice.call(menu.children);
            // Запазваме най-вътрешните елементи — кликът им "мехурчира" към родителите.
            return items.filter(function (it) {
              return !items.some(function (o) { return o !== it && it.contains(o); });
            });
          }
          function clearPlayerBox() {
            var box = qs('#player-box');
            if (!box) return;
            qsa('iframe', box).forEach(function (f) { try { f.src = 'about:blank'; } catch (e) {} });
            box.innerHTML = '';
          }

          var FS_PROPS = {
            position: 'fixed', top: '0', left: '0', right: 'auto', bottom: 'auto',
            width: '100vw', height: '100vh', 'max-width': 'none', 'max-height': 'none',
            'min-width': '0', 'min-height': '0', margin: '0', padding: '0', border: '0',
            'z-index': '9999999', background: '#000', display: 'block',
            visibility: 'visible', opacity: '1', transform: 'none'
          };
          function rememberStyle(node) {
            S.fsTouched.push({ node: node, style: node.getAttribute('style') });
          }
          function applyFullscreen(frame) {
            restoreFullscreen();
            rememberStyle(frame);
            Object.keys(FS_PROPS).forEach(function (p) { frame.style.setProperty(p, FS_PROPS[p], 'important'); });
            // Неутрализираме предци, които биха "хванали" position: fixed или z-index.
            var p = frame.parentElement;
            while (p && p !== document.body && p !== document.documentElement) {
              rememberStyle(p);
              ['transform', 'filter', 'perspective', 'backdrop-filter'].forEach(function (pr) {
                p.style.setProperty(pr, 'none', 'important');
              });
              p.style.setProperty('contain', 'none', 'important');
              p.style.setProperty('will-change', 'auto', 'important');
              p.style.setProperty('z-index', 'auto', 'important');
              var cs = getComputedStyle(p);
              if (cs.display === 'none') p.style.setProperty('display', 'block', 'important');
              if (parseFloat(cs.opacity) < 1) p.style.setProperty('opacity', '1', 'important');
              p = p.parentElement;
            }
            document.documentElement.classList.add('tv-video-on');
          }
          function restoreFullscreen() {
            S.fsTouched.forEach(function (r) {
              if (r.style === null) r.node.removeAttribute('style');
              else r.node.setAttribute('style', r.style);
            });
            S.fsTouched = [];
            document.documentElement.classList.remove('tv-video-on');
          }

          function play(sIdx, cIdx) {
            var season = S.seasons[sIdx];
            var card = season && season.cards[cIdx];
            if (!card) return;

            stopWatchers();
            clearTimers();
            restoreFullscreen();
            hideVideoHint();
            S.osdOpen = false;
            S.seasonIdx = sIdx;
            S.cardIdx = cIdx;
            S.zone = 'grid';
            S.playing = { sIdx: sIdx, cIdx: cIdx };
            S.servers = [];
            S.serverIdx = 0;
            S.autoTries = 0;
            renderOsd();

            save(LS_LAST, { seasonId: season.id, index: cIdx, title: card.title, ts: Date.now() });

            setMode('loading');
            showLoading();
            setStatus('Отваряне на лекцията…');
            clearPlayerBox();

            var tab = liveTab(season);
            try { if (tab) tab.click(); } catch (e) { log('tab click: ' + e); }

            later(function () {
              if (S.mode !== 'loading') return;
              var target = liveCard(season, cIdx);
              if (!target) { loadingError('Лекцията не е намерена в страницата на архива.'); return; }
              S.menuSnapshot = menuHtml();
              try { target.click(); } catch (e) { log('card click: ' + e); }
              setStatus('Търсене на видео сървъри…');
              waitForServers();
            }, CARD_CLICK_DELAY);
          }

          function waitForServers() {
            var start = Date.now();
            watch(function () {
              if (S.mode !== 'loading') return true;
              var items = serverItems();
              var elapsed = Date.now() - start;
              if (items.length && (menuHtml() !== S.menuSnapshot || elapsed > MENU_STABLE_MS)) {
                S.servers = items.map(function (it, i) {
                  return { label: clean(it.textContent) || ('Сървър ' + (i + 1)) };
                });
                selectServer(0, false);
                return true;
              }
              if (elapsed > SERVER_TIMEOUT) {
                loadingError('Не бяха открити видео сървъри за тази лекция.');
                return true;
              }
              return false;
            });
          }

          function selectServer(i, manual) {
            var n = S.servers.length;
            if (!n) return;
            i = ((i % n) + n) % n;
            if (manual) S.autoTries = 0;
            S.serverIdx = i;

            clearTimers();
            restoreFullscreen();
            hideVideoHint();
            S.osdOpen = false;
            renderOsd();
            setMode('loading');
            showLoading();
            setStatus('Свързване със сървър ' + (i + 1) + ' от ' + n + '  ·  ' + S.servers[i].label);
            clearPlayerBox();

            var item = serverItems()[i];
            if (!item) { loadingError('Сървър ' + (i + 1) + ' вече не е наличен.'); return; }
            try { item.click(); } catch (e) { log('server click: ' + e); }

            var start = Date.now();
            watch(function () {
              if (S.mode !== 'loading') return true;
              var frame = qs('#player-box iframe');
              if (frame) {
                if (!frame.getAttribute('src') && frame.getAttribute('data-src')) {
                  frame.setAttribute('src', frame.getAttribute('data-src'));
                }
                var src = frame.getAttribute('src') || '';
                if (src && src !== 'about:blank') {
                  enterVideo(frame);
                  return true;
                }
              }
              if (Date.now() - start > IFRAME_TIMEOUT) {
                S.autoTries++;
                if (S.autoTries < n) {
                  toast('Сървър ' + (i + 1) + ' не отговаря – опитваме следващия');
                  selectServer(i + 1, false);
                } else {
                  loadingError('Нито един от сървърите не зареди видеото. Изберете ръчно с ◀ ▶.');
                }
                return true;
              }
              return false;
            });
          }

          function enterVideo(frame) {
            stopWatchers();
            clearTimers();
            try {
              frame.setAttribute('allowfullscreen', '');
              var allow = frame.getAttribute('allow') || '';
              if (allow.indexOf('autoplay') < 0) {
                frame.setAttribute('allow', (allow ? allow + '; ' : '') + 'autoplay; fullscreen; encrypted-media; picture-in-picture');
              }
            } catch (e) {}
            applyFullscreen(frame);
            hideLoading();
            S.osdOpen = false;
            renderOsd();
            setMode('video');

            var p = S.playing;
            var card = p && S.seasons[p.sIdx] && S.seasons[p.sIdx].cards[p.cIdx];
            if (card) {
              var w = load(LS_WATCHED, {});
              w[card.key] = Date.now();
              save(LS_WATCHED, w);
            }

            flashVideoHint();
            later(function () {
              if (S.mode === 'video') { try { Bridge.tapCenter(); } catch (e) {} }
            }, AUTOPLAY_DELAY);
          }

          function exitPlayback() {
            stopWatchers();
            clearTimers();
            restoreFullscreen();
            clearPlayerBox();
            hideLoading();
            hideVideoHint();
            S.osdOpen = false;
            renderOsd();
            setMode('browse');
            if (S.playing) {
              S.seasonIdx = S.playing.sIdx;
              S.cardIdx = S.playing.cIdx;
            }
            S.zone = 'grid';
            renderGrid();
            updateContinue();
            refresh();
            setTimeout(function () { scrollCardIntoView(true); }, 30);
          }

          // ---------------- Навигация ----------------
          function navVideo(dir) {
            if (S.osdOpen) {
              if (dir === 'LEFT') S.osdIdx = clamp(S.osdIdx - 1, 0, S.servers.length - 1);
              else if (dir === 'RIGHT') S.osdIdx = clamp(S.osdIdx + 1, 0, S.servers.length - 1);
              else if (dir === 'ENTER') { selectServer(S.osdIdx, true); return; }
              else S.osdOpen = false;
              renderOsd();
              return;
            }
            if (dir === 'ENTER') {
              try { Bridge.tapCenter(); } catch (e) {}
              flashVideoHint();
            } else if (dir === 'LEFT' || dir === 'RIGHT') {
              try { Bridge.passKey(dir); } catch (e) {}
            } else if (S.servers.length) {
              hideVideoHint();
              S.osdOpen = true;
              S.osdIdx = S.serverIdx;
              renderOsd();
            } else {
              flashVideoHint();
            }
          }

          function navLoading(dir) {
            var n = S.servers.length;
            var isError = UI.loading.classList.contains('is-error');
            if ((dir === 'LEFT' || dir === 'RIGHT') && n) {
              selectServer(S.serverIdx + (dir === 'RIGHT' ? 1 : -1), true);
            } else if (dir === 'ENTER' && isError) {
              if (n) selectServer(S.serverIdx, true);
              else if (S.playing) play(S.playing.sIdx, S.playing.cIdx);
            }
          }

          function nav(dir) {
            if (S.fallback) {
              if (dir === 'UP' || dir === 'DOWN') {
                window.scrollBy({ top: (dir === 'UP' ? -1 : 1) * window.innerHeight * 0.4, behavior: 'smooth' });
              }
              return;
            }
            if (!UI.root) return;
            if (S.mode === 'video') { navVideo(dir); return; }
            if (S.mode === 'loading') { navLoading(dir); return; }

            var season = S.seasons[S.seasonIdx];
            var n = season.cards.length;

            if (S.zone === 'header') {
              if (dir === 'DOWN') S.zone = 'seasons';
              else if (dir === 'ENTER') {
                var r = resolveLast();
                if (r) {
                  if (r.s !== S.seasonIdx) { S.seasonIdx = r.s; renderGrid(); }
                  play(r.s, r.c);
                  return;
                }
              }
            } else if (S.zone === 'seasons') {
              if (dir === 'LEFT') selectSeason(S.seasonIdx - 1);
              else if (dir === 'RIGHT') selectSeason(S.seasonIdx + 1);
              else if (dir === 'DOWN' || dir === 'ENTER') S.zone = 'grid';
              else if (dir === 'UP' && resolveLast()) S.zone = 'header';
            } else {
              var col = S.cardIdx % COLS;
              if (dir === 'LEFT') {
                if (col > 0) S.cardIdx--;
              } else if (dir === 'RIGHT') {
                if (col < COLS - 1 && S.cardIdx < n - 1) S.cardIdx++;
              } else if (dir === 'UP') {
                if (S.cardIdx - COLS >= 0) S.cardIdx -= COLS;
                else S.zone = 'seasons';
              } else if (dir === 'DOWN') {
                if (S.cardIdx + COLS < n) S.cardIdx += COLS;
                else if (Math.floor(S.cardIdx / COLS) < Math.floor((n - 1) / COLS)) S.cardIdx = n - 1;
              } else if (dir === 'ENTER') {
                play(S.seasonIdx, S.cardIdx);
                return;
              }
            }
            refresh();
          }

          function back() {
            if (!UI.root) return 'none';
            if (S.mode === 'video') {
              if (S.osdOpen) { S.osdOpen = false; renderOsd(); return 'handled'; }
              exitPlayback();
              return 'handled';
            }
            if (S.mode === 'loading') { exitPlayback(); return 'handled'; }
            if (S.zone === 'grid' || S.zone === 'header') {
              S.zone = 'seasons';
              refresh();
              return 'handled';
            }
            return 'root';
          }

          window.tvNav = function (dir) {
            try { nav(dir); } catch (e) { log('tvNav error: ' + e); }
          };
          window.tvBack = function () {
            try { return back(); } catch (e) { log('tvBack error: ' + e); return 'none'; }
          };
          window.tvNext = function (delta) {
            if ((S.mode !== 'video' && S.mode !== 'loading') || !S.playing) return;
            var season = S.seasons[S.playing.sIdx];
            var j = S.playing.cIdx + delta;
            if (!season || j < 0 || j >= season.cards.length) {
              toast(delta > 0 ? 'Това е последната лекция в модула' : 'Това е първата лекция в модула');
              return;
            }
            toast(delta > 0 ? 'Следваща лекция' : 'Предишна лекция');
            play(S.playing.sIdx, j);
          };

          // ---------------- Старт ----------------
          var attempts = 0;
          function init() {
            var data = scan();
            if (!data.length) {
              if (++attempts < SCAN_ATTEMPTS) { setTimeout(init, SCAN_INTERVAL); return; }
              S.fallback = true;
              log('No seasons/episodes found on ' + location.href);
              return;
            }
            S.seasons = data;
            buildUI();
            var r = resolveLast();
            if (r) { S.seasonIdx = r.s; S.cardIdx = r.c; }
            S.zone = 'seasons';
            renderSeasons();
            renderGrid();
            updateContinue();
            setMode('browse');
            refresh();
            setTimeout(function () { scrollCardIntoView(true); }, 50);
            tick();
            setInterval(tick, 1000);
            window.addEventListener('resize', centerSeason);
            log('TV UI ready: ' + data.length + ' modules');
          }

          init();
        })();
    """.trimIndent()
}
