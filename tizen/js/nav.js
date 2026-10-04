/* Remote-control key handling and D-pad spatial navigation. */
(function (global) {
  'use strict';

  var KEYS = {
    37: 'LEFT', 38: 'UP', 39: 'RIGHT', 40: 'DOWN', 13: 'ENTER',
    10009: 'BACK', 8: 'BACK', 27: 'BACK', 461: 'BACK',
    10252: 'PLAYPAUSE', 415: 'PLAY', 19: 'PAUSE', 413: 'STOP', 417: 'FF', 412: 'RW',
    427: 'CHUP', 428: 'CHDOWN', 33: 'CHUP', 34: 'CHDOWN',
    403: 'RED', 404: 'GREEN', 405: 'YELLOW', 406: 'BLUE',
    10182: 'EXIT', 32: 'PLAYPAUSE'
  };

  // Desktop preview helpers so the app is usable with a keyboard.
  var DESKTOP_KEYS = { r: 'RED', g: 'GREEN', y: 'YELLOW', b: 'BLUE', '[': 'CHUP', ']': 'CHDOWN', ',': 'RW', '.': 'FF' };

  var TIZEN_KEYS = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind',
    'ChannelUp', 'ChannelDown', 'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ColorF3Blue'];

  function registerTizenKeys() {
    if (!global.tizen || !tizen.tvinputdevice) return;
    TIZEN_KEYS.forEach(function (k) {
      try { tizen.tvinputdevice.registerKey(k); } catch (e) { /* key not on this model */ }
    });
  }

  var layers = [];

  function top() { return layers[layers.length - 1]; }

  function focusables(root) {
    var list = root.querySelectorAll('[data-focus]');
    var out = [];
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el.offsetParent !== null && !el.hasAttribute('disabled')) out.push(el);
    }
    return out;
  }

  function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function scrollIntoView(el) {
    var node = el.parentElement;
    while (node && node !== document.body) {
      var cs = global.getComputedStyle(node);
      var r = el.getBoundingClientRect();
      var pr = node.getBoundingClientRect();
      var pad = 48;
      if (/(auto|scroll|hidden)/.test(cs.overflowX) && node.scrollWidth > node.clientWidth) {
        if (r.left < pr.left + pad) node.scrollLeft -= (pr.left + pad - r.left);
        else if (r.right > pr.right - pad) node.scrollLeft += (r.right - pr.right + pad);
      }
      if (/(auto|scroll|hidden)/.test(cs.overflowY) && node.scrollHeight > node.clientHeight) {
        if (r.top < pr.top + pad) node.scrollTop -= (pr.top + pad - r.top);
        else if (r.bottom > pr.bottom - pad) node.scrollTop += (r.bottom - pr.bottom + pad);
      }
      node = node.parentElement;
    }
  }

  function focus(el) {
    if (!el) return;
    var layer = top();
    if (layer && layer.current && layer.current !== el) layer.current.classList.remove('focused');
    el.classList.add('focused');
    if (layer) layer.current = el;
    var row = el.closest('[data-row]');
    if (row) row._lastFocus = el;
    scrollIntoView(el);
    if (layer && layer.onFocus) layer.onFocus(el);
  }

  function rowOf(el) { return el.closest('[data-row]'); }

  function move(dir) {
    var layer = top();
    if (!layer) return;
    var items = focusables(layer.root);
    if (!items.length) return;
    var cur = layer.current && layer.root.contains(layer.current) && layer.current.offsetParent !== null ? layer.current : null;
    if (!cur) { focus(items[0]); return; }

    var curRow = rowOf(cur);
    var horizontal = dir === 'LEFT' || dir === 'RIGHT';
    var c = center(cur.getBoundingClientRect());

    function nearest(pool) {
      var target = null; var score = Infinity;
      pool.forEach(function (it) {
        if (it === cur) return;
        var p = center(it.getBoundingClientRect());
        var dx = p.x - c.x; var dy = p.y - c.y;
        var primary; var secondary;
        if (dir === 'LEFT') { primary = -dx; secondary = Math.abs(dy); }
        else if (dir === 'RIGHT') { primary = dx; secondary = Math.abs(dy); }
        else if (dir === 'UP') { primary = -dy; secondary = Math.abs(dx); }
        else { primary = dy; secondary = Math.abs(dx); }
        if (primary <= 1) return;
        // Horizontal moves stay on the same visual line inside wrapping grids.
        if (horizontal && Math.abs(dy) > it.getBoundingClientRect().height / 2) return;
        var s = primary + secondary * 2.5;
        if (s < score) { score = s; target = it; }
      });
      return target;
    }

    var sameRow = curRow ? items.filter(function (it) { return rowOf(it) === curRow; }) : null;
    if (sameRow) {
      var inRow = nearest(sameRow);
      if (inRow) { focus(inRow); return; }
      if (horizontal) { if (layer.onEdge) layer.onEdge(dir); return; }
    }

    // Up/down between rows: jump to the row's remembered item if any.
    if (!horizontal && curRow) {
      var rows = [];
      items.forEach(function (it) { var r = rowOf(it); if (r && rows.indexOf(r) < 0) rows.push(r); });
      var cr = curRow.getBoundingClientRect();
      var best = null; var bestD = Infinity;
      rows.forEach(function (r) {
        if (r === curRow) return;
        var rr = r.getBoundingClientRect();
        var d = dir === 'DOWN' ? rr.top - cr.top : cr.top - rr.top;
        if (d > 1 && d < bestD) { bestD = d; best = r; }
      });
      if (best) {
        var mem = best._lastFocus;
        if (mem && best.contains(mem) && mem.offsetParent !== null) { focus(mem); return; }
        var rowItems = items.filter(function (it) { return rowOf(it) === best; });
        var cx = center(cur.getBoundingClientRect()).x;
        rowItems.sort(function (a, b) {
          return Math.abs(center(a.getBoundingClientRect()).x - cx) - Math.abs(center(b.getBoundingClientRect()).x - cx);
        });
        focus(rowItems[0]);
        return;
      }
    }

    var target = nearest(items);
    if (target) focus(target);
    else if (layer.onEdge) layer.onEdge(dir);
  }

  function handle(e) {
    var key = KEYS[e.keyCode] || DESKTOP_KEYS[e.key];
    var target = e.target;
    var typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA');
    if (typing) {
      // While the on-screen keyboard is up, let it own the arrows; Enter/Back leave the field.
      if (key === 'ENTER' || e.keyCode === 65376 || e.keyCode === 65385) { target.blur(); e.preventDefault(); return; }
      if (key === 'BACK' && e.keyCode !== 8) { target.blur(); e.preventDefault(); return; }
      if (key !== 'UP' && key !== 'DOWN') return;
      target.blur();
    } else if (!key || (DESKTOP_KEYS[e.key] && (e.ctrlKey || e.metaKey))) {
      return;
    }

    var layer = top();
    if (!layer) return;
    e.preventDefault();

    if (key === 'EXIT') { Nav.exitApp(); return; }
    if (layer.onKey && layer.onKey(key, e) === true) return;

    switch (key) {
      case 'LEFT': case 'RIGHT': case 'UP': case 'DOWN':
        move(key); break;
      case 'ENTER':
        if (layer.current) activate(layer.current);
        break;
      case 'BACK':
        if (layer.onBack) layer.onBack();
        break;
    }
  }

  function activate(el) {
    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') { el.focus(); return; }
    if (el._onSelect) el._onSelect(el);
  }

  global.Nav = {
    init: function () {
      registerTizenKeys();
      document.addEventListener('keydown', handle);
      document.addEventListener('click', function (e) {
        var el = e.target.closest && e.target.closest('[data-focus]');
        var layer = top();
        if (el && layer && layer.root.contains(el)) { focus(el); activate(el); }
      });
    },

    /** Push a navigation layer. opts: {root, onKey, onBack, onFocus, onEdge, initial} */
    push: function (opts) {
      layers.push(opts);
      Nav.refresh(opts.initial);
      return opts;
    },

    pop: function (layer) {
      var i = layers.indexOf(layer);
      if (i >= 0) layers.splice(i, 1);
      var t = top();
      if (t && t.current && t.root.contains(t.current)) focus(t.current);
    },

    replaceBase: function (opts) {
      layers.length = 0;
      return Nav.push(opts);
    },

    /** Re-focus after content changed: keeps the current item if still present. */
    refresh: function (preferred) {
      var layer = top();
      if (!layer) return;
      if (preferred && layer.root.contains(preferred)) { focus(preferred); return; }
      if (layer.current && layer.root.contains(layer.current) && layer.current.offsetParent !== null) { focus(layer.current); return; }
      var items = focusables(layer.root);
      if (items.length) focus(items[0]);
    },

    focus: focus,
    top: top,
    onSelect: function (el, fn) { el.setAttribute('data-focus', ''); el._onSelect = fn; return el; },

    exitApp: function () {
      try { tizen.application.getCurrentApplication().exit(); } catch (e) { global.close(); }
    }
  };
})(window);
