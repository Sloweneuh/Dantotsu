package ani.dantotsu.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Audits MyAnimeList and MangaBaka against the source lists (AniList, plus MangaUpdates for
 * MangaBaka when active) and lets the user push the differences. Reachable from the List sync
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

    private fun load() {
        binding.compareSections.removeAllViews()
        binding.compareMessage.visibility = View.GONE
        if (Anilist.userid == null) {
            showMessage(getString(R.string.list_compare_login_anilist))
            return
        }
        binding.compareProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ListCompare.compareAll() }
            binding.compareProgress.visibility = View.GONE
            render(result)
        }
    }

    private fun showMessage(text: String) {
        binding.compareProgress.visibility = View.GONE
        binding.compareMessage.text = text
        binding.compareMessage.visibility = View.VISIBLE
    }

    private fun render(result: ListCompare.CompareResult) {
        binding.compareSections.removeAllViews()
        if (result.malAnime == null && result.malManga == null && result.mangaBaka == null) {
            showMessage(getString(R.string.list_compare_login_trackers))
            return
        }
        result.malAnime?.let {
            addSection(
                getString(R.string.anime), R.drawable.ic_myanimelist,
                listOf(R.drawable.ic_anilist), listOf(R.drawable.ic_myanimelist), it, isAnime = true,
            )
        }
        result.malManga?.let {
            addSection(
                getString(R.string.manga), R.drawable.ic_myanimelist,
                listOf(R.drawable.ic_anilist), listOf(R.drawable.ic_myanimelist), it, isAnime = false,
            )
        }
        result.mangaBaka?.let {
            val sourceIcons = if (result.muActive)
                listOf(R.drawable.ic_anilist, R.drawable.ic_round_mangaupdates_24)
            else listOf(R.drawable.ic_anilist)
            addSection(
                getString(R.string.mangabaka), R.drawable.ic_round_mangabaka_24,
                sourceIcons, listOf(R.drawable.ic_round_mangabaka_24), it, isAnime = false,
            )
        }
    }

    private fun addSection(
        sectionTitle: String,
        @DrawableRes headerIcon: Int,
        sourceIcons: List<Int>,
        destIcons: List<Int>,
        sub: ListCompare.SubsectionResult,
        isAnime: Boolean,
    ) {
        val sb = ItemListSyncSectionBinding.inflate(layoutInflater, binding.compareSections, false)
        sb.sectionLabel.text = sectionTitle
        sb.sectionIcon.setImageResource(headerIcon)
        setStatsIcons(sb.statsSourceIcons, sourceIcons)
        setStatsIcons(sb.statsDestIcons, destIcons)
        sb.statsSource.text = statsText(sub.source)
        // Dest totals are updated in place as entries sync (see [ListCompare.applied]), so the header
        // stays accurate without re-running the comparison.
        var destStats = sub.dest
        fun refreshStats() { sb.statsDest.text = statsText(destStats) }
        refreshStats()

        val items = sub.diffs.toMutableList()
        lateinit var adapter: ListSyncDiffAdapter
        // The changes list is collapsed by default; the count sits in the header.
        var expanded = false

        fun applyState() {
            val count = adapter.itemCount
            val empty = count == 0
            sb.sectionCount.text = count.toString()
            sb.sectionCount.visibility = if (empty) View.GONE else View.VISIBLE
            sb.sectionChevron.visibility = if (empty) View.INVISIBLE else View.VISIBLE
            sb.sectionSyncAll.visibility = if (empty) View.GONE else View.VISIBLE
            sb.sectionEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            sb.sectionDiffList.visibility = if (!empty && expanded) View.VISIBLE else View.GONE
            sb.sectionChevron.rotation = if (expanded) 180f else 0f
            sb.sectionHeader.isClickable = !empty
        }

        adapter = ListSyncDiffAdapter(items, lifecycleScope) { entry, position ->
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { ListCompare.sync(entry) }
                if (ok) {
                    destStats = ListCompare.applied(destStats, entry)
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
            lifecycleScope.launch {
                val results = withContext(Dispatchers.IO) { entries.map { it to ListCompare.sync(it) } }
                // Drop the entries that synced; keep failures in the list for retry. Update the header
                // stats from the successes instead of re-running the full (network-heavy) comparison.
                results.forEach { (entry, ok) -> if (ok) destStats = ListCompare.applied(destStats, entry) }
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
