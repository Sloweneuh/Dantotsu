package ani.dantotsu.settings

import ani.dantotsu.util.capContentWidth
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.sync.ListCompare
import ani.dantotsu.databinding.ActivityListSyncCompareBinding
import ani.dantotsu.databinding.ItemListSyncSectionBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Audits MyAnimeList and MangaBaka against the source lists (AniList, plus MangaUpdates for the manga
 * comparisons when active) and lets the user push the differences. Reachable from the List sync
 * settings screen. See [ListCompare] for the comparison logic.
 */
class ListSyncCompareActivity : AppCompatActivity() {
    private lateinit var binding: ActivityListSyncCompareBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivityListSyncCompareBinding.inflate(layoutInflater)
        setContentView(binding.root)
        capContentWidth()
        binding.listSyncCompareLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.listSyncCompareBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        load()
    }

    /** A section card that's already on screen, filled in as its comparison reports back. */
    private class SectionHandle(
        val onStats: (ListCompare.SectionStats) -> Unit,
        val onResult: (ListCompare.SubsectionResult) -> Unit,
        val onError: () -> Unit,
    )

    private fun load() {
        binding.compareSections.removeAllViews()
        binding.compareMessage.visibility = View.GONE
        if (Anilist.userid == null) {
            showMessage(getString(R.string.list_compare_login_anilist))
            return
        }
        // Which sections exist is decided by which tokens are in memory, and those are restored in
        // the background — so this has to wait for that, or a screen opened without going through
        // the home screen finds no tokens and tells the user to log in to trackers they are already
        // logged in to. Resumes on the main thread, which is where the rest of this belongs.
        lifecycleScope.launch {
            ani.dantotsu.connections.TrackerSessions.await()
            val sections = ListCompare.availableSections()
            if (sections.isEmpty()) {
                showMessage(getString(R.string.list_compare_login_trackers))
                return@launch
            }
            // Every card goes up straight away with its own spinner, so there's no screen-wide
            // loading state and no section waiting on another one's network work.
            binding.compareProgress.visibility = View.GONE
            val muActive = ListCompare.muActive()
            val handles = sections.associateWith { addSection(it, muActive) }
            launch(Dispatchers.IO) {
                ListCompare.compareStreaming(
                    onStats = { section, stats ->
                        withContext(Dispatchers.Main) { handles[section]?.onStats?.invoke(stats) }
                    },
                    onSection = { section, result ->
                        withContext(Dispatchers.Main) { handles[section]?.onResult?.invoke(result) }
                    },
                    onError = { section, e ->
                        Logger.log(e)
                        withContext(Dispatchers.Main) { handles[section]?.onError?.invoke() }
                    },
                )
            }
        }
    }

    private fun showMessage(text: String) {
        binding.compareProgress.visibility = View.GONE
        binding.compareMessage.text = text
        binding.compareMessage.visibility = View.VISIBLE
    }

    /** Adds an empty, spinning card for [section] and returns the handle that fills it in. */
    /** Closes each section that is open; one opening closes the rest, like the settings cards. */
    private val sectionClosers = mutableListOf<() -> Unit>()

    private fun addSection(section: ListCompare.Section, muActive: Boolean): SectionHandle {
        val (sectionTitle, headerIcon) = when (section) {
            ListCompare.Section.MAL_ANIME -> getString(R.string.anime) to R.drawable.ic_myanimelist
            ListCompare.Section.MAL_MANGA -> getString(R.string.manga) to R.drawable.ic_myanimelist
            ListCompare.Section.KITSU_ANIME ->
                "${getString(R.string.kitsu)} · ${getString(R.string.anime)}" to R.drawable.ic_kitsu
            ListCompare.Section.KITSU_MANGA ->
                "${getString(R.string.kitsu)} · ${getString(R.string.manga)}" to R.drawable.ic_kitsu
            ListCompare.Section.SIMKL_ANIME ->
                "${getString(R.string.simkl)} · ${getString(R.string.anime)}" to R.drawable.ic_simkl
            ListCompare.Section.MANGABAKA ->
                getString(R.string.mangabaka) to R.drawable.ic_round_mangabaka_24
            ListCompare.Section.COMICK_ANIME ->
                "${getString(R.string.comick)} · ${getString(R.string.anime)}" to R.drawable.ic_round_comick_24
            ListCompare.Section.COMICK_MANGA ->
                "${getString(R.string.comick)} · ${getString(R.string.manga)}" to R.drawable.ic_round_comick_24
        }
        // MangaUpdates contributes to the manga comparisons when it's active — never to the
        // anime-only sections (MAL anime, Simkl).
        val animeOnly = section == ListCompare.Section.MAL_ANIME ||
            section == ListCompare.Section.KITSU_ANIME ||
            section == ListCompare.Section.SIMKL_ANIME ||
            section == ListCompare.Section.COMICK_ANIME
        val sourceIcons = if (animeOnly || !muActive)
            listOf(R.drawable.ic_anilist)
        else listOf(R.drawable.ic_anilist, R.drawable.ic_round_mangaupdates_24)

        val sb = ItemListSyncSectionBinding.inflate(layoutInflater, binding.compareSections, false)
        sb.sectionLabel.text = sectionTitle
        sb.sectionIcon.setImageResource(headerIcon)
        // Both sides' per-status counts. Dest totals are updated in place as entries sync (see
        // [ListCompare.applied]), so the table stays accurate without re-running the comparison.
        // Null until the lists have been fetched.
        var sourceStats: ListCompare.SideStats? = null
        var destStats: ListCompare.SideStats? = null
        fun refreshStats() {
            val source = sourceStats
            val dest = destStats
            if (source == null || dest == null) {
                sb.sectionStats.removeAllViews()
                return
            }
            CompareTable.fill(sb.sectionStats, sourceIcons, listOf(headerIcon), statsRows(source, dest))
        }

        val items = mutableListOf<ListCompare.DiffEntry>()
        lateinit var adapter: ListSyncDiffAdapter
        // Collapsed by default; the count sits in the header.
        var expanded = false
        // Until the diffs land there's no count, nothing to open and nothing to sync — just the
        // spinner in their place.
        var loading = true
        var failed = false

        fun styleCard(animate: Boolean) = SectionCard.bind(
            sb.sectionHeader, sb.sectionIconTile, sb.sectionIcon, sb.sectionChevron,
            open = expanded, animate = animate,
        ) { sb.sectionCard.setCardBackgroundColor(it) }

        fun applyState() {
            val count = adapter.itemCount
            val empty = count == 0
            sb.sectionProgress.isVisible = loading
            sb.sectionCount.text = count.toString()
            sb.sectionCount.isVisible = !loading && !empty
            sb.sectionSubtitle.text = when {
                loading -> getString(R.string.list_compare_comparing)
                failed -> getString(R.string.list_compare_failed)
                empty -> getString(R.string.list_sync_up_to_date)
                else -> resources.getQuantityString(R.plurals.list_compare_differences, count, count)
            }
            sb.sectionSyncAll.isVisible = !empty
            // Open once there's something to show: the counts, even with nothing left to sync.
            val openable = !loading && destStats != null
            sb.sectionChevron.visibility = if (openable) View.VISIBLE else View.INVISIBLE
            sb.sectionHeader.isClickable = openable
        }

        adapter = ListSyncDiffAdapter(items, lifecycleScope) { entry, position ->
            adapter.setSyncing(entry, true)
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { ListCompare.sync(entry) }
                adapter.setSyncing(entry, false)
                if (ok) {
                    destStats = destStats?.let { ListCompare.applied(it, entry) }
                    refreshStats()
                    adapter.removeAt(position)
                    applyState()
                } else {
                    snackString(getString(R.string.list_sync_failed))
                }
            }
        }
        sb.sectionDiffList.layoutManager = LinearLayoutManager(this)
        sb.sectionDiffList.adapter = adapter
        sb.sectionDiffList.isNestedScrollingEnabled = false

        fun setOpen(open: Boolean) {
            expanded = open
            styleCard(animate = true)
            SectionCard.toggleBody(sb.sectionBody, sb.sectionDiffList, expanded) {
                sb.sectionBody.isVisible = it
            }
        }
        val close = { if (expanded) setOpen(false) }
        sectionClosers += close
        sb.sectionHeader.setOnClickListener {
            if (loading || destStats == null) return@setOnClickListener
            if (!expanded) sectionClosers.forEach { if (it !== close) it() }
            setOpen(!expanded)
        }
        SectionCard.pressFeedback(sb.sectionHeader, sb.sectionCard)

        sb.sectionSyncAll.setOnClickListener {
            val entries = items.toList()
            if (entries.isEmpty()) return@setOnClickListener
            sb.sectionSyncAll.isEnabled = false
            sb.sectionSyncAll.setIconSpinning(true)
            lifecycleScope.launch {
                val results = ListCompare.syncAll(entries)
                sb.sectionSyncAll.setIconSpinning(false)
                // Drop the entries that synced; keep failures in the list for retry. Update the header
                // stats from the successes instead of re-running the full (network-heavy) comparison.
                results.forEach { (entry, ok) ->
                    if (ok) destStats = destStats?.let { ListCompare.applied(it, entry) }
                }
                refreshStats()
                val failed = results.filterNot { it.second }.map { it.first }
                adapter.replaceAll(failed)
                applyState()
                sb.sectionSyncAll.isEnabled = failed.isNotEmpty()
                val synced = entries.size - failed.size
                if (failed.isEmpty()) snackString(getString(R.string.list_sync_synced, synced))
                else snackString(getString(R.string.list_sync_synced_partial, synced, failed.size))
            }
        }

        CompareTable.tintAccentButton(sb.sectionSyncAll)
        styleCard(animate = false)
        applyState()
        binding.compareSections.addView(sb.root)

        return SectionHandle(
            onStats = { stats ->
                sourceStats = stats.source
                destStats = stats.dest
                refreshStats()
                applyState()
            },
            onResult = { result ->
                // Same totals onStats already published; re-applied so the finished result is the
                // one the table reflects.
                sourceStats = result.source
                destStats = result.dest
                refreshStats()
                adapter.replaceAll(result.diffs)
                loading = false
                applyState()
            },
            onError = {
                // Stop this card spinning and say so; the other sections carry on.
                loading = false
                failed = true
                applyState()
            },
        )
    }

    /**
     * The per-status table: a total, then every status either side has entries in, with the rows
     * whose counts disagree marked.
     */
    private fun statsRows(
        source: ListCompare.SideStats,
        dest: ListCompare.SideStats,
    ): List<CompareTable.Row> {
        val rows = mutableListOf(
            CompareTable.Row(
                getString(R.string.list_sync_total_label),
                source.total.toString(), dest.total.toString(),
                differs = source.total != dest.total, strong = true, band = false,
            )
        )
        for (status in ListCompare.STATUS_ORDER) {
            val a = source.perStatus[status] ?: 0
            val b = dest.perStatus[status] ?: 0
            if (a == 0 && b == 0) continue
            rows += CompareTable.Row(
                statusLabel(status), a.toString(), b.toString(), differs = a != b, band = false,
            )
        }
        return rows
    }

    private fun statusLabel(canonical: String): String =
        canonical.lowercase().replaceFirstChar { it.uppercase() }
}
