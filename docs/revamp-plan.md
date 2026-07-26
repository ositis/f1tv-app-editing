# F1TV Android TV — Full Revamp Plan

**Repo:** `/root/f1tv-app-editing` (ositis/f1tv-app-editing ← st14n/race-control-tv)  
**Status:** P0 shipped in 1.0.5; multi-cam + calendar/standings/results in 1.0.6  
**Includes:** Best-of-n comparison · F1-branded design overhaul · series IA · race map · MultiViewer-style multi-cam · Amsterdam calendar · standings/results

Evidence: **[verified]** repo/screenshot · **[external]** public F1 knowledge · **[assumed]** needs probe

---

## 0. Best-of-n results (design / IA / map)

Three isolated worktree plans were run against this repo. Parent checkout was not modified by those runs.

| Model | Worktree | Branch | Artifact |
|-------|----------|--------|----------|
| **claude-opus-5** | `/root/.paseo/worktrees/1mv9hn5l/revamp-plan-candidate-b` | `revamp-plan-candidate-b` | `docs/revamp-plan.md` |
| **composer-2.5** | `/root/.paseo/worktrees/1mv9hn5l/revamp-plan-candidate` | `revamp-plan-candidate` | `docs/revamp-plan.md` |
| **grok-4.5** | `/root/.paseo/worktrees/1mv9hn5l/revamp-plan-candidate-c` | `revamp-plan-candidate-c` | `docs/revamp-plan.md` |
| gpt-5.4 / auto | Skipped (no available subagent slug) | — | — |

### Consensus (all three)

1. UI is **stock Leanback demo**, not “bad architecture” — playback/Media3 is ahead of browse chrome.
2. F2 / F3 / Porsche / Academy feel missing because they’re **mixed into GP weekend rows**, not because the API omits them.
3. Race map in screenshots is **in-stream Tracker/Data video**, not an app overlay.
4. Ship **Tracker “Map” shortcut** before building a custom timing map.
5. Stay **TV-native**; no phone Material mashup. Leanback restyle first; Compose for new surfaces later.
6. Hard-fence **HDR / secure-surface** code during UI/IA work.

### Where they differed

| Topic | opus | composer | grok |
|-------|------|----------|------|
| Design depth | Strongest code/screenshot evidence | Clear nav-rail proposal | Concise “Media3 in old chrome” |
| Series key | Unused `Series` EMF field → Room | Title heuristics + `Series` + PAGE hubs | **`uiSeries`** (`F1`/`F2`/`F3`/`F1A`/`PSC`) + volume estimates |
| Race map preference | Custom overlay + delayed SignalR (after JWT spike) | Dual-feed Tracker PiP first | **Tracker shortcut first** (most pragmatic) |
| Nav chrome | Top tab strip (reclaim fastlane width) | Left series rail | Filter chips + rails |

### Adopted blend (this document)

- **Design:** Full F1-branded overhaul (below) — replaces teal/Blue Grey entirely.  
- **Series:** Parse **both** `uiSeries` and `Series`; client-side hubs; settings default + home filter.  
- **Map:** Tracker shortcut P0; dual-view optional P1; custom SignalR P2 spike-gated.  
- **Multi-cam:** MultiViewer-style right column, up to 4 OBC, synced (new section).  
- **Risk fences:** opus decoder/entitlement analysis + grok “don’t couple to HDR”.

---

## 1. Design overhaul — modern F1 TV look

### 1.1 Why current UI feels old **[verified]**

| Symptom | Evidence |
|---------|----------|
| Stock Leanback shell | `AppTheme` → bare `Theme.Leanback` (`styles.xml`) |
| Demo teal chrome | `fastlane_background` = `#0096a6` |
| 2014 Material cards | `card_background` = `#37474F` (Blue Grey 800); only two colors in `colors.xml` |
| Tiny soft art | `MAIN_IMAGE` 313×176 baked into image URL |
| Flat IA | No series badges, no hero, weekend dump of every subtype |
| Phone dialogs | `AlertDialog` + `simple_list_item_1` for channel/audio |
| Player vs broadcast | Overlay text/controls drawn through burned-in tower/map |

