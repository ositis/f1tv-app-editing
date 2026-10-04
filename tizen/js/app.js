/* Screens and routing. */
(function (global) {
  'use strict';

  var h = UI.h;
  var screenEl;
  var stack = [];          // [{name, params, focusKey}]
  var cache = {};
  var CACHE_MS = 5 * 60 * 1000;
  var renderToken = 0;

  async function cached(key, fn, ttl) {
    var hit = cache[key];
    if (hit && Date.now() - hit.at < (ttl || CACHE_MS)) return hit.value;
    var value = await fn();
    cache[key] = { at: Date.now(), value: value };
    return value;
  }

  function seriesFilter(list) {
    var f = Store.get('series');
    return list.filter(function (s) { return F1Api.Series.matches(f, s.series); });
  }

  /* ---------- routing ---------- */

  function go(name, params) {
    var cur = stack[stack.length - 1];
    var layer = Nav.top();
    if (cur && layer && layer.current) cur.focusKey = layer.current.getAttribute('data-key');
    stack.push({ name: name, params: params || {} });
    render();
  }

  function back() {
    if (stack.length <= 1) {
      UI.pick('Exit UgisF1?', [{ label: 'Exit', exit: true }, { label: 'Stay', selected: true }]).then(function (c) {
        if (c && c.exit) Nav.exitApp();
      });
      return;
    }
    stack.pop();
    render();
  }

  function reset(name, params) {
    stack = [];
    go(name, params);
  }

  function setScreen(content, title, opts) {
    opts = opts || {};
    screenEl.innerHTML = '';
    screenEl.scrollTop = 0;
    screenEl.appendChild(content);
    UI.crumb(title);
    var entry = stack[stack.length - 1];
    var initial = entry && entry.focusKey ? screenEl.querySelector('[data-key="' + cssEscape(entry.focusKey) + '"]') : null;
    if (!initial && opts.initial) initial = opts.initial;
    Nav.replaceBase({
      root: screenEl,
      initial: initial,
      onBack: back,
      onKey: function (key) {
        if (key === 'BLUE' || key === 'RED') { go('settings'); return true; }
        return opts.onKey ? opts.onKey(key) : false;
      }
    });
  }

  function cssEscape(s) { return String(s).replace(/["\\]/g, '\\$&'); }

  function keyed(el, key) { el.setAttribute('data-key', key); return el; }

  async function render() {
    var my = ++renderToken;
    var entry = stack[stack.length - 1];
    var fn = SCREENS[entry.name];
    setScreen(UI.loading(), '');
    try {
      await fn(entry.params, function () { return my === renderToken; });
    } catch (e) {
      if (my !== renderToken) return;
      console.error(e);
      if (e.status === 401) { reset('login', { error: 'Your session expired — please sign in again.' }); return; }
      setScreen(UI.message('Something went wrong', e.message, [
        UI.button('Retry', function () { cache = {}; render(); }),
        UI.button('Settings', function () { go('settings'); }),
        stack.length > 1 ? UI.button('Back', back) : null
      ].filter(Boolean)), 'Error');
    }
  }

  /* ---------- session cards ---------- */

  function sessionBadges(s) {
    var b = [];
    if (UI.isLive(s)) b.push({ text: 'LIVE', cls: 'badge-live' });
    else if (s.upcoming) b.push({ text: UI.fmtDateTime(s.start), cls: 'badge-soon' });
    if (s.series && s.series !== 'F1') b.push({ text: s.series, cls: 'badge-series' });
    return b;
  }

  function sessionCard(s, size) {
    return keyed(UI.card({
      title: s.title,
      subtitle: s.upcoming ? 'Starts ' + UI.fmtDateTime(s.start) : (s.subtype || ''),
      picture: s.picture,
      badges: sessionBadges(s),
      size: size,
      onSelect: function () {
        if (s.upcoming && !UI.isLive(s)) { UI.toast('Starts ' + UI.fmtDateTime(s.start) + ' — check back then'); return; }
        openSession(s);
      }
    }), 's' + s.contentId);
  }

  async function openSession(s) {
    var channels;
    try {
      channels = await cached('ch' + s.contentId, function () { return F1Api.getChannels(s.contentId); }, UI.isLive(s) ? 60000 : CACHE_MS);
    } catch (e) {
      channels = [{ type: 'WIF', contentId: s.contentId, channelId: null, name: 'Main feed', label: 'Main feed' }];
    }
    if (channels.length > 1 && Store.get('showChannelGrid')) go('channels', { session: s, channels: channels });
    else play(s, channels, 0);
  }

  function play(session, channels, index) {
    Player.open(session, channels, index, function () { Nav.refresh(); });
  }

  /* ---------- screens ---------- */

  var SCREENS = {};

  SCREENS.login = async function (params) {
    var creds = Store.getCredentials() || {};
    var user = h('input', { type: 'email', class: 'input', placeholder: 'F1 TV e-mail', value: creds.login || '', 'data-focus': '' });
    var pass = h('input', { type: 'password', class: 'input', placeholder: 'Password', 'data-focus': '' });
    var token = h('textarea', { class: 'input token', placeholder: 'or paste a subscription token / login-session cookie', 'data-focus': '', rows: '2' });
    var err = h('p', { class: 'form-error', text: params.error || '' });
    var busy = false;

    async function doLogin() {
      if (busy) return;
      err.textContent = '';
      var pasted = F1Api.normalizeToken(token.value);
      if (pasted) {
        if (!F1Api.tokenValid(pasted)) { err.textContent = 'That token is invalid or already expired.'; return; }
        Store.setToken(pasted);
        Store.set('entitlementToken', null);
        reset('home');
        return;
      }
      if (!user.value || !pass.value) { err.textContent = 'Enter your e-mail and password, or paste a token.'; return; }
      busy = true;
      err.textContent = 'Signing in…';
      try {
        await F1Api.login(user.value.trim(), pass.value);
        Store.setCredentials(Store.get('rememberPassword') ? { login: user.value.trim(), password: pass.value } : { login: user.value.trim() });
        reset('home');
      } catch (e) {
        err.textContent = (e.status === 401 || e.status === 400 ? 'Wrong e-mail or password. ' :
          e.status === 403 ? 'F1 blocked the sign-in request (bot protection). Paste a token instead, or set an API proxy in Settings. ' : '') + e.message;
      } finally {
        busy = false;
      }
    }

    var form = h('div', { class: 'login' }, [
      h('div', { class: 'login-logo' }, [h('span', { class: 'brand-f1', text: 'F1' }), ' TV']),
      h('h1', { text: 'Sign in' }),
      h('p', { class: 'muted', text: 'Use your F1 TV account. Tip: the SmartThings app’s remote keyboard lets you paste from your phone.' }),
      user, pass,
      h('div', { class: 'or', text: 'or' }),
      token,
      err,
      h('div', { class: 'actions', 'data-row': '' }, [
        UI.button('Sign in', doLogin, 'btn-primary'),
        UI.button('Settings', function () { go('settings'); })
      ])
    ]);
    setScreen(form, 'Sign in', { initial: creds.login ? pass : user });
  };

  SCREENS.home = async function (params, alive) {
    var year = new Date().getFullYear();
    var events = await cached('season' + year, function () { return F1Api.getSeason(year); });
    if (!events.length && new Date().getMonth() < 3) {
      year -= 1;
      events = await cached('season' + year, function () { return F1Api.getSeason(year); });
    }
    if (!alive()) return;

    var now = Date.now();
    var past = events.filter(function (e) { return e.start && e.start <= now; }).sort(function (a, b) { return b.start - a.start; });
    var future = events.filter(function (e) { return e.start > now; }).sort(function (a, b) { return a.start - b.start; });
    var current = past[0] && past[0].end >= now - 24 * 3600 * 1000 ? past[0] : null;

    var rows = [];

    if (current) {
      var weekend = [];
      try { weekend = seriesFilter(await cached('ev' + current.id, function () { return F1Api.getSessions(current); }, 60000)); } catch (e) { /* ignore */ }
      if (!alive()) return;
      weekend.sort(function (a, b) { return (UI.isLive(b) - UI.isLive(a)) || ((b.start || 0) - (a.start || 0)); });
      if (weekend.length) rows.push(UI.row(current.title + ' — this weekend', weekend.map(function (s) { return sessionCard(s); })));
    }

    var eventCards = past.map(eventCard).concat(future.slice(0, 3).map(eventCard));
    if (eventCards.length) rows.push(UI.row(year + ' season', eventCards));

    var explore = F1Config.editorialPages.map(function (p) {
      return keyed(UI.card({ title: p.name, mediaText: p.name, size: 'card-sm', onSelect: function () { go('editorial', p); } }), 'p' + p.id);
    });
    explore.push(keyed(UI.card({ title: 'Archive', mediaText: 'Archive', size: 'card-sm', onSelect: function () { go('archive'); } }), 'archive'));
    explore.push(keyed(UI.card({ title: 'Settings', mediaText: '⚙', size: 'card-sm', onSelect: function () { go('settings'); } }), 'settings'));
    rows.push(UI.row('Explore', explore));

    setScreen(h('div', { class: 'rows' }, rows), 'Home');
  };

  function eventCard(e) {
    var upcoming = e.start > Date.now();
    return keyed(UI.card({
      title: e.title,
      subtitle: UI.fmtRange(e.start, e.end) + (e.country ? ' · ' + e.country : ''),
      picture: e.picture,
      mediaText: e.title.replace(/FORMULA 1 |GRAND PRIX/gi, '').trim(),
      badges: UI.isLive(e) ? [{ text: 'LIVE', cls: 'badge-live' }] : upcoming ? [{ text: 'Upcoming', cls: 'badge-soon' }] : [],
      disabled: upcoming,
      onSelect: function () {
        if (upcoming) { UI.toast('This weekend starts ' + UI.fmtDate(e.start)); return; }
        go('event', e);
      }
    }), 'e' + e.id);
  }

  SCREENS.archive = async function () {
    var years = [];
    for (var y = new Date().getFullYear(); y >= F1Config.firstArchiveYear; y--) years.push(y);
    var cards = years.map(function (y) {
      return keyed(UI.card({ title: String(y), mediaText: String(y), size: 'card-sm', onSelect: function () { go('season', { year: y }); } }), 'y' + y);
    });
    setScreen(h('div', { class: 'rows' }, [UI.row('Seasons', cards, { wrap: true })]), 'Archive');
  };

  SCREENS.season = async function (params, alive) {
    var events = await cached('season' + params.year, function () { return F1Api.getSeason(params.year); });
    if (!alive()) return;
    events = events.slice().sort(function (a, b) { return a.start - b.start; });
    var content = events.length
      ? UI.row(params.year + ' — ' + events.length + ' events', events.map(eventCard), { wrap: true })
      : UI.message('No events', 'F1 TV returned no events for ' + params.year + '.');
    setScreen(h('div', { class: 'rows' }, [content]), String(params.year));
  };

  SCREENS.event = async function (event, alive) {
    var sessions = await cached('ev' + event.id, function () { return F1Api.getSessions(event); }, UI.isLive(event) ? 60000 : CACHE_MS);
    if (!alive()) return;
    var filtered = seriesFilter(sessions);
    var content;
    if (!filtered.length) {
      content = UI.message('No sessions', sessions.length
        ? 'Nothing matches the ' + Store.get('series') + ' series filter (Settings).'
        : 'No replays are available for this event yet.', [UI.button('Back', back)]);
    } else {
      var groups = {};
      var order = [];
      filtered.forEach(function (s) {
        var g = s.series === 'F1' ? 'Formula 1' : { F2: 'Formula 2', F3: 'Formula 3', F1A: 'F1 Academy', PSC: 'Porsche Supercup' }[s.series] || s.series;
        if (!groups[g]) { groups[g] = []; order.push(g); }
        groups[g].push(s);
      });
      content = h('div', { class: 'rows' }, order.map(function (g) {
        return UI.row(g, groups[g].map(function (s) { return sessionCard(s); }), { wrap: true });
      }));
    }
    var header = h('div', { class: 'hero' }, [
      h('h1', { text: event.title }),
      h('p', { class: 'muted', text: [UI.fmtRange(event.start, event.end), event.circuit, event.country].filter(Boolean).join(' · ') })
    ]);
    setScreen(h('div', null, [header, content]), event.title);
  };

  SCREENS.editorial = async function (page, alive) {
    var videos = await cached('page' + page.id, function () { return F1Api.getEditorialPage(page.id); });
    if (!alive()) return;
    var content = videos.length
      ? UI.row(page.name, videos.map(function (s) { return sessionCard(s); }), { wrap: true })
      : UI.message('Nothing here', 'F1 TV returned no videos for ' + page.name + '.');
    setScreen(h('div', { class: 'rows' }, [content]), page.name);
  };

  SCREENS.channels = async function (params) {
    var s = params.session;
    var channels = params.channels;
    function chCard(ch) {
      var i = channels.indexOf(ch);
      return keyed(UI.card({
        title: ch.type === 'OBC' ? ch.name : ch.label,
        subtitle: ch.type === 'OBC' ? ch.team : '',
        mediaText: ch.type === 'OBC' ? String(ch.number || '') : ch.label,
        color: ch.color ? '#' + ch.color.replace('#', '') : null,
        size: 'card-sm',
        onSelect: function () { play(s, channels, i); }
      }), 'c' + i);
    }
    var feeds = channels.filter(function (c) { return c.type !== 'OBC'; });
    var onboards = channels.filter(function (c) { return c.type === 'OBC'; });
    var rows = [UI.row('Feeds', feeds.map(chCard))];
    if (onboards.length) rows.push(UI.row('Onboard cameras', onboards.map(chCard), { wrap: true }));
    var header = h('div', { class: 'hero' }, [
      h('h1', { text: s.title }),
      h('p', { class: 'muted', text: 'Pick a feed — in the player use ▲▼ or CH+/CH− to switch, Green for the full list.' })
    ]);
    setScreen(h('div', null, [header, h('div', { class: 'rows' }, rows)]), s.title);
  };

  SCREENS.settings = async function () {
    var token = Store.getToken();
    var exp = token ? F1Api.tokenExpiry(token) : 0;
    var creds = Store.getCredentials();

    function option(label, valueText, onSelect) {
      return Nav.onSelect(h('div', { class: 'setting' }, [
        h('span', { class: 'setting-label', text: label }),
        h('span', { class: 'setting-value', text: valueText })
      ]), onSelect);
    }

    function choose(title, key, values) {
      return function () {
        UI.pick(title, values.map(function (v) { return { label: v.label, value: v.value, selected: Store.get(key) === v.value }; }))
          .then(function (c) {
            if (!c) return;
            Store.set(key, c.value);
            if (key === 'rememberPassword' && !c.value && creds) Store.setCredentials({ login: creds.login });
            cache = {};
            render();
          });
      };
    }

    function textSetting(label, key, placeholder) {
      var input = h('input', { type: 'url', class: 'input', placeholder: placeholder, value: Store.get(key) || '', 'data-focus': '' });
      input.addEventListener('change', function () { Store.set(key, input.value.trim() || null); cache = {}; UI.toast(label + ' saved'); });
      return h('div', { class: 'setting setting-input' }, [h('span', { class: 'setting-label', text: label }), input]);
    }

    var list = h('div', { class: 'settings' }, [
      h('h2', { text: 'Playback' }),
      option('Stream format', Store.get('streamType'), choose('Stream format', 'streamType', [
        { label: 'DASH (recommended on Samsung)', value: 'DASH' }, { label: 'HLS', value: 'HLS' }])),
      option('Try 4K / HDR streams', Store.get('preferUhd') ? 'On' : 'Off', choose('4K / HDR', 'preferUhd', [
        { label: 'Off', value: false }, { label: 'On (needs Premium + UHD TV)', value: true }])),
      option('Show feed picker before playing', Store.get('showChannelGrid') ? 'On' : 'Off', choose('Feed picker', 'showChannelGrid', [
        { label: 'On', value: true }, { label: 'Off — start main feed', value: false }])),
      textSetting('Custom radio URL', 'customRadioUrl', 'https://… (mp3 / aac stream)'),

      h('h2', { text: 'Browse' }),
      option('Series filter', Store.get('series'), choose('Series', 'series', [
        { label: 'All series', value: 'ALL' }, { label: 'Formula 1', value: 'F1' }, { label: 'Formula 2', value: 'F2' },
        { label: 'Formula 3', value: 'F3' }, { label: 'F1 Academy', value: 'F1A' }, { label: 'Porsche Supercup', value: 'PSC' }])),

      h('h2', { text: 'Network' }),
      textSetting('API proxy (optional)', 'proxyUrl', 'http://your-server:8787 — see tizen/README.md'),

      h('h2', { text: 'Account' }),
      option('Signed in as', creds && creds.login ? creds.login : (token ? 'token' : 'not signed in'), function () {}),
      option('Token expires', exp ? UI.fmtDateTime(exp) : '—', function () {}),
      option('Remember password for auto re-login', Store.get('rememberPassword') ? 'On' : 'Off', choose('Remember password', 'rememberPassword', [
        { label: 'On', value: true }, { label: 'Off', value: false }])),
      option('Sign out', '', function () { Store.signOut(); cache = {}; reset('login'); }),

      h('h2', { text: 'About' }),
      option('Version', 'UgisF1 for Tizen ' + F1Config.appVersion, function () {}),
      option('Clear cache', '', function () { cache = {}; UI.toast('Cache cleared'); })
    ]);
    setScreen(list, 'Settings');
  };

  /* ---------- boot ---------- */

  function start() {
    screenEl = document.getElementById('screen');
    Nav.init();
    Player.init();
    UI.startClock();
    document.addEventListener('visibilitychange', function () {
      if (document.hidden && Player.isOpen()) document.getElementById('video').pause();
    });
    var token = Store.getToken();
    if (F1Api.tokenValid(token) || (Store.getCredentials() && Store.getCredentials().password)) reset('home');
    else reset('login');
  }

  global.App = { go: go, back: back, reset: reset };

  if (/[?&]mock=1/.test(location.search)) {
    var s = document.createElement('script');
    s.src = 'js/mock.js';
    s.onload = start;
    document.head.appendChild(s);
  } else {
    start();
  }
})(window);
