/* Full-screen player: Shaka Player (DASH/HLS + Widevine) with feed switching and pickers. */
(function (global) {
  'use strict';

  var root, video, osd, panel, spinner;
  var player = null;
  var layer = null;
  var state = null;          // { session, channels, index, viewing, onClose }
  var osdTimer = null;
  var progressTimer = null;
  var seekStep = { at: 0, count: 0 };
  var radio = null;          // HTMLAudioElement for custom radio
  var loadToken = 0;

  function $(id) { return document.getElementById(id); }

  function setScreenSaver(on) {
    try {
      webapis.appcommon.setScreenSaver(on
        ? webapis.appcommon.AppCommonScreenSaverState.SCREEN_SAVER_ON
        : webapis.appcommon.AppCommonScreenSaverState.SCREEN_SAVER_OFF);
    } catch (e) { /* not on a TV */ }
  }

  async function ensurePlayer() {
    if (player) return player;
    shaka.polyfill.installAll();
    if (!shaka.Player.isBrowserSupported()) throw new Error('This TV does not support MSE/EME playback.');
    player = new shaka.Player();
    await player.attach(video);
    player.configure({
      preferredAudioLanguage: (navigator.language || 'en').slice(0, 2),
      streaming: {
        bufferingGoal: 30,
        rebufferingGoal: 2,
        retryParameters: { maxAttempts: 4, baseDelay: 500, backoffFactor: 2, timeout: 20000 }
      },
      manifest: { retryParameters: { maxAttempts: 3, timeout: 20000 } },
      drm: { retryParameters: { maxAttempts: 3, timeout: 15000 } }
    });
    player.getNetworkingEngine().registerRequestFilter(function (type, req) {
      if (type === shaka.net.NetworkingEngine.RequestType.LICENSE && state && state.viewing) {
        var hdrs = F1Api.licenseHeaders(state.viewing);
        Object.keys(hdrs).forEach(function (k) { req.headers[k] = hdrs[k]; });
      }
    });
    player.addEventListener('error', function (ev) { onError(ev.detail); });
    player.addEventListener('buffering', function (ev) { spinner.classList.toggle('hidden', !ev.buffering); });
    return player;
  }

  function currentChannel() { return state.channels[state.index]; }

  function describeError(err) {
    if (!err) return 'Unknown error';
    if (err.code && global.shaka) {
      var name = '';
      Object.keys(shaka.util.Error.Code).some(function (k) {
        if (shaka.util.Error.Code[k] === err.code) { name = k; return true; }
        return false;
      });
      var extra = err.data && err.data[0] && err.data[0].status ? ' (HTTP ' + err.data[0].status + ')' : '';
      return 'Playback error ' + err.code + (name ? ' ' + name : '') + extra;
    }
    return err.message || String(err);
  }

  function onError(err) {
    console.error('Player error', err);
    spinner.classList.add('hidden');
    showPanel('Cannot play this feed', describeError(err), [
      { label: 'Retry', action: function () { load(state.index, true); } },
      { label: 'Try ' + (Store.get('streamType') === 'HLS' ? 'DASH' : 'HLS') + ' instead', action: function () {
        Store.set('streamType', Store.get('streamType') === 'HLS' ? 'DASH' : 'HLS');
        load(state.index, true);
      } },
      { label: 'Close player', action: close }
    ]);
  }

  async function load(index, keepPosition) {
    var my = ++loadToken;
    hidePanel();
    var prevTime = video.currentTime;
    var wasLive = player && player.isLive();
    state.index = (index + state.channels.length) % state.channels.length;
    var ch = currentChannel();
    $('osd-title').textContent = state.session.title;
    $('osd-sub').textContent = ch.label + (ch.team ? ' · ' + ch.team : '');
    showOsd();
    spinner.classList.remove('hidden');
    try {
      await ensurePlayer();
      var viewing = await F1Api.getViewing(ch.contentId, ch.channelId);
      if (my !== loadToken) return;
      state.viewing = viewing;
      var servers = {};
      if (viewing.drmType === 'widevine' || viewing.laURL) servers['com.widevine.alpha'] = F1Api.licenseUrl(viewing);
      player.configure({ drm: { servers: servers } });
      console.info('Loading', viewing.platform, viewing.streamType, viewing.url);
      var start = keepPosition && !wasLive && prevTime > 1 ? prevTime : undefined;
      await player.load(viewing.url, start);
      if (my !== loadToken) return;
      spinner.classList.add('hidden');
      video.play();
      $('osd-live').classList.toggle('hidden', !player.isLive());
      if (radio) video.muted = true;
    } catch (e) {
      if (my !== loadToken) return;
      if (e && e.code === 7000) return; // LOAD_INTERRUPTED by a newer load
      onError(e);
    }
  }

  /* ---------- OSD ---------- */

  function showOsd(sticky) {
    osd.classList.remove('hidden');
    clearTimeout(osdTimer);
    if (!sticky) osdTimer = setTimeout(function () { if (!video.paused) osd.classList.add('hidden'); }, 4000);
    updateProgress();
  }

  function updateProgress() {
    if (!player || !state) return;
    var live = player.isLive();
    var range = player.seekRange();
    var pos = video.currentTime;
    var span = Math.max(1, range.end - range.start);
    var pct = live ? ((pos - range.start) / span) * 100 : (video.duration ? (pos / video.duration) * 100 : 0);
    $('osd-bar').style.width = Math.max(0, Math.min(100, pct)) + '%';
    if (live) {
      var behind = range.end - pos;
      $('osd-pos').textContent = behind > 15 ? '-' + UI.fmtTime(behind) : 'At live edge';
      $('osd-dur').textContent = '';
    } else {
      $('osd-pos').textContent = UI.fmtTime(pos);
      $('osd-dur').textContent = UI.fmtTime(video.duration);
    }
  }

  function seek(delta) {
    if (!player) return;
    var now = Date.now();
    seekStep.count = now - seekStep.at < 700 ? seekStep.count + 1 : 0;
    seekStep.at = now;
    var mult = seekStep.count > 8 ? 6 : seekStep.count > 3 ? 3 : 1;
    var range = player.seekRange();
    var target = Math.max(range.start, Math.min(range.end - 1, video.currentTime + delta * mult));
    video.currentTime = target;
    showOsd();
  }

  function togglePause() {
    if (video.paused) video.play(); else video.pause();
    showOsd(video.paused);
  }

  /* ---------- panels / pickers ---------- */

  function hidePanel() {
    panel.classList.add('hidden');
    panel.innerHTML = '';
    if (panel._layer) { Nav.pop(panel._layer); panel._layer = null; }
  }

  function showPanel(title, text, actions) {
    hidePanel();
    var box = UI.h('div', { class: 'player-msg' }, [
      UI.h('h2', { text: title }),
      UI.h('p', { text: text }),
      UI.h('div', { class: 'actions', 'data-row': '' }, actions.map(function (a) { return UI.button(a.label, a.action); }))
    ]);
    panel.appendChild(box);
    panel.classList.remove('hidden');
    panel._layer = Nav.push({ root: box, onBack: function () { hidePanel(); close(); } });
  }

  async function pickChannel() {
    var items = state.channels.map(function (ch, i) {
      return { label: ch.label, sub: ch.type === 'OBC' ? ch.team : '', color: ch.color ? '#' + ch.color.replace('#', '') : null, selected: i === state.index, index: i };
    });
    showOsd(true);
    var chosen = await UI.pick('Feeds', items, root);
    showOsd();
    if (chosen && chosen.index !== state.index) load(chosen.index, true);
  }

  async function pickAudio() {
    if (!player) return;
    var langs = player.getAudioLanguagesAndRoles();
    var active = player.getVariantTracks().filter(function (t) { return t.active; })[0];
    var items = langs.map(function (l) {
      return { label: (l.label || l.language || 'Audio') + (l.role ? ' (' + l.role + ')' : ''), sub: l.language,
        selected: !radio && active && active.language === l.language && (!l.role || active.roles.indexOf(l.role) >= 0), lang: l };
    });
    F1Config.radioStreams.concat(Store.get('customRadioUrl') ? [{ name: 'Custom radio', url: Store.get('customRadioUrl') }] : [])
      .forEach(function (r) { items.push({ label: r.name, sub: 'External radio — video muted', radio: r, selected: radio && radio.src === r.url }); });
    var chosen = await UI.pick('Audio', items, root);
    if (!chosen) return;
    if (chosen.radio) {
      startRadio(chosen.radio.url);
    } else {
      stopRadio();
      player.selectAudioLanguage(chosen.lang.language, chosen.lang.role || undefined);
    }
  }

  async function pickQuality() {
    if (!player) return;
    var tracks = player.getVariantTracks();
    var abr = player.getConfiguration().abr.enabled;
    var seen = {};
    var items = [{ label: 'Auto', sub: 'Adaptive bitrate', selected: abr, auto: true }];
    tracks.filter(function (t) { return t.height; })
      .sort(function (a, b) { return b.height - a.height || b.bandwidth - a.bandwidth; })
      .forEach(function (t) {
        if (seen[t.height]) return;
        seen[t.height] = true;
        items.push({ label: t.height + 'p', sub: Math.round(t.bandwidth / 1e5) / 10 + ' Mbps' + (t.hdr ? ' · ' + t.hdr : ''),
          selected: !abr && t.active, track: t });
      });
    var chosen = await UI.pick('Quality', items, root);
    if (!chosen) return;
    if (chosen.auto) {
      player.configure({ abr: { enabled: true } });
    } else {
      player.configure({ abr: { enabled: false } });
      var activeAudio = tracks.filter(function (t) { return t.active; })[0];
      var match = tracks.filter(function (t) {
        return t.height === chosen.track.height && (!activeAudio || t.language === activeAudio.language);
      }).sort(function (a, b) { return b.bandwidth - a.bandwidth; })[0] || chosen.track;
      player.selectVariantTrack(match, true);
    }
  }

  function startRadio(url) {
    stopRadio();
    radio = new Audio(url);
    radio.play().catch(function (e) { UI.toast('Radio failed: ' + e.message); stopRadio(); });
    video.muted = true;
    UI.toast('Custom radio on — Yellow to switch back');
  }

  function stopRadio() {
    if (radio) { radio.pause(); radio.src = ''; radio = null; }
    video.muted = false;
  }

  /* ---------- lifecycle ---------- */

  function onKey(key) {
    switch (key) {
      case 'ENTER': case 'PLAYPAUSE': togglePause(); return true;
      case 'PLAY': video.play(); showOsd(); return true;
      case 'PAUSE': video.pause(); showOsd(true); return true;
      case 'LEFT': seek(-10); return true;
      case 'RIGHT': seek(10); return true;
      case 'RW': seek(-30); return true;
      case 'FF': seek(30); return true;
      case 'UP': case 'CHUP': if (state.channels.length > 1) load(state.index - 1, true); else showOsd(); return true;
      case 'DOWN': case 'CHDOWN': if (state.channels.length > 1) load(state.index + 1, true); else showOsd(); return true;
      case 'GREEN': pickChannel(); return true;
      case 'YELLOW': pickAudio(); return true;
      case 'BLUE': pickQuality(); return true;
      case 'RED':
        if (player && player.isLive()) { player.goToLive ? player.goToLive() : (video.currentTime = player.seekRange().end); UI.toast('Live'); }
        else { video.currentTime = 0; }
        showOsd();
        return true;
      case 'STOP': close(); return true;
    }
    return false;
  }

  function onBack() {
    if (!osd.classList.contains('hidden') && !video.paused) { osd.classList.add('hidden'); return; }
    close();
  }

  function close() {
    if (!state) return;
    loadToken++;
    clearInterval(progressTimer);
    clearTimeout(osdTimer);
    hidePanel();
    stopRadio();
    setScreenSaver(true);
    // Hide immediately; tearing down MSE/EME can take a moment on TVs.
    if (player) player.unload().catch(function () { /* ignore */ });
    osd.classList.add('hidden');
    spinner.classList.add('hidden');
    root.classList.add('hidden');
    document.body.classList.remove('playing');
    var s = state;
    state = null;
    if (layer) { Nav.pop(layer); layer = null; }
    if (s && s.onClose) s.onClose();
  }

  global.Player = {
    init: function () {
      root = $('player'); video = $('video'); osd = $('player-osd'); panel = $('player-panel'); spinner = $('player-spinner');
      video.addEventListener('pause', function () { if (state) showOsd(true); });
    },

    /** Open a session with its feeds. index = feed to start on. */
    open: function (session, channels, index, onClose) {
      state = { session: session, channels: channels, index: index || 0, onClose: onClose };
      root.classList.remove('hidden');
      document.body.classList.add('playing');
      setScreenSaver(false);
      layer = Nav.push({ root: root, onKey: onKey, onBack: onBack });
      clearInterval(progressTimer);
      progressTimer = setInterval(function () { if (!osd.classList.contains('hidden')) updateProgress(); }, 500);
      load(state.index, false);
    },

    isOpen: function () { return !!state; },
    close: close
  };
})(window);
