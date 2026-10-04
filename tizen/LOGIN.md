# F1 TV login on Tizen — notes for AI assistants and contributors

Read this before changing anything about sign-in in the Tizen app. It explains how the Android
app logs in, why that approach cannot be copied to Tizen, and what the Tizen app does instead.

> Status: the Tizen login code has **not yet been tested against the live F1 servers**. Treat
> it as the intended design, and report what actually happens on a real TV.

## TL;DR for an AI assistant

- **Do not** embed the F1 login page in an iframe and try to read its cookies. A Tizen app is a
  web page, and browser security will never let it read `formula1.com` cookies. This approach
  cannot work, so don't spend time on it.
- **Do not** add a WebView or a `CookieManager` equivalent. No such API exists for Tizen web apps.
- **Pasting a token is the dependable path.** Direct password login is a bonus that F1's bot
  protection may block (HTTP 403).
- Everything after login (entitlement, PLAY, licence) only needs the subscription token. Get
  that right and the rest of the app works the same as on Android.

## How the Android app logs in

Code: `app/.../ui/signin/SignInActivity.kt` and `app/.../core/credentials/CredentialsService.kt`.

1. The app opens the real login page `https://account.formula1.com/#/en/login` in an Android
   **WebView**, which is a real browser engine.
2. The user logs in normally. F1's bot protection sees a genuine browser and lets it through.
3. On success, F1 sets a cookie named **`login-session`**. Android's `CookieManager` can read
   cookies from any site loaded in the app's WebView, so the app reads it.
4. The cookie value is URL-encoded JSON of the form `{"data":{"subscriptionToken":"<JWT>"}}`.
   The app decodes it and stores `data.subscriptionToken`.
5. That JWT is sent as the `ascendonToken` header on entitlement, PLAY and licence requests.

## Why that cannot be copied to Tizen

A Samsung Tizen TV app is HTML and JavaScript running in the TV's browser engine.

- It can display `account.formula1.com`, in an iframe or by navigating to it.
- It **cannot** read that site's cookies. The same-origin policy blocks it, and Tizen offers
  nothing like Android's `CookieManager`.
- So the `login-session` cookie, and the token inside it, is out of reach for the app.

## What the Tizen app does instead

Code: `tizen/js/api.js` (`login`, `normalizeToken`, `ensureToken`), `tizen/js/app.js`
(`SCREENS.login`) and `tizen/js/storage.js`.

### Option 1: direct password login (may be blocked)

```
POST https://api.formula1.com/v2/account/subscriber/authenticate/by-password
Headers:
  apiKey: CWkfako6ppvmpxVOaBwCeGZfiY1Kpi1w
  Content-Type: application/json
Body:
  {"Login": "<email>", "Password": "<password>",
   "DistributionChannel": "d861e38f-05ea-4063-8776-a7e2b6d885a4"}
Response:
  {"data": {"subscriptionToken": "<JWT>"}, ...}
```

- If this works, the app stores the token. If "remember password" is on, it also stores the
  credentials, so `ensureToken()` can log in again silently when the JWT's `exp` has passed.
- Risk: F1's bot protection may answer **HTTP 403**, because this request does not come from a
  real browser session. That is the reason Android uses a WebView. The app then shows a message
  suggesting option 2 or the proxy.

### Option 2: paste a token (the dependable path)

1. On a computer, sign in at <https://f1tv.formula1.com>.
2. Open DevTools → Application (Storage in Firefox) → Cookies → `https://f1tv.formula1.com`
   (or `account.formula1.com`). Copy the value of **`login-session`**.
3. On the TV, paste it into the token box on the sign-in screen. The SmartThings phone app's
   remote keyboard can paste text from your phone.

`normalizeToken()` accepts any of these:

- the whole cookie string, such as `login-session=...; other=...`
- the URL-encoded value, starting with `%7B`
- the decoded JSON, `{"data":{"subscriptionToken":"..."}}`
- the bare JWT

The app reads the JWT's `exp` claim and rejects tokens that have already expired. A pasted
token cannot be refreshed automatically. When it expires, paste a new one, or use option 1.

### Option 3: bake it into the build

Copy `tizen/js/local.example.js` to `tizen/js/local.js`, which is git-ignored, and fill in
`token`, or `login` and `password`. These become defaults when the app is packaged.

### Optional relay proxy

`tizen/tools/f1-proxy.js` is a Node script with no dependencies. Run it on any machine the TV
can reach, then set **Settings → API proxy**. It relays `*.formula1.com` requests and adds the
`User-Agent`, `Origin` and `Referer` headers a browser app is not allowed to set, plus CORS
headers. Use it when the TV gets CORS errors or 403s that the Android app does not.

## After login: identical to Android

With the subscription token (JWT):

1. `GET https://f1tv.formula1.com/2.0/R/ENG/BIG_SCREEN_HLS/ALL/USER/ENTITLEMENT` with headers
   `ascendonToken`, `x-f1-device-info`, `CD-DeviceType: 49` and
   `CD-DistributionChannel: e827264e-a81b-4f65-a567-2070850c128e` returns `entitlementToken`.
2. `GET https://f1tv.formula1.com/{3.0|2.0}/R/ENG/{platform}/ALL/CONTENT/PLAY?contentId=..&channelId=..&player=player_bm`
   sends the same headers plus `entitlementToken`. The response has `url` (the manifest),
   `drmType` and `laURL`.
3. Widevine licence POST to `laURL` with headers `ascendonToken`, `entitlementToken` and
   `x-f1-device-info`. The body and response are the raw Widevine challenge and licence. On
   Tizen, this is done in the Shaka Player request filter in `tizen/js/player.js`.

`x-f1-device-info` is the same spoofed Android TV string the Android app sends (see
`tizen/js/config.js`).

## If you're debugging login

- **403 on the password login:** expected bot protection. Use token paste or the proxy, and
  don't try to work around the bot protection itself.
- **"Network error / Failed to fetch":** probably CORS. Try the proxy.
- **Login works but PLAY returns 401/403:** the token has probably expired. Check `exp` with
  `F1Api.decodeJwt(token)`, and note that the cached entitlement token is cleared on re-login.
- Report the exact HTTP status and response body; the app shows them on screen.
