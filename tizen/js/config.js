/* Static configuration mirrored from the Android client (F1Client / F1TvClient / DeviceInfo). */
(function (global) {
  'use strict';

  var local = global.UGISF1_LOCAL || {};

  global.F1Config = {
    appVersion: '1.0.0',

    rootUrl: 'https://f1tv.formula1.com',
    authUrl: 'https://api.formula1.com/v2/account/subscriber/authenticate/by-password',
    apiKey: 'CWkfako6ppvmpxVOaBwCeGZfiY1Kpi1w',
    distributionChannel: 'd861e38f-05ea-4063-8776-a7e2b6d885a4',

    groupId: 2,
    product: 'F1_TV_Premium_Monthly',

    cdDeviceType: '49',
    cdDistributionChannel: 'e827264e-a81b-4f65-a567-2070850c128e',

    // Same spoofed identity the Android TV build uses for PLAY / ENTITLEMENT / licence calls.
    f1DeviceInfo: 'device=android_tv;screen=bigscreen;os=android;model=sabrina;osVersion=34;' +
      'appVersion=30482001;playerVersion=3.112.0;tms=1;',

    editorialPages: [
      { id: 410, name: 'Shows' },
      { id: 413, name: 'Documentaries' }
    ],

    firstArchiveYear: 2018,

    radioStreams: [
      { name: 'Grand Prix Radio', url: 'https://playerservices.streamtheworld.com/api/livestream-redirect/GRAND_PRIX_RADIO.mp3' },
      { name: 'F1 Live Timing audio', url: 'https://livetiming.formula1.com/static/F1LiveTiming.mp3' }
    ],

    // Optional build-time defaults from js/local.js (git-ignored).
    local: {
      token: local.token || '',
      login: local.login || '',
      password: local.password || '',
      proxyUrl: local.proxyUrl || ''
    }
  };
})(window);
