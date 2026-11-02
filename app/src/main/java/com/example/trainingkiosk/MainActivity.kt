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

        // ОПЦИЯ ЗА АВТОМАТИЧНИ ЪПДЕЙТИ (OTA):
        // Ако качиш JS кода отдолу във файл "tv_ui.js" в публично GitHub репо или Gist,
        // сложи Raw линка тук. При всяко пускане телевизорът ще тегли най-новата версия автоматично!
        // Ако е празно (""), ползва вградения код.
        private const val REMOTE_SCRIPT_URL = "private const val REMOTE_SCRIPT_URL = "https://raw.githubusercontent.com/hardgames323-hash/novite-susedi-tv/refs/heads/main/tv_ui.js"

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

    // Изпраща клавишно събитие (стрелка наляво/надясно или Space) директно към фокусирания видео плеър (iframe)
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
            // 1. Първо фокусираме центъра (ако още не е фокусиран iframe-ът) и пращаме нативен клавиш Left/Right към плеъра (JWPlayer / VideoJS разбират стрелките)
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
            if (REMOTE_SCRIPT_URL.isBlank()) {
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "Вече сте с най-новата вградена версия!", Toast.LENGTH_SHORT).show()
                }
                return
            }
            mainHandler.post {
                Toast.makeText(this@MainActivity, "Проверка за обновления...", Toast.LENGTH_SHORT).show()
            }
            loadAndInjectTvInterface(forceReload = true)
        }
    }

    private fun loadAndInjectTvInterface(forceReload: Boolean = false) {
        if (REMOTE_SCRIPT_URL.isNotBlank()) {
            thread {
                try {
                    val conn = URL("$REMOTE_SCRIPT_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    val remoteJs = conn.inputStream.bufferedReader().use { it.readText() }
                    if (remoteJs.isNotBlank()) {
                        mainHandler.post {
                            if (forceReload) {
                                webView.evaluateJavascript("window.tvInterfaceInitialized = false; document.getElementById('tv-custom-overlay')?.remove();", null)
                                Toast.makeText(this, "Интерфейсът е обновен успешно!", Toast.LENGTH_SHORT).show()
                            }
                            webView.evaluateJavascript(remoteJs, null)
                        }
                        return@thread
                    }
                } catch (_: Exception) {
                    // Ако няма връзка с GitHub, падаме автоматично към вградения скрипт
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
                        episodes.push({
                            title: titleEl ? titleEl.innerText.trim() : ('Епизод ' + (eIdx + 1)),
                            img: imgEl ? imgEl.src : '',
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

            const style = document.createElement('style');
            style.innerHTML = `
                body { overflow: hidden !important; background: #000 !important; margin: 0 !important; }
                header, nav, footer, .site-header, .detail-hero, .recommended-section, .player-toolbar, #player-current-episode {
                    display: none !important;
                }
                #tv-custom-overlay {
                    position: fixed; inset: 0; z-index: 999990;
                    background: linear-gradient(180deg, #1a1a1a 0%, #101010 100%);
                    color: #fff; font-family: sans-serif;
                    display: flex; flex-direction: column;
                    padding: 28px 40px; box-sizing: border-box;
                    user-select: none;
                }
                #tv-header {
                    display: flex; align-items: center; justify-content: space-between;
                    margin-bottom: 18px;
                }
                #tv-header h1 {
                    margin: 0; font-size: 34px; color: #E50914; font-weight: 800; letter-spacing: 0.5px;
                }
                #tv-header-right {
                    display: flex; align-items: center; gap: 16px;
                }
                #tv-status {
                    font-size: 17px; color: #aaa;
                }
                #tv-update-btn {
                    padding: 8px 16px; border-radius: 6px; background: #2b2b2b;
                    color: #ccc; font-size: 16px; font-weight: bold; border: 2px solid transparent;
                }
                #tv-update-btn.tv-focused {
                    background: #E50914 !important; color: #fff !important; border-color: #fff !important;
                }
                #tv-seasons-row {
                    display: flex; gap: 12px; overflow-x: auto; padding: 10px 4px;
                    scrollbar-width: none; flex-shrink: 0; margin-bottom: 20px;
                }
                #tv-seasons-row::-webkit-scrollbar { display: none; }
                .tv-season-btn {
                    padding: 12px 24px; border-radius: 8px; background: #262626;
                    color: #ddd; font-size: 20px; font-weight: bold; white-space: nowrap;
                    border: 3px solid transparent; transition: all 0.15s ease;
                }
                .tv-season-btn.active-season {
                    background: #383838; color: #fff; border-color: #555;
                }
                .tv-season-btn.tv-focused {
                    background: #E50914 !important; color: #fff !important;
                    border-color: #fff !important; transform: scale(1.08);
                    box-shadow: 0 0 20px rgba(229, 9, 20, 0.7);
                }
                #tv-episodes-grid {
                    display: grid; grid-template-columns: repeat(4, 1fr);
                    gap: 20px; overflow-y: auto; padding: 10px 6px 60px 6px;
                    flex: 1; scrollbar-width: none;
                }
                #tv-episodes-grid::-webkit-scrollbar { display: none; }
                .tv-ep-card {
                    background: #222; border-radius: 10px; overflow: hidden;
                    border: 4px solid transparent; transition: all 0.15s ease;
                    display: flex; flex-direction: column; position: relative;
                    height: 185px;
                }
                .tv-ep-card img {
                    width: 100%; height: 130px; object-fit: cover; background: #111;
                }
                .tv-ep-title {
                    padding: 10px 14px; font-size: 20px; font-weight: bold; color: #eee;
                    display: flex; justify-content: space-between; align-items: center;
                }
                .tv-ep-card.watched-ep {
                    border-color: #46d369;
                }
                .tv-ep-card.watched-ep .tv-ep-title::after {
                    content: 'ПОСЛЕДЕН'; font-size: 12px; background: #46d369;
                    color: #000; padding: 3px 7px; border-radius: 4px; font-weight: 900;
                }
                .tv-ep-card.tv-focused {
                    border-color: #E50914 !important; background: #333 !important;
                    transform: scale(1.06); z-index: 10;
                    box-shadow: 0 8px 25px rgba(229, 9, 20, 0.65);
                }
                #tv-loader {
                    position: fixed; inset: 0; z-index: 999995;
                    background: #000; color: #fff;
                    display: none; flex-direction: column;
                    align-items: center; justify-content: center;
                    font-size: 28px; font-weight: bold;
                }
                #tv-osd {
                    position: fixed; bottom: 40px; left: 50%; transform: translateX(-50%);
                    z-index: 99999999; background: rgba(0,0,0,0.85); color: #fff;
                    padding: 12px 28px; border-radius: 30px; border: 2px solid #E50914;
                    font-size: 22px; font-weight: bold; display: none; pointer-events: none;
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

            const overlay = document.createElement('div');
            overlay.id = 'tv-custom-overlay';
            overlay.innerHTML = `
                <div id="tv-header">
                    <h1>НОВИТЕ СЪСЕДИ</h1>
                    <div id="tv-header-right">
                        <div id="tv-status">ОК = Пусни | Назад = Списък със сезони</div>
                        <div id="tv-update-btn">⟳ Обнови</div>
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
                osdTimer = setTimeout(() => { osd.style.display = 'none'; }, 1800);
            }

            let zone = 'episodes'; // 'update', 'seasons', 'episodes', 'player'
            let sIndex = parseInt(localStorage.getItem('tv_last_season') || '0', 10);
            if (sIndex >= seasonsData.length || sIndex < 0) sIndex = 0;
            let eIndex = parseInt(localStorage.getItem('tv_last_ep') || '0', 10);
            let watchedSeason = sIndex;
            let watchedEp = eIndex;
            let checkTimer = null;

            const seasonsRow = document.getElementById('tv-seasons-row');
            const episodesGrid = document.getElementById('tv-episodes-grid');
            const updateBtn = document.getElementById('tv-update-btn');
            const loaderMsg = document.getElementById('tv-loader-msg');

            function renderSeasons() {
                seasonsRow.innerHTML = '';
                seasonsData.forEach((s, idx) => {
                    const btn = document.createElement('div');
                    btn.className = 'tv-season-btn' + (idx === sIndex ? ' active-season' : '');
                    if (zone === 'seasons' && idx === sIndex) btn.className += ' tv-focused';
                    btn.innerText = s.title;
                    seasonsRow.appendChild(btn);
                });
            }

            function renderEpisodes() {
                episodesGrid.innerHTML = '';
                const eps = seasonsData[sIndex].episodes;
                if (eIndex >= eps.length) eIndex = 0;
                eps.forEach((ep, idx) => {
                    const card = document.createElement('div');
                    let cls = 'tv-ep-card';
                    if (sIndex === watchedSeason && idx === watchedEp) cls += ' watched-ep';
                    if (zone === 'episodes' && idx === eIndex) cls += ' tv-focused';
                    card.className = cls;
                    card.innerHTML = `
                        <img src="${'$'}{ep.img}" alt="">
                        <div class="tv-ep-title"><span>▶ ${'$'}{ep.title}</span></div>
                    `;
                    episodesGrid.appendChild(card);
                });
            }

            function updateFocusVisuals() {
                updateBtn.classList.toggle('tv-focused', zone === 'update');

                const sBtns = seasonsRow.querySelectorAll('.tv-season-btn');
                sBtns.forEach((b, idx) => {
                    b.classList.toggle('active-season', idx === sIndex);
                    b.classList.toggle('tv-focused', zone === 'seasons' && idx === sIndex);
                    if (zone === 'seasons' && idx === sIndex) {
                        b.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
                    }
                });

                const eCards = episodesGrid.querySelectorAll('.tv-ep-card');
                eCards.forEach((c, idx) => {
                    c.classList.toggle('tv-focused', zone === 'episodes' && idx === eIndex);
                    if (zone === 'episodes' && idx === eIndex) {
                        c.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    }
                });
            }

            // Помощна функция: намира всички истински бутони за сървъри в BG менюто
            function getBgButtons() {
                const menu = document.querySelector('.player-picker[data-player-group="bg"] .player-picker-menu');
                if (!menu) return [];
                const btns = menu.querySelectorAll('button, a, [role="menuitem"], li');
                return btns.length > 0 ? Array.from(btns) : Array.from(menu.children);
            }

            function playSelectedEpisode() {
                const season = seasonsData[sIndex];
                const ep = season.episodes[eIndex];
                if (!ep) return;

                if (checkTimer) clearInterval(checkTimer);

                watchedSeason = sIndex;
                watchedEp = eIndex;
                localStorage.setItem('tv_last_season', String(sIndex));
                localStorage.setItem('tv_last_ep', String(eIndex));

                zone = 'player';
                loaderMsg.innerText = 'Зареждане на ' + season.title + ' - ' + ep.title + '...';
                loader.style.display = 'flex';

                const playerBox = document.getElementById('player-box');
                if (playerBox) playerBox.innerHTML = '';

                // Махаме класа active/current от всички карти, за да не игнорира ZenoPlay клика върху текущо избрана карта!
                document.querySelectorAll('.episode-card').forEach(c => {
                    c.classList.remove('active', 'is-active', 'current', 'selected');
                });

                // 1. Активираме таба на сезона
                if (season.tabElement) season.tabElement.click();

                // 2. Кликваме директно желания епизод
                setTimeout(() => {
                    if (ep.domElement) ep.domElement.click();
                }, 150);

                let attempts = 0;
                let triedFallbackBtn = 0;

                checkTimer = setInterval(() => {
                    attempts++;
                    const iframe = document.querySelector('#player-box iframe');

                    // АКО сайтът вече сам е заредил iframe (което прави по подразбиране за повечето епизоди),
                    // НЕ пипаме bgMenu, за да не го разкачим!
                    if (iframe && iframe.src && iframe.src.length > 10 && !iframe.src.includes('about:blank')) {
                        clearInterval(checkTimer);
                        checkTimer = null;
                        setTimeout(() => {
                            loader.style.display = 'none';
                            document.body.classList.add('tv-playing-mode');
                            iframe.focus();
                            // Симулираме клик в центъра след 2.2 сек, за да стартира видеото И да получи фокус за превъртане
                            if (window.AndroidTV && window.AndroidTV.clickCenterAfterDelay) {
                                window.AndroidTV.clickCenterAfterDelay(2200);
                            }
                        }, 600);
                        return;
                    }

                    // АКО до 1.5 сек (attempts == 6) сайтът НЕ е пуснал сам iframe, чак тогава кликваме първия BG бутон!
                    if (attempts === 6 || attempts === 14 || attempts === 22) {
                        const bgBtns = getBgButtons();
                        if (bgBtns.length > 0) {
                            const btnToClick = bgBtns[triedFallbackBtn % bgBtns.length];
                            triedFallbackBtn++;
                            if (btnToClick) btnToClick.click();
                        }
                    }

                    if (attempts > 45) {
                        clearInterval(checkTimer);
                        checkTimer = null;
                        loader.style.display = 'none';
                        window.tvBack();
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

                if (zone === 'update') {
                    if (dir === 'DOWN') {
                        zone = 'seasons';
                    } else if (dir === 'ENTER' && window.AndroidTV) {
                        window.AndroidTV.checkUpdates();
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
                        zone = 'update';
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
                    loader.style.display = 'none';
                    document.body.classList.remove('tv-playing-mode');
                    const playerBox = document.getElementById('player-box');
                    if (playerBox) playerBox.innerHTML = '';
                    zone = 'episodes';
                    renderSeasons();
                    renderEpisodes();
                    updateFocusVisuals();
                    return true;
                }
                if (zone === 'episodes') {
                    zone = 'seasons';
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
