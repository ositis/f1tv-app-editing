/* Persistent settings and credentials (localStorage survives app restarts on Tizen). */
(function (global) {
  'use strict';

  var PREFIX = 'ugisf1.';

  var DEFAULTS = {
    streamType: 'DASH',      // DASH | HLS
    series: 'ALL',           // ALL | F1 | F2 | F3 | F1A | PSC
    showChannelGrid: true,
    rememberPassword: true
  };

  function read(key) {
    try {
      var raw = global.localStorage.getItem(PREFIX + key);
      return raw === null ? undefined : JSON.parse(raw);
    } catch (e) {
      return undefined;
    }
  }

  function write(key, value) {
    try {
      if (value === undefined || value === null) {
        global.localStorage.removeItem(PREFIX + key);
      } else {
        global.localStorage.setItem(PREFIX + key, JSON.stringify(value));
      }
    } catch (e) { /* storage full or unavailable */ }
  }

  global.Store = {
    get: function (key) {
      var v = read(key);
      if (v === undefined && Object.prototype.hasOwnProperty.call(DEFAULTS, key)) return DEFAULTS[key];
      if (v === undefined && key === 'proxyUrl') return F1Config.local.proxyUrl;
      return v;
    },
    set: write,

    getToken: function () { return read('token') || F1Config.local.token || ''; },
    setToken: function (t) { write('token', t || null); },

    getCredentials: function () {
      var c = read('credentials');
      if (c && c.login) return c;
      if (F1Config.local.login) return { login: F1Config.local.login, password: F1Config.local.password };
      return null;
    },
    setCredentials: function (c) { write('credentials', c || null); },

    signOut: function () {
      write('token', null);
      write('credentials', null);
      write('entitlementToken', null);
    }
  };
})(window);
