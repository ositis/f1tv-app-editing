package fr.groggy.racecontrol.tv.ui.info

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.f1.JolpicaClient
import fr.groggy.racecontrol.tv.f1.JolpicaRace
import kotlinx.coroutines.launch
import org.threeten.bp.Instant
import javax.inject.Inject

@AndroidEntryPoint
class RaceCalendarActivity : FragmentActivity(R.layout.activity_info_browse) {
    companion object {
        fun intent(context: Context) = Intent(context, RaceCalendarActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, RaceCalendarFragment())
            }
        }
    }
}

@AndroidEntryPoint
class RaceCalendarFragment : BrowseSupportFragment() {
    @Inject lateinit var jolpicaClient: JolpicaClient

    private val rows = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.menu_race_calendar)
        brandColor = ContextCompat.getColor(requireContext(), R.color.f1_red)
        headersState = HEADERS_ENABLED
        adapter = rows
        lifecycleScope.launch {
            runCatching { jolpicaClient.currentSeasonSchedule() }
                .onSuccess { bind(it) }
                .onFailure {
                    rows.add(
                        ListRow(
                            HeaderItem(getString(R.string.info_load_error)),
                            ArrayObjectAdapter(InfoCardPresenter()).apply {
                                add(
                                    InfoRowItem(
                                        "err",
                                        getString(R.string.info_load_error),
                                        it.message.orEmpty()
                                    )
                                )
                            }
                        )
                    )
                }
        }
    }

    private fun bind(races: List<JolpicaRace>) {
        val now = Instant.now()
        val upcoming = races.filter {
            val t = AmsterdamTime.parseUtc(it.date, it.time)
            t == null || !t.isBefore(now)
        }
        val past = races.filterNot { upcoming.contains(it) }
        rows.clear()
        if (upcoming.isNotEmpty()) {
            rows.add(raceRow(getString(R.string.calendar_upcoming), upcoming))
        }
        if (past.isNotEmpty()) {
            rows.add(raceRow(getString(R.string.calendar_completed), past.reversed()))
        }
    }

    private fun raceRow(header: String, races: List<JolpicaRace>): ListRow {
        val adapter = ArrayObjectAdapter(InfoCardPresenter())
        races.forEach { race ->
            val place = listOfNotNull(
                race.circuit?.location?.locality,
                race.circuit?.location?.country
            ).joinToString(", ")
            val sessions = buildString {
                append(AmsterdamTime.formatUtc(race.date, race.time))
                race.firstPractice?.let {
                    append(" · FP1 ")
                    append(AmsterdamTime.formatUtc(it.date, it.time))
                }
                race.qualifying?.let {
                    append(" · Q ")
                    append(AmsterdamTime.formatUtc(it.date, it.time))
                }
                race.sprint?.let {
                    append(" · Sprint ")
                    append(AmsterdamTime.formatUtc(it.date, it.time))
                }
            }
            adapter.add(
                InfoRowItem(
                    id = "${race.season}-${race.round}",
                    title = "R${race.round} · ${race.raceName}",
                    subtitle = listOf(place, race.circuit?.circuitName, sessions)
                        .filter { !it.isNullOrBlank() }
                        .joinToString("\n")
                )
            )
        }
        return ListRow(HeaderItem(header), adapter)
    }
}
