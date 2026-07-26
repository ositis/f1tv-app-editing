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
import fr.groggy.racecontrol.tv.f1tv.F1TvOnboardChannel
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * MultiViewer-style side OBC cams: up to 4 muted SD (≤480p) feeds synced to the main player.
 * Slots that fail to open are dropped so multi-cam only shows reliable feeds.
 */
class MultiCamController(
    private val context: Context,
    private val sideColumn: LinearLayout,
    private val viewingService: ViewingService,
    private val httpDataSourceFactory: HttpDataSource.Factory,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "MultiCamController"
        const val MAX_SLOTS = 4
        private const val MAX_VIDEO_WIDTH = 854
        private const val MAX_VIDEO_HEIGHT = 480
        private const val DRIFT_THRESHOLD_MS = 450L
        private const val DRIFT_POLL_MS = 1_000L
    }

    private data class Slot(
        val channel: F1TvOnboardChannel,
        val player: ExoPlayer,
        val container: FrameLayout
    )

    private val slots = mutableListOf<Slot>()
    private var mainPlayer: ExoPlayer? = null
    private var startJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var active = false

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
                        Log.d(TAG, "Resync ${slot.channel.name} drift=${drift}ms -> $target")
                        slot.player.seekTo(target)
                    }
                }
            }
            handler.postDelayed(this, DRIFT_POLL_MS)
        }
    }

    val isActive: Boolean get() = active

    fun start(
        main: ExoPlayer,
        candidates: List<F1TvOnboardChannel>,
        streamType: Settings.StreamType,
        onResult: (enabledSlots: Int) -> Unit
    ) {
        stop()
        mainPlayer = main
        main.addListener(mainListener)
        sideColumn.removeAllViews()
        sideColumn.visibility = View.VISIBLE
        startJob = scope.launch {
            val opened = mutableListOf<Slot>()
            for (channel in candidates.take(MAX_SLOTS * 2)) {
                if (opened.size >= MAX_SLOTS) break
                val slot = openSlot(channel, streamType, main) ?: continue
                opened += slot
                withContext(Dispatchers.Main) {
                    sideColumn.addView(
                        slot.container,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        )
                    )
                }
            }
            withContext(Dispatchers.Main) {
                slots.clear()
                slots.addAll(opened)
                active = opened.isNotEmpty()
                if (!active) {
                    sideColumn.visibility = View.GONE
                    main.removeListener(mainListener)
                    mainPlayer = null
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
        sideColumn.removeAllViews()
        sideColumn.visibility = View.GONE
        active = false
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
        channel: F1TvOnboardChannel,
        streamType: Settings.StreamType,
        main: ExoPlayer
    ): Slot? = withContext(Dispatchers.IO) {
        try {
            val viewing = viewingService.getViewing(
                channelId = channel.channelId,
                contentId = channel.contentId,
                streamType = streamType,
                preferHdrManifest = false
            )
            withContext(Dispatchers.Main) {
                buildSlot(channel, viewing, main)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Skipping OBC ${channel.name}: ${e.message}")
            null
        }
    }

    private fun buildSlot(
        channel: F1TvOnboardChannel,
        viewing: F1TvViewing,
        main: ExoPlayer
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
                    Log.w(TAG, "Side cam error ${channel.name}: ${error.errorCodeName}")
                    removeFailedSlot(channel.channelId)
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
                text = channel.name
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
            Slot(channel, player, container)
        } catch (e: Exception) {
            Log.w(TAG, "buildSlot failed for ${channel.name}", e)
            null
        }
    }

    private fun removeFailedSlot(channelId: String) {
        val index = slots.indexOfFirst { it.channel.channelId == channelId }
        if (index < 0) return
        val slot = slots.removeAt(index)
        runCatching {
            slot.player.release()
        }
        sideColumn.removeView(slot.container)
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
