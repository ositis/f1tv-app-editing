# 1.0.4 — Fullscreen zoom + quality pin

Based on `st14n/race-control-tv` (June 2026 Media3 / 4K HDR fork).

## Fixes

### Live ABR zoom in/out
Leanback `VideoSupportFragment.onVideoSizeChanged()` was resizing the
`SurfaceView` whenever ABR changed coded width/height. The surface is now
locked to `MATCH_PARENT` on the host viewport, and ExoPlayer uses
`VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING` so the picture stays
edge-to-edge without layout jumps.

### Manual quality jumping back to Auto
Selecting a rung now pins both `minVideoSize` and `maxVideoSize`. Toggling
main-player audio (e.g. custom radio) no longer rebuilds track parameters
from season defaults, which previously wiped the pin.
