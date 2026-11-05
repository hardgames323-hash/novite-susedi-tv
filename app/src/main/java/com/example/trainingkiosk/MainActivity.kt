package com.example.trainingkiosk

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TARGET_URL = "https://zenoplay.to/tv-show/novite-sasedi-2007/"

        // Сложи тук твоя линк към tv_ui.js от GitHub (дори да е с /blob/, кодът долу сам ще го оправи на raw!)
        private const val REMOTE_SCRIPT_URL = "https://raw.githubusercontent.com/hardgames323-hash/novite-susedi-tv/refs/heads/main/tv_ui.js"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val BLOCKED_KEYWORDS = listOf(
            "disable-devtool",
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
            "simple-views"
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout.addView(webView)
        rootLayout.addView(fullscreenContainer)
        setContentView(rootLayout)

        setupWebView()
        setupBackNavigation()

        webView.loadUrl(TARGET_URL)
        webView.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = DESKTOP_USER_AGENT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.addJavascriptInterface(TvBridge(), "AndroidTV")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (request.isForMainFrame) {
                    return !url.contains("zenoplay.to")
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString().lowercase()
                if (BLOCKED_KEYWORDS.any { url.contains(it) }) {
                    return WebResourceResponse(
                        "application/javascript",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("zenoplay.to")) {
                    loadAndInjectTvInterface()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                if (customView == null) return
                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    webView.evaluateJavascript("window.tvNav && window.tvNav('UP');", null)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    webView.evaluateJavascript("window.tvNav && window.tvNav('DOWN');", null)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    webView.evaluateJavascript("window.tvNav && window.tvNav('LEFT');", null)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    webView.evaluateJavascript("window.tvNav && window.tvNav('RIGHT');", null)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    webView.evaluateJavascript("window.tvNav && window.tvNav('ENTER');", null)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return
                }
                webView.evaluateJavascript("window.tvBack && window.tvBack();", null)
            }
        })
    }

    private fun simulateTouch(x: Float, y: Float) {
        mainHandler.post {
            val downTime = SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val upEvent = MotionEvent.obtain(downTime, downTime + 70, MotionEvent.ACTION_UP, x, y, 0)
            webView.dispatchTouchEvent(downEvent)
            webView.dispatchTouchEvent(upEvent)
            downEvent.recycle()
            upEvent.recycle()
        }
    }

    private fun sendNativeKeyToWebView(keyCode: Int) {
        mainHandler.post {
            val now = SystemClock.uptimeMillis()
            webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            webView.dispatchKeyEvent(KeyEvent(now, now + 40, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }

    inner class TvBridge {
        @JavascriptInterface
        fun clickCenterAfterDelay(delayMs: Long) {
            mainHandler.postDelayed({
                simulateTouch(webView.width / 2f, webView.height / 2f)
            }, delayMs)
        }

        @JavascriptInterface
        fun seekVideo(direction: String) {
            mainHandler.post {
                val key = if (direction == "LEFT") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                sendNativeKeyToWebView(key)
            }
        }

        @JavascriptInterface
        fun togglePlayPause() {
            mainHandler.post {
                simulateTouch(webView.width / 2f, webView.height / 2f)
            }
        }

        @JavascriptInterface
        fun checkUpdates() {
            mainHandler.post {
                Toast.makeText(this@MainActivity, "Обновяване на интерфейса...", Toast.LENGTH_SHORT).show()
                webView.loadUrl(TARGET_URL)
            }
        }
    }

    // Автоматично превръща стандартен GitHub линк (/blob/) в директен Raw линк
    private fun normalizeGitHubUrl(rawUrl: String): String {
        var clean = rawUrl.trim()
        if (clean.contains("github.com") && clean.contains("/blob/")) {
            clean = clean.replace("https://github.com/", "https://raw.githubusercontent.com/")
                .replace("/blob/", "/")
        }
        return clean
    }

    private fun loadAndInjectTvInterface() {
        val cleanUrl = normalizeGitHubUrl(REMOTE_SCRIPT_URL)
        if (cleanUrl.isNotBlank()) {
            thread {
                try {
                    val conn = URL("$cleanUrl?nocache=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                    conn.useCaches = false
                    conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                    conn.setRequestProperty("Pragma", "no-cache")
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    val remoteJs = conn.inputStream.bufferedReader().use { it.readText() }
                    // Проверяваме дали върнатият файл наистина е JS код, а не HTML страница за грешка
                    if (remoteJs.isNotBlank() && !remoteJs.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) && !remoteJs.trimStart().startsWith("<html", ignoreCase = true)) {
                        mainHandler.post {
                            webView.evaluateJavascript(remoteJs, null)
                        }
                        return@thread
                    }
                } catch (_: Exception) {
                }
                mainHandler.post { injectBuiltInTvInterface() }
            }
        } else {
            injectBuiltInTvInterface()
        }
    }

    private fun injectBuiltInTvInterface() {
        val jsCode = """
        (function() {
            if (window.location.pathname.indexOf('/episode/') !== -1) {
                window.location.replace('https://zenoplay.to/tv-show/novite-sasedi-2007/');
                return;
            }

            if (window.tvInterfaceInitialized) return;
            window.tvInterfaceInitialized = true;

            const seasonsData = [];
            const seasonTabs = document.querySelectorAll('.episode-season-tab');
            seasonTabs.forEach((tab) => {
                const seasonId = tab.getAttribute('data-season-id');
                const seasonTitle = tab.innerText.trim();
                const block = document.getElementById(seasonId);
                const episodes = [];
                if (block) {
                    const cards = block.querySelectorAll('.episode-card');
                    cards.forEach((card, eIdx) => {
                        const imgEl = card.querySelector('img');
                        const titleEl = card.querySelector('strong');
                        let imgSrc = imgEl ? imgEl.src : '';
                        if (imgSrc.indexOf('/w300/') !== -1) {
                            imgSrc = imgSrc.replace('/w300/', '/w185/');
                        }
                        if (imgEl) imgEl.remove();

                        episodes.push({
                            title: titleEl ? titleEl.innerText.trim() : ('Епизод ' + (eIdx + 1)),
                            img: imgSrc,
                            href: card.getAttribute('href'),
                            domElement: card
                        });
                    });
                }
                seasonsData.push({
                    id: seasonId,
                    title: seasonTitle,
                    tabElement: tab,
                    episodes: episodes
                });
            });

            if (seasonsData.length === 0) return;

            const junkSelectors = [
                'header', 'nav', 'footer', '.site-header', '.site-footer',
                '.detail-hero', '.recommended-section', '#player-current-episode', '.tv-show-season-select-wrap'
            ];
            junkSelectors.forEach(sel => {
                document.querySelectorAll(sel).forEach(el => el.remove());
            });

            const style = document.createElement('style');
            style.innerHTML = `
                html, body { overflow: hidden !important; background: #000 !important; margin: 0 !important; padding: 0 !important; }
                .player-toolbar { display: none !important; }
                #tv-custom-overlay {
                    position: fixed; inset: 0; z-index: 999990;
                    background: #101010;
                    color: #fff; font-family: sans-serif;
                    display: flex; flex-direction: column;
                    padding: 22px 34px; box-sizing: border-box;
                    user-select: none; contain: layout style paint;
                }
                #tv-header {
                    display: flex; align-items: center; justify-content: space-between;
                    margin-bottom: 14px;
                }
                #tv-header h1 {
                    margin: 0; font-size: 30px; color: #E50914; font-weight: 800;
                }
                #tv-header-right {
                    display: flex; align-items: center; gap: 12px;
                }
                .tv-top-btn {
                    padding: 8px 16px; border-radius: 4px; background: #242424;
                    color: #ddd; font-size: 16px; font-weight: bold; border: 3px solid transparent;
                }
                .tv-top-btn.tv-focused {
                    background: #E50914 !important; color: #fff !important; border-color: #fff !important;
                }
                #tv-seasons-row {
                    display: flex; gap: 10px; overflow-x: auto; padding: 4px 2px;
                    scrollbar-width: none; flex-shrink: 0; margin-bottom: 14px;
                }
                #tv-seasons-row::-webkit-scrollbar { display: none; }
                .tv-season-btn {
                    padding: 10px 20px; border-radius: 5px; background: #202020;
                    color: #bbb; font-size: 19px; font-weight: bold; white-space: nowrap;
                    border: 3px solid transparent;
                }
                .tv-season-btn.active-season {
                    background: #333; color: #fff; border-color: #666;
                }
                .tv-season-btn.tv-focused {
                    background: #E50914 !important; color: #fff !important; border-color: #fff !important;
                }
                #tv-episodes-grid {
                    display: grid; grid-template-columns: repeat(4, 1fr);
                    gap: 14px; overflow-y: auto; padding: 4px 2px 40px 2px;
                    flex: 1; scrollbar-width: none; contain: content;
                }
                #tv-episodes-grid::-webkit-scrollbar { display: none; }
                .tv-ep-card {
                    background: #1c1c1c; border-radius: 5px; overflow: hidden;
                    border: 4px solid transparent;
                    display: flex; flex-direction: column; position: relative;
                    height: 175px;
                }
                .tv-ep-card img {
                    width: 100%; height: 122px; object-fit: cover; background: #080808;
                }
                .tv-ep-title {
                    padding: 8px 12px; font-size: 18px; font-weight: bold; color: #ccc;
                    display: flex; justify-content: space-between; align-items: center;
                }
                .tv-ep-card.history-ep {
                    border-color: #2e5c38;
                }
                .tv-ep-card.history-ep .tv-ep-title::after {
                    content: '✓ ГЛЕДАН'; font-size: 11px; background: #2e5c38;
                    color: #fff; padding: 2px 6px; border-radius: 3px;
                }
                .tv-ep-card.watched-ep {
                    border-color: #46d369 !important;
                }
                .tv-ep-card.watched-ep .tv-ep-title::after {
                    content: 'ПОСЛЕДЕН' !important; font-size: 11px; background: #46d369 !important;
                    color: #000 !important; padding: 2px 6px; border-radius: 3px; font-weight: 900;
                }
                .tv-ep-card.tv-focused {
                    border-color: #E50914 !important; background: #303030 !important;
                }
                .tv-ep-card.tv-focused .tv-ep-title {
                    color: #fff !important;
                }
                #tv-loader {
                    position: fixed; inset: 0; z-index: 999995;
                    background: #000; color: #fff;
                    display: none; flex-direction: column;
                    align-items: center; justify-content: center;
                    font-size: 26px; font-weight: bold;
                }
                #tv-osd {
                    position: fixed; bottom: 40px; left: 50%; transform: translateX(-50%);
                    z-index: 99999999; background: #111; color: #fff;
                    padding: 10px 24px; border-radius: 6px; border: 2px solid #E50914;
                    font-size: 20px; font-weight: bold; display: none; pointer-events: none;
                }
                body.tv-playing-mode #tv-custom-overlay { display: none !important; }
                body.tv-playing-mode #episodes { display: none !important; }
                body.tv-playing-mode #player-box iframe {
                    position: fixed !important; top: 0 !important; left: 0 !important;
                    width: 100vw !important; height: 100vh !important;
                    z-index: 9999999 !important; border: none !important;
                    background: #000 !important;
                }
            `;
            document.head.appendChild(style);

            let watchedSeason = parseInt(localStorage.getItem('tv_last_season') || '0', 10);
            if (watchedSeason >= seasonsData.length || watchedSeason < 0) watchedSeason = 0;
            let watchedEp = parseInt(localStorage.getItem('tv_last_ep') || '0', 10);
            if (watchedEp >= seasonsData[watchedSeason].episodes.length || watchedEp < 0) watchedEp = 0;

            let watchedHistory = {};
            try {
                watchedHistory = JSON.parse(localStorage.getItem('tv_watched_history') || '{}');
            } catch (e) {
                watchedHistory = {};
            }

            const overlay = document.createElement('div');
            overlay.id = 'tv-custom-overlay';
            overlay.innerHTML = `
                <div id="tv-header">
                    <h1>НОВИТЕ СЪСЕДИ</h1>
                    <div id="tv-header-right">
                        <div id="tv-continue-btn" class="tv-top-btn">▶ Продължи: С` + (watchedSeason + 1) + ` Еп ` + (watchedEp + 1) + `</div>
                        <div id="tv-update-btn" class="tv-top-btn">⟳ Обнови</div>
                    </div>
                </div>
                <div id="tv-seasons-row"></div>
                <div id="tv-episodes-grid"></div>
            `;
            document.body.appendChild(overlay);

            const loader = document.createElement('div');
            loader.id = 'tv-loader';
            loader.innerHTML = '<div id="tv-loader-msg">Зареждане на епизода...</div>';
            document.body.appendChild(loader);

            const osd = document.createElement('div');
            osd.id = 'tv-osd';
            document.body.appendChild(osd);

            let osdTimer = null;
            function showOsd(text) {
                osd.innerText = text;
                osd.style.display = 'block';
                if (osdTimer) clearTimeout(osdTimer);
                osdTimer = setTimeout(() => { osd.style.display = 'none'; }, 1400);
            }

            let zone = 'episodes';
            let topIndex = 0;
            let sIndex = watchedSeason;
            let eIndex = watchedEp;
            let checkTimer = null;

            let seasonBtnNodes = [];
            let episodeCardNodes = [];
            let prevFocusedEl = null;
            let prevActiveSeasonEl = null;

            const seasonsRow = document.getElementById('tv-seasons-row');
            const episodesGrid = document.getElementById('tv-episodes-grid');
            const continueBtn = document.getElementById('tv-continue-btn');
            const updateBtn = document.getElementById('tv-update-btn');
            const topBtns = [continueBtn, updateBtn];
            const loaderMsg = document.getElementById('tv-loader-msg');

            function renderSeasons() {
                seasonsRow.innerHTML = '';
                seasonBtnNodes = [];
                const frag = document.createDocumentFragment();
                seasonsData.forEach((s, idx) => {
                    const btn = document.createElement('div');
                    btn.className = 'tv-season-btn' + (idx === sIndex ? ' active-season' : '');
                    if (idx === sIndex) prevActiveSeasonEl = btn;
                    btn.innerText = s.title;
                    seasonBtnNodes.push(btn);
                    frag.appendChild(btn);
                });
                seasonsRow.appendChild(frag);
            }

            function renderEpisodes() {
                episodesGrid.innerHTML = '';
                episodeCardNodes = [];
                const eps = seasonsData[sIndex].episodes;
                if (eIndex >= eps.length) eIndex = 0;
                const frag = document.createDocumentFragment();
                eps.forEach((ep, idx) => {
                    const card = document.createElement('div');
                    let cls = 'tv-ep-card';
                    const key = sIndex + '_' + idx;
                    if (sIndex === watchedSeason && idx === watchedEp) {
                        cls += ' watched-ep';
                    } else if (watchedHistory[key]) {
                        cls += ' history-ep';
                    }
                    card.className = cls;
                    card.innerHTML = '<img src="' + ep.img + '" decoding="async" alt="">' +
                        '<div class="tv-ep-title"><span>▶ ' + ep.title + '</span></div>';
                    episodeCardNodes.push(card);
                    frag.appendChild(card);
                });
                episodesGrid.appendChild(frag);
            }

            function updateFocusVisuals() {
                if (prevFocusedEl) {
                    prevFocusedEl.classList.remove('tv-focused');
                }
                if (prevActiveSeasonEl && prevActiveSeasonEl !== seasonBtnNodes[sIndex]) {
                    prevActiveSeasonEl.classList.remove('active-season');
                }

                const currentSeasonBtn = seasonBtnNodes[sIndex];
                if (currentSeasonBtn) {
                    currentSeasonBtn.classList.add('active-season');
                    prevActiveSeasonEl = currentSeasonBtn;
                }

                let newFocusEl = null;
                if (zone === 'top') {
                    newFocusEl = topBtns[topIndex];
                } else if (zone === 'seasons') {
                    newFocusEl = seasonBtnNodes[sIndex];
                    if (newFocusEl) {
                        newFocusEl.scrollIntoView({ behavior: 'auto', block: 'nearest', inline: 'center' });
                    }
                } else if (zone === 'episodes') {
                    newFocusEl = episodeCardNodes[eIndex];
                    if (newFocusEl) {
                        newFocusEl.scrollIntoView({ behavior: 'auto', block: 'nearest' });
                    }
                }

                if (newFocusEl) {
                    newFocusEl.classList.add('tv-focused');
                    prevFocusedEl = newFocusEl;
                }
            }

            function playSelectedEpisode() {
                const season = seasonsData[sIndex];
                const ep = season.episodes[eIndex];
                if (!ep) return;

                if (checkTimer) clearInterval(checkTimer);

                watchedSeason = sIndex;
                watchedEp = eIndex;
                watchedHistory[sIndex + '_' + eIndex] = 1;
                localStorage.setItem('tv_last_season', String(sIndex));
                localStorage.setItem('tv_last_ep', String(eIndex));
                try {
                    localStorage.setItem('tv_watched_history', JSON.stringify(watchedHistory));
                } catch (e) {}

                continueBtn.innerText = '▶ Продължи: С' + (watchedSeason + 1) + ' Еп ' + (watchedEp + 1);

                zone = 'player';
                loaderMsg.innerText = 'Зареждане на ' + season.title + ' - ' + ep.title + '...';
                loader.style.display = 'flex';

                if (season.tabElement) season.tabElement.click();

                const allEps = season.episodes;
                const isLastOrActive = (eIndex === allEps.length - 1) || (ep.domElement && ep.domElement.classList.contains('active'));
                if (isLastOrActive && allEps.length > 1) {
                    const dummyIdx = (eIndex === 0) ? 1 : 0;
                    if (allEps[dummyIdx] && allEps[dummyIdx].domElement) {
                        allEps[dummyIdx].domElement.click();
                    }
                }

                setTimeout(() => {
                    if (ep.domElement) ep.domElement.click();
                }, 160);

                let attempts = 0;
                const bgMenu = document.querySelector('.player-picker[data-player-group="bg"] .player-picker-menu');
                if (bgMenu) delete bgMenu.dataset.autoClicked;

                checkTimer = setInterval(() => {
                    attempts++;
                    const currentBgMenu = document.querySelector('.player-picker[data-player-group="bg"] .player-picker-menu');
                    if (attempts >= 2 && currentBgMenu && currentBgMenu.children.length > 0 && !currentBgMenu.dataset.autoClicked) {
                        currentBgMenu.dataset.autoClicked = 'true';
                        currentBgMenu.children[0].click();
                    }

                    const iframe = document.querySelector('#player-box iframe');
                    if (iframe && iframe.src && iframe.src.length > 10 && !iframe.src.includes('about:blank')) {
                        clearInterval(checkTimer);
                        checkTimer = null;
                        setTimeout(() => {
                            loader.style.display = 'none';
                            document.body.classList.add('tv-playing-mode');
                            iframe.focus();
                            if (window.AndroidTV && window.AndroidTV.clickCenterAfterDelay) {
                                window.AndroidTV.clickCenterAfterDelay(2200);
                            }
                        }, 650);
                    } else if (attempts > 40) {
                        clearInterval(checkTimer);
                        checkTimer = null;
                        window.location.replace('https://zenoplay.to/tv-show/novite-sasedi-2007/');
                    }
                }, 250);
            }

            window.tvNav = function(dir) {
                if (zone === 'player') {
                    const iframe = document.querySelector('#player-box iframe');
                    if (iframe) iframe.focus();

                    if (dir === 'ENTER' && window.AndroidTV) {
                        showOsd('⏯ Пусни / Пауза');
                        window.AndroidTV.togglePlayPause();
                    } else if (dir === 'LEFT' && window.AndroidTV) {
                        showOsd('⏪ Назад');
                        window.AndroidTV.seekVideo('LEFT');
                    } else if (dir === 'RIGHT' && window.AndroidTV) {
                        showOsd('⏩ Напред');
                        window.AndroidTV.seekVideo('RIGHT');
                    }
                    return;
                }

                const epsCount = seasonsData[sIndex].episodes.length;
                const cols = 4;

                if (zone === 'top') {
                    if (dir === 'LEFT' && topIndex > 0) {
                        topIndex--;
                    } else if (dir === 'RIGHT' && topIndex < topBtns.length - 1) {
                        topIndex++;
                    } else if (dir === 'DOWN') {
                        zone = 'seasons';
                    } else if (dir === 'ENTER') {
                        if (topIndex === 0) {
                            sIndex = watchedSeason;
                            eIndex = watchedEp;
                            renderSeasons();
                            renderEpisodes();
                            playSelectedEpisode();
                        } else if (window.AndroidTV) {
                            window.AndroidTV.checkUpdates();
                        }
                        return;
                    }
                } else if (zone === 'seasons') {
                    if (dir === 'LEFT' && sIndex > 0) {
                        sIndex--;
                        eIndex = 0;
                        renderEpisodes();
                    } else if (dir === 'RIGHT' && sIndex < seasonsData.length - 1) {
                        sIndex++;
                        eIndex = 0;
                        renderEpisodes();
                    } else if (dir === 'UP') {
                        zone = 'top';
                    } else if (dir === 'DOWN' || dir === 'ENTER') {
                        zone = 'episodes';
                    }
                } else if (zone === 'episodes') {
                    if (dir === 'LEFT') {
                        if (eIndex > 0) eIndex--;
                    } else if (dir === 'RIGHT') {
                        if (eIndex < epsCount - 1) eIndex++;
                    } else if (dir === 'UP') {
                        if (eIndex >= cols) {
                            eIndex -= cols;
                        } else {
                            zone = 'seasons';
                        }
                    } else if (dir === 'DOWN') {
                        if (eIndex + cols < epsCount) {
                            eIndex += cols;
                        } else {
                            eIndex = epsCount - 1;
                        }
                    } else if (dir === 'ENTER') {
                        playSelectedEpisode();
                        return;
                    }
                }
                updateFocusVisuals();
            };

            window.tvBack = function() {
                if (checkTimer) {
                    clearInterval(checkTimer);
                    checkTimer = null;
                }
                if (zone === 'player' || document.body.classList.contains('tv-playing-mode')) {
                    loaderMsg.innerText = 'Връщане към списъка с епизоди...';
                    loader.style.display = 'flex';
                    const playerBox = document.getElementById('player-box');
                    if (playerBox) playerBox.innerHTML = '';
                    window.location.replace('https://zenoplay.to/tv-show/novite-sasedi-2007/');
                    return true;
                }
                if (zone === 'episodes') {
                    zone = 'seasons';
                    updateFocusVisuals();
                    return true;
                }
                if (zone === 'seasons') {
                    zone = 'top';
                    updateFocusVisuals();
                    return true;
                }
                return false;
            };

            renderSeasons();
            renderEpisodes();
            updateFocusVisuals();
        })();
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }
}
