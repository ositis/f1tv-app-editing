/* F1 TV API client — a port of the Android F1Client / F1TvClient. */
(function (global) {
  'use strict';

  var C = global.F1Config;

  function ApiError(message, status, body) {
    var e = new Error(message);
    e.status = status;
    e.body = body;
    return e;
  }

  function proxied(url) {
    var proxy = (Store.get('proxyUrl') || '').trim();
    if (!proxy) return url;
    return proxy.replace(/\/+$/, '') + '/' + url;
  }

  function locale() {
    var lang = (navigator.language || 'en').slice(0, 2).toLowerCase();
    var map = { de: 'DEU', fr: 'FRA', nl: 'NLD', es: 'SPA', pt: 'POR' };
    return map[lang] || 'ENG';
  }

  async function request(url, options) {
    options = options || {};
    var res;
    try {
      res = await fetch(proxied(url), {
        method: options.method || 'GET',
        headers: options.headers || {},
        body: options.body,
        credentials: 'omit'
      });
    } catch (e) {
      throw ApiError('Network error: ' + e.message + ' (' + url.split('?')[0] + ')', 0);
    }
    var text = await res.text();
    var json = null;
    try { json = text ? JSON.parse(text) : null; } catch (e) { /* not JSON */ }
    if (!res.ok) {
      var msg = (json && (json.Message || json.message || json.errorDescription)) || text.slice(0, 160) || res.statusText;
      throw ApiError('HTTP ' + res.status + ': ' + msg, res.status, json || text);
    }
    if (json === null) throw ApiError('Unexpected non-JSON response from ' + url.split('?')[0], res.status, text);
    return json;
  }

  function get(path) { return request(C.rootUrl + path); }

  function playHeaders(token, entitlementToken) {
    var h = {
      'x-f1-device-info': C.f1DeviceInfo,
      'ascendonToken': token,
      'CD-DeviceType': C.cdDeviceType,
      'CD-DistributionChannel': C.cdDistributionChannel
    };
    if (entitlementToken) h.entitlementToken = entitlementToken;
    return h;
  }

  /* ---------- auth ---------- */

  function decodeJwt(token) {
    try {
      var part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      while (part.length % 4) part += '=';
      return JSON.parse(decodeURIComponent(escape(atob(part))));
    } catch (e) {
      return null;
    }
  }

  /**
   * Accepts a raw subscription token, a "login-session" cookie value (URL-encoded JSON),
   * or the JSON itself — whatever the user managed to copy from a desktop browser.
   */
  function normalizeToken(input) {
    var s = (input || '').trim();
    if (!s) return '';
    if (s.indexOf('login-session=') >= 0) s = s.split('login-session=')[1].split(';')[0];
    if (s.indexOf('%7B') === 0 || s.indexOf('%7b') === 0) {
      try { s = decodeURIComponent(s); } catch (e) { /* keep */ }
    }
    if (s.charAt(0) === '{') {
      try {
        var obj = JSON.parse(s);
        s = (obj.data && obj.data.subscriptionToken) || obj.subscriptionToken || '';
      } catch (e) { return ''; }
    }
    return s.replace(/^"|"$/g, '');
  }

  function tokenExpiry(token) {
    var p = decodeJwt(token);
    return p && p.exp ? p.exp * 1000 : 0;
  }

  function tokenValid(token) {
    if (!token) return false;
    var exp = tokenExpiry(token);
    return exp === 0 ? true : exp > Date.now() + 60 * 1000;
  }

  async function login(username, password) {
    var json = await request(C.authUrl, {
      method: 'POST',
      headers: { 'apiKey': C.apiKey, 'Content-Type': 'application/json' },
      body: JSON.stringify({ Login: username, Password: password, DistributionChannel: C.distributionChannel })
    });
    var token = json && json.data && json.data.subscriptionToken;
    if (!token) throw ApiError('Login succeeded but no subscription token was returned', 200, json);
    Store.setToken(token);
    Store.set('entitlementToken', null);
    return token;
  }

  /** Returns a valid token, silently re-logging in with saved credentials when it expired. */
  async function ensureToken() {
    var token = Store.getToken();
    if (tokenValid(token)) return token;
    var creds = Store.getCredentials();
    if (creds && creds.password) return login(creds.login, creds.password);
    throw ApiError('Not signed in or token expired', 401);
  }

  /* ---------- browse ---------- */

  var PICTURE = '/image-resizer/image/%s?w=640&h=360&o=L&q=HI';
  var LARGE_PICTURE = '/image-resizer/image/%s?w=1920&h=1080&o=L&q=HI';

  function picture(id, large) {
    if (!id) return '';
    return C.rootUrl + (large ? LARGE_PICTURE : PICTURE).replace('%s', id);
  }

  function parseDate(s) {
    var t = s ? Date.parse(s) : NaN;
    return isNaN(t) ? 0 : t;
  }

  function base(version) {
    return '/' + (version || '2.0') + '/R/' + locale() + '/WEB_HLS/ALL';
  }

  var Series = {
    classify: function (uiSeries, series, title) {
      var u = (uiSeries || '').trim().toUpperCase();
      var ui = { 'F1': 'F1', 'FORMULA1': 'F1', 'FORMULA 1': 'F1', 'F2': 'F2', 'FORMULA2': 'F2', 'FORMULA 2': 'F2',
        'F3': 'F3', 'FORMULA3': 'F3', 'FORMULA 3': 'F3', 'F1A': 'F1A', 'F1 ACADEMY': 'F1A', 'ACADEMY': 'F1A',
        'PSC': 'PSC', 'PORSCHE': 'PSC', 'PORSCHE SUPER CUP': 'PSC', 'SUPER CUP': 'PSC' }[u];
      if (ui) return ui;
      var s = (series || '').trim().toUpperCase();
      if (s) {
        if (s.indexOf('ACADEMY') >= 0) return 'F1A';
        if (s.indexOf('PORSCHE') >= 0 || s.indexOf('SUPER CUP') >= 0 || s === 'PSC') return 'PSC';
        if (/FORMULA ?3/.test(s) || s === 'F3') return 'F3';
        if (/FORMULA ?2/.test(s) || s === 'F2') return 'F2';
        if (/FORMULA ?1/.test(s) || s === 'F1') return 'F1';
      }
      var t = (title || '').toUpperCase();
      if (t.indexOf('F1 ACADEMY') >= 0 || (t.indexOf('ACADEMY') >= 0 && t.indexOf('F1') >= 0)) return 'F1A';
      if (t.indexOf('PORSCHE') >= 0 || t.indexOf('SUPER CUP') >= 0 || t.indexOf('SUPERCUP') >= 0) return 'PSC';
      if (/\bF3\b/.test(t) || t.indexOf('FORMULA 3') >= 0) return 'F3';
      if (/\bF2\b/.test(t) || t.indexOf('FORMULA 2') >= 0) return 'F2';
      return 'F1';
    },
    matches: function (filter, series) { return !filter || filter === 'ALL' || filter === series; }
  };

  async function getSeason(year) {
    var json = await get(base() + '/PAGE/SEARCH/VOD/' + C.product + '/' + C.groupId +
      '?filter_objectSubtype=Meeting&filter_season=' + year + '&filter_orderByFom=Y&maxResults=100');
    var containers = (json.resultObj && json.resultObj.containers) || [];
    return containers.map(function (c) {
      var m = c.metadata || {};
      var emf = m.emfAttributes || {};
      return {
        id: c.id,
        meetingKey: emf.MeetingKey,
        title: emf.Global_Meeting_Name || m.title || 'Event',
        country: emf.Meeting_Country_Name || '',
        circuit: emf.Circuit_Short_Name || '',
        start: parseDate(emf.Meeting_Start_Date),
        end: parseDate(emf.Meeting_End_Date),
        picture: picture(m.pictureUrl)
      };
    });
  }

  function toSession(c, eventId) {
    var m = c.metadata || {};
    var emf = m.emfAttributes || {};
    var hasSessionTimes = !!(emf.sessionStartDate && emf.sessionEndDate);
    var start = hasSessionTimes ? emf.sessionStartDate : parseDate(emf.Meeting_Start_Date);
    var end = hasSessionTimes ? emf.sessionEndDate : parseDate(emf.Meeting_End_Date);
    var now = Date.now();
    return {
      // Meeting dates span the whole weekend, so only trust session times or the LIVE subtype.
      live: (m.contentSubtype || '').toUpperCase() === 'LIVE' || (hasSessionTimes && start <= now && end >= now),
      id: c.id || m.contentId,
      eventId: eventId,
      contentId: String(m.contentId || ''),
      title: m.title || m.label || 'Session',
      subtype: m.contentSubtype || '',
      series: Series.classify(m.uiSeries, emf.Series, m.title || ''),
      start: start,
      end: end,
      picture: picture(m.pictureUrl),
      largePicture: picture(m.pictureUrl, true)
    };
  }

  async function getBroadcastSessions(event) {
    var json = await get(base() + '/PAGE/SANDWICH/' + C.product + '/' + C.groupId +
      '?meetingId=' + encodeURIComponent(event.meetingKey) + '&title=weekend-sessions');
    var containers = (json.resultObj && json.resultObj.containers) || [];
    return containers.map(function (c) { return toSession(c, event.id); })
      .filter(function (s) { return s.contentId; });
  }

  /** Scheduled (not yet started) sessions of the running weekend — page 1350 "schedule" layout. */
  async function getUpcomingSessions(event) {
    var json = await get(base() + '/PAGE/1350/' + C.product + '/' + C.groupId);
    var out = [];
    ((json.resultObj && json.resultObj.containers) || []).forEach(function (layout) {
      if (layout.layout !== 'schedule') return;
      var items = (layout.retrieveItems && layout.retrieveItems.resultObj && layout.retrieveItems.resultObj.containers) || [];
      items.forEach(function (group) {
        if (group.eventName !== 'ALL') return;
        (group.events || []).forEach(function (ev) {
          var s = toSession(ev, event ? event.id : 'live');
          if (s.start > Date.now()) {
            s.upcoming = true;
            out.push(s);
          }
        });
      });
    });
    return out;
  }

  async function getSessions(event) {
    var now = Date.now();
    var list = [];
    if (event.start && event.start > now) return list;
    if (event.end && event.end > now) {
      try { list = list.concat(await getUpcomingSessions(event)); } catch (e) { /* schedule is optional */ }
    }
    return list.concat(await getBroadcastSessions(event));
  }

  function channelType(stream) {
    var key = function (v) { return (v || '').trim().toUpperCase().replace(/\s+/g, ' '); };
    var id = key(stream.identifier);
    var name = key(stream.title);
    if (id === 'WIF') return 'WIF';
    if (id === 'PRES' || id === 'F1LIVE' || id === 'F1 LIVE' || name.indexOf('F1 LIVE') >= 0 || name === 'F1LIVE' || name === 'PRES') return 'F1LIVE';
    if (id === 'TRACKER' || name === 'TRACKER') return 'TRACKER';
    if (id === 'DATA' || name === 'DATA') return 'DATA';
    if (id === 'PIT LANE' || id === 'PITLANE' || id === 'PLP' || name === 'PIT LANE' || name === 'PITLANE') return 'PITLANE';
    if ((stream.type || '').toLowerCase() === 'wif') return 'WIF';
    if ((stream.type || '').toLowerCase() === 'obc') return 'OBC';
    return 'OTHER';
  }

  var CHANNEL_LABEL = { WIF: 'Main feed', F1LIVE: 'F1 Live', TRACKER: 'Driver tracker', DATA: 'Data channel', PITLANE: 'Pit lane' };
  var CHANNEL_ORDER = { WIF: 0, F1LIVE: 1, PITLANE: 2, TRACKER: 3, DATA: 4, OTHER: 5, OBC: 6 };

  async function getChannels(contentId) {
    var json = await get(base('3.0') + '/CONTENT/VIDEO/' + contentId + '/' + C.product + '/' + C.groupId);
    var container = json.resultObj && json.resultObj.containers && json.resultObj.containers[0];
    var streams = (container && container.metadata && container.metadata.additionalStreams) || [];
    var channels = streams.map(function (s) {
      var q = {};
      (s.playbackUrl || '').split('?')[1] && s.playbackUrl.split('?')[1].split('&').forEach(function (kv) {
        var p = kv.split('=');
        q[p[0]] = decodeURIComponent(p[1] || '');
      });
      var type = channelType(s);
      var ch = {
        type: type,
        contentId: q.contentId || contentId,
        channelId: q.channelId || (s.channelId ? String(s.channelId) : null),
        isDefault: !!s.default
      };
      if (type === 'OBC') {
        ch.name = [s.driverFirstName, s.driverLastName].filter(Boolean).join(' ');
        ch.number = s.racingNumber;
        ch.team = s.teamName || '';
        ch.color = s.hex || '';
        ch.label = (s.racingNumber ? s.racingNumber + ' ' : '') + (s.driverLastName || s.title);
      } else {
        ch.name = CHANNEL_LABEL[type] || s.title || 'Feed';
        ch.label = ch.name;
      }
      return ch;
    });
    channels.sort(function (a, b) {
      return (CHANNEL_ORDER[a.type] - CHANNEL_ORDER[b.type]) || ((a.number || 0) - (b.number || 0));
    });
    if (!channels.length) channels.push({ type: 'WIF', contentId: contentId, channelId: null, name: 'Main feed', label: 'Main feed', isDefault: true });
    return channels;
  }

  function flattenPage(containers, out) {
    (containers || []).forEach(function (c) {
      var m = c.metadata;
      if (m && m.contentId) {
        var type = (m.contentType || '').toUpperCase();
        var title = (m.title || m.label || '').trim();
        if (title && (!type || type === 'VIDEO' || type === 'BUNDLE' || type === 'EPISODE')) {
          out.push(toSession(c, 'editorial'));
        }
      }
      if (c.retrieveItems && c.retrieveItems.resultObj) flattenPage(c.retrieveItems.resultObj.containers, out);
    });
    return out;
  }

  async function getEditorialPage(pageId) {
    var json = await get(base() + '/PAGE/' + pageId + '/' + C.product + '/' + C.groupId);
    var seen = {};
    return flattenPage(json.resultObj && json.resultObj.containers, []).filter(function (s) {
      if (seen[s.contentId]) return false;
      seen[s.contentId] = true;
      return true;
    });
  }

  /* ---------- playback ---------- */

  async function getEntitlementToken(token) {
    var cached = Store.get('entitlementToken');
    if (cached && cached.forToken === token.slice(-24) && cached.value) return cached.value;
    try {
      var json = await request(C.rootUrl + '/2.0/R/ENG/BIG_SCREEN_HLS/ALL/USER/ENTITLEMENT', { headers: playHeaders(token) });
      var value = json.entitlementToken || (json.resultObj && json.resultObj.entitlementToken) ||
        (json.result && json.result.entitlementToken) || '';
      if (value) Store.set('entitlementToken', { forToken: token.slice(-24), value: value });
      return value;
    } catch (e) {
      console.warn('Entitlement request failed', e);
      return '';
    }
  }

  function playCandidates(streamType, preferUhd) {
    var c = function (api, platform, override) { return { api: api, platform: platform, override: override || null }; };
    var standard = streamType === 'HLS'
      ? [c('3.0', 'WEB_HLS'), c('3.0', 'BIG_SCREEN_HLS'), c('2.0', 'BIG_SCREEN_HLS'), c('3.0', 'WEB_DASH'), c('2.0', 'BIG_SCREEN_DASH')]
      : [c('3.0', 'BIG_SCREEN_HLS'), c('3.0', 'WEB_DASH'), c('2.0', 'BIG_SCREEN_DASH'), c('2.0', 'WEB_DASH'), c('3.0', 'WEB_HLS'), c('2.0', 'BIG_SCREEN_HLS')];
    if (!preferUhd) return standard;
    var uhd = [];
    ['HDR_UHD_DASHWV', 'HDR_UHD_DASHWV_SINGLE', 'HDR_UHD_CMAFWV', 'HDR_UHD_CMAFWV_SINGLE'].forEach(function (o) {
      uhd.push(c('3.0', 'BIG_SCREEN_HLS', o), c('3.0', 'WEB_DASH', o));
    });
    return uhd.concat(standard);
  }

  function looksUhd(s) { s = (s || '').toUpperCase(); return s.indexOf('UHD') >= 0 || s.indexOf('HDR') >= 0 || s.indexOf('2160') >= 0; }

  /** Resolves a playable manifest + DRM info for a content/channel pair. */
  async function getViewing(contentId, channelId) {
    var token = await ensureToken();
    var entitlement = await getEntitlementToken(token);
    var preferUhd = !!Store.get('preferUhd');
    var lastError = null;
    var fallback = null;
    var candidates = playCandidates(Store.get('streamType'), preferUhd);

    for (var i = 0; i < candidates.length; i++) {
      var cand = candidates[i];
      var url = C.rootUrl + '/' + cand.api + '/R/ENG/' + cand.platform + '/ALL/CONTENT/PLAY?contentId=' +
        encodeURIComponent(contentId) + '&player=player_bm' + (channelId ? '&channelId=' + encodeURIComponent(channelId) : '');
      var headers = playHeaders(token, entitlement);
      if (cand.override) headers['x-f1-override-video-stream'] = cand.override;
      try {
        var json = await request(url, { headers: headers });
        if (String(json.resultCode).toUpperCase() !== 'OK') throw ApiError(json.errorDescription || json.message || json.resultCode, 200, json);
        var r = json.resultObj || {};
        var drm = (r.drmType || '').toLowerCase();
        if (!r.url) throw ApiError('No manifest URL for ' + cand.platform, 200, json);
        if (drm && drm !== 'widevine') throw ApiError('Unsupported DRM ' + r.drmType, 200, json);
        var viewing = {
          url: r.url,
          contentId: contentId,
          channelId: r.channelId || channelId,
          platform: cand.platform,
          api: r.playApiVersion || cand.api,
          streamType: r.streamType || '',
          drmType: drm,
          laURL: r.laURL || '',
          token: token,
          entitlementToken: r.entitlementToken || entitlement,
          override: cand.override
        };
        if (cand.override && !(looksUhd(r.streamType) && drm === 'widevine' && r.laURL)) {
          continue; // rejected UHD probe, keep looking
        }
        if (preferUhd && !cand.override && !fallback) {
          fallback = viewing;
          continue;
        }
        return viewing;
      } catch (e) {
        lastError = e;
        console.warn('PLAY candidate failed', cand, e.message);
        if (e.status === 401 || e.status === 403) {
          // Token may have been revoked server-side; force a fresh login next time.
          if (/token|auth|expired/i.test(e.message)) Store.set('entitlementToken', null);
        }
      }
    }
    if (fallback) return fallback;
    throw lastError || ApiError('Unable to fetch stream', 0);
  }

  function licenseUrl(viewing) {
    if (viewing.laURL) return proxied(viewing.laURL);
    return proxied(C.rootUrl + '/' + viewing.api + '/R/ENG/' + viewing.platform + '/ALL/CONTENT/PLAY/WIDEVINE?contentId=' +
      viewing.contentId + (viewing.channelId ? '&channelId=' + viewing.channelId : ''));
  }

  function licenseHeaders(viewing) {
    return {
      'x-f1-device-info': C.f1DeviceInfo,
      'ascendonToken': viewing.token,
      'entitlementToken': viewing.entitlementToken
    };
  }

  global.F1Api = {
    login: login,
    ensureToken: ensureToken,
    normalizeToken: normalizeToken,
    tokenValid: tokenValid,
    tokenExpiry: tokenExpiry,
    decodeJwt: decodeJwt,
    getSeason: getSeason,
    getSessions: getSessions,
    getUpcomingSessions: getUpcomingSessions,
    getChannels: getChannels,
    getEditorialPage: getEditorialPage,
    getViewing: getViewing,
    licenseUrl: licenseUrl,
    licenseHeaders: licenseHeaders,
    proxied: proxied,
    Series: Series
  };
})(window);
