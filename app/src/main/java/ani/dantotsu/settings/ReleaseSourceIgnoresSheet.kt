package ani.dantotsu.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.ReleaseSourceIgnores
import ani.dantotsu.connections.ReleaseSourceIgnores.IgnoredSeries
import ani.dantotsu.connections.ReleaseSourceIgnores.Series
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.databinding.BottomSheetRecyclerBinding
import ani.dantotsu.databinding.ItemReleaseSourceSeriesBinding
import ani.dantotsu.inAppIntentForLink
import ani.dantotsu.loadImage
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.notifications.unread.UnreadCache
import ani.dantotsu.px
import ani.dantotsu.widgets.MuListCache
import com.google.android.material.chip.Chip
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every series with a release progress source ignored, across all of them — the page-by-page sheet
 * ([ani.dantotsu.media.ReleaseSourcesSheet]) seen from above. Each ignored source is a chip; closing
 * it counts that source for the series again. A series goes when its last chip does.
 */
class ReleaseSourceIgnoresSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetRecyclerBinding? = null
    private val binding get() = _binding!!
    private val adapter = GroupieAdapter()

    /** Told when the sheet closes, for a page that shows what these sources report. */
    var onDismissed: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetRecyclerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.title.setText(R.string.release_sources_manage)
        binding.replyButton.visibility = View.GONE
        binding.repliesRecyclerView.adapter = adapter
        binding.repliesRecyclerView.layoutManager = LinearLayoutManager(context)

        val all = ReleaseSourceIgnores.ignoredSeries()
        if (all.none { it.series.title == null }) {
            render(all)
            return
        }
        binding.repliesRefresh.visibility = View.VISIBLE
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val named = withContext(Dispatchers.IO) { named(context, all) }
            if (_binding == null) return@launch
            binding.repliesRefresh.visibility = View.GONE
            render(named)
        }
    }

    /**
     * Fills in the series ignored before names were kept, or on a device that didn't keep them:
     * AniList from its API, MangaUpdates from the lists on this device.
     */
    private suspend fun named(context: Context, all: List<IgnoredSeries>): List<IgnoredSeries> {
        val unnamed = all.filter { it.series.title == null }.map { it.series.target }.toSet()
        val found = HashMap<String, Series>()
        val anilistIds = unnamed.mapNotNull(ReleaseSourceIgnores::anilistIdOf)
        runCatching { Anilist.query.getMediaBatch(anilistIds) }.getOrNull()?.forEach { media ->
            val target = ReleaseSourceIgnores.anilist(media.id)
            found[target] = Series(target, media.userPreferredName, media.cover)
        }
        if (unnamed.any { ReleaseSourceIgnores.muKeyOf(it) != null }) {
            val known = MuListCache.load(context) + UnreadCache.cachedMuMedia() +
                UnreadCache.rawComick().mapNotNull { it.muMedia }
            known.forEach { mu ->
                val target = ReleaseSourceIgnores.mu(mu.id)
                if (target in unnamed && mu.title != null) {
                    found[target] = Series(target, mu.title, mu.coverUrl, mu.id)
                }
            }
        }
        ReleaseSourceIgnores.rememberSeries(found.values)
        return all.map { ignored ->
            found[ignored.series.target]?.let { ignored.copy(series = it) } ?: ignored
        }
    }

    private fun render(all: List<IgnoredSeries>) {
        if (all.isEmpty()) {
            showEmpty()
            return
        }
        all.sortedBy { (it.series.title ?: it.series.target).lowercase() }.forEach { ignored ->
            adapter.add(SeriesItem(ignored))
        }
    }

    private fun showEmpty() {
        binding.root.addView(TextView(requireContext()).apply {
            setText(R.string.release_sources_manage_empty)
            alpha = 0.75f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(24f.px, 16f.px, 24f.px, 32f.px)
        })
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissed?.invoke()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private inner class SeriesItem(ignored: IgnoredSeries) : BindableItem<ItemReleaseSourceSeriesBinding>() {
        private val series = ignored.series
        private val providers = ignored.providers.toMutableList()

        override fun bind(viewBinding: ItemReleaseSourceSeriesBinding, position: Int) {
            val context = viewBinding.root.context
            viewBinding.releaseSeriesName.text = series.title
                ?: context.getString(R.string.release_sources_unknown_series, series.target.substringAfter(':'))
            viewBinding.releaseSeriesCover.loadImage(series.cover)

            viewBinding.root.setOnClickListener { open(it) }
            viewBinding.releaseSeriesSources.removeAllViews()
            providers.forEach { provider ->
                viewBinding.releaseSeriesSources.addView(Chip(context).apply {
                    setText(provider.title)
                    isCloseIconVisible = true
                    closeIconContentDescription = context.getString(R.string.release_source_track)
                    val restore = View.OnClickListener { restore(provider) }
                    setOnClickListener(restore)
                    setOnCloseIconClickListener(restore)
                })
            }
        }

        private fun restore(provider: ReleaseSourceIgnores.Provider) {
            ReleaseSourceIgnores.setIgnored(provider, series.target, false)
            providers -= provider
            if (providers.isEmpty()) {
                adapter.remove(this)
                if (adapter.itemCount == 0 && _binding != null) showEmpty()
            } else {
                notifyChanged()
            }
        }

        private fun open(view: View) {
            val context = view.context
            val intent = ReleaseSourceIgnores.anilistIdOf(series.target)?.let {
                Intent(context, MediaDetailsActivity::class.java).putExtra("mediaId", it)
            } ?: series.muSeriesId?.let {
                inAppIntentForLink(context, "https://www.mangaupdates.com/series/${it.toString(36)}")
            } ?: ReleaseSourceIgnores.comickHidOf(series.target)?.let {
                inAppIntentForLink(context, ComickApi.webUrl(it))
            }
            intent?.let(context::startActivity)
        }

        override fun getLayout(): Int = R.layout.item_release_source_series

        override fun initializeViewBinding(view: View) = ItemReleaseSourceSeriesBinding.bind(view)
    }
}
