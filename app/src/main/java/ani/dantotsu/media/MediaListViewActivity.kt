package ani.dantotsu.media

import android.content.Intent
import android.os.Bundle
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.mal.MALQueries
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.mangabaka.MangaBakaStacks
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.notifications.unread.ComickUnreadEntry
import ani.dantotsu.databinding.ActivityMediaListViewBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.home.MergedReadingAdapter
import ani.dantotsu.initActivity
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.others.getSerialized
import ani.dantotsu.openLinkInAppOrBrowser
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.snackString
import ani.dantotsu.util.materialDialog
import ani.dantotsu.settings.enableSettingsLongPress
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
    private var comickList: ArrayList<ComickUnreadEntry> = arrayListOf()
    private var unreadInfo: Map<Int, UnreadChapterInfo>? = null
    private var unreleasedInfo: Map<Int, UnreleasedEpisodeInfo>? = null
    private var description: String? = null
    private var screenTitle: String = ""
    private var fromMalStack = false
    private var isStack = false

    /** Set for a MangaBaka stack; see [loadMangaBakaStack]. */
    private var mangaBakaStackId: Long? = null
    private var mangaBakaStack: MangaBakaStacks.StackDetail? = null
    private var mangaBakaStatus: MangaBakaStacks.StackStatus? = null

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
        // Before inflating, not after. Every `?attr/` in the layout is resolved as the views are
        // created, so a theme applied afterwards arrives too late for any of them: the spinner's
        // colorPrimary tint came from the manifest's stock Theme.Dantotsu rather than whichever
        // theme the user picked, while the title right above it — coloured in code further down,
        // by which point setTheme has run — used the real one.
        ThemeManager(this).applyTheme()
        binding = ActivityMediaListViewBinding.inflate(layoutInflater)
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
        val primaryTextColor = getThemeColor(androidx.appcompat.R.attr.colorPrimary)

        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.listTitle.setTextColor(primaryTextColor)
        binding.listTitle.isSelected = true
        binding.listBack.enableSettingsLongPress()
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
        // A MangaBaka stack comes by id, or as a mangabaka.org/stacks/{id}-{slug} link.
        mangaBakaStackId = intent.getLongExtra(EXTRA_MANGABAKA_STACK_ID, -1L).takeIf { it > 0 }
            ?: intent.data?.takeIf { it.pathSegments.firstOrNull() == "stacks" }
                ?.let { StackResolver.mangaBakaStackId(it.pathSegments.getOrNull(1)) }
        isStack = stackUrl != null || mangaBakaStackId != null
        // A stack from before a rotation is republished through the statics below, so a
        // recreation reuses it instead of running the whole lookup again.
        val mbStackId = mangaBakaStackId
        if (mbStackId != null && passedMedia == null) {
            loadMangaBakaStack(mbStackId)
        } else if (stackUrl != null && passedMedia == null) {
            loadStack(stackUrl)
        } else {
            fromMalStack = passedMedia != null
            mediaList =
                passedMedia ?: intent.getSerialized("media") as? ArrayList<Media> ?: ArrayList()
            muMediaList = passedMuMedia ?: arrayListOf()
            comickList = passedComick ?: arrayListOf()
            unreadInfo = passedUnreadInfo
            unreleasedInfo = passedUnreleasedInfo
            description = passedDescription
            recommendationSource = passedRecommendationSource
            showContent()
            // A recreated MangaBaka stack screen still needs the stack itself for its actions.
            mbStackId?.let { loadMangaBakaMeta(it) }
        }
    }

    /**
     * [loadStack] for a MangaBaka stack: one request returns the stack with every entry's series and
     * cross-site ids, which [StackResolver.resolveMangaBaka] turns into media.
     */
    private fun loadMangaBakaStack(stackId: Long) {
        fromMalStack = true
        description = passedDescription
        binding.mediaListProgress.visibility = View.VISIBLE
        binding.mediaListEmpty.visibility = View.GONE
        binding.listTitle.text = screenTitle
        showDescriptionButton()

        lifecycleScope.launch {
            val stack = MangaBakaStacks.getStack(stackId)
            if (stack == null) {
                mediaList = mutableListOf()
                showContent()
                return@launch
            }
            applyMangaBakaStack(stack)
            val resolved = StackResolver.resolveMangaBaka(stack.entries.orEmpty())
            mediaList = resolved.media.toMutableList()
            unreadInfo = resolved.unread
            unreleasedInfo = null
            passedMedia = ArrayList(resolved.media)
            passedUnreadInfo = resolved.unread
            passedUnreleasedInfo = null
            binding.mediaListProgress.visibility = View.GONE
            showContent()
        }
    }

    /** Fetches just the stack (not its media), for a recreated screen's title and actions. */
    private fun loadMangaBakaMeta(stackId: Long) {
        lifecycleScope.launch {
            MangaBakaStacks.getStack(stackId)?.let { applyMangaBakaStack(it, keepTitleCount = true) }
        }
    }

    private fun applyMangaBakaStack(stack: MangaBakaStacks.StackDetail, keepTitleCount: Boolean = false) {
        mangaBakaStack = stack
        screenTitle = stack.title
        intent.putExtra("title", screenTitle)
        description = stack.description
        passedDescription = description
        if (keepTitleCount) updateTitle() else binding.listTitle.text = screenTitle
        showDescriptionButton()
        binding.listStackActions.visibility = View.VISIBLE
        binding.listStackActions.setOnClickListener { showStackActions(it) }
        lifecycleScope.launch {
            TrackerSessions.await()
            if (MangaBaka.token == null) return@launch
            // The owner route answers only for the owner, and carries the token an edit needs.
            if (!stack.isMine || stack.token == null) {
                MangaBakaStacks.getMyStack(stack.id).getOrNull()?.let { mine ->
                    if (mangaBakaStack?.id == mine.id) mangaBakaStack = mine
                }
            }
            mangaBakaStatus = MangaBakaStacks.getStatus(listOf(stack.id))[stack.id]
        }
    }

    /** Vote/save/follow for anyone logged in; edit, visibility and delete for the stack's owner. */
    private fun showStackActions(anchor: View) {
        val stack = mangaBakaStack ?: return
        val status = mangaBakaStatus
        val loggedIn = MangaBaka.token != null
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        if (loggedIn) {
            val votes = status?.voteCount ?: stack.voteCount
            menu.add(0, ACTION_VOTE, 0, getString(R.string.stack_vote_count, votes))
                .setCheckable(true).isChecked = status?.hasVoted == true
            menu.add(0, ACTION_SAVE, 1, getString(R.string.stack_save))
                .setCheckable(true).isChecked = status?.hasSaved == true
            menu.add(0, ACTION_FOLLOW, 2, getString(R.string.stack_follow))
                .setCheckable(true).isChecked = status?.hasFollowed == true
        }
        if (stack.isMine) {
            menu.add(1, ACTION_EDIT, 10, getString(R.string.stack_edit))
            menu.add(1, ACTION_VISIBILITY, 11, getString(R.string.stack_change_visibility))
            menu.add(1, ACTION_DELETE, 12, getString(R.string.stack_delete))
        }
        menu.add(2, ACTION_OPEN, 20, getString(R.string.open_on_mangabaka))
        menu.add(2, ACTION_SHARE, 21, getString(R.string.share))
        popup.setOnMenuItemClickListener { item ->
            val url = stack.canonicalUrl ?: "${MangaBaka.WEB_URL}/stacks/${stack.id}"
            when (item.itemId) {
                ACTION_VOTE -> toggleStackStatus(stack.id) { MangaBakaStacks.setVoted(stack.id, status?.hasVoted != true) }
                ACTION_SAVE -> toggleStackStatus(stack.id) { MangaBakaStacks.setSaved(stack.id, status?.hasSaved != true) }
                ACTION_FOLLOW -> toggleStackStatus(stack.id) { MangaBakaStacks.setFollowed(stack.id, status?.hasFollowed != true) }
                ACTION_EDIT -> editLauncher.launch(MangaBakaStackEditActivity.editIntent(this, stack.id))
                ACTION_VISIBILITY -> pickVisibility(stack)
                ACTION_DELETE -> confirmDelete(stack)
                ACTION_OPEN -> openLinkInBrowser(url)
                ACTION_SHARE -> startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                        stack.title
                    )
                )
            }
            true
        }
        popup.show()
    }

    private fun toggleStackStatus(
        stackId: Long,
        call: suspend () -> MangaBakaStacks.Result<MangaBakaStacks.StackStatus?>,
    ) {
        lifecycleScope.launch {
            when (val result = call()) {
                is MangaBakaStacks.Result.Ok ->
                    // Not every toggle route returns the new status; read it back when it doesn't.
                    mangaBakaStatus = result.value
                        ?: MangaBakaStacks.getStatus(listOf(stackId))[stackId]

                is MangaBakaStacks.Result.Error -> MangaBakaStackUi.reportError(result)
            }
        }
    }

    private fun pickVisibility(stack: MangaBakaStacks.StackDetail) {
        val values = MangaBakaStacks.VISIBILITIES
        val labels = values.map { getString(MangaBakaStackUi.visibilityLabel(it)) }.toTypedArray()
        materialDialog()
            .setTitle(R.string.stack_change_visibility)
            .setSingleChoiceItems(labels, values.indexOf(stack.visibility).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                val picked = values[which]
                if (picked == stack.visibility) return@setSingleChoiceItems
                lifecycleScope.launch {
                    when (val result = MangaBakaStacks.setVisibility(stack.id, picked)) {
                        is MangaBakaStacks.Result.Ok -> {
                            mangaBakaStack = mangaBakaStack?.copy(visibility = picked)
                            snackString(getString(R.string.stack_visibility_changed, labels[which]))
                        }

                        is MangaBakaStacks.Result.Error -> MangaBakaStackUi.reportError(result)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(stack: MangaBakaStacks.StackDetail) {
        materialDialog()
            .setTitle(R.string.stack_delete)
            .setMessage(getString(R.string.stack_delete_confirm, stack.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    when (val result = MangaBakaStacks.deleteStack(stack.id)) {
                        is MangaBakaStacks.Result.Ok -> {
                            snackString(R.string.stack_deleted)
                            setResult(RESULT_OK)
                            finish()
                        }

                        is MangaBakaStacks.Result.Error -> MangaBakaStackUi.reportError(result)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Reloads the stack after the edit screen saved it. */
    private val editLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val id = mangaBakaStackId ?: return@registerForActivityResult
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            setResult(RESULT_OK)
            passedMedia = null
            mediaList = mutableListOf()
            binding.mediaRecyclerView.adapter = null
            loadMangaBakaStack(id)
        }

    /**
     * Scrapes and resolves a MAL interest stack in place. It's a page scrape, an AniList batch and
     * then a lookup per unmatched manga, so the list stays behind a spinner until it's done rather
     * than the caller holding a toast up for the whole trip. Title and description, on the other
     * hand, are usually already known before this is even called — the caller's row already
     * scraped and passed them through (see [StackResolver.open]) — so they're shown immediately
     * below rather than waiting on the slow entry scrape + AniList resolve too.
     */
    private fun loadStack(stackUrl: String) {
        fromMalStack = true
        description = passedDescription
        binding.mediaListProgress.visibility = View.VISIBLE

        // Show whatever's already known (usually both) right away; only a stack opened with no
        // title at all (a link inside another stack's description) has nothing to show yet.
        binding.listTitle.text = screenTitle
        showDescriptionButton()

        lifecycleScope.launch {
            val isAnime = intent.getBooleanExtra("isAnime", false)
            val queries = MALQueries()

            // The caller's row usually has both by now, but a stack opened from a bare link only
            // has the URL — fetch whichever of title/description is still missing.
            if (screenTitle.isBlank() || description.isNullOrBlank()) {
                val info = withContext(Dispatchers.IO) {
                    runCatching { queries.getStackNameAndDescription(stackUrl) }.getOrNull()
                }
                if (screenTitle.isBlank()) screenTitle = info?.first.orEmpty()
                if (description.isNullOrBlank()) description = info?.second
                // Republish for a possible recreation (see onCreate); the intent keeps whatever
                // we had to scrape, so a rotation doesn't lose the stack's name either.
                intent.putExtra("title", screenTitle)
                passedDescription = description
                binding.listTitle.text = screenTitle
                showDescriptionButton()
            }

            val entries = withContext(Dispatchers.IO) {
                runCatching { queries.getStackEntries(stackUrl) }.getOrDefault(emptyList())
            }
            val resolved = StackResolver.resolve(entries, isAnime)
            mediaList = resolved.media.toMutableList()
            unreadInfo = resolved.unread
            unreleasedInfo = resolved.unreleased
            passedMedia = ArrayList(resolved.media)
            passedUnreadInfo = resolved.unread
            passedUnreleasedInfo = resolved.unreleased
            binding.mediaListProgress.visibility = View.GONE
            showContent()
        }
    }

    /** Shows the info button and wires it to open [description] in a bottom dialog, if there is one. */
    private fun showDescriptionButton() {
        val description = description
        if (description.isNullOrBlank()) return
        binding.listDescription.visibility = View.VISIBLE
        binding.listDescription.setOnClickListener {
            val descView = TextView(this).apply {
                setPadding(32, 16, 32, 16)
                textSize = 14f
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            }
            if (mangaBakaStackId != null) {
                // MangaBaka descriptions are Markdown, not MAL's HTML.
                buildMarkwon(this, userInputContent = true, linkResolver = { openLinkInAppOrBrowser(it) })
                    .setMarkdown(descView, description)
            } else {
                descView.text = HtmlCompat.fromHtml(description, HtmlCompat.FROM_HTML_MODE_LEGACY)
                Linkify.addLinks(descView, Linkify.WEB_URLS)
                StackListAdapter.interceptLinks(
                    descView,
                    getThemeColor(androidx.appcompat.R.attr.colorPrimary),
                    intent.getBooleanExtra("isAnime", false)
                )
            }
            CustomBottomDialog.newInstance().apply {
                setTitleText(screenTitle)
                addView(descView)
            }.show(supportFragmentManager, "stackDesc")
        }
    }

    /** Renders whatever [mediaList] holds; called once the list is actually known. */
    private fun showContent() {
        // An unread list orders MangaUpdates entries by their newest release, which is fetched per
        // series — so hold the screen on its spinner until those are in rather than drawing them
        // all at the bottom and reshuffling. See [ani.dantotsu.home.UnreadOrder.awaitReleaseDates].
        unreadInfo?.takeIf { !isStack }?.let {
            val pending: List<Any> = mediaList + muMediaList
            if (!ani.dantotsu.home.UnreadOrder.awaitReleaseDates(lifecycleScope, pending) {
                    showContent()
                }
            ) {
                binding.mediaListProgress.visibility = View.VISIBLE
                return
            }
        }
        binding.mediaListProgress.visibility = View.GONE

        // Build a merged list when MU items are present. An unread list is ordered by how far
        // behind each entry is (or how recently it updated) rather than by when the user last
        // touched it, so it uses the same rule the home row does instead of the last-read merge
        // that "Continue Reading" wants.
        val localUnreadInfo = unreadInfo
        val order = passedOrder
        combinedItems = when {
            // The caller's row already settled the order; show it as it was.
            order != null -> order
            muMediaList.isEmpty() && comickList.isEmpty() -> null
            // [isStack], not [fromMalStack] — the latter is set for any caller that passed media
            // in, which includes the unread list itself; only stackUrl marks an actual stack.
            localUnreadInfo != null && !isStack ->
                ani.dantotsu.home.UnreadOrder.sort(mediaList + muMediaList + comickList, localUnreadInfo)

            else -> (mediaList.map { it to (it.userUpdatedAt ?: 0L) } +
                muMediaList.map { it to (it.updatedAt ?: 0L) })
                .sortedByDescending { (_, ts) -> ts }
                .map { (item, _) -> item }
        }

        showDescriptionButton()

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
        val localUnreadInfo = unreadInfo
        val localUnreleasedInfo = unreleasedInfo
        if (combined != null) {
            // UnreadChaptersAdapter takes Media and MUMedia alike, so an unread list keeps its
            // per-entry chapter counts and source labels here rather than being flattened into
            // the plain reading adapter the moment a MangaUpdates series is in it.
            binding.mediaRecyclerView.adapter = if (localUnreadInfo != null)
                ani.dantotsu.home.UnreadChaptersAdapter(combined, localUnreadInfo, mode, fromMalStack)
            else MergedReadingAdapter(combined, mode)
            binding.mediaRecyclerView.layoutManager = GridLayoutManager(
                this,
                if (mode == 1) listColumns(this) else (screenWidth / 120f).toInt()
            )
            return
        }

        val list = filteredMediaList()
        // Use custom adapter based on what info we have
        when {
            localUnreadInfo != null -> {
                // Manga with unread chapters. Sorted here rather than trusted from the caller: the
                // home row's copy was ordered whenever it last drew, so a sort setting changed
                // since then would arrive stale. A stack is left alone — its order is the stack's
                // own, and unread info riding along doesn't make it an unread list.
                val ordered =
                    if (isStack) list else ani.dantotsu.home.UnreadOrder.sort(list, localUnreadInfo)
                binding.mediaRecyclerView.adapter =
                    ani.dantotsu.home.UnreadChaptersAdapter(ordered, localUnreadInfo, mode, fromMalStack)
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
            if (mode == 1) listColumns(this) else (screenWidth / 120f).toInt()
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
            passedComick = null
            passedOrder = null
            passedUnreadInfo = null
            passedUnreleasedInfo = null
            passedDescription = null
            passedRecommendationSource = null
        }
    }

    companion object {
        // Shared by all interest stacks, not stored per stack.
        const val SHOW_NOVELS = "stackShowNovels"

        /** A MangaBaka stack id (Long) to load instead of a MAL `stackUrl`. */
        const val EXTRA_MANGABAKA_STACK_ID = "mangaBakaStackId"

        private const val ACTION_VOTE = 1
        private const val ACTION_SAVE = 2
        private const val ACTION_FOLLOW = 3
        private const val ACTION_EDIT = 10
        private const val ACTION_VISIBILITY = 11
        private const val ACTION_DELETE = 12
        private const val ACTION_OPEN = 20
        private const val ACTION_SHARE = 21

        var passedMedia: ArrayList<Media>? = null
        var passedMuMedia: ArrayList<MUMedia>? = null
        /** Unread titles only Comick knows; the rest arrive as [passedMedia]/[passedMuMedia]. */
        var passedComick: ArrayList<ComickUnreadEntry>? = null
        /**
         * The exact order to show [passedMedia] and [passedMuMedia] in, as the home row that opened
         * this has them — for a row sorted by something this screen can't recompute (the reader's
         * local history). Null lets the screen sort them itself.
         */
        var passedOrder: ArrayList<Any>? = null
        var passedUnreadInfo: Map<Int, UnreadChapterInfo>? = null
        var passedUnreleasedInfo: Map<Int, UnreleasedEpisodeInfo>? = null
        var passedDescription: String? = null
        var passedRecommendationSource: Media? = null

    }
}
