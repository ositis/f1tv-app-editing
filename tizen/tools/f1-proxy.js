#!/usr/bin/env node
/*
 * Optional relay for the Tizen app (no dependencies, Node 14+).
 *
 * Use it only if requests from the TV fail (CORS errors, or F1 rejecting requests because a
 * browser cannot spoof User-Agent/Origin like the Android app does). Run it on any machine the
 * TV can reach, then set Settings → API proxy to http://<host>:<port>.
 *
 *   node f1-proxy.js [port]          (default 8787)
 *
 * Requests look like  http://host:8787/https://f1tv.formula1.com/2.0/R/ENG/...
 * Only *.formula1.com targets are relayed.
 */
'use strict';

const http = require('http');
const https = require('https');
const { URL } = require('url');

const PORT = Number(process.argv[2] || process.env.PORT || 8787);
const ALLOWED_HOST = /(^|\.)formula1\.com$/i;
const USER_AGENT = 'Mozilla/5.0 (Linux; Android 12; Google Chromecast Build/STTE.231215.005; wv) ' +
  'AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.114 Mobile Safari/537.36';
const DROP = new Set(['host', 'origin', 'referer', 'user-agent', 'connection', 'content-length', 'accept-encoding']);

function cors(res, req) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', req.headers['access-control-request-headers'] || '*');
  res.setHeader('Access-Control-Max-Age', '86400');
}

http.createServer((req, res) => {
  cors(res, req);
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  let target;
  try { target = new URL(req.url.slice(1)); } catch (e) { target = null; }
  if (!target || target.protocol !== 'https:' || !ALLOWED_HOST.test(target.hostname)) {
    res.writeHead(400, { 'Content-Type': 'text/plain' });
    res.end('Expected /https://<something>.formula1.com/...');
    return;
  }

  const headers = {};
  for (const [k, v] of Object.entries(req.headers)) if (!DROP.has(k)) headers[k] = v;
  headers['user-agent'] = USER_AGENT;
  headers.origin = 'https://f1tv.formula1.com';
  headers.referer = 'https://f1tv.formula1.com/';

  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    if (body.length) headers['content-length'] = body.length;
    const upstream = https.request(target, { method: req.method, headers }, (up) => {
      const out = {};
      for (const [k, v] of Object.entries(up.headers)) {
        if (!/^access-control-|^connection$|^transfer-encoding$/i.test(k)) out[k] = v;
      }
      res.writeHead(up.statusCode || 502, out);
      up.pipe(res);
    });
    upstream.on('error', (e) => {
      res.writeHead(502, { 'Content-Type': 'text/plain' });
      res.end('Upstream error: ' + e.message);
    });
    upstream.end(body);
  });
  console.log(new Date().toISOString(), req.method, target.hostname + target.pathname);
}).listen(PORT, () => console.log('F1 proxy listening on :' + PORT));
