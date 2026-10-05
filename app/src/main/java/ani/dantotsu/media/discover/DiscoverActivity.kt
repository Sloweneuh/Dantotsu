package ani.dantotsu.media.discover

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.MangaBakaSearchResults
import ani.dantotsu.databinding.ActivityDiscoverBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.px
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import ani.dantotsu.media.MangaBakaSearchFilterBottomSheet
import ani.dantotsu.initActivity
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point of a discovery queue. Builds (or resumes) the [DiscoverySource]'s queue, then opens
 * its picks in queue mode on the source's own media page, under the queue bar. This screen only
 * covers what happens around that: building, readiness problems, and the end-of-queue summary
 * with "Start another queue".
 *
 * For a source with filters it also mirrors the site's two ways in: a start screen with the plain
 * queue and "Start filtered queue" (the search filter sheet, whose Apply builds the queue), and on
 * the summary, another queue with the same filters or an unfiltered one.
 */
class DiscoverActivity : AppCompatActivity(), MangaBakaSearchFilterBottomSheet.Host {
    private lateinit var binding: ActivityDiscoverBinding
    private lateinit var source: DiscoverySource

    /** What the filter sheet edits; Apply builds a queue from it. */
    override var mangaBakaFilters: MangaBakaSearchResults = MangaBakaDiscovery.decodeFilters(null)
        private set
    override val mangaBakaFilterApplyLabel = R.string.discover_start_queue
    override val mangaBakaManageFilters = false
    /** Skips from the finished queue, for the queue the sheet is about to start. */
    private var pendingCarrySkipped: List<Long> = emptyList()

    private val queuePage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Left early: the queue is saved, so leave with it rather than parking on a summary.
        if (result.resultCode != RESULT_OK) {
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val queue = source.accountId()?.let { source.load(it) }
            if (queue == null) finish() else showComplete(queue)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityDiscoverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)
        source = DiscoverySource.byId(intent.getStringExtra(DiscoverySource.EXTRA_SOURCE)) ?: MangaBakaDiscovery

