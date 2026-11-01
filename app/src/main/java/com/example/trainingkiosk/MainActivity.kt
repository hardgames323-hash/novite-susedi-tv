package com.example.trainingkiosk

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONObject
import java.io.ByteArrayInputStream

private const val TARGET_URL = "https://zenoplay.to/tv-show/novite-sasedi-2007/"

/** Стандартен десктоп User-Agent (Windows 10, Chrome), за да се рендира десктоп навигацията със сезоните. */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"

/** Рекламни домейни – блокират се заедно с всичките им поддомейни. */
private val BLOCKED_DOMAINS = hashSetOf(
    "doubleclick.net",
    "googlesyndication.com",
    "popads.net",
    "exoclick.com",
    "juicyads.com",
    "adsterra.com",
    "acscdn.com",
    "histats.com",
    "yandex.ru",
    "onclickalgo.com",
    "adtng.com",
    "cdn4ads.com",
)

/** Части от URL, които се блокират независимо от домейна. */
private val BLOCKED_URL_PARTS = listOf("disable-devtool")

/** Основният хост; навигацията в главния прозорец е разрешена само към него и поддомейните му. */
private val ALLOWED_HOST: String = Uri.parse(TARGET_URL).host.orEmpty().lowercase()

private val BACKGROUND_COLOR = Color.parseColor("#141414")

private const val KIOSK_CSS = """
header, footer, nav, .site-header, .site-footer, .detail-socials, .mobile-detail-socials, .taxonomy-group, .detail-year-link, .player-provider-group[data-player-group="en"], .tv-show-season-select-wrap, .recommended-section { display: none !important; }
body, .site-main, .detail-page { background-color: #141414 !important; color: #ffffff !important; }
button:focus, a:focus, select:focus, .episode-season-tab:focus, .episode-card:focus, .player-picker-button:focus {
    outline: 4px solid #E50914 !important;
    outline-offset: 2px !important;
    transform: scale(1.04) !important;
    transition: transform 0.15s ease !important;
    background-color: #2b2b2b !important;
    z-index: 50 !important;
}
.episode-season-tab.active { background-color: #E50914 !important; color: #fff !important; }
.episode-card.last-watched { border: 3px solid #46d369 !important; }
#player { margin-top: 10px !important; }
"""

/**
 * JS за ТВ дистанционно. Слушателите и наблюдателят се закачат веднъж (onPageFinished може да се
 * извика няколко пъти за една страница), а CSS и tabindex се прилагат при всяко извикване.
 */
