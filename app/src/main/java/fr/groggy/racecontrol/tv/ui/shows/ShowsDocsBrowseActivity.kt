package fr.groggy.racecontrol.tv.ui.shows

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
import androidx.leanback.widget.Row
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.InstantPeriod
import fr.groggy.racecontrol.tv.f1tv.F1TvClient
import fr.groggy.racecontrol.tv.f1tv.F1TvEditorialPage
import fr.groggy.racecontrol.tv.f1tv.F1TvSession
import fr.groggy.racecontrol.tv.ui.season.browse.Image
import fr.groggy.racecontrol.tv.ui.season.browse.Session
import fr.groggy.racecontrol.tv.ui.session.SessionCardPresenter
import fr.groggy.racecontrol.tv.ui.session.browse.SessionBrowseActivity
import kotlinx.coroutines.launch
import org.threeten.bp.Instant
import org.threeten.bp.Year
import javax.inject.Inject

@AndroidEntryPoint
class ShowsDocsBrowseActivity : FragmentActivity(R.layout.activity_info_browse) {
    companion object {
        fun intent(context: Context) = Intent(context, ShowsDocsBrowseActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, ShowsDocsBrowseFragment())
            }
        }
    }
}

@AndroidEntryPoint
class ShowsDocsBrowseFragment : BrowseSupportFragment(), OnItemViewClickedListener {
    @Inject internal lateinit var sessionCardPresenter: SessionCardPresenter
    @Inject internal lateinit var f1TvClient: F1TvClient

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val currentYear = Year.now().value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.menu_shows_docs)
        brandColor = ContextCompat.getColor(requireContext(), R.color.f1_red)
        headersState = HEADERS_ENABLED
        adapter = rowsAdapter
        onItemViewClickedListener = this
        lifecycleScope.launch { loadContent() }
    }

    private suspend fun loadContent() {
        rowsAdapter.clear()
        val shows = runCatching { f1TvClient.getEditorialPageVideos(F1TvEditorialPage.SHOWS) }
            .getOrElse { emptyList() }
        val docs = runCatching { f1TvClient.getEditorialPageVideos(F1TvEditorialPage.DOCUMENTARIES) }
            .getOrElse { emptyList() }

        if (shows.isNotEmpty()) {
            rowsAdapter.add(buildRow(getString(R.string.shows_docs_shows_row), shows))
        }
        if (docs.isNotEmpty()) {
            rowsAdapter.add(buildRow(getString(R.string.shows_docs_documentaries_row), docs))
        }
        if (shows.isEmpty() && docs.isEmpty()) {
            val errorAdapter = ArrayObjectAdapter(sessionCardPresenter)
            errorAdapter.add(
                Session(
                    id = fr.groggy.racecontrol.tv.f1tv.F1TvSessionId("shows-docs-empty"),
                    contentId = "",
                    name = getString(R.string.info_load_error),
                    contentSubtype = getString(R.string.info_try_again_later),
                    series = fr.groggy.racecontrol.tv.f1tv.RacingSeries.F1,
                    thumbnail = null,
                    largePictureUrl = "",
                    channels = emptyList(),
                    period = InstantPeriod(Instant.EPOCH, Instant.EPOCH)
                )
            )
            rowsAdapter.add(ListRow(HeaderItem(getString(R.string.menu_shows_docs)), errorAdapter))
        }
    }

    private fun buildRow(header: String, items: List<F1TvSession>): ListRow {
        val adapter = ArrayObjectAdapter(sessionCardPresenter)
        items.map { it.toBrowseSession() }.forEach { adapter.add(it) }
        return ListRow(HeaderItem(header), adapter)
    }

    override fun onItemClicked(
        itemViewHolder: androidx.leanback.widget.Presenter.ViewHolder?,
        item: Any?,
        rowViewHolder: androidx.leanback.widget.RowPresenter.ViewHolder?,
        row: Row?
    ) {
        val session = item as? Session ?: return
        if (session.contentId.isBlank()) return
        startActivity(
            SessionBrowseActivity.intent(
                requireActivity(),
                session.id.value,
                session.contentId,
                currentYear
            )
        )
    }
}

private fun F1TvSession.toBrowseSession(): Session {
    return Session(
        id = id,
        contentId = contentId,
        name = name,
        contentSubtype = contentSubtype,
        series = series,
        thumbnail = if (pictureUrl.isNotBlank()) Image(android.net.Uri.parse(pictureUrl)) else null,
        largePictureUrl = largePictureUrl,
        channels = channels,
        period = period
    )
}
