# Changelog

## 2.0.3 — 2026-08-22

### Fixed
- **Playback entry** — selecting a session always opens the camera/channel grid instead of skipping straight to a broken auto-play path.
- **Stream stability** — stop releasing the ExoPlayer on transient `onStop` events that were killing live streams mid-session.

## 2.0.2 — 2026-08-22

### Added
- **In-app updates** — Settings → Check for updates downloads and installs new APKs (with unknown-sources permission flow).

## 2.0.1 — 2026-08-22

Major playback and browse fixes for **UgisF1** (`com.ugisf1.tv`).

### Added
- **Shows & docs** hub on the home Explore row (F1 editorial pages 410 / 413).
- Built-in **custom radio** fallbacks when no URL is configured (Grand Prix Radio + F1 live timing).
- User-visible toast when custom radio cannot start.

### Fixed
- **In-player channel switch** — switching feeds no longer exits playback or reopens the camera grid.
- **Race map (Tracker)** switches in-place during live sessions.
- **Multiview layouts** (side / quad) restore after an in-player channel change when they were active.
- **Session channel grid** shown correctly for multi-channel sessions (unless bypass is enabled).
- **Series filter** (F1 / F2 / F3 / …) re-applies when returning from Settings without stale F1-only rows.

### Changed
- Version line reset to **2.x** for UgisF1 releases (supersedes legacy 1.2.x sideload builds).
- Custom radio “live sessions only” preference defaults to **off**.

## Prior history

See git history on `main` and upstream [st14n/race-control-tv](https://github.com/st14n/race-control-tv) for earlier lineage (1.0.x / 1.2.x).