private val KIOSK_JS = """
(function() {
    try {
        // --- CSS ---
        if (!document.getElementById('kiosk-tv-style')) {
            var style = document.createElement('style');
            style.id = 'kiosk-tv-style';
            style.textContent = ${JSONObject.quote(KIOSK_CSS)};
            (document.head || document.documentElement).appendChild(style);
        }

        var FOCUSABLE = '.episode-season-tab, .episode-card, .player-picker-button';
        var BG_MENU = '.player-picker[data-player-group="bg"] .player-picker-menu';
        var BG_ITEM = 'button, a, [role="option"], [role="menuitem"], li';
        var BG_ACTIVE = '.active, .selected, [aria-selected="true"], [aria-pressed="true"]';

        // 1. tabindex за навигация със стрелките
        function makeFocusable() {
            var nodes = document.querySelectorAll(FOCUSABLE);
            for (var i = 0; i < nodes.length; i++) {
                if (!nodes[i].hasAttribute('tabindex')) nodes[i].setAttribute('tabindex', '0');
            }
        }

        function cardHref(card) {
            var href = card.getAttribute('href');
            if (!href) {
                var a = card.querySelector('a[href]');
                if (a) href = a.getAttribute('href');
            }
            return href;
        }

        function markLastWatched(card) {
            var old = document.querySelectorAll('.episode-card.last-watched');
            for (var i = 0; i < old.length; i++) old[i].classList.remove('last-watched');
            card.classList.add('last-watched');
        }

        // 3. Автоматичен избор на първия BG плеър, щом менюто се напълни
        function autoPickBgPlayer() {
            var menu = document.querySelector(BG_MENU);
            if (!menu || menu.getAttribute('data-kiosk-autopicked') === '1') return;
            if (menu.querySelector(BG_ACTIVE)) return; // вече има избран активен BG плеър
            var first = menu.querySelector(BG_ITEM);
            if (!first) return; // менюто още е празно
            menu.setAttribute('data-kiosk-autopicked', '1');
            first.click();
        }

        // 4. Възстановяване на последно гледания епизод
        function restoreLastWatched() {
            var seasonId = localStorage.getItem('lastSeasonId');
            var episodeHref = localStorage.getItem('lastEpisodeHref');
            if (!seasonId) return;

            var tab = document.querySelector('.episode-season-tab[data-season-id="' + CSS.escape(seasonId) + '"]');
            if (tab) tab.click();

            // Даваме време на сайта да покаже епизодите от избрания сезон
            setTimeout(function() {
                if (!episodeHref) return;
                var cards = document.querySelectorAll('.episode-card');
                for (var i = 0; i < cards.length; i++) {
                    if (cardHref(cards[i]) === episodeHref) {
                        markLastWatched(cards[i]);
                        cards[i].focus();
                        cards[i].scrollIntoView({ block: 'center' });
                        break;
                    }
                }
            }, 300);
        }

        makeFocusable();

        if (!window.__kioskInit) {
            window.__kioskInit = true;

            // Един наблюдател за целия документ: следи и за нови фокусируеми елементи,
            // и за появата/напълването на BG менюто (то може да не съществува при зареждане).
            var pending = false;
            new MutationObserver(function() {
                if (pending) return;
                pending = true;
                setTimeout(function() {
                    pending = false;
                    makeFocusable();
                    autoPickBgPlayer();
                }, 100);
            }).observe(document.documentElement, { childList: true, subtree: true });

            // 2. Запаметяване на избрания епизод + скрол до плеъра
            document.addEventListener('click', function(e) {
                var card = e.target.closest ? e.target.closest('.episode-card') : null;
                if (!card) return;

                var block = card.closest('.season-block');
                var seasonId = block ? block.getAttribute('data-season-id') : null;
                var href = cardHref(card);

                if (seasonId) localStorage.setItem('lastSeasonId', seasonId);
                if (href) localStorage.setItem('lastEpisodeHref', href);
                markLastWatched(card);

                // Нов епизод → позволяваме нов автоматичен избор на BG плеър
                var menu = document.querySelector(BG_MENU);
                if (menu) menu.removeAttribute('data-kiosk-autopicked');

                var player = document.getElementById('player');
                if (player) player.scrollIntoView({ behavior: 'smooth' });
            }, true);

            // OK/Enter от дистанционното върху div с tabindex не предизвиква click сам по себе си
            document.addEventListener('keydown', function(e) {
                if (e.key !== 'Enter' && e.keyCode !== 13) return;
                var el = document.activeElement;
                if (!el || !el.matches || !el.matches(FOCUSABLE)) return;
                if (el.tagName === 'A' || el.tagName === 'BUTTON') return; // те кликат сами
                e.preventDefault();
                el.click();
            }, true);

            restoreLastWatched();
            autoPickBgPlayer();
        }
    } catch (err) {
        console.log('Kiosk inject error: ' + err);
    }
})();
""".trimIndent()

/**
 * Скролва до #episodes, ако списъкът не е във видимата част. Връща "scrolled", ако е скролнал,
 * "visible", ако списъкът вече се вижда, и "missing", ако на страницата няма #episodes.
 */
private val SCROLL_TO_EPISODES_JS = """
(function() {
    var el = document.getElementById('episodes');
    if (!el) return 'missing';
    var r = el.getBoundingClientRect();
    if (r.top >= -10 && r.top < window.innerHeight * 0.5) return 'visible';
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    var focusTarget = el.querySelector('.episode-card.last-watched') || el.querySelector('.episode-card, .episode-season-tab');
    if (focusTarget) focusTarget.focus({ preventScroll: true });
    return 'scrolled';
})();
""".trimIndent()

