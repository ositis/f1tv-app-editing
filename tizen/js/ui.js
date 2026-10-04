/* Small DOM helpers and reusable TV components (cards, rows, toast, dialogs). */
(function (global) {
  'use strict';

  function h(tag, attrs, children) {
    var el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (v === undefined || v === null || v === false) return;
        if (k === 'class') el.className = v;
        else if (k === 'text') el.textContent = v;
        else if (k === 'style' && typeof v === 'object') Object.keys(v).forEach(function (s) { el.style[s] = v[s]; });
        else el.setAttribute(k, v === true ? '' : v);
      });
    }
    (children || []).forEach(function (c) {
      if (c === null || c === undefined || c === false) return;
      el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    });
    return el;
  }

  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  function fmtDate(t) {
    if (!t) return '';
    var d = new Date(t);
    return d.getDate() + ' ' + MONTHS[d.getMonth()];
  }

  function fmtDateTime(t) {
    if (!t) return '';
    var d = new Date(t);
    return d.getDate() + ' ' + MONTHS[d.getMonth()] + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  function fmtRange(a, b) {
    if (!a) return '';
    if (!b || new Date(a).getMonth() !== new Date(b).getMonth()) return fmtDate(a) + (b ? ' – ' + fmtDate(b) : '');
    return new Date(a).getDate() + '–' + fmtDate(b);
  }

  function fmtTime(sec) {
    if (!isFinite(sec) || sec < 0) sec = 0;
    sec = Math.floor(sec);
    var hh = Math.floor(sec / 3600); var mm = Math.floor((sec % 3600) / 60); var ss = sec % 60;
    return (hh ? hh + ':' + pad(mm) : mm) + ':' + pad(ss);
  }

  function isLive(item) {
    if (item.live !== undefined) return item.live;
    var now = Date.now();
    return item.start && item.end && item.start <= now && item.end >= now;
  }

  /** Media card: image (or text fallback), title, subtitle, optional badges. */
  function card(opts) {
    var media = opts.picture
      ? h('div', { class: 'card-media', style: { backgroundImage: 'url("' + opts.picture + '")' } })
      : h('div', { class: 'card-media card-media-text', style: opts.color ? { background: opts.color } : null }, [
        h('span', { text: opts.mediaText || opts.title })
      ]);
    var badges = h('div', { class: 'card-badges' }, (opts.badges || []).map(function (b) {
      return h('span', { class: 'badge ' + (b.cls || ''), text: b.text });
    }));
    var el = h('div', { class: 'card ' + (opts.size || '') + (opts.disabled ? ' card-disabled' : '') }, [
      h('div', { class: 'card-frame' }, [media, badges]),
      h('div', { class: 'card-title', text: opts.title }),
      opts.subtitle ? h('div', { class: 'card-sub', text: opts.subtitle }) : null
    ]);
    return Nav.onSelect(el, opts.onSelect || function () {});
  }

  function row(title, cards, opts) {
    opts = opts || {};
    return h('section', { class: 'row', 'data-row': '' }, [
      title ? h('h2', { class: 'row-title', text: title }) : null,
      h('div', { class: 'row-track' + (opts.wrap ? ' row-wrap' : '') }, cards)
    ]);
  }

  function grid(cards) {
    // A grid is made of rows so up/down keeps column memory.
    return h('div', { class: 'grid' }, cards);
  }

  function button(label, onSelect, cls) {
    return Nav.onSelect(h('div', { class: 'btn ' + (cls || ''), text: label }), onSelect);
  }

  function message(title, text, actions) {
    return h('div', { class: 'message' }, [
      h('h1', { text: title }),
      text ? h('p', { text: text }) : null,
      actions && actions.length ? h('div', { class: 'actions', 'data-row': '' }, actions) : null
    ]);
  }

  function loading(text) {
    return h('div', { class: 'loading' }, [h('div', { class: 'spinner' }), h('p', { text: text || 'Loading…' })]);
  }

  var toastTimer = null;
  function toast(text, ms) {
    var el = document.getElementById('toast');
    el.textContent = text;
    el.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.add('hidden'); }, ms || 3500);
  }

  /**
   * Modal list picker. items: [{label, sub, selected, value}]; resolves with the chosen item or null.
   */
  function pick(title, items, host) {
    return new Promise(function (resolve) {
      var container = host || document.body;
      var list = h('div', { class: 'pick-list' });
      var panel = h('div', { class: 'pick' }, [h('h2', { text: title }), list]);
      var backdrop = h('div', { class: 'pick-backdrop' }, [panel]);
      var initial = null;
      var layer;
      function close(v) {
        if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
        Nav.pop(layer);
        resolve(v);
      }
      items.forEach(function (it) {
        var el = h('div', { class: 'pick-item' + (it.selected ? ' selected' : '') }, [
          it.color ? h('i', { class: 'swatch', style: { background: it.color } }) : null,
          h('span', { class: 'pick-label', text: it.label }),
          it.sub ? h('span', { class: 'pick-sub', text: it.sub }) : null
        ]);
        Nav.onSelect(el, function () { close(it); });
        if (it.selected) initial = el;
        list.appendChild(el);
      });
      container.appendChild(backdrop);
      layer = Nav.push({ root: panel, initial: initial, onBack: function () { close(null); } });
    });
  }

  function crumb(text) { document.getElementById('crumb').textContent = text || ''; }

  function startClock() {
    var el = document.getElementById('clock');
    var tick = function () { var d = new Date(); el.textContent = pad(d.getHours()) + ':' + pad(d.getMinutes()); };
    tick();
    setInterval(tick, 15000);
  }

  global.UI = {
    h: h, card: card, row: row, grid: grid, button: button, message: message, loading: loading,
    toast: toast, pick: pick, crumb: crumb, startClock: startClock,
    fmtDate: fmtDate, fmtDateTime: fmtDateTime, fmtRange: fmtRange, fmtTime: fmtTime, isLive: isLive
  };
})(window);
