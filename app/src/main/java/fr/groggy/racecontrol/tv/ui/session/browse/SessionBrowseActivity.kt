package fr.groggy.racecontrol.tv.ui.session.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.ui.channel.playback.ChannelPlaybackActivity
import kotlinx.coroutines.launch
import org.threeten.bp.Year
import javax.inject.Inject

@AndroidEntryPoint
class SessionBrowseActivity : FragmentActivity() {
    companion object {
        private const val TAG = "SessionBrowseActivity"

        fun intent(
            context: Context,
            sessionId: String,
            contentId: String,
            seasonYear: Int = Year.now().value
        ): Intent {
            val intent = Intent(context, SessionBrowseActivity::class.java)
            SessionGridFragment.putContentId(intent, contentId)
            SessionGridFragment.putSessionId(intent, sessionId)
            SessionGridFragment.putSeasonYear(intent, seasonYear)
            return intent
        }
    }

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_browse)

        val contentId = SessionGridFragment.findContentId(this)
            ?: return finish()
        val sessionId = SessionGridFragment.findSessionId(this)
            ?: return finish()
        val seasonYear = SessionGridFragment.findSeasonYear(this)
        val viewModel: SessionBrowseViewModel by viewModels()

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                when (val session = viewModel.sessionLoaded(sessionId, contentId)) {
                    is SingleChannelSession -> {
                        startActivity(
                            ChannelPlaybackActivity.intent(
                                this@SessionBrowseActivity,
                                sessionId,
                                session.channel?.value,
                                session.contentId,
                                session.isLiveSession,
                                seasonYear
                            )
                        )
                        finish()
                    }
                    is MultiChannelsSession -> {
                        if (settingsRepository.getCurrent().bypassChannelSelection) {
                            Log.d(TAG, "Bypass channel selection — opening playback")
                            startActivity(
                                ChannelPlaybackActivity.intent(
                                    this@SessionBrowseActivity,
                                    sessionId,
                                    null,
                                    session.contentId,
                                    session.isLiveSession,
                                    seasonYear
                                )
                            )
                            finish()
                        } else {
                            Log.d(TAG, "Showing channel grid (${session.channels.size} channels)")
                            supportFragmentManager.beginTransaction()
                                .replace(R.id.fragment_container, SessionGridFragment())
                                .commitNowAllowingStateLoss()
                        }
                    }
                }
            }
        }
    }
}