        val baseTopMargin = (binding.discoverTopBar.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        binding.discoverTopBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = baseTopMargin + statusBarHeight
        }
        // A cold start from the quick tile may only have a guessed status bar height until now.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (status > 0) {
                statusBarHeight = status
                binding.discoverTopBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = baseTopMargin + status
                }
            }
            insets
        }
        binding.discoverTitle.setText(
            if (source == AniListAnimeDiscovery) R.string.quick_tile_discover_anime else R.string.quick_tile_discover_manga
        )
        binding.discoverClose.setOnClickListener { finish() }
        binding.discoverMessageSecondary.setOnClickListener { finish() }
        if (savedInstanceState != null) {
            savedInstanceState.getString(STATE_FILTERS)?.let { mangaBakaFilters = MangaBakaDiscovery.decodeFilters(it) }
            pendingCarrySkipped = savedInstanceState.getLongArray(STATE_CARRY)?.toList().orEmpty()
        }

        // Recreated (rotation, process death) with the queue page on top: it reports back here.
        if (savedInstanceState != null) return

        lifecycleScope.launch {
            showLoading()
            val account = source.accountId()
            if (account == null) {
                snackString(source.needsLoginMessage)
                finish()
                return@launch
            }
            val saved = source.load(account)
            when {
                saved == null && source.supportsFilters -> showStart()
                saved == null -> build(jitter = false)
                saved.isComplete -> showComplete(saved)
                else -> showResume(saved)
            }
        }
    }

    private fun openQueue(queue: DiscoveryQueue) {
        val first = queue.next() ?: return showComplete(queue)
        showLoading()
        queuePage.launch(source.queuePageIntent(this, first))
    }

    private suspend fun build(jitter: Boolean, carrySkipped: List<Long> = emptyList(), filters: String? = null) {
        showLoading()
        val result = withContext(Dispatchers.IO) {
            source.build(this@DiscoverActivity, jitter, carrySkipped, filters)
        }
        when (result) {
            is DiscoveryBuildResult.Ready -> openQueue(result.queue)
            is DiscoveryBuildResult.ColdStart -> showMessage(
                result.title, result.body, R.string.discover_retry,
            ) { build(jitter, carrySkipped, filters) }
            DiscoveryBuildResult.ProfileStale -> showMessage(
                R.string.discover_profile_stale_title,
                getString(R.string.discover_profile_stale_body),
                R.string.discover_retry,
            ) { build(jitter, carrySkipped, filters) }
            // Filtered down to nothing: the way out is looser filters, or none.
            DiscoveryBuildResult.Empty -> if (filters != null) {
                showMessage(
                    R.string.discover_empty_title,
                    getString(R.string.discover_filtered_empty_body),
                    R.string.discover_change_filters,
                    filters = filters,
                    alt2 = R.string.discover_start_unfiltered to { build(jitter = true, carrySkipped) },
                ) { editFilters(filters, carrySkipped) }
            } else showMessage(
                R.string.discover_empty_title,
                getString(R.string.discover_empty_body),
                R.string.discover_retry,
            ) { build(jitter = true) }
            DiscoveryBuildResult.Failed -> showMessage(
                R.string.discover_failed_title,
                getString(source.failedBody),
                R.string.discover_retry,
            ) { build(jitter, carrySkipped, filters) }
        }
    }

    /**
     * A queue left part-way, as the site's "Pick up where you left off": carry on, or drop it for a
     * new one (same filters, or changed ones). A new queue replaces it once built.
     */
    private fun showResume(queue: DiscoveryQueue) {
        val skipped = queue.skippedIds()
        val filters = queue.filters
        showMessage(
            R.string.discover_resume_title,
            getString(R.string.discover_resume_body, queue.items.count { it.action == null }),
            R.string.discover_resume,
            filters = filters,
            alt = if (!source.supportsFilters) null
            else (if (filters != null) R.string.discover_change_filters else R.string.discover_start_filtered) to
                { editFilters(filters, skipped) },
            alt2 = R.string.discover_start_fresh to { build(jitter = true, carrySkipped = skipped, filters = filters) },
        ) { openQueue(queue) }
    }

    /** No queue yet: the plain queue, or set filters first. */
    private fun showStart() {
        showMessage(
            R.string.discover_start_title,
            getString(R.string.discover_start_body),
            R.string.discover_start,
            alt = R.string.discover_start_filtered to { editFilters(null, emptyList()) },
        ) { build(jitter = false) }
    }

    /** Opens the filter sheet on [filters]; its Apply ("Start queue") builds the queue. */
    private fun editFilters(filters: String?, carrySkipped: List<Long>) {
        mangaBakaFilters = MangaBakaDiscovery.decodeFilters(filters)
        pendingCarrySkipped = carrySkipped
        MangaBakaSearchFilterBottomSheet.newInstance().show(supportFragmentManager, "discover_filters")
    }

    override fun onMangaBakaFiltersChanged() {
        val filters = MangaBakaDiscovery.encodeFilters(mangaBakaFilters)
        val carry = pendingCarrySkipped
        // New filters start from their best matches; the re-roll is for "another" queue.
        lifecycleScope.launch { build(jitter = false, carrySkipped = carry, filters = filters) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        MangaBakaDiscovery.encodeFilters(mangaBakaFilters)?.let { outState.putString(STATE_FILTERS, it) }
        outState.putLongArray(STATE_CARRY, pendingCarrySkipped.toLongArray())
    }

    /**
     * Another queue with the same filters (as the site does), plus, where the source has filters,
     * changing them or dropping them for an unfiltered queue.
     */
    private fun showComplete(queue: DiscoveryQueue) {
        val skipped = queue.skippedIds()
        val filters = queue.filters
        showMessage(
            R.string.discover_complete_title,
            getString(
                R.string.discover_complete_body,
                queue.count(DiscoveryQueue.Action.ADDED),
                queue.count(DiscoveryQueue.Action.HIDDEN),
                queue.count(DiscoveryQueue.Action.SKIPPED),
            ),
            R.string.discover_start_another,
            filters = filters,
            alt = if (!source.supportsFilters) null
            else (if (filters != null) R.string.discover_change_filters else R.string.discover_start_filtered) to
                { editFilters(filters, skipped) },
            alt2 = if (filters == null) null
            else R.string.discover_start_unfiltered to { build(jitter = true, carrySkipped = skipped) },
        ) { build(jitter = true, carrySkipped = skipped, filters = filters) }
    }

    private fun showMessage(
        @StringRes title: Int,
        body: String,
        @StringRes primary: Int,
        filters: String? = null,
        alt: Pair<Int, suspend () -> Unit>? = null,
        alt2: Pair<Int, suspend () -> Unit>? = null,
        onPrimary: suspend () -> Unit,
    ) {
        binding.discoverLoading.isVisible = false
        binding.discoverMessage.isVisible = true
        binding.discoverMessageTitle.setText(title)
        binding.discoverMessageBody.text = body
        bindFilters(source.describeFilters(filters))
        binding.discoverMessagePrimary.setText(primary)
        binding.discoverMessagePrimary.setOnClickListener { lifecycleScope.launch { onPrimary() } }
        listOf(binding.discoverMessageAlt to alt, binding.discoverMessageAlt2 to alt2).forEach { (button, action) ->
            button.isVisible = action != null
            if (action == null) return@forEach
            button.setText(action.first)
            button.setOnClickListener { lifecycleScope.launch { action.second() } }
        }
    }

    private fun bindFilters(labels: List<DiscoverySource.FilterLabel>) {
        val row = binding.discoverMessageFilters
        row.removeAllViews()
        row.isVisible = labels.isNotEmpty()
        labels.forEach { label ->
            val chip = ItemChipBinding.inflate(layoutInflater, row, false).root as Chip
            chip.text = label.text
            chip.isClickable = false
            chip.isCheckable = false
            if (label.excluded) {
                chip.chipBackgroundColor = ColorStateList.valueOf(getThemeColor(com.google.android.material.R.attr.colorErrorContainer))
                chip.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnErrorContainer))
            } else {
                chip.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.filter_chip_include_bg))
                chip.setTextColor(ContextCompat.getColor(this, R.color.filter_chip_include_text))
            }
            (chip.layoutParams as ViewGroup.MarginLayoutParams).setMargins(4f.px, 2f.px, 4f.px, 2f.px)
            row.addView(chip)
        }
    }

    private fun showLoading() {
        binding.discoverMessage.isVisible = false
        binding.discoverLoading.isVisible = true
    }

    companion object {
        private const val STATE_FILTERS = "discover_filters"
        private const val STATE_CARRY = "discover_carry_skipped"

        fun intent(context: Context, source: DiscoverySource): Intent =
            Intent(context, DiscoverActivity::class.java).putExtra(DiscoverySource.EXTRA_SOURCE, source.id)
    }
}