### 1.2 Visual north star

**One composition per screen, 10-foot remote UX, unmistakably F1 — not a generic dark streaming skin and not a phone app on a TV.**

- Cinema black canvas, **F1 red** as the only primary accent (focus, live, CTAs).
- Large 16:9 cards, persistent titles, series chips, LIVE pulse.
- Immersive focus: scale + red focus ring + optional backdrop from `LARGE_PICTURE_URL`.
- Player chrome is minimal and **keeps the bottom-right clear** (broadcast map zone).
- Motion: short focus transitions + background crossfade; respect animator duration scale / reduced motion.

### 1.3 Design tokens (implement in `colors.xml` / theme)

Official-adjacent F1 palette (brand-inspired; not a trademark asset dump):

| Token | Hex | Use |
|-------|-----|-----|
| `f1_black` | `#15151E` | App/window background (F1 “carbon” near-black) |
| `f1_black_elevated` | `#1F1F2B` | Cards, panels, side sheets |
| `f1_black_deep` | `#0A0A0F` | Player / multi-cam letterbox |
| `f1_red` | `#E10600` | Primary accent — focus ring, LIVE, primary CTA |
| `f1_red_dim` | `#B00500` | Pressed / secondary emphasis |
| `f1_white` | `#FFFFFF` | Primary text |
| `f1_silver` | `#C8C8D0` | Secondary text, meta |
| `f1_muted` | `#8D8D9A` | Tertiary / disabled |
| `f1_divider` | `#2A2A36` | Hairlines, slot borders |
| `f1_live` | `#E10600` | LIVE pill fill (+ white label) |
| `f1_replay` | `#3D3D4A` | REPLAY pill |
| Series chips | see below | Small solid chips, white text |

**Series chip colors (restrained, readable on black):**

| Series | Chip |
|--------|------|
| F1 | `#E10600` |
| F2 | `#0090D0` |
| F3 | `#E56A00` |
| F1 Academy | `#9B59B6` |
| Porsche Supercup | `#6B6B6B` + gold text `#D4AF37` optional |

**Typography (TV):**

- Display / row headers: bold condensed sans (system `sans-serif-condensed` or bundled face) ≥ 32sp.
- Card title ≥ 18sp; meta ≥ 14sp in `f1_silver`.
- Avoid decorative serifs and phone-dense type scales.

**Focus:**

- Default: 3dp `f1_red` stroke + scale 1.06–1.08.
- No purple glow, no neon bloom, no multi-layer Material shadows.
- Selected nav item: red underline or left bar, not filled teal rail.

**Kill list:** `#0096a6` fastlane, `#37474F` cards, unlabeled thumbnail walls, centered opaque `AlertDialog` lists as primary pickers.

### 1.4 Shell & navigation

**Recommended (blend):** Top **series + destination tab strip** (reclaims the ~25% teal fastlane width) **[opus]**, with optional left mini-rail only for Archive years.

```
[ F1 ] [ F2 ] [ F3 ] [ Academy ] [ Porsche ] [ Shows ] [ Archive ]     ⚙
────────────────────────────────────────────────────────────────────────
 HERO / LIVE BILLBOARD
────────────────────────────────────────────────────────────────────────
 Row: Live now
 Row: This weekend
 Row: Continue watching
 Row: Onboards (when session live)
```

**Home hero:** Next/live session — large backdrop, series chip, countdown from existing `sessionStartDate` **[verified]**, primary “Watch” action.

**Season/event browse:** Rows grouped by session type (Practice / Quali / Race / Sprint / Support / Shows), not one unsorted strip.

**Channel picker:** Grouped sections — Main feeds · Tracker & Data · Onboards (team-colored from stream `hex` **[verified]**).

