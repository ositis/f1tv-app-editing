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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class StandingsActivity : FragmentActivity(R.layout.activity_info_browse) {
    companion object {
        fun intent(context: Context) = Intent(context, StandingsActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, StandingsFragment())
            }
        }
    }
}

@AndroidEntryPoint
class StandingsFragment : BrowseSupportFragment() {
    @Inject lateinit var jolpicaClient: JolpicaClient
    private val rows = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.menu_standings)
        brandColor = ContextCompat.getColor(requireContext(), R.color.f1_red)
        headersState = HEADERS_ENABLED
        adapter = rows
        lifecycleScope.launch {
            val drivers = async {
                runCatching { jolpicaClient.driverStandings() }.getOrElse { emptyList() }
            }
            val constructors = async {
                runCatching { jolpicaClient.constructorStandings() }.getOrElse { emptyList() }
            }
            val driverList = drivers.await()
            val constructorList = constructors.await()
            rows.clear()
            if (driverList.isEmpty() && constructorList.isEmpty()) {
                rows.add(
                    ListRow(
                        HeaderItem(getString(R.string.info_load_error)),
                        ArrayObjectAdapter(InfoCardPresenter()).apply {
                            add(
                                InfoRowItem(
                                    "err",
                                    getString(R.string.info_load_error),
                                    getString(R.string.info_try_again_later)
                                )
                            )
                        }
                    )
                )
                return@launch
            }
            if (driverList.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(InfoCardPresenter())
                driverList.forEach { s ->
                    val team = s.constructors?.firstOrNull()?.name.orEmpty()
                    adapter.add(
                        InfoRowItem(
                            id = s.driver.familyName,
                            title = "P${s.position}  ${s.driver.givenName} ${s.driver.familyName}" +
                                (s.driver.code?.let { " ($it)" } ?: ""),
                            subtitle = "$team · ${s.points} pts · ${s.wins} wins"
                        )
                    )
                }
                rows.add(ListRow(HeaderItem(getString(R.string.standings_drivers)), adapter))
            }
            if (constructorList.isNotEmpty()) {
                val adapter = ArrayObjectAdapter(InfoCardPresenter())
                constructorList.forEach { s ->
                    adapter.add(
                        InfoRowItem(
                            id = s.constructor.name,
                            title = "P${s.position}  ${s.constructor.name}",
                            subtitle = "${s.points} pts · ${s.wins} wins"
                        )
                    )
                }
                rows.add(ListRow(HeaderItem(getString(R.string.standings_constructors)), adapter))
            }
        }
    }
}
