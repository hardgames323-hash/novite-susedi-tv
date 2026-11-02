(function() {
    // Ако сме попаднали на /episode/ страница, веднага връщаме към главната страница със сезоните
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
        /* Скриваме горната лента на ZenoPlay и излишните елементи */
        header, nav, footer, .site-header, .site-footer, .detail-hero, .recommended-section, .player-toolbar, #player-current-episode {
            display: none !important;
        }
        #tv-custom-overlay {
            position: fixed; inset: 0; z-index: 999990;
            background: #121212;
            color: #fff; font-family: sans-serif;
            display: flex; flex-direction: column;
            padding: 24px 36px; box-sizing: border-box;
            user-select: none;
        }
        #tv-header {
            display: flex; align-items: center; justify-content: space-between;
            margin-bottom: 16px;
        }
        #tv-header h1 {
            margin: 0; font-size: 32px; color: #E50914; font-weight: 800;
        }
        #tv-header-right {
            display: flex; align-items: center; gap: 16px;
        }
        #tv-status {
            font-size: 16px; color: #999;
        }
        #tv-update-btn {
            padding: 8px 16px; border-radius: 4px; background: #262626;
            color: #ccc; font-size: 16px; font-weight: bold; border: 3px solid transparent;
        }
        #tv-update-btn.tv-focused {
            background: #E50914 !important; color: #fff !important; border-color: #fff !important;
        }
        #tv-seasons-row {
            display: flex; gap: 10px; overflow-x: auto; padding: 6px 2px;
            scrollbar-width: none; flex-shrink: 0; margin-bottom: 16px;
        }
        #tv-seasons-row::-webkit-scrollbar { display: none; }
        .tv-season-btn {
            padding: 10px 20px; border-radius: 6px; background: #222;
            color: #ccc; font-size: 19px; font-weight: bold; white-space: nowrap;
            border: 3px solid transparent;
        }
        .tv-season-btn.active-season {
            background: #333; color: #fff; border-color: #666;
        }
        .tv-season-btn.tv-focused {
            background: #E50914 !important; color: #fff !important;
            border-color: #fff !important;
        }
        #tv-episodes-grid {
            display: grid; grid-template-columns: repeat(4, 1fr);
            gap: 16px; overflow-y: auto; padding: 6px 4px 50px 4px;
            flex: 1; scrollbar-width: none;
        }
        #tv-episodes-grid::-webkit-scrollbar { display: none; }
        .tv-ep-card {
            background: #1e1e1e; border-radius: 6px; overflow: hidden;
            border: 4px solid transparent;
            display: flex; flex-direction: column; position: relative;
            height: 180px;
        }
        .tv-ep-card img {
            width: 100%; height: 125px; object-fit: cover; background: #0a0a0a;
        }
        .tv-ep-title {
            padding: 10px 12px; font-size: 19px; font-weight: bold; color: #ddd;
            display: flex; justify-content: space-between; align-items: center;
        }
        .tv-ep-card.watched-ep {
            border-color: #46d369;
        }
        .tv-ep-card.watched-ep .tv-ep-title::after {
            content: 'ПОСЛЕДЕН'; font-size: 11px; background: #46d369;
            color: #000; padding: 2px 6px; border-radius: 3px; font-weight: 900;
        }
        .tv-ep-card.tv-focused {
            border-color: #E50914 !important; background: #333 !important;
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
        osdTimer = setTimeout(() => { osd.style.display = 'none'; }, 1500);
    }

    let zone = 'episodes'; // 'update', 'seasons', 'episodes', 'player'
    let sIndex = parseInt(localStorage.getItem('tv_last_season') || '0', 10);
    if (sIndex >= seasonsData.length || sIndex < 0) sIndex = 0;
    let eIndex = parseInt(localStorage.getItem('tv_last_ep') || '0', 10);
    let watchedSeason = sIndex;
    let watchedEp = eIndex;
    let checkTimer = null;

    let seasonBtnNodes = [];
    let episodeCardNodes = [];
    let prevFocusedEl = null;
    let prevActiveSeasonEl = null;

    const seasonsRow = document.getElementById('tv-seasons-row');
    const episodesGrid = document.getElementById('tv-episodes-grid');
    const updateBtn = document.getElementById('tv-update-btn');
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
            if (sIndex === watchedSeason && idx === watchedEp) cls += ' watched-ep';
            card.className = cls;
            card.innerHTML = '<img src="' + ep.img + '" loading="lazy" decoding="async" alt="">' +
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
        if (zone === 'update') {
            newFocusEl = updateBtn;
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

    // ОРИГИНАЛНАТА РАБОТЕЩА ФУНКЦИЯ ЗА ПУСКАНЕ НА ЕПИЗОД (която винаги успява на чиста страница)
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

        if (season.tabElement) season.tabElement.click();
        setTimeout(() => {
            if (ep.domElement) ep.domElement.click();
        }, 150);

        let attempts = 0;
        const bgMenu = document.querySelector('.player-picker[data-player-group="bg"] .player-picker-menu');
        if (bgMenu) delete bgMenu.dataset.autoClicked;

        checkTimer = setInterval(() => {
            attempts++;
            const currentBgMenu = document.querySelector('.player-picker[data-player-group="bg"] .player-picker-menu');
            if (currentBgMenu && currentBgMenu.children.length > 0 && !currentBgMenu.dataset.autoClicked) {
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
                }, 700);
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

        if (zone === 'update') {
            if (dir === 'DOWN') {
                zone = 'seasons';
            } else if (dir === 'ENTER') {
                // При натискане на Обнови презареждаме страницата начисто, за да изтегли новия скрипт от GitHub
                window.location.replace('https://zenoplay.to/tv-show/novite-sasedi-2007/');
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
            // КЛЮЧОВ МОМЕНТ: Вместо да чупим DOM-а на сайта при излизане от видеото,
            // презареждаме главната страница начисто за 0.5 сек!
            // Така следващият епизод ВИНАГИ се пуска като "1-ви опит" и никога не забива!
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
        return false;
    };

    renderSeasons();
    renderEpisodes();
    updateFocusVisuals();
})();
