package ani.dantotsu.media

import android.os.Bundle
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.mal.MALQueries
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.databinding.ActivityMediaListViewBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.home.MergedReadingAdapter
import ani.dantotsu.initActivity
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.others.getSerialized
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A plain list of media, opened either with the list already in hand (via [passedMedia] and friends)
 * or, for a MAL interest stack, with just the stack's URL — in which case this screen resolves the
 * stack itself behind a spinner instead of the caller stalling on it. See [StackResolver].
 */
class MediaListViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaListViewBinding

    // Filled in from the caller, or once a stack has been resolved.
    private var mediaList: MutableList<Media> = mutableListOf()
    private var muMediaList: ArrayList<MUMedia> = arrayListOf()
    private var unreadInfo: Map<Int, UnreadChapterInfo>? = null
    private var unreleasedInfo: Map<Int, UnreleasedEpisodeInfo>? = null
    private var description: String? = null
    private var screenTitle: String = ""
    private var fromMalStack = false
    private var isStack = false

    /** Timestamp-sorted merge of [mediaList] and [muMediaList]; null unless MU items are present. */
    private var combinedItems: List<Any>? = null

    private var currentMode = 0
    private var showNovels = true
    private var screenWidth = 0f
    private lateinit var mediaViewButton: ImageView

    // Set only when this list is a "Recommended" carousel's full list, so cards can badge
    // entries whose type differs from the media the recommendations came from.
    private var recommendationSource: Media? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaListViewBinding.inflate(layoutInflater)
        ThemeManager(this).applyTheme()
        initActivity(this)
        if (!PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            this.window.statusBarColor =
                ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true

        } else {
            binding.root.fitsSystemWindows = false
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            hideSystemBarsExtendView()
            binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
        }

        setContentView(binding.root)

        val primaryColor = getThemeColor(com.google.android.material.R.attr.colorSurface)
        val primaryTextColor = getThemeColor(com.google.android.material.R.attr.colorPrimary)

        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.listTitle.setTextColor(primaryTextColor)
        binding.listTitle.isSelected = true
        binding.listBack.setOnClickListener { finish() }

        screenWidth = resources.displayMetrics.run { widthPixels / density }
        screenTitle = intent.getStringExtra("title") ?: ""
        binding.listTitle.text = screenTitle
        showNovels = PrefManager.getCustomVal(SHOW_NOVELS, true)

        currentMode = PrefManager.getCustomVal("mediaView", 0)
        mediaViewButton = if (currentMode == 1) binding.mediaList else binding.mediaGrid
        // Selected button fully opaque, the other at ~33%
        binding.mediaList.imageAlpha = if (currentMode == 1) 255 else 84
        binding.mediaGrid.imageAlpha = if (currentMode == 1) 84 else 255
        binding.mediaList.setOnClickListener { changeView(1, binding.mediaList) }
        binding.mediaGrid.setOnClickListener { changeView(0, binding.mediaGrid) }

        val stackUrl = intent.getStringExtra("stackUrl")
        isStack = stackUrl != null
        // A stack resolved before a rotation is republished through the statics below, so a
        // recreation reuses it instead of scraping and matching the whole thing again.
        if (stackUrl != null && passedMedia == null) {
            loadStack(stackUrl)
        } else {
            fromMalStack = passedMedia != null
            mediaList =
                passedMedia ?: intent.getSerialized("media") as? ArrayList<Media> ?: ArrayList()
            muMediaList = passedMuMedia ?: arrayListOf()
            unreadInfo = passedUnreadInfo
            unreleasedInfo = passedUnreleasedInfo
            description = passedDescription
            recommendationSource = passedRecommendationSource
            showContent()
        }
    }

    /**
     * Scrapes and resolves a MAL interest stack in place. It's a page scrape, an AniList batch and
     * then a lookup per unmatched manga, so the list stays behind a spinner until it's done rather
     * than the caller holding a toast up for the whole trip.
     */
    private fun loadStack(stackUrl: String) {
        fromMalStack = true
        description = passedDescription
        binding.mediaListProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val isAnime = intent.getBooleanExtra("isAnime", false)
            val queries = MALQueries()
            val entries = withContext(Dispatchers.IO) {
                runCatching { queries.getStackEntries(stackUrl) }.getOrDefault(emptyList())
            }
            // Opened from a stack list, the name and description came with the row — a blank name
            // means the caller had neither (a stack link inside another stack's description), so
            // that's the only case worth a second scrape. A stack with no description is normal.
            if (screenTitle.isBlank()) {
                val info = withContext(Dispatchers.IO) {
                    runCatching { queries.getStackNameAndDescription(stackUrl) }.getOrNull()
                }
                screenTitle = info?.first.orEmpty()
                description = info?.second
            }
            val resolved = StackResolver.resolve(entries, isAnime)
            mediaList = resolved.media.toMutableList()
            unreadInfo = resolved.unread
            unreleasedInfo = resolved.unreleased
            // Republish for a possible recreation (see onCreate); the intent keeps whatever we
            // had to scrape, so a rotation doesn't lose the stack's name either.
            intent.putExtra("title", screenTitle)
            passedMedia = ArrayList(resolved.media)
            passedUnreadInfo = resolved.unread
            passedUnreleasedInfo = resolved.unreleased
            passedDescription = description
            binding.mediaListProgress.visibility = View.GONE
            showContent()
        }
    }

    /** Renders whatever [mediaList] holds; called once the list is actually known. */
    private fun showContent() {
        // Build a timestamp-sorted merged list when MU items are present
        combinedItems = if (muMediaList.isNotEmpty()) {
            (mediaList.map { it to (it.userUpdatedAt ?: 0L) } +
                muMediaList.map { it to (it.updatedAt ?: 0L) })
                .sortedByDescending { (_, ts) -> ts }
                .map { (item, _) -> item }
        } else null

        val description = description
        if (!description.isNullOrBlank()) {
            binding.listDescription.visibility = View.VISIBLE
            binding.listDescription.setOnClickListener {
                val descView = TextView(this).apply {
                    setPadding(32, 16, 32, 16)
                    text = HtmlCompat.fromHtml(description, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    textSize = 14f
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
                Linkify.addLinks(descView, Linkify.WEB_URLS)
                StackListAdapter.interceptLinks(
                    descView,
                    getThemeColor(com.google.android.material.R.attr.colorPrimary),
                    intent.getBooleanExtra("isAnime", false)
                )
                CustomBottomDialog.newInstance().apply {
                    setTitleText(screenTitle)
                    addView(descView)
                }.show(supportFragmentManager, "stackDesc")
            }
        }

        // Toggle to hide novels from a MAL interest stack's list. The choice is shared by every
        // stack, so it's persisted under a single pref rather than one per stack.
        // Only relevant when there's actually a novel in the list to hide.
        val hasNovels = fromMalStack && combinedItems == null &&
            mediaList.any { it.format?.equals("NOVEL", ignoreCase = true) == true }
        if (hasNovels) {
            binding.listNovelToggle.visibility = View.VISIBLE
            binding.listNovelToggle.imageAlpha = if (showNovels) 255 else 84
            binding.listNovelToggle.setOnClickListener {
                showNovels = !showNovels
                PrefManager.setCustomVal(SHOW_NOVELS, showNovels)
                binding.listNovelToggle.imageAlpha = if (showNovels) 255 else 84
                updateTitle()
                buildAdapter(currentMode)
            }
        }

        // A stack that resolved to nothing (scrape failed, or no entry matched anywhere) would
        // otherwise leave an empty screen with no explanation.
        if (isStack && mediaList.isEmpty() && combinedItems == null) {
            binding.mediaListEmpty.text = getString(R.string.stack_empty)
            binding.mediaListEmpty.visibility = View.VISIBLE
        } else {
            binding.mediaListEmpty.visibility = View.GONE
        }

        updateTitle()
        buildAdapter(currentMode)
    }

    private fun filteredMediaList(): MutableList<Media> =
        if (showNovels) mediaList
        else mediaList.filterNot { it.format?.equals("NOVEL", ignoreCase = true) == true }
            .toMutableList()

    private fun updateTitle() {
        val totalCount = combinedItems?.count() ?: filteredMediaList().count()
        binding.listTitle.text = "$screenTitle ($totalCount)"
    }

    private fun buildAdapter(mode: Int) {
        val combined = combinedItems
        if (combined != null) {
            binding.mediaRecyclerView.adapter = MergedReadingAdapter(combined, mode)
            binding.mediaRecyclerView.layoutManager = GridLayoutManager(
                this,
                if (mode == 1) 1 else (screenWidth / 120f).toInt()
            )
            return
        }

        val list = filteredMediaList()
        val localUnreadInfo = unreadInfo
        val localUnreleasedInfo = unreleasedInfo
        // Use custom adapter based on what info we have
        when {
            localUnreadInfo != null -> {
                // Manga with unread chapters
                binding.mediaRecyclerView.adapter =
                    ani.dantotsu.home.UnreadChaptersAdapter(list, localUnreadInfo, mode, fromMalStack)
            }

            localUnreleasedInfo != null -> {
                // Anime with unreleased episodes
                binding.mediaRecyclerView.adapter =
                    ani.dantotsu.home.UnreleasedEpisodesAdapter(list, localUnreleasedInfo, mode)
            }

            else -> {
                // Standard adapter
                binding.mediaRecyclerView.adapter =
                    MediaAdaptor(
                        mode, list, this,
                        fromMalStack = fromMalStack,
                        currentMedia = recommendationSource
                    )
            }
        }
        binding.mediaRecyclerView.layoutManager = GridLayoutManager(
            this,
            if (mode == 1) 1 else (screenWidth / 120f).toInt()
        )
    }

    private fun changeView(mode: Int, current: ImageView) {
        mediaViewButton.imageAlpha = 84
        mediaViewButton = current
        current.imageAlpha = 255
        currentMode = mode
        PrefManager.setCustomVal("mediaView", mode)
        buildAdapter(mode)
    }

    override fun onDestroy() {
        super.onDestroy()
        // These are passed via static fields (large lists would blow the Bundle size
        // limit as extras) so they must survive a rotation-triggered recreation; only
        // clear them once this activity is actually going away for good.
        if (!isChangingConfigurations) {
            passedMedia = null
            passedMuMedia = null
            passedUnreadInfo = null
            passedUnreleasedInfo = null
            passedDescription = null
            passedRecommendationSource = null
        }
    }

    companion object {
        // Shared by all interest stacks, not stored per stack.
        const val SHOW_NOVELS = "stackShowNovels"

        var passedMedia: ArrayList<Media>? = null
        var passedMuMedia: ArrayList<MUMedia>? = null
        var passedUnreadInfo: Map<Int, UnreadChapterInfo>? = null
        var passedUnreleasedInfo: Map<Int, UnreleasedEpisodeInfo>? = null
        var passedDescription: String? = null
        var passedRecommendationSource: Media? = null
    }
}