**Player:** Bottom gradient scrim only; quality / audio / multi-cam / map as **chip row**; replace blocking dialogs with right **side sheet**.

**Multi-cam chrome:** Deep black gutters; slot frames `f1_divider`, focused slot `f1_red`; driver number badge using team `hex` when available.

### 1.5 Component checklist (P0 design)

- [ ] Theme: `AppTheme` / browse brandColor → F1 tokens; delete teal usage  
- [ ] Custom `Presenter` cards: 16:9 ~420×236 (request art ≥ 640×360)  
- [ ] Series badge + LIVE/REPLAY pills  
- [ ] Top tab strip + settings default series  
- [ ] Hero billboard on home  
- [ ] Side sheet pickers (channel / audio / quality)  
- [ ] Player overlay safe zones (clear BR for map; clear right for multi-cam column)  
- [ ] Focus choreography + reduced-motion path  

### 1.6 Leanback vs Compose

| Phase | Approach |
|-------|----------|
| P0 | Restyle in place — themes, presenters, layouts |
| P1 | `ComposeView` islands: series chooser, side sheets, **multi-cam shell** |
| P2 | Optional Compose browse shell |
| Never | Rewrite HDR / `ChannelPlaybackFragment` surface binding for “design” |

---

## 2. Series IA — other views & picker

### Root cause

Weekend sandwich already returns mixed series. App shows them unlabeled under the GP **[verified]**.

### Feasible views

| View | Feasibility | How |
|------|-------------|-----|
| F1 / F2 / F3 / Academy / Porsche hubs | High | Filter `uiSeries` / `Series` |
| Live now | High | PAGE `395` **[external]** |
| Shows / documentaries | High | PAGE `410` / `413` **[external]** |
| Archive hub | High | PAGE `493`/`729` or improved year UX |
| Onboard hub | High | Filter OBC channels |
| Tracker / Data quick lane | High | Existing channel types |
| Search | Medium | Unused SEARCH filters |
| Standings / stats | Out of scope | Not in F1 TV browse API |

### Series picker on open

1. **P0:** Home filter tabs + Settings “Default series”.  
2. **P1:** Optional first-run chooser (remember choice — not every cold start).  
3. **P2:** Deep links `f1tvplayer://series/{uiSeries}`.

Also fix locale bug: type channels via `identifier` (`TRACKER`/`DATA`/`OBC`), not localized title strings **[verified]**.

---

## 3. Live race map

| Option | Rating | Plan |
|--------|--------|------|
| **A. Tracker channel “Map” action** | High | P0 — discovery + transport shortcut |
| **B. Main + Tracker dual surface** | Medium | P1 — device-gated |
| **C. Custom SignalR map (`Position.z`)** | Live med / Replay low | P2 — JWT negotiate spike; delay timing behind video 20–60s |

Screenshot map/tower = broadcast/Tracker graphics, not app UI.

---

## 4. Multi-cam side panel (MultiViewer-style)

### Goal

Main feed left; up to **4 driver OBC cams** stacked vertically on the right; **synced** for live and replay (MultiViewer-like).

```
┌─────────────────────────────┬──────────┐
│                             │  OBC 1   │
│         MAIN FEED           ├──────────┤
│      (audio + focus)        │  OBC 2   │
│                             ├──────────┤
│         F1 styling          │  OBC 3   │
│                             ├──────────┤
│                             │  OBC 4   │
└─────────────────────────────┴──────────┘
```

### UX

- Transport “Multi-cam”; slots pick from OBC list; empty = Add driver.  
- Side audio muted; side quality capped (≤720p/540p).  
- D-pad across slots; OK swap with main (P1); Back exits.  
- Global sync nudge (±250ms); optional per-slot fine tune.  
- Visual: `f1_black_deep` canvas, red focus on active slot, team `hex` number badge.

### Sync (live + replay)

