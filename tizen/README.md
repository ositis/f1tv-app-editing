# UgisF1 for Samsung TV (Tizen)

A Tizen web app port of the Android TV client: browse F1 TV seasons, weekends, shows and
documentaries, then play any feed (main feed, F1 Live, tracker, data, pit lane, onboards) with
Widevine DRM through [Shaka Player](https://github.com/shaka-project/shaka-player).

Not affiliated with Formula 1 or F1 TV. A valid F1 TV subscription is required.

## Supported TVs

- Samsung Smart TVs from **2018 onwards** (Tizen 4.0+). That's where Widevine in the TV browser
  engine starts. Older models can install the app but won't play DRM streams.
- 4K/HDR is opt-in (**Settings → Try 4K / HDR streams**) and needs an F1 TV Premium plan.

## Remote control

| Where | Key | Action |
|---|---|---|
| Menus | ▲▼◀▶ / OK | Move / select |
| Menus | Back | Previous screen (exit prompt on Home) |
| Menus | Red or Blue | Settings |
| Player | OK, ⏯ | Play / pause (shows the info bar) |
| Player | ◀ ▶ | Seek 10 s (hold or repeat to speed up) |
| Player | ⏪ ⏩ | Seek 30 s |
| Player | ▲ ▼, CH+ / CH− | Previous / next feed |
| Player | Green | Feed list (all onboards) |
| Player | Yellow | Audio track, or Grand Prix Radio / custom radio (mutes video) |
| Player | Blue | Video quality |
| Player | Red | Jump to live edge (live) / restart (replay) |
| Player | Back, ⏹ | Hide info bar, then close player |

## Signing in

You can sign in three ways:

1. **E-mail + password** on the sign-in screen. The password is saved on the TV so the app can
   refresh the token on its own when it expires. You can turn that off in Settings.
2. **Paste a token.** Sign in at <https://f1tv.formula1.com> on a computer, then copy the
   `login-session` cookie value (DevTools → Application → Cookies). Paste it into the token box.
   The raw cookie, its decoded JSON and the bare `subscriptionToken` all work. The SmartThings
   phone app's remote keyboard lets you paste text from your phone.
3. **Bake it into your build.** Copy `js/local.example.js` to `js/local.js` (git-ignored) and fill
   in `token` and/or `login`/`password`.

F1's account API sometimes blocks password logins from non-browser clients (HTTP 403). If that
happens, use option 2 or the proxy below.

## Install on the TV (Tizen Studio)

One-time setup:

1. Install [Tizen Studio](https://developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/installing-tv-sdk.html)
   (the CLI is enough). In Package Manager, add **Samsung Certificate Extension** and the
   **TV Extensions** tools.
2. On the TV: open **Apps**, then press `1 2 3 4 5` on the remote. Turn **Developer mode** on and
   enter your computer's IP address. Restart the TV.
3. In Tizen Studio's **Device Manager**, connect to the TV's IP, or run `sdb connect <tv-ip>`.
   Note the TV's DUID.
4. In **Certificate Manager**, create a **Samsung** certificate profile (it needs a Samsung
   account). Choose the TV device type and include the connected TV's DUID.

Build and install:

```bash
cd tizen
TV=192.168.1.50 PROFILE=<your-profile-name> tools/package.sh
```

The script copies only the runtime files into `dist/`, signs `dist/UgisF1.wgt` and installs it
with `tizen install`. You can also open the `tizen/` folder as a project in Tizen Studio and use
**Run As → Tizen Web Application**.

Developer-mode installs stay on the TV, but Samsung certificates expire. Rebuild and reinstall
when the app stops launching.

## Optional API proxy

A browser engine can't set `User-Agent`/`Origin`/`Referer` the way the Android app does, and
some Tizen firmware enforces CORS on cross-origin calls. If browsing or playback fails with
network or 403 errors, run the zero-dependency relay on any always-on machine on your network:

```bash
node tizen/tools/f1-proxy.js 8787
```

Then set **Settings → API proxy** to `http://<that-machine>:8787`. API, login and licence
requests go through it. Video segments still come straight from the CDN. Only `*.formula1.com`
hosts are relayed.

## Desktop preview

```bash
cd tizen && python3 -m http.server 8099
# http://localhost:8099/index.html?mock=1  → fixture data + a public test stream
```

Use the arrow keys, Enter and Esc/Backspace (Back). `r` `g` `y` `b` stand in for the colour keys,
and `[` / `]` for CH+/CH−. Without `?mock=1`, desktop browsers block the F1 API through CORS,
so use the proxy setting.

## How it maps to the Android app

| Android | Tizen |
|---|---|
| `F1TvClient` (season, sessions, channels, editorial pages) | `js/api.js` → `getSeason`, `getSessions`, `getChannels`, `getEditorialPage` |
| `F1Client.getViewing` (PLAY candidates, entitlement, UHD probes) | `js/api.js` → `getViewing` |
| ExoPlayer + Widevine licence headers | Shaka Player + licence request filter (`js/player.js`) |
| Leanback rows / D-pad focus | `js/nav.js` spatial navigation + `js/ui.js` cards and rows |
| Custom radio | Yellow → audio picker → external radio streams |

Not ported yet: multiview layouts (TVs generally have one DRM video decoder), the Jolpica
calendar, standings and results screens, radio sync delay, and pre-2018 archive seasons.
