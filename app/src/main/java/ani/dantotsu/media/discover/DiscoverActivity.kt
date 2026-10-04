package ani.dantotsu.media.discover

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityDiscoverBinding
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
 */
class DiscoverActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiscoverBinding
    private lateinit var source: DiscoverySource

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

        binding.discoverTopBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin += statusBarHeight
        }
        binding.discoverTitle.setText(
            if (source == AniListAnimeDiscovery) R.string.quick_tile_discover_anime else R.string.quick_tile_discover_manga
        )
        binding.discoverClose.setOnClickListener { finish() }
        binding.discoverMessageSecondary.setOnClickListener { finish() }

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
                saved == null -> build(jitter = false)
                saved.isComplete -> showComplete(saved)
                else -> openQueue(saved)
            }
        }
    }

    private fun openQueue(queue: DiscoveryQueue) {
        val first = queue.next() ?: return showComplete(queue)
        showLoading()
        queuePage.launch(source.queuePageIntent(this, first))
    }

    private suspend fun build(jitter: Boolean, carrySkipped: List<Long> = emptyList()) {
        showLoading()
        val result = withContext(Dispatchers.IO) {
            source.build(this@DiscoverActivity, jitter, carrySkipped)
        }
        when (result) {
            is DiscoveryBuildResult.Ready -> openQueue(result.queue)
            is DiscoveryBuildResult.ColdStart -> showMessage(
                result.title, result.body, R.string.discover_retry,
            ) { build(jitter, carrySkipped) }
            DiscoveryBuildResult.ProfileStale -> showMessage(
                R.string.discover_profile_stale_title,
                getString(R.string.discover_profile_stale_body),
                R.string.discover_retry,
            ) { build(jitter, carrySkipped) }
            DiscoveryBuildResult.Empty -> showMessage(
                R.string.discover_empty_title,
                getString(R.string.discover_empty_body),
                R.string.discover_retry,
            ) { build(jitter = true) }
            DiscoveryBuildResult.Failed -> showMessage(
                R.string.discover_failed_title,
                getString(source.failedBody),
                R.string.discover_retry,
            ) { build(jitter, carrySkipped) }
        }
    }

    private fun showComplete(queue: DiscoveryQueue) {
        showMessage(
            R.string.discover_complete_title,
            getString(
                R.string.discover_complete_body,
                queue.count(DiscoveryQueue.Action.ADDED),
                queue.count(DiscoveryQueue.Action.HIDDEN),
                queue.count(DiscoveryQueue.Action.SKIPPED),
            ),
            R.string.discover_start_another,
        ) { build(jitter = true, carrySkipped = queue.skippedIds()) }
    }

    private fun showMessage(
        @StringRes title: Int,
        body: String,
        @StringRes primary: Int,
        onPrimary: suspend () -> Unit,
    ) {
        binding.discoverLoading.isVisible = false
        binding.discoverMessage.isVisible = true
        binding.discoverMessageTitle.setText(title)
        binding.discoverMessageBody.text = body
        binding.discoverMessagePrimary.setText(primary)
        binding.discoverMessagePrimary.setOnClickListener { lifecycleScope.launch { onPrimary() } }
    }

    private fun showLoading() {
        binding.discoverMessage.isVisible = false
        binding.discoverLoading.isVisible = true
    }

    companion object {
        fun intent(context: Context, source: DiscoverySource): Intent =
            Intent(context, DiscoverActivity::class.java).putExtra(DiscoverySource.EXTRA_SOURCE, source.id)
    }
}
