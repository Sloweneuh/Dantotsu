package ani.dantotsu.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.sync.ListCompare
import ani.dantotsu.databinding.ActivityListSyncCompareBinding
import ani.dantotsu.databinding.ItemListSyncSectionBinding
import ani.dantotsu.getThemeColor
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
        val sections = ListCompare.availableSections()
        if (sections.isEmpty()) {
            showMessage(getString(R.string.list_compare_login_trackers))
            return
        }
        // Every card goes up straight away with its own spinner, so there's no screen-wide
        // loading state and no section waiting on another one's network work.
        binding.compareProgress.visibility = View.GONE
        val muActive = ListCompare.muActive()
        val handles = sections.associateWith { addSection(it, muActive) }
        lifecycleScope.launch(Dispatchers.IO) {
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

    private fun showMessage(text: String) {
        binding.compareProgress.visibility = View.GONE
        binding.compareMessage.text = text
        binding.compareMessage.visibility = View.VISIBLE
    }

    /** Adds an empty, spinning card for [section] and returns the handle that fills it in. */
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
        }
        // MangaUpdates contributes to the manga comparisons when it's active — never to the
        // anime-only sections (MAL anime, Simkl).
        val animeOnly = section == ListCompare.Section.MAL_ANIME ||
            section == ListCompare.Section.KITSU_ANIME ||
            section == ListCompare.Section.SIMKL_ANIME
        val sourceIcons = if (animeOnly || !muActive)
            listOf(R.drawable.ic_anilist)
        else listOf(R.drawable.ic_anilist, R.drawable.ic_round_mangaupdates_24)

        val sb = ItemListSyncSectionBinding.inflate(layoutInflater, binding.compareSections, false)
        sb.sectionLabel.text = sectionTitle
        sb.sectionIcon.setImageResource(headerIcon)
        setStatsIcons(sb.statsSourceIcons, sourceIcons)
        setStatsIcons(sb.statsDestIcons, listOf(headerIcon))
        // Dest totals are updated in place as entries sync (see [ListCompare.applied]), so the header
        // stays accurate without re-running the comparison. Null until the lists have been fetched.
        var destStats: ListCompare.SideStats? = null
        fun refreshStats() { sb.statsDest.text = destStats?.let { statsText(it) } ?: "" }
        refreshStats()

        val items = mutableListOf<ListCompare.DiffEntry>()
        lateinit var adapter: ListSyncDiffAdapter
        // The changes list is collapsed by default; the count sits in the header.
        var expanded = false
        // Until the diffs land there's no count, nothing to expand and nothing to sync — just the
        // spinner in their place.
        var loading = true

        fun applyState() {
            val count = adapter.itemCount
            val empty = count == 0
            sb.sectionProgress.visibility = if (loading) View.VISIBLE else View.GONE
            sb.sectionCount.text = count.toString()
            sb.sectionCount.visibility = if (loading || empty) View.GONE else View.VISIBLE
            sb.sectionChevron.visibility = if (loading || empty) View.INVISIBLE else View.VISIBLE
            sb.sectionSyncAll.visibility = if (loading || empty) View.GONE else View.VISIBLE
            sb.sectionEmpty.visibility = if (!loading && empty) View.VISIBLE else View.GONE
            sb.sectionDiffList.visibility =
                if (!loading && !empty && expanded) View.VISIBLE else View.GONE
            sb.sectionChevron.rotation = if (expanded) 180f else 0f
            sb.sectionHeader.isClickable = !loading && !empty
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

        sb.sectionHeader.setOnClickListener {
            if (adapter.itemCount == 0) return@setOnClickListener
            expanded = !expanded
            applyState()
        }

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

        applyState()
        binding.compareSections.addView(sb.root)

        return SectionHandle(
            onStats = { stats ->
                sb.statsSource.text = statsText(stats.source)
                destStats = stats.dest
                refreshStats()
            },
            onResult = { result ->
                // Same totals onStats already published; re-applied so the finished result is the
                // one the header reflects.
                sb.statsSource.text = statsText(result.source)
                destStats = result.dest
                refreshStats()
                adapter.replaceAll(result.diffs)
                loading = false
                applyState()
            },
            onError = {
                // Stop this card spinning and say so; the other sections carry on.
                loading = false
                applyState()
                sb.sectionEmpty.text = getString(R.string.list_compare_failed)
                sb.sectionEmpty.visibility = View.VISIBLE
            },
        )
    }

    /** Populates a stats column's icon row with the given service icons, tinted to the theme accent. */
    private fun setStatsIcons(container: LinearLayout, icons: List<Int>) {
        container.removeAllViews()
        val size = (20 * resources.displayMetrics.density).toInt()
        val gap = (6 * resources.displayMetrics.density).toInt()
        val tint = ColorStateList.valueOf(getThemeColor(com.google.android.material.R.attr.colorPrimary))
        for (res in icons) {
            val icon = ImageView(this)
            icon.layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
            icon.setImageResource(res)
            ImageViewCompat.setImageTintList(icon, tint)
            container.addView(icon)
        }
    }

    private fun statsText(stats: ListCompare.SideStats): String {
        val builder = StringBuilder(getString(R.string.list_sync_total, stats.total))
        for (status in ListCompare.STATUS_ORDER) {
            val count = stats.perStatus[status] ?: 0
            if (count > 0) builder.append('\n').append(statusLabel(status)).append(' ').append(count)
        }
        return builder.toString()
    }

    private fun statusLabel(canonical: String): String =
        canonical.lowercase().replaceFirstChar { it.uppercase() }
}