class MainActivity : ComponentActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var webView: WebView
    private val chromeClient = KioskWebChromeClient()

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val matchParent = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        rootLayout = FrameLayout(this).apply { setBackgroundColor(BACKGROUND_COLOR) }
        webView = WebView(this).apply {
            setBackgroundColor(BACKGROUND_COLOR)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        // Контейнер за видеото на цял екран – стои над WebView и е скрит, докато не потрябва
        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        rootLayout.addView(webView, FrameLayout.LayoutParams(matchParent))
        rootLayout.addView(fullscreenContainer, FrameLayout.LayoutParams(matchParent))
        setContentView(rootLayout)
        enterImmersiveMode()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = DESKTOP_USER_AGENT
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            // Popup blocker
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }

        webView.webViewClient = KioskWebViewClient()
        webView.webChromeClient = chromeClient

        onBackPressedDispatcher.addCallback(this, backCallback)

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(TARGET_URL)
        }
        webView.requestFocus()
    }

    // ---------------------------------------------------------------------------------------------
    // WebViewClient: ограничаване на навигацията, AdBlock / Script Blocker, инжектиране на CSS/JS
    // ---------------------------------------------------------------------------------------------

    private inner class KioskWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false // iframe → видео плеърите се зареждат нормално
            return !isAllowedMainFrameUrl(request.url)
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            !isAllowedMainFrameUrl(Uri.parse(url))

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (isBlockedRequest(request.url)) {
                WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(ByteArray(0)))
            } else {
                null
            }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            view.evaluateJavascript(KIOSK_JS, null)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Рендер процесът е паднал – пресъздаваме активитито (onDestroy унищожава стария WebView).
            recreate()
            return true
        }
    }

    /** Главният прозорец може да отиде само на http(s) адрес към основния хост или негов поддомейн. */
    private fun isAllowedMainFrameUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false // intent:, market:, javascript: и др.
        val host = uri.host?.lowercase() ?: return false
        return host == ALLOWED_HOST || host.endsWith(".$ALLOWED_HOST")
    }

    private fun isBlockedRequest(uri: Uri): Boolean {
        val url = uri.toString().lowercase()
        if (BLOCKED_URL_PARTS.any { it in url }) return true
        val host = uri.host ?: return false
        return isBlockedHost(host)
    }

    /** Проверява хоста и родителските му домейни: a.b.doubleclick.net → b.doubleclick.net → doubleclick.net. */
    private fun isBlockedHost(host: String): Boolean {
        var candidate = host.lowercase()
        while (true) {
            if (candidate in BLOCKED_DOMAINS) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // WebChromeClient: без popup-и + HTML5 видео на цял екран
    // ---------------------------------------------------------------------------------------------

    private inner class KioskWebChromeClient : WebChromeClient() {

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean = false

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback

            fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            fullscreenContainer.visibility = View.VISIBLE
            webView.visibility = View.GONE

            view.isFocusable = true
            view.requestFocus()
            enterImmersiveMode()
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            fullscreenContainer.removeView(view)
            fullscreenContainer.visibility = View.GONE
            customView = null

            customViewCallback?.onCustomViewHidden()
            customViewCallback = null

            webView.visibility = View.VISIBLE
            webView.requestFocus()
            enterImmersiveMode()
        }

        /** Прозрачен постер вместо сивата "play" икона на WebView. */
        override fun getDefaultVideoPoster(): Bitmap =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------------------------------------
    // Бутон Назад на дистанционното
    // ---------------------------------------------------------------------------------------------

    /**
     * 1. Видео на цял екран → излиза от цял екран.
     * 2. Списъкът #episodes не се вижда → скролва до него.
     * 3. Списъкът вече се вижда (или липсва) → предишна страница, а ако няма такава – затваря приложението.
     */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (customView != null) {
                chromeClient.onHideCustomView()
                return
            }
            webView.evaluateJavascript(SCROLL_TO_EPISODES_JS) { result ->
                if (result == "\"scrolled\"") return@evaluateJavascript
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Цял екран / жизнен цикъл
    // ---------------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        customViewCallback?.onCustomViewHidden()
        rootLayout.removeAllViews()
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
        super.onDestroy()
    }
}
