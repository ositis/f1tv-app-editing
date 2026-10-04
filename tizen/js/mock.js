/* Desktop preview: open index.html?mock=1 to browse fixture data and play a public test stream. */
(function (global) {
  'use strict';

  var DAY = 24 * 3600 * 1000;
  var now = Date.now();
  var GPS = ['Australian', 'Chinese', 'Japanese', 'Bahrain', 'Saudi Arabian', 'Miami', 'Emilia Romagna', 'Monaco',
    'Spanish', 'Canadian', 'Austrian', 'British', 'Belgian', 'Hungarian', 'Dutch', 'Italian', 'Azerbaijan', 'Singapore'];

  function events() {
    return GPS.map(function (name, i) {
      var start = now - (GPS.length - 2 - i) * 14 * DAY - DAY;
      return { id: 'ev' + i, meetingKey: String(1200 + i), title: 'FORMULA 1 ' + name.toUpperCase() + ' GRAND PRIX',
        country: name, circuit: '', start: start, end: start + 2 * DAY, picture: '' };
    });
  }

  function sessions(event) {
    var names = [['Race', 'REPLAY'], ['Qualifying', 'REPLAY'], ['Sprint', 'REPLAY'], ['Practice 1', 'REPLAY'],
      ['F2 Feature Race', 'REPLAY'], ['F3 Sprint Race', 'REPLAY'], ['Race Highlights', 'HIGHLIGHTS']];
    return names.map(function (n, i) {
      var start = event.start + i * 3 * 3600 * 1000;
      var live = i === 0 && event.end > now;
      return { id: event.id + 's' + i, eventId: event.id, contentId: '10000' + i, title: n[0], subtype: live ? 'LIVE' : n[1], live: live,
        series: F1Api.Series.classify(null, null, n[0]), start: start, end: event.end, picture: '', largePicture: '' };
    });
  }

  var DRIVERS = [['Max', 'Verstappen', 1, 'Red Bull Racing', '3671C6'], ['Lando', 'Norris', 4, 'McLaren', 'FF8000'],
    ['Charles', 'Leclerc', 16, 'Ferrari', 'E8002D'], ['Lewis', 'Hamilton', 44, 'Ferrari', 'E8002D'],
    ['George', 'Russell', 63, 'Mercedes', '27F4D2'], ['Oscar', 'Piastri', 81, 'McLaren', 'FF8000']];

  F1Api.getSeason = function () { return Promise.resolve(events()); };
  F1Api.getSessions = function (e) { return Promise.resolve(sessions(e)); };
  F1Api.getEditorialPage = function (id) {
    return Promise.resolve([1, 2, 3, 4, 5].map(function (i) {
      return { id: 'p' + id + i, contentId: '20' + id + i, title: (id === 410 ? 'Show ' : 'Documentary ') + i, subtype: 'SHOW', series: 'F1', start: 0, end: 0, picture: '' };
    }));
  };
  F1Api.getChannels = function (contentId) {
    var feeds = [
      { type: 'WIF', name: 'Main feed', label: 'Main feed' }, { type: 'F1LIVE', name: 'F1 Live', label: 'F1 Live' },
      { type: 'TRACKER', name: 'Driver tracker', label: 'Driver tracker' }, { type: 'DATA', name: 'Data channel', label: 'Data channel' }
    ].map(function (f, i) { f.contentId = contentId; f.channelId = String(i + 1); return f; });
    return Promise.resolve(feeds.concat(DRIVERS.map(function (d, i) {
      return { type: 'OBC', contentId: contentId, channelId: String(100 + i), name: d[0] + ' ' + d[1], number: d[2],
        team: d[3], color: d[4], label: d[2] + ' ' + d[1] };
    })));
  };
  F1Api.login = function () { Store.setToken('mock-token'); return Promise.resolve('mock-token'); };
  F1Api.getViewing = function (contentId, channelId) {
    return Promise.resolve({
      url: 'https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd',
      contentId: contentId, channelId: channelId, platform: 'MOCK', api: '3.0', streamType: 'MOCK', drmType: '',
      laURL: '', token: 'mock-token', entitlementToken: ''
    });
  };

  if (!Store.getToken()) Store.setToken('mock-token');
})(window);