- Master = main position (replay) or live-edge offset (live).  
- Followers: `target = master + calibrationOffsetMs`; drift watchdog ~1s.  
- Propagate seek / pause / play.  
- Probe `EXT-X-PROGRAM-DATE-TIME` for auto align **[assumed]**.  
- Honest bar: ±~0.3–0.5s, not frame-genlock.

### Constraints

| Risk | Mitigation |
|------|------------|
| 2–5 Widevine decoders | Capability probe; maxSlots 4→3→2→1→0 |
| Entitlement / concurrent PLAY | Spike; release unused views |
| HDR × N | Side cams SDR-only; optional disable main UHD in multi-cam |
| Bandwidth | Low rung on sides |

### Multi-cam phases

- **M0 spike:** 2 secure players + entitlement + PDT check.  
- **P1a MVP:** 1–2 synced sides + F1 chrome.  
- **P1b:** Up to 4 slots + presets memory.  
- **P2:** Presets, listen-in on focused tile, Compose shell.

Non-goals: 4× UHD HDR sides; system PiP as the layout; frame-accurate genlock.

---

## 5. Phased delivery (merged)

### P0 — F1 design + discoverability

- [ ] Apply F1 token theme; remove teal / Blue Grey  
- [ ] Modern cards, focus rings, hero, top series tabs  
- [ ] Parse `uiSeries` + `Series`; filter hubs; settings default  
- [ ] `identifier`-based channel typing  
- [ ] Tracker “Map” shortcut; player safe zones  
- [ ] Live row / PAGE probes  
- [ ] **Fence:** no HDR/surface rewrites  

### P1 — Shell depth + multi-cam

- [ ] Side sheets; in-place channel switch  
- [ ] Shows / Docs hubs; optional series chooser  
- [ ] Multi-cam M0 → MVP (1–2) → 4 slots when probe allows  
- [ ] Optional Main+Tracker dual-view  

### P2 — Advanced

- [ ] Compose browse / multi-cam polish  
- [ ] Custom timing map (spike-gated)  
- [ ] Deep links, search, multi-cam presets  

### Global non-goals

Phone UI · official-app pixel clone · fantasy/social · replacing DRM/auth · full live-timing product · coupling design/multi-cam to HDR experiments.

---

## 6. Risks & unknowns

1. Concurrent PLAY / secure decoder limits for multi-cam **[assumed]**  
2. Cross-feed sync without PDT **[assumed]**  
3. `Series` / `uiSeries` completeness on replay payloads **[assumed]**  
4. PAGE / `GROUP_ID` churn **[verified TODO]**  
5. Tracker/OBC sparse for support series **[assumed]**  
6. SignalR JWT vs Ascendon token for custom map **[assumed]**  
7. Brand asset/legal care with F1 red / marks (use colors + original UI, not ripped official assets)

---

## 7. Suggested engineering order

1. **F1 design tokens + card/focus overhaul** (visible win).  
2. **Series filter + badges + default series**.  
3. **Tracker Map shortcut**.  
4. **Multi-cam M0 spike** on real hardware.  
5. **Multi-cam MVP → 4 cams**.  
6. Shows/Docs + side sheets.  
7. Custom map only after JWT spike.

---

## 8. Pointers

| Area | Path |
|------|------|
| Theme today | `res/values/colors.xml`, `styles.xml` |
| API / images | `f1tv/F1TvClient.kt` |
| Channels / OBC | `f1tv/channel.kt` |
| Browse | `HomeFragment`, `SeasonBrowseFragment`, `SessionGridFragment` |
| Playback | `ChannelPlaybackFragment`, `ExoPlayerPlaybackTransportControlGlue` |
| Sync precedent | Custom radio offset + `CustomRadioSyncDialog` |
| BoN plans | worktrees under `/root/.paseo/worktrees/1mv9hn5l/revamp-plan-candidate{,-b,-c}/` |

---

*Single source planning doc for the revamp. Implementation not started.*
