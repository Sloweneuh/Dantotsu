package ani.dantotsu.media.novel

import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemMediaSourceBinding
import ani.dantotsu.isOnline
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.handleProgress
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.media.ExtensionDropdownAdapter
import ani.dantotsu.parsers.NovelReadSources
import ani.dantotsu.parsers.novel.lnreader.LNReaderChapter
import ani.dantotsu.parsers.novel.lnreader.LNReaderPluginManager
import ani.dantotsu.parsers.showUserTextOn
import ani.dantotsu.settings.ExtensionsActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The header of the novel page.
 *
 * Uses the same layout as the anime and manga headers so a novel offers the same controls in the
 * same places — source picker, matched-entry title, search, download, continue and the chapter
 * range chips. The pieces a novel source cannot answer for are hidden rather than shown inert:
 * there is no dub track, no per-source language list, and a plugin exposes no settings screen.
 */
class NovelReadAdapter(
    private val media: Media,
    private val fragment: NovelReadFragment,
    private val novelReadSources: NovelReadSources,
) : RecyclerView.Adapter<NovelReadAdapter.ViewHolder>() {

    private var _binding: ItemMediaSourceBinding? = null
    val progress get() = _binding?.sourceProgressBar

    /** Whether chapters are being fetched; drives the bar between the header and the list. */
    private var loading = false

    /** Fetched plugin icons, kept so rebinding the header does not refetch them. */
    private val iconCache = mutableMapOf<String, Drawable?>()

    private companion object {
        const val DOWNLOADED_SOURCE = "Downloaded"

        /** "Available Novels" in the extensions pager, matching how the manga header links out. */
        const val AVAILABLE_NOVELS_TAB = 5
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val bind = ItemMediaSourceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(bind)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        _binding = binding
        binding.sourceTitle.setText(R.string.chaps)

        // Not anime-only despite the name: `animeSourceContainer` is the outer wrapper for this
        // whole header, and hiding it takes the source picker, chips and continue card with it.
        // The genuinely anime-only views are `animeSourceYT` and `animeSourceDubbedCont`, both of
        // which the layout already leaves hidden until the anime header turns them on.
        binding.mediaSourceLanguageContainer.isGone = true
        binding.mediaSourceSubscribe.isGone = true
        binding.faqbutton.isGone = true

        // Only `tools:visibility` in the layout, so at runtime it starts on screen. Bound to the
        // fragment's current state rather than blanked, or a header rebound mid-load would drop
        // the spinner while chapters were still coming.
        binding.sourceProgressBar.isVisible = loading

        // Height is match_parent, which sizes against the row's tallest sibling. With subscribe
        // hidden and download not yet shown, the row collapses to the title's height and the icon
        // shrinks with it.
        binding.mediaNestedButton.updateLayoutParams { height = ViewGroup.LayoutParams.WRAP_CONTENT }

        val offline = !isOnline(binding.root.context) || PrefManager.getVal(PrefName.OfflineMode)
        binding.mediaSourceNameContainer.isGone = offline
        binding.mediaSourceSearch.isGone = offline
        binding.mediaSourceAddExtension.isGone = offline

        // A plugin is a script with no preference screen, unlike an extension source.
        binding.mediaSourceSettings.setOnClickListener {
            snackString(fragment.getString(R.string.source_not_configurable))
        }

        binding.mediaSourceAddExtension.setOnClickListener {
            ContextCompat.startActivity(
                fragment.requireContext(),
                Intent(fragment.requireContext(), ExtensionsActivity::class.java)
                    .putExtra("tab", AVAILABLE_NOVELS_TAB),
                null
            )
        }

        val source = media.selected!!.sourceIndex
            .let { if (it >= novelReadSources.names.size) 0 else it }
        if (novelReadSources.names.isNotEmpty() && source in novelReadSources.names.indices) {
            binding.mediaSource.setText(novelReadSources.names[source])
        }

        applySourceDropdown(binding)
        binding.mediaSource.setOnItemClickListener { _, _, i, _ ->
            fragment.onSourceChange(i)
            binding.mediaSource.setText(novelReadSources.names[i], false)
        }

        // The same "Searching : x" / "Found : x" line the other source headers show, driven by the
        // parser so it updates as the match is resolved rather than only when the header is bound.
        novelReadSources[source]?.apply {
            binding.mediaSourceTitle.text = showUserText
            showUserTextOn(binding.mediaSourceTitle)
        }
        binding.mediaSourceSearch.setOnClickListener { fragment.openEntryPicker() }
        binding.mediaSourceTitleBrowser.isVisible = fragment.entryUrl() != null
        binding.mediaSourceTitleBrowser.setOnClickListener {
            fragment.entryUrl()?.let { openLinkInBrowser(it) }
        }

        binding.mediaNestedButton.setOnClickListener { fragment.toggleReverse() }
        binding.mediaSourceDownload.setOnClickListener { fragment.promptMultiDownload() }
    }

    /**
     * Fills the source dropdown, fetching plugin icons in the background.
     *
     * An extension hands over a launcher drawable; a plugin only names a URL, and the dropdown
     * adapter wants drawables up front. So the list is built without icons first and rebuilt once
     * they arrive — a dropdown that appears instantly without icons beats one that waits.
     */
    private fun applySourceDropdown(binding: ItemMediaSourceBinding) {
        fun setAdapter(icons: List<Drawable?>) {
            binding.mediaSource.setAdapter(
                ExtensionDropdownAdapter(fragment.requireContext(), novelReadSources.names, icons)
            )
        }

        val downloadIcon = AppCompatResources.getDrawable(
            fragment.requireContext(), R.drawable.ic_download_24
        )
        val placeholder = novelReadSources.names.map { name ->
            if (name == DOWNLOADED_SOURCE) downloadIcon else iconCache[name]
        }
        setAdapter(placeholder)

        val missing = novelReadSources.names.filter {
            it != DOWNLOADED_SOURCE && !iconCache.containsKey(it)
        }
        if (missing.isEmpty()) return

        val plugins = Injekt.get<LNReaderPluginManager>().installedPluginsFlow.value
        fragment.lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                missing.associateWith { name ->
                    val url = plugins.firstOrNull { it.name == name }?.plugin?.iconUrl
                        ?: return@associateWith null
                    runCatching {
                        Glide.with(fragment.requireContext().applicationContext)
                            .load(url).submit().get()
                    }.getOrNull()
                }
            }
            if (_binding == null || fetched.values.all { it == null }) return@launch
            iconCache.putAll(fetched)
            setAdapter(
                novelReadSources.names.map { name ->
                    if (name == DOWNLOADED_SOURCE) downloadIcon else iconCache[name]
                }
            )
        }
    }

    override fun getItemCount(): Int = 1

    // --------------------------------------------------------------------------------------
    // State the fragment pushes in as chapters load
    // --------------------------------------------------------------------------------------

    fun setLoading(show: Boolean) {
        loading = show
        _binding?.sourceProgressBar?.isVisible = show
    }

    fun setEntryTitle(title: String?) {
        _binding?.mediaSourceTitle?.text = title ?: fragment.getString(R.string.no_results_found)
        _binding?.mediaSourceTitleBrowser?.isVisible = fragment.entryUrl() != null
    }

    fun setFound(found: Boolean) {
        _binding?.sourceNotFound?.isGone = found
        _binding?.mediaSourceDownload?.isVisible = found
    }

    fun clearChips() {
        _binding?.mediaSourceChipGroup?.removeAllViews()
    }

    /**
     * Builds the range chips.
     *
     * A novel can run to thousands of chapters, so the list is paged the way the manga list is —
     * without this the user is scrolling one flat list of everything.
     */
    fun updateChips(limit: Int, names: Array<String>, arr: Array<Int>, selected: Int = 0) {
        val binding = _binding ?: return
        val screenWidth = binding.root.resources.displayMetrics.widthPixels
        var select: Chip? = null

        for (position in arr.indices) {
            val last = if (position + 1 == arr.size) names.size else (limit * (position + 1))
            val chip = ItemChipBinding.inflate(
                LayoutInflater.from(fragment.context), binding.mediaSourceChipGroup, false
            ).root
            chip.isCheckable = true

            val first = names[limit * position]
            val lastName = names[last - 1]
            chip.text = "${first.take(12)} - ${lastName.take(12)}"
            // Without this a chip keeps the default text colour in both states, so the selected one
            // is indistinguishable from the rest. The manga chips carry the same list.
            chip.setTextColor(
                ContextCompat.getColorStateList(fragment.requireContext(), R.color.chip_text_color)
            )
            chip.setOnClickListener {
                fragment.onChipClicked(position, limit * position, last - 1)
            }
            binding.mediaSourceChipGroup.addView(chip)
            if (position == selected) {
                select = chip
                chip.isChecked = true
            }
        }

        binding.mediaWatchChipScroll.post {
            select?.let {
                binding.mediaWatchChipScroll.smoothScrollTo(
                    (it.left - screenWidth / 2) + (it.width / 2), 0
                )
            }
        }
    }

    /**
     * Shows the continue card for the first unread chapter.
     *
     * Read state drives this rather than tracker progress: a source's numbering often does not
     * line up with AniList's, so the chapter the user actually stopped on is the local one.
     */
    /**
     * @param progressKey the chapter's number, which is what its saved position is filed under.
     *   Null for a chapter carrying none, and then there is no bar to draw — the same as a manga
     *   chapter whose number cannot be parsed.
     */
    fun setContinue(chapter: LNReaderChapter?, index: Int, progressKey: String?) {
        val binding = _binding ?: return
        if (chapter == null) {
            binding.sourceContinue.isVisible = false
            return
        }
        binding.sourceContinue.isVisible = true
        binding.itemMediaImage.loadImage(media.banner ?: media.cover)
        binding.mediaSourceContinueText.text =
            fragment.getString(R.string.continue_reading_chapter, chapter.name)
        if (progressKey != null) {
            handleProgress(
                binding.itemMediaProgressCont,
                binding.itemMediaProgress,
                binding.itemMediaProgressEmpty,
                media.id,
                progressKey,
            )
        } else {
            binding.itemMediaProgressCont.isVisible = false
        }
        binding.sourceContinue.setOnClickListener { fragment.onChapterClick(index) }
    }

    inner class ViewHolder(val binding: ItemMediaSourceBinding) :
        RecyclerView.ViewHolder(binding.root)
}
