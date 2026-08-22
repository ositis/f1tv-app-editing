package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.ViewingService
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Secondary F1TV feed for Multiview (OBC or basic channel). */
data class MultiCamFeed(
    val channelId: String,
    val contentId: String,
    val label: String
)

enum class RaceLayoutMode {
    FULLSCREEN,
    SIDE,
    QUAD
}

/**
 * Multiview: Side strip (up to 3 muted ≤480p) or Quad grid (3 secondary cells beside main TL).
 */
class MultiCamController(
    private val context: Context,
    private val sideColumn: LinearLayout,
    private val quadCells: List<FrameLayout>,
    private val viewingService: ViewingService,
    private val httpDataSourceFactory: HttpDataSource.Factory,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MultiCamController"
        const val MAX_SIDE_SLOTS = 3
        const val MAX_QUAD_SLOTS = 3
        private const val MAX_VIDEO_WIDTH = 854
        private const val MAX_VIDEO_HEIGHT = 480
        private const val DRIFT_THRESHOLD_MS = 450L
        private const val DRIFT_POLL_MS = 1_000L
    }

    private data class Slot(
        val feed: MultiCamFeed,
        val player: ExoPlayer,
        val container: FrameLayout,
        val host: ViewGroup
    )

    private val slots = mutableListOf<Slot>()
    private var mainPlayer: ExoPlayer? = null
    private var startJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var mode: RaceLayoutMode = RaceLayoutMode.FULLSCREEN

    private val mainListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            syncPlayWhenReady(playWhenReady)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                seekSidesToMain("main-seek")
            }
        }
    }

    private val driftWatchdog = object : Runnable {
        override fun run() {
            if (!active) return
            val main = mainPlayer
            if (main != null && main.playbackState != Player.STATE_IDLE) {
                val target = main.currentPosition
                slots.forEach { slot ->
                    val drift = abs(slot.player.currentPosition - target)
                    if (drift > DRIFT_THRESHOLD_MS &&
                        slot.player.playbackState == Player.STATE_READY
                    ) {
                        Log.d(TAG, "Resync ${slot.feed.label} drift=${drift}ms -> $target")
                        slot.player.seekTo(target)
                    }
                }
            }
            handler.postDelayed(this, DRIFT_POLL_MS)
        }
    }

    val isActive: Boolean get() = active
    val currentMode: RaceLayoutMode get() = mode

    fun startSide(
        main: ExoPlayer,
        candidates: List<MultiCamFeed>,
        streamType: Settings.StreamType,
        onResult: (enabledSlots: Int) -> Unit
    ) {
        start(
            mode = RaceLayoutMode.SIDE,
            main = main,
            candidates = candidates,
            maxSlots = MAX_SIDE_SLOTS,
            hosts = emptyList(),
            streamType = streamType,
            onResult = onResult
        )
    }

    fun startQuad(
        main: ExoPlayer,
        candidates: List<MultiCamFeed>,
        streamType: Settings.StreamType,
        onResult: (enabledSlots: Int) -> Unit
    ) {
        start(
            mode = RaceLayoutMode.QUAD,
            main = main,
            candidates = candidates,
            maxSlots = MAX_QUAD_SLOTS.coerceAtMost(quadCells.size),
            hosts = quadCells,
            streamType = streamType,
            onResult = onResult
        )
    }

    private fun start(
        mode: RaceLayoutMode,
        main: ExoPlayer,
        candidates: List<MultiCamFeed>,
        maxSlots: Int,
        hosts: List<FrameLayout>,
        streamType: Settings.StreamType,
        onResult: (enabledSlots: Int) -> Unit
    ) {
        stop()
        this.mode = mode
        mainPlayer = main
        main.addListener(mainListener)
        sideColumn.removeAllViews()
        if (mode == RaceLayoutMode.SIDE) {
            sideColumn.visibility = View.VISIBLE
        }
        startJob = scope.launch {
            val opened = mutableListOf<Slot>()
            for (feed in candidates.take(maxSlots * 2)) {
                if (opened.size >= maxSlots) break
                val host: ViewGroup = when (mode) {
                    RaceLayoutMode.SIDE -> sideColumn
                    RaceLayoutMode.QUAD -> hosts.getOrNull(opened.size) ?: break
                    RaceLayoutMode.FULLSCREEN -> break
                }
                val slot = openSlot(feed, streamType, main, host) ?: continue
                opened += slot
                withContext(Dispatchers.Main) {
                    if (mode == RaceLayoutMode.SIDE) {
                        sideColumn.addView(
                            slot.container,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                0,
                                1f
                            )
                        )
                    } else {
                        host.removeAllViews()
                        host.addView(
                            slot.container,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        host.visibility = View.VISIBLE
                    }
                }
            }
            withContext(Dispatchers.Main) {
                slots.clear()
                slots.addAll(opened)
                active = opened.isNotEmpty()
                if (!active) {
                    clearHosts()
                    main.removeListener(mainListener)
                    mainPlayer = null
                    this@MultiCamController.mode = RaceLayoutMode.FULLSCREEN
                } else {
                    seekSidesToMain("start")
                    syncPlayWhenReady(main.playWhenReady)
                    handler.post(driftWatchdog)
                }
                onResult(opened.size)
            }
        }
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        handler.removeCallbacks(driftWatchdog)
        mainPlayer?.removeListener(mainListener)
        mainPlayer = null
        slots.forEach { slot ->
            runCatching {
                slot.player.clearVideoSurface()
                slot.player.release()
            }
        }
        slots.clear()
        clearHosts()
        active = false
        mode = RaceLayoutMode.FULLSCREEN
    }

    private fun clearHosts() {
        sideColumn.removeAllViews()
        sideColumn.visibility = View.GONE
        quadCells.forEach { cell ->
            cell.removeAllViews()
            cell.visibility = View.GONE
        }
    }

    fun onMainPaused() = syncPlayWhenReady(false)
    fun onMainResumed() = syncPlayWhenReady(true)

    private fun syncPlayWhenReady(playWhenReady: Boolean) {
        slots.forEach { slot ->
            slot.player.playWhenReady = playWhenReady
            if (playWhenReady) slot.player.play() else slot.player.pause()
        }
    }

    private fun seekSidesToMain(reason: String) {
        val main = mainPlayer ?: return
        val target = main.currentPosition
        Log.d(TAG, "seekSidesToMain reason=$reason target=$target")
        slots.forEach { it.player.seekTo(target) }
    }

    private suspend fun openSlot(
        feed: MultiCamFeed,
        streamType: Settings.StreamType,
        main: ExoPlayer,
        host: ViewGroup
    ): Slot? = withContext(Dispatchers.IO) {
        try {
            val viewing = viewingService.getViewing(
                channelId = feed.channelId,
                contentId = feed.contentId,
                streamType = streamType,
                preferHdrManifest = false
            )
            withContext(Dispatchers.Main) {
                buildSlot(feed, viewing, main, host)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Skipping feed ${feed.label}: ${e.message}")
            null
        }
    }

    private fun buildSlot(
        feed: MultiCamFeed,
        viewing: F1TvViewing,
        main: ExoPlayer,
        host: ViewGroup
    ): Slot? {
        return try {
            val trackSelector = DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setMaxVideoSize(MAX_VIDEO_WIDTH, MAX_VIDEO_HEIGHT)
                        .setForceHighestSupportedBitrate(true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                )
            }
            val player = ExoPlayer.Builder(context)
                .setTrackSelector(trackSelector)
                .build()
            player.volume = 0f
            player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            player.playWhenReady = main.playWhenReady
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "Side cam error ${feed.label}: ${error.errorCodeName}")
                    removeFailedSlot(feed.channelId)
                }
            })

            val surface = SurfaceView(context).apply {
                keepScreenOn = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            val label = TextView(context).apply {
                text = feed.label
                setTextColor(ContextCompat.getColor(context, R.color.f1_white))
                textSize = 12f
                setBackgroundColor(0x990A0A0F.toInt())
                setPadding(12, 6, 12, 6)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                )
            }
            val container = FrameLayout(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.f1_divider))
                setPadding(2, 2, 2, 2)
                addView(surface)
                addView(label)
            }
            player.setVideoSurfaceView(surface)
            val mediaItem = MediaSourceItemFactory.newMediaItem(viewing)
            val source = createMediaSource(viewing.url.toString(), viewing.streamType, mediaItem)
            player.setMediaSource(source)
            player.prepare()
            player.seekTo(main.currentPosition)
            Slot(feed, player, container, host)
        } catch (e: Exception) {
            Log.w(TAG, "buildSlot failed for ${feed.label}", e)
            null
        }
    }

    private fun removeFailedSlot(channelId: String) {
        val index = slots.indexOfFirst { it.feed.channelId == channelId }
        if (index < 0) return
        val slot = slots.removeAt(index)
        runCatching {
            slot.player.release()
        }
        when (mode) {
            RaceLayoutMode.SIDE -> sideColumn.removeView(slot.container)
            RaceLayoutMode.QUAD -> {
                slot.host.removeAllViews()
                slot.host.visibility = View.GONE
            }
            RaceLayoutMode.FULLSCREEN -> Unit
        }
        if (slots.isEmpty()) {
            stop()
        }
    }

    private fun createMediaSource(
        urlString: String,
        streamType: String?,
        mediaItem: androidx.media3.common.MediaItem
    ): androidx.media3.exoplayer.source.MediaSource {
        val isDash = streamType?.contains("DASH", ignoreCase = true) == true ||
            urlString.contains(".mpd", ignoreCase = true)
        return if (isDash) {
            DashMediaSource.Factory(httpDataSourceFactory).createMediaSource(mediaItem)
        } else {
            HlsMediaSource.Factory(httpDataSourceFactory).createMediaSource(mediaItem)
        }
    }
}
