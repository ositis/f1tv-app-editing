package fr.groggy.racecontrol.tv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.Keep
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.f1tv.Archive
import fr.groggy.racecontrol.tv.f1tv.RacingSeries
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.ui.info.RaceCalendarActivity
import fr.groggy.racecontrol.tv.ui.info.ResultsBrowserActivity
import fr.groggy.racecontrol.tv.ui.info.StandingsActivity
import fr.groggy.racecontrol.tv.ui.shows.ShowsDocsBrowseActivity
import fr.groggy.racecontrol.tv.ui.season.archive.SeasonArchiveActivity
import fr.groggy.racecontrol.tv.ui.season.browse.Season
import fr.groggy.racecontrol.tv.ui.season.browse.SeasonBrowseActivity
import fr.groggy.racecontrol.tv.ui.season.browse.Session
import fr.groggy.racecontrol.tv.ui.session.SessionCardPresenter
import fr.groggy.racecontrol.tv.ui.session.browse.SessionBrowseActivity
import org.threeten.bp.Year
import javax.inject.Inject

@Keep
@AndroidEntryPoint
class HomeFragment : RowsSupportFragment(), OnItemViewClickedListener {
    @Inject internal lateinit var sessionCardPresenter: SessionCardPresenter
    @Inject internal lateinit var settingsRepository: SettingsRepository

    private val homeEntriesAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val currentYear = Year.now().value
    private var archivesRow: ListRow? = null
    private var exploreRow: ListRow? = null
    private var hasAppliedInitialLatestEventSelection = false

    private var liveNowHeader: String = ""
    private var weekendHeader: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveNowHeader = getString(R.string.home_live_now)
        weekendHeader = getString(R.string.home_weekend)
        setupUIElements()
        setupEventListeners()
        buildRowsAdapter()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        view?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            val dimensionPixelSize =
                inflater.context.resources.getDimensionPixelSize(R.dimen.lb_browse_rows_fading_edge)
            val horizontalMargin = -dimensionPixelSize * 2 - 4

