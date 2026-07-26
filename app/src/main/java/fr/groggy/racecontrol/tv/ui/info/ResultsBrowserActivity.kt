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
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.f1.JolpicaClient
import fr.groggy.racecontrol.tv.f1.JolpicaRace
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ResultsBrowserActivity : FragmentActivity(R.layout.activity_info_browse) {
    companion object {
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_ROUND = "round"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context) = Intent(context, ResultsBrowserActivity::class.java)

        fun resultsIntent(
            context: Context,
            season: String,
            round: String,
            title: String
        ) = Intent(context, ResultsBrowserActivity::class.java)
            .putExtra(EXTRA_SEASON, season)
            .putExtra(EXTRA_ROUND, round)
            .putExtra(EXTRA_TITLE, title)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val season = intent.getStringExtra(EXTRA_SEASON)
            val round = intent.getStringExtra(EXTRA_ROUND)
            val fragment = if (season != null && round != null) {
                RaceResultsFragment.newInstance(
                    season,
                    round,
                    intent.getStringExtra(EXTRA_TITLE).orEmpty()
                )
            } else {
                ResultsBrowserFragment()
            }
            supportFragmentManager.commit {
                replace(R.id.fragment_container, fragment)
            }
        }
    }
}

@AndroidEntryPoint
class ResultsBrowserFragment : BrowseSupportFragment(), OnItemViewClickedListener {
    @Inject lateinit var jolpicaClient: JolpicaClient
    private val rows = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.menu_results)
        brandColor = ContextCompat.getColor(requireContext(), R.color.f1_red)
        headersState = HEADERS_DISABLED
        adapter = rows
        onItemViewClickedListener = this
        lifecycleScope.launch {
            val races = runCatching { jolpicaClient.seasonRaces() }.getOrElse { emptyList() }
            val adapter = ArrayObjectAdapter(InfoCardPresenter())
            races.asReversed().forEach { race ->
                adapter.add(race.toInfoItem())
            }
            if (adapter.size() == 0) {
                adapter.add(
                    InfoRowItem("err", getString(R.string.info_load_error), getString(R.string.info_try_again_later))
                )
            }
            rows.clear()
            rows.add(ListRow(HeaderItem(getString(R.string.results_season_races)), adapter))
        }
    }

    override fun onItemClicked(
        itemViewHolder: Presenter.ViewHolder?,
        item: Any?,
        rowViewHolder: RowPresenter.ViewHolder?,
        row: Row?
    ) {
        val info = item as? InfoRowItem ?: return
        val parts = info.payload?.split('|') ?: return
        if (parts.size < 2) return
        startActivity(
            ResultsBrowserActivity.resultsIntent(
                requireContext(),
                parts[0],
                parts[1],
                info.title
            )
        )
    }

    private fun JolpicaRace.toInfoItem(): InfoRowItem {
        val place = listOfNotNull(circuit?.location?.locality, circuit?.location?.country)
            .joinToString(", ")
        return InfoRowItem(
            id = "$season-$round",
            title = "R$round · $raceName",
            subtitle = listOf(
                place,
                AmsterdamTime.formatUtc(date, time)
            ).filter { it.isNotBlank() }.joinToString(" · "),
            payload = "$season|$round"
        )
    }
}

@AndroidEntryPoint
class RaceResultsFragment : BrowseSupportFragment() {
    @Inject lateinit var jolpicaClient: JolpicaClient
    private val rows = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val season = requireArguments().getString(ARG_SEASON).orEmpty()
        val round = requireArguments().getString(ARG_ROUND).orEmpty()
        title = requireArguments().getString(ARG_TITLE).orEmpty()
            .ifBlank { getString(R.string.menu_results) }
        brandColor = ContextCompat.getColor(requireContext(), R.color.f1_red)
        headersState = HEADERS_DISABLED
        adapter = rows
        lifecycleScope.launch {
            val results = runCatching { jolpicaClient.raceResults(season, round) }
                .getOrElse { emptyList() }
            val adapter = ArrayObjectAdapter(InfoCardPresenter())
            results.forEach { r ->
                val time = r.time?.time ?: r.status.orEmpty()
                adapter.add(
                    InfoRowItem(
                        id = r.position,
                        title = "P${r.position}  ${r.driver.givenName} ${r.driver.familyName}",
                        subtitle = listOfNotNull(r.constructor?.name, "+${r.points} pts", time)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    )
                )
            }
            if (adapter.size() == 0) {
                adapter.add(
                    InfoRowItem(
                        "empty",
                        getString(R.string.results_not_available),
                        getString(R.string.info_try_again_later)
                    )
                )
            }
            rows.clear()
            rows.add(ListRow(HeaderItem(getString(R.string.results_classification)), adapter))
        }
    }

    companion object {
        private const val ARG_SEASON = "season"
        private const val ARG_ROUND = "round"
        private const val ARG_TITLE = "title"

        fun newInstance(season: String, round: String, title: String) =
            RaceResultsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SEASON, season)
                    putString(ARG_ROUND, round)
                    putString(ARG_TITLE, title)
                }
            }
    }
}
