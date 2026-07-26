package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.ViewingService
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.f1tv.F1TvBasicChannel
import fr.groggy.racecontrol.tv.f1tv.F1TvBasicChannelType
import fr.groggy.racecontrol.tv.f1tv.F1TvClient
import fr.groggy.racecontrol.tv.f1tv.F1TvOnboardChannel
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing
import fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr.ProtectedHdrRendererRouter
import fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr.ProtectedHdrStreamClassifier
import fr.groggy.racecontrol.tv.ui.player.ChannelSelectionDialog
import fr.groggy.racecontrol.tv.ui.session.browse.Channel
import fr.groggy.racecontrol.tv.ui.signin.SignInActivity
import fr.groggy.racecontrol.tv.utils.DeviceInfo
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChannelPlaybackActivity : FragmentActivity(R.layout.activity_channel_playback),
    ChannelSelectionDialog.ChannelManagerListener {

    @Inject internal lateinit var viewingService: ViewingService
    @Inject internal lateinit var settingsRepository: SettingsRepository
    @Inject internal lateinit var f1TvClient: F1TvClient
    @Inject internal lateinit var httpDataSourceFactory: HttpDataSource.Factory

    /** The [F1TvViewing] currently loaded into the player, used for 4K fallback detection. */
    private var currentViewing: F1TvViewing? = null
    private var currentAttemptUsesProtectedHlgGraph: Boolean = false
    private var retriedDirectMedia3HdrSurface: Boolean = false
    private var multiCamController: MultiCamController? = null
    private var cachedObcChannels: List<F1TvOnboardChannel> = emptyList()
    private var multiCamProbed = false

    private val playbackTouchGestureDetector by lazy(LazyThreadSafetyMode.NONE) {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    return activePlaybackFragment()?.shouldHandleTouchOverlayGesture() == true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    return activePlaybackFragment()?.showControlsOverlayFromTouch() == true
                }
            }
        )
    }

    private val preferHdrManifestForDevice: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        val preferHdrManifest = !settingsRepository.getCurrent().disableUhdManifests &&
            DeviceInfo.shouldRequestHdrManifest(this) &&
            allowsUhdPlaybackForSeason()
        Log.i(TAG, "preferHdrManifestForDevice=$preferHdrManifest")
        preferHdrManifest
    }

    companion object {
        private val TAG = ChannelPlaybackActivity::class.simpleName

        fun intent(
            context: Context,
            sessionId: String,
            channelId: String?,
            contentId: String,
            isLiveSession: Boolean,
            seasonYear: Int
        ): Intent {
            val intent = Intent(context, ChannelPlaybackActivity::class.java)
            // Pass IDs needed to *fetch* viewing details initially
            ChannelPlaybackFragment.putChannelId(intent, channelId)
            ChannelPlaybackFragment.putContentId(intent, contentId)
            ChannelPlaybackFragment.putSessionId(intent, sessionId)
            ChannelPlaybackFragment.putIsLiveSession(intent, isLiveSession)
            ChannelPlaybackFragment.putSeasonYear(intent, seasonYear)
            return intent
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = findViewById<LinearLayout>(R.id.multi_cam_column)
        multiCamController = MultiCamController(
            context = this,
            sideColumn = column,
            viewingService = viewingService,
            httpDataSourceFactory = httpDataSourceFactory,
            scope = lifecycleScope
        )
        lifecycleScope.launch {
            attachViewingIfNeeded(Settings.StreamType.HLS, preferHdrManifest = preferHdrManifestForDevice)
            probeMultiCamAvailability()
        }
    }

    override fun onDestroy() {
        multiCamController?.stop()
        multiCamController = null
        setMultiCamColumnExpanded(false)
        super.onDestroy()
    }

    fun isMultiCamAvailable(): Boolean = multiCamProbed && cachedObcChannels.isNotEmpty()

    fun isMultiCamActive(): Boolean = multiCamController?.isActive == true

    fun stopMultiCamSilent() {
        multiCamController?.stop()
        setMultiCamColumnExpanded(false)
    }

    fun toggleMultiCam(mainPlayer: ExoPlayer?) {
        val controller = multiCamController ?: return
        if (controller.isActive) {
            stopMultiCamSilent()
            Toast.makeText(this, R.string.player_multicam_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        if (!isMultiCamAvailable() || mainPlayer == null) {
            Toast.makeText(this, R.string.player_multicam_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        setMultiCamColumnExpanded(true)
        controller.start(
            main = mainPlayer,
            candidates = cachedObcChannels,
            streamType = Settings.StreamType.HLS
        ) { count ->
            if (count <= 0) {
                setMultiCamColumnExpanded(false)
                Toast.makeText(this, R.string.player_multicam_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.player_multicam_enabled, count),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun notifyMultiCamMainPaused() {
        multiCamController?.onMainPaused()
    }

    fun notifyMultiCamMainResumed() {
        multiCamController?.onMainResumed()
    }

    private fun setMultiCamColumnExpanded(expanded: Boolean) {
        val column = findViewById<LinearLayout>(R.id.multi_cam_column) ?: return
        val lp = column.layoutParams as LinearLayout.LayoutParams
        if (expanded) {
            lp.width = 0
            lp.weight = 0.28f
            column.visibility = View.VISIBLE
        } else {
            lp.width = 0
            lp.weight = 0f
            column.visibility = View.GONE
        }
        column.layoutParams = lp
        val mainPane = findViewById<ViewGroup>(R.id.main_playback_pane)
        val mainLp = mainPane.layoutParams as LinearLayout.LayoutParams
        mainLp.weight = if (expanded) 0.72f else 1f
        mainPane.layoutParams = mainLp
    }

    private suspend fun probeMultiCamAvailability() {
        val contentId = ChannelPlaybackFragment.findContentId(this) ?: return
        val channels = runCatching { f1TvClient.getChannels(contentId) }.getOrElse { emptyList() }
        cachedObcChannels = channels.filterIsInstance<F1TvOnboardChannel>()
        multiCamProbed = true
        Log.i(TAG, "Multi-cam probe: ${cachedObcChannels.size} OBC channels")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (supportFragmentManager.findFragmentByTag(ChannelPlaybackFragment.TAG) as? ChannelPlaybackFragment)
            ?.onHostConfigurationChanged()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val playbackFragment = activePlaybackFragment()
        if (playbackFragment?.shouldHandleTouchOverlayGesture() == true &&
            playbackTouchGestureDetector.onTouchEvent(event)
        ) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onSwitchChannel(channel: Channel) {
        val sessionId = ChannelPlaybackFragment.findSessionId(this) ?: return
        startActivity(
            intent(
                this,
                sessionId,
                channel.id?.value,
                channel.contentId,
                ChannelPlaybackFragment.findIsLiveSession(this),
                ChannelPlaybackFragment.findSeasonYear(this)
            )
        )
        finish()
    }

    /**
     * Switch to the official Tracker feed (race map / timing tower video).
     */
    fun switchToRaceMap() {
        val sessionId = ChannelPlaybackFragment.findSessionId(this) ?: return
        val contentId = ChannelPlaybackFragment.findContentId(this) ?: return
        lifecycleScope.launch {
            val tracker = runCatching {
                f1TvClient.getChannels(contentId)
                    .filterIsInstance<F1TvBasicChannel>()
                    .firstOrNull { it.type == F1TvBasicChannelType.Tracker }
            }.getOrNull()
            if (tracker?.channelId == null) {
                Toast.makeText(
                    this@ChannelPlaybackActivity,
                    R.string.player_map_unavailable,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            if (tracker.channelId == ChannelPlaybackFragment.findChannelId(this@ChannelPlaybackActivity)) {
                return@launch
            }
            startActivity(
                intent(
                    this@ChannelPlaybackActivity,
                    sessionId,
                    tracker.channelId,
                    tracker.contentId,
                    ChannelPlaybackFragment.findIsLiveSession(this@ChannelPlaybackActivity),
                    ChannelPlaybackFragment.findSeasonYear(this@ChannelPlaybackActivity)
                )
            )
            finish()
        }
    }

    private fun allowsUhdPlaybackForSeason(): Boolean {
        return ChannelPlaybackFragment.findSeasonYear(this) >= 2026
    }

    private suspend fun attachViewingIfNeeded(
        streamType: Settings.StreamType,
        preferHdrManifest: Boolean = true
    ) {
        if (supportFragmentManager.findFragmentByTag(ChannelPlaybackFragment.TAG) != null) {
            Log.d(TAG, "Playback fragment already exists — skipping fetch")
            return
        }
        val contentId = ChannelPlaybackFragment.findContentId(this) ?: return finish()
        val channelId = resolvePreferredChannelId(
            contentId = contentId,
            requestedChannelId = ChannelPlaybackFragment.findChannelId(this)
        )
        Log.i(
            TAG,
            "Fetching viewing for contentId=$contentId channelId=$channelId " +
                "sessionId=${ChannelPlaybackFragment.findSessionId(this)} " +
                "isLiveSession=${ChannelPlaybackFragment.findIsLiveSession(this)} " +
                "seasonYear=${ChannelPlaybackFragment.findSeasonYear(this)} " +
                "preferHdrManifest=$preferHdrManifest"
        )
        try {
            var viewing = viewingService.getViewing(channelId, contentId, streamType, preferHdrManifest)
            Log.i(
                TAG,
                "Main viewing: url=${viewing.url} platform=${viewing.platform} " +
                    "type=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType}"
            )

            // Fetch PRES / F1Live channel for external audio if the user wants it
            // and they are not already watching the PRES channel itself
            val settings = settingsRepository.getCurrent()
            if (needsHdrEmbeddedAudioWorkaround(viewing)) {
                viewing = tryAttachStandardAudioCompanion(viewing, contentId, channelId, streamType)
            }
            if (settings.useExternalAudio) {
                val externalAudioPreferHdr = preferHdrManifest && !viewing.externalAudioRequired
                viewing = tryAttachExternalAudio(viewing, contentId, channelId, streamType, externalAudioPreferHdr)
            }

            onViewingCreated(viewing)
        } catch (e: ViewingService.TokenExpiredException) {
            Log.e(TAG, "Token expired", e)
            handleError(R.string.unable_to_play_video_session_expired) {
                startActivity(SignInActivity.intentClearTask(this))
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching viewing", e)
            handleError(R.string.unable_to_play_video_message, ::finish)
        }
    }

    private suspend fun resolvePreferredChannelId(contentId: String, requestedChannelId: String?): String? {
        if (requestedChannelId != null) {
            return requestedChannelId
        }
        return runCatching {
            f1TvClient.getChannels(contentId)
                .filterIsInstance<F1TvBasicChannel>()
                .firstOrNull { it.type == F1TvBasicChannelType.Wif }
                ?.channelId
        }.onFailure {
            Log.w(TAG, "Failed to resolve preferred International channel: ${it.message}")
        }.getOrNull()
    }

    /**
     * Non-fatal: attempts to find the PRES/F1Live channel and populate [F1TvViewing]
     * with external audio info. Returns the original [viewing] unchanged on any failure.
     */
    private suspend fun tryAttachExternalAudio(
        viewing: F1TvViewing,
        contentId: String,
        currentChannelId: String?,
        streamType: Settings.StreamType,
        preferHdrManifest: Boolean
    ): F1TvViewing = try {
        val channels = f1TvClient.getChannels(contentId)
        val presChannel = channels
            .filterIsInstance<F1TvBasicChannel>()
            .firstOrNull { it.type == F1TvBasicChannelType.F1Live }
        if (presChannel == null) {
            Log.d(TAG, "No PRES/F1Live channel found for contentId=$contentId")
            return viewing
        }
        if (presChannel.channelId == currentChannelId) {
            Log.d(TAG, "Already watching PRES channel — skipping external audio")
            return viewing
        }
        Log.i(TAG, "Fetching PRES audio from channelId=${presChannel.channelId}")
        val presViewing = viewingService.getViewing(
            presChannel.channelId,
            presChannel.contentId,
            streamType,
            preferHdrManifest
        )
        viewing.copy(
            externalAudioUri = presViewing.url,
            externalAudioStreamType = presViewing.streamType,
            externalAudioLaURL = presViewing.laURL,
            externalAudioPlayApiVersion = presViewing.playApiVersion,
            externalAudioEntitlementtoken = presViewing.entitlementtoken,
            externalAudioContentId = presViewing.contentId,
            externalAudioChannelId = presViewing.channelId,
            externalAudioRequired = viewing.externalAudioRequired
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to fetch PRES audio (non-fatal): ${e.message}")
        viewing
    }

    /**
     * F1's current UHD/HDR embedded audio can be unreliable in Media3 on Android TV.
     * Keep the UHD/HDR video and pair it with a same-channel standard audio companion.
     */
    private suspend fun tryAttachStandardAudioCompanion(
        viewing: F1TvViewing,
        contentId: String,
        currentChannelId: String?,
        streamType: Settings.StreamType
    ): F1TvViewing = try {
        Log.i(
            TAG,
            "Fetching standard same-channel audio companion for UHD/HDR playback " +
                "contentId=$contentId channelId=$currentChannelId"
        )
        val audioViewing = viewingService.getViewing(
            currentChannelId,
            contentId,
            Settings.StreamType.HLS,
            preferHdrManifest = false
        )
        Log.i(
            TAG,
            "Attached standard audio companion for UHD/HDR video-only playback " +
                "url=${audioViewing.url} type=${audioViewing.streamType} laUrl=${audioViewing.laURL} " +
                "dash=${looksLikeDash(audioViewing.streamType, audioViewing.url.toString())}"
        )
        viewing.copy(
            externalAudioUri = audioViewing.url,
            externalAudioStreamType = audioViewing.streamType,
            externalAudioLaURL = audioViewing.laURL,
            externalAudioPlayApiVersion = audioViewing.playApiVersion,
            externalAudioEntitlementtoken = audioViewing.entitlementtoken,
            externalAudioContentId = audioViewing.contentId,
            externalAudioChannelId = audioViewing.channelId,
            externalAudioRequired = true
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to fetch standard audio companion (non-fatal): ${e.message}")
        viewing
    }

    private fun needsHdrEmbeddedAudioWorkaround(viewing: F1TvViewing): Boolean {
        return ProtectedHdrStreamClassifier.looksLikeHdrUhdWidevine(viewing)
    }

    private fun onViewingCreated(viewing: F1TvViewing) {
        currentViewing = viewing
        currentAttemptUsesProtectedHlgGraph = false
        retriedDirectMedia3HdrSurface = false
        Log.d(
            TAG,
            "Proceeding to create player with " +
                "contentId=${viewing.contentId} channelId=${viewing.channelId} " +
                "platform=${viewing.platform} playApiVersion=${viewing.playApiVersion} " +
                "streamType=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType} " +
                "url=${viewing.url}"
        )
        if (settingsRepository.getCurrent().openWithExternalPlayer) {
            Log.d(TAG, "Opening with external player.")
            openWithExternalPlayer(viewing)
        } else {
            val protectedHdrDecision = ProtectedHdrRendererRouter.decide(viewing)
            if (protectedHdrDecision.shouldUseProtectedRenderer) {
                Log.i(TAG, "Opening with protected HDR renderer reason=${protectedHdrDecision.reason}")
                openWithProtectedHdrRenderer(viewing)
                return
            }
            Log.i(
                TAG,
                "Protected HDR renderer unavailable; falling back to Media3 internal player " +
                    "reason=${protectedHdrDecision.reason}"
            )
            Log.d(TAG, "Opening with internal player.")
            openWithInternalPlayer(viewing)
        }
    }

    private fun activePlaybackFragment(): ChannelPlaybackFragment? {
        return supportFragmentManager.findFragmentByTag(ChannelPlaybackFragment.TAG) as? ChannelPlaybackFragment
    }

    private fun openWithExternalPlayer(viewing: F1TvViewing) {
        supportFragmentManager.commit {
            replace(R.id.fragment_container, OpenedWithExternalPlayerFragment(), ChannelPlaybackFragment.TAG)
            setReorderingAllowed(true)
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(viewing.url, "video/*") // Use the final URL
            Log.i(TAG, "Starting external player intent for URL: ${viewing.url}")
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No external player found.", e)
            handleError(R.string.unable_to_open_with_external_player, ::finish)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening external player", e)
            handleError(R.string.unable_to_open_with_external_player, ::finish)
        }
    }

    private fun openWithInternalPlayer(
        viewing: F1TvViewing,
        forceDirectMedia3HdrSurface: Boolean = false,
        usesProtectedHlgGraph: Boolean = false,
        forceProtectedHlgGraph: Boolean = false
    ) {
        currentAttemptUsesProtectedHlgGraph = usesProtectedHlgGraph
        Log.d(
            TAG,
            "Committing internal player fragment " +
                "forceDirectMedia3HdrSurface=$forceDirectMedia3HdrSurface " +
                "usesProtectedHlgGraph=$usesProtectedHlgGraph " +
                "forceProtectedHlgGraph=$forceProtectedHlgGraph "
        )
        supportFragmentManager.commit {
            replace(
                R.id.fragment_container,
                ChannelPlaybackFragment.newInstance(
                    viewing,
                    forceDirectMedia3HdrSurface = forceDirectMedia3HdrSurface,
                    forceProtectedHlgGraph = forceProtectedHlgGraph
                ),
                ChannelPlaybackFragment.TAG
            )
            setReorderingAllowed(true)
        }
    }

    private fun openWithProtectedHdrRenderer(viewing: F1TvViewing) {
        Log.i(
            TAG,
            "Committing Media3 protected HLG graph renderer " +
                "streamType=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType}"
        )
        openWithInternalPlayer(
            viewing = viewing,
            usesProtectedHlgGraph = true,
            forceProtectedHlgGraph = true
        )
    }

    private fun handleError(@StringRes errorMessage: Int, cancelAction: () -> Unit) {
        // Ensure this runs on the main thread
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                try {
                    // Use AppCompat AlertDialog
                    AlertDialog.Builder(this)
                        .setMessage(errorMessage)
                        .setPositiveButton(android.R.string.ok) { dialog, _ ->
                            dialog.dismiss()
                            cancelAction.invoke()
                        }
                        .setCancelable(false) // Prevent dismissal on back press during error
                        .show()
                } catch (e: Exception) {
                    // Catch potential exceptions during dialog show (like theme issues)
                    Log.e(TAG, "Error showing AlertDialog", e)
                    // Fallback or just finish
                    cancelAction.invoke()
                }
            } else {
                Log.w(TAG, "Activity finishing, not showing error dialog.")
            }
        }
    }

    /**
     * Called by [ChannelPlaybackFragment] when ExoPlayer reports an unrecoverable error.
     */
    fun playerError() {
        val failedViewing = currentViewing
        val triedHdrManifest = failedViewing?.let {
            ProtectedHdrStreamClassifier.looksLikeHdrUhdWidevine(it) ||
                looksLikeUhdOrHdr(it.streamType) ||
                looksLikeUhdOrHdr(it.requestedOverrideStreamType)
        } == true

        Log.e(
            TAG,
            "Player error. " +
                "contentId=${failedViewing?.contentId} " +
                "channelId=${failedViewing?.channelId} " +
                "platform=${failedViewing?.platform} " +
                "streamType=${failedViewing?.streamType} " +
                "requestedOverride=${failedViewing?.requestedOverrideStreamType} " +
                "playApiVersion=${failedViewing?.playApiVersion} " +
                "laUrl=${failedViewing?.laURL} " +
                "isLiveSession=${ChannelPlaybackFragment.findIsLiveSession(this)} " +
                "seasonYear=${ChannelPlaybackFragment.findSeasonYear(this)} " +
                "triedHdrManifest=$triedHdrManifest"
        )
        if (
            failedViewing != null &&
            triedHdrManifest &&
            currentAttemptUsesProtectedHlgGraph &&
            !retriedDirectMedia3HdrSurface &&
            !isFinishing &&
            !isDestroyed
        ) {
            retriedDirectMedia3HdrSurface = true
            currentAttemptUsesProtectedHlgGraph = false
            Log.i(
                TAG,
                "HDR/UHD protected HLG graph failed - retrying same HDR manifest with direct Media3 surface fallback"
            )
            openWithInternalPlayer(failedViewing, forceDirectMedia3HdrSurface = true)
            return
        }

        currentViewing = null

        if (triedHdrManifest && !isFinishing && !isDestroyed) {
            Log.i(TAG, "HDR/UHD manifest failed - retrying with standard HLS/SDR stream")
            val fragment = supportFragmentManager.findFragmentByTag(ChannelPlaybackFragment.TAG)
            if (fragment != null) {
                supportFragmentManager.commit {
                    remove(fragment)
                    runOnCommit {
                        lifecycleScope.launch {
                            attachViewingIfNeeded(Settings.StreamType.HLS, preferHdrManifest = false)
                        }
                    }
                }
                return
            }
        }

        handleError(R.string.unable_to_play_video_message, ::finish)
    }

    private fun looksLikeDash(streamType: String?, url: String): Boolean {
        return streamType?.contains("DASH", ignoreCase = true) == true ||
            url.contains(".mpd", ignoreCase = true)
    }

    private fun looksLikeUhdOrHdr(streamType: String?): Boolean {
        val normalized = streamType?.uppercase() ?: return false
        return "UHD" in normalized || "2160" in normalized || "HDR" in normalized
    }
}