            leftMargin = horizontalMargin
            rightMargin = horizontalMargin
        }

        return view
    }

    private fun buildRowsAdapter() {
        val viewModel: HomeViewModel by viewModels()

        archivesRow = getArchiveRow(viewModel.listArchive())
        exploreRow = getExploreRow()
        homeEntriesAdapter.add(exploreRow!!)

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getCurrentSeason(Archive(currentYear))
                    .collect(::onUpdatedSeason)
            }
        }
    }

    private fun getExploreRow(): ListRow {
        val adapter = ArrayObjectAdapter(HomeItemPresenter())
        adapter.add(HomeItem(HomeItemType.SHOWS_DOCS, getString(R.string.menu_shows_docs)))
        adapter.add(HomeItem(HomeItemType.RACE_CALENDAR, getString(R.string.menu_race_calendar)))
        adapter.add(HomeItem(HomeItemType.STANDINGS, getString(R.string.menu_standings)))
        adapter.add(HomeItem(HomeItemType.RESULTS, getString(R.string.menu_results)))
        return ListRow(HeaderItem(getString(R.string.menu_hub)), adapter)
    }

    private fun onUpdatedSeason(season: Season) {
        val seriesFilter = RacingSeries.fromPreference(settingsRepository.getCurrent().defaultSeries)
        val events = season.events.map { event ->
            if (seriesFilter == RacingSeries.ALL) event
            else event.copy(sessions = event.sessions.filter { it.series == seriesFilter })
        }.filter { it.sessions.isNotEmpty() }
        if (events.isEmpty()) {
            onEmptySeason()
            return
        }

        val liveSessions = events
            .flatMap { it.sessions }
            .filter { it.isLiveNow() }
            .distinctBy { it.id.value }

        val weekendEvent = events.first()
        val weekendSessions = weekendEvent.sessions

        upsertSessionRow(liveNowHeader, liveSessions, insertAt = 0, removeIfEmpty = true)
        upsertSessionRow(
            headerName = weekendHeader,
            sessions = weekendSessions,
            insertAt = if (liveSessions.isNotEmpty()) 1 else 0,
            removeIfEmpty = false
        )

        ensureExploreRowPresent()
        ensureArchiveRowPresent()
        maybeSelectLatestEventByDefault()
    }

    private fun upsertSessionRow(
        headerName: String,
        sessions: List<Session>,
        insertAt: Int,
        removeIfEmpty: Boolean
    ) {
        val existingListRows = homeEntriesAdapter.unmodifiableList<ListRow>()
        val existingIndex = existingListRows.indexOfFirst { it.headerItem.name == headerName }

        if (sessions.isEmpty()) {
            if (removeIfEmpty && existingIndex >= 0) {
                homeEntriesAdapter.removeItems(existingIndex, 1)
            }
            return
        }

        val existingListRow = existingListRows.getOrNull(existingIndex)
        val sessionsListRow = getLastSessionsRow(sessions, headerName, existingListRow)

        when {
            existingIndex < 0 -> {
                val safeIndex = insertAt.coerceIn(0, homeEntriesAdapter.size())
                homeEntriesAdapter.add(safeIndex, sessionsListRow)
            }
            !hasMatchingSessions(existingListRow!!, sessionsListRow) -> {
                homeEntriesAdapter.replace(existingIndex, sessionsListRow)
                if (existingIndex != insertAt && insertAt in 0 until homeEntriesAdapter.size()) {
                    // Keep LIVE NOW / Weekend ordering stable after replace
                    val row = homeEntriesAdapter.get(existingIndex) as ListRow
                    homeEntriesAdapter.removeItems(existingIndex, 1)
                    val target = insertAt.coerceIn(0, homeEntriesAdapter.size())
                    homeEntriesAdapter.add(target, row)
                }
            }
        }
    }

    private fun maybeSelectLatestEventByDefault() {
        if (hasAppliedInitialLatestEventSelection) return
        hasAppliedInitialLatestEventSelection = true
        view?.post {
            setSelectedPosition(0, false)
            view?.requestFocus()
        }
    }

    private fun hasMatchingSessions(
        existingListRow: ListRow,
        sessionsListRow: ListRow
    ) = (existingListRow.adapter.size() == sessionsListRow.adapter.size()
            && (0 until existingListRow.adapter.size()).all { index ->
        existingListRow.adapter[index] as Session == sessionsListRow.adapter[index] as Session
    })

    private fun onEmptySeason() {
        ensureExploreRowPresent()
        ensureArchiveRowPresent()
    }

    private fun ensureExploreRowPresent() {
        val explore = exploreRow ?: return
        val hasExplore = homeEntriesAdapter.unmodifiableList<ListRow>()
            .any { it.headerItem.name == explore.headerItem.name }
        if (!hasExplore) {
            val archiveIndex = homeEntriesAdapter.unmodifiableList<ListRow>()
                .indexOfFirst { it.headerItem.name == archivesRow?.headerItem?.name }
            if (archiveIndex >= 0) {
                homeEntriesAdapter.add(archiveIndex, explore)
            } else {
                homeEntriesAdapter.add(explore)
            }
        }
    }

    private fun ensureArchiveRowPresent() {
        val archive = archivesRow ?: return
        val hasArchiveRow = homeEntriesAdapter.unmodifiableList<ListRow>()
            .any { it.headerItem.name == archive.headerItem.name }
        if (!hasArchiveRow) {
            homeEntriesAdapter.add(archive)
        }
    }

    private fun getLastSessionsRow(
        sessions: List<Session>,
        headerName: String,
        existingListRow: ListRow?
    ): ListRow {
        val (listRow, listRowAdapter) = if (existingListRow == null) {
            val listRowAdapter = ArrayObjectAdapter(sessionCardPresenter)
            val listRow = ListRow(HeaderItem(headerName), listRowAdapter)
            listRow to listRowAdapter
        } else {
            val listRowAdapter = existingListRow.adapter as ArrayObjectAdapter
            existingListRow to listRowAdapter
        }
        listRowAdapter.setItems(sessions, Session.diffCallback)
        return listRow
    }

    private fun getArchiveRow(archives: List<Archive>): ListRow {
        val subArchives = archives.map { archive -> HomeItem(HomeItemType.ARCHIVE, archive.year.toString()) }

        val listRowAdapter = ArrayObjectAdapter(HomeItemPresenter())
        listRowAdapter.setItems(subArchives, HomeItem.diffCallback)
        listRowAdapter.add(
            HomeItem(
                HomeItemType.ARCHIVE_ALL,
                resources.getString(R.string.home_all)
            )
        )

        return ListRow(HeaderItem(resources.getString(R.string.home_archive)), listRowAdapter)
    }

    private fun setupUIElements() {
        adapter = homeEntriesAdapter
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = this
    }

    override fun onItemClicked(
        itemViewHolder: Presenter.ViewHolder?,
        item: Any?,
        rowViewHolder: RowPresenter.ViewHolder?,
        row: Row?
    ) {
        val activity = when (item) {
            is Session -> {
                SessionBrowseActivity.intent(requireActivity(), item.id.value, item.contentId, currentYear)
            }
            is HomeItem -> when (item.type) {
                HomeItemType.ARCHIVE -> {
                    SeasonBrowseActivity.intent(requireContext(), Archive(item.text.toInt()))
                }
                HomeItemType.ARCHIVE_ALL -> {
                    SeasonArchiveActivity.intent(requireContext())
                }
                HomeItemType.SHOWS_DOCS -> ShowsDocsBrowseActivity.intent(requireContext())
                HomeItemType.RACE_CALENDAR -> RaceCalendarActivity.intent(requireContext())
                HomeItemType.STANDINGS -> StandingsActivity.intent(requireContext())
                HomeItemType.RESULTS -> ResultsBrowserActivity.intent(requireContext())
            }
            else -> null
        }
        activity?.let { startActivity(it) }
    }
}
