package ani.dantotsu.media.comments

import androidx.transition.TransitionManager
import ani.dantotsu.isOnline
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.manga.mangareader.ChapterLoaderDialog
import android.content.res.ColorStateList
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import ani.dantotsu.getThemeColor
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context.INPUT_METHOD_SERVICE
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.PopupMenu
import androidx.core.animation.doOnEnd
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comments.Comment
import ani.dantotsu.connections.comments.CommentResponse
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.databinding.DialogEdittextBinding
import ani.dantotsu.databinding.FragmentCommentsBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.setBaseline
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.Section
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.MarkwonEditorTextWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@SuppressLint("ClickableViewAccessibility")
class CommentsFragment : Fragment() {
    private companion object {
        const val MAX_COMMENT_LENGTH = 300

        /** How far up a notified reply's chain is followed to its top-level comment. */
        const val MAX_THREAD_HOPS = 10
    }

    private val activityViewModel: ani.dantotsu.media.MediaDetailsViewModel by activityViewModels()
    lateinit var binding: FragmentCommentsBinding
    lateinit var activity: MediaDetailsActivity
    private var interactionState = InteractionState.NONE
    private var commentWithInteraction: CommentItem? = null
    private val section = Section()
    private val adapter = GroupieAdapter()
    private var tag: Int? = null
    private var filterTag: Int? = null
    private var mediaId: Int = -1
    var mediaName: String = ""
    private var backgroundColor: Int = 0
    var pagesLoaded = 1
    var totalPages = 1

    /** The comment box belongs to the activity and outlives this view: these come off with it. */
    private var markwonWatcher: TextWatcher? = null
    private var limitWatcher: TextWatcher? = null

    /** The next comment goes out as a spoiler; see the spoiler button. */
    private var spoilerMode = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCommentsBinding.inflate(inflater, container, false)
        binding.commentsLayout.isNestedScrollingEnabled = true
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MediaDetailsActivity
        if (!activity.isNavBarReady) return

        binding.commentsListContainer.setBaseline(
            activity.navBar,
            0
        )

        //get the media id from the intent
        val mediaId = arguments?.getInt("mediaId") ?: -1
        mediaName = arguments?.getString("mediaName") ?: "unknown"
        if (mediaId == -1) {
            snackString(getString(R.string.comment_invalid_media))
            return
        }
        this.mediaId = mediaId
        backgroundColor = (binding.root.background as? ColorDrawable)?.color ?: 0

        val markwon = buildMarkwon(activity, fragment = this@CommentsFragment)

        activity.binding.commentUserAvatar.loadImage(Anilist.avatar)
        val markwonEditor = MarkwonEditor.create(markwon)
        markwonWatcher = MarkwonEditorTextWatcher.withProcess(markwonEditor).also {
            activity.binding.commentInput.addTextChangedListener(it)
        }

        binding.commentsRefresh.setOnRefreshListener {
            lifecycleScope.launch {
                loadAndDisplayComments()
                binding.commentsRefresh.isRefreshing = false
            }
            activity.binding.commentReplyToContainer.visibility = View.GONE
        }

        binding.commentsList.adapter = adapter
        binding.commentsList.layoutManager = LinearLayoutManager(activity)

        // Loaded whether or not the start-up sign-in has finished: a read signs in itself if it has
        // to. Waiting on a token here left the tab on its spinner for good whenever the sign-in
        // hadn't happened, or had failed, by the time the tab opened.
        lifecycleScope.launch {
            val commentId = arguments?.getInt("commentId")
            if (commentId != null && commentId > 0) {
                loadCommentThread(commentId)
            } else {
                loadAndDisplayComments()
            }
            // Still signed out after trying (no AniList login, or the server refused it): reading
            // works for nobody then, and neither would writing.
            if (CommentsAPI.authToken == null) {
                activity.binding.commentMessageContainer.visibility = View.GONE
            }
        }

        binding.commentSort.setOnClickListener { sortView ->
            fun sortComments(sortOrder: String) {
                val groups = section.groups
                when (sortOrder) {
                    "newest" -> groups.sortByDescending { CommentItem.timestampToMillis((it as CommentItem).comment.timestamp) }
                    "oldest" -> groups.sortBy { CommentItem.timestampToMillis((it as CommentItem).comment.timestamp) }
                    "highest_rated" -> groups.sortByDescending { (it as CommentItem).comment.upvotes - it.comment.downvotes }
                    "lowest_rated" -> groups.sortBy { (it as CommentItem).comment.upvotes - it.comment.downvotes }
                }
                section.update(groups)
            }

            val popup = PopupMenu(activity, sortView)
            popup.setOnMenuItemClickListener { item ->
                val sortOrder = when (item.itemId) {
                    R.id.comment_sort_newest -> "newest"
                    R.id.comment_sort_oldest -> "oldest"
                    R.id.comment_sort_highest_rated -> "highest_rated"
                    R.id.comment_sort_lowest_rated -> "lowest_rated"
                    else -> return@setOnMenuItemClickListener false
                }
                PrefManager.setVal(PrefName.CommentSortOrder, sortOrder)
                if (totalPages > pagesLoaded) {
                    lifecycleScope.launch {
                        loadAndDisplayComments()
                        activity.binding.commentReplyToContainer.visibility = View.GONE
                    }
                } else {
                    sortComments(sortOrder)
                }
                binding.commentsList.scrollToPosition(0)
                true
            }
            popup.inflate(R.menu.comments_sort_menu)
            popup.show()
        }
        binding.openRules.setOnClickListener {
            activity.customAlertDialog().apply {
                setTitle(getString(R.string.commenting_rules))
                    .setMessage(getString(R.string.comment_rules_text))
                setNegButton(getString(R.string.comment_rules_accept)) {}
                show()
            }
        }

        binding.commentFilter.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("commentFilter") == null) {
                CommentFilterBottomSheet().show(childFragmentManager, "commentFilter")
            }
        }
        updateFilterButton()

        var isFetching = false
        binding.commentsList.setOnTouchListener(
            object : View.OnTouchListener {
                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    if (event?.action == MotionEvent.ACTION_UP) {
                        if (!binding.commentsList.canScrollVertically(1) && !isFetching &&
                            (binding.commentsList.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() == (binding.commentsList.adapter!!.itemCount - 1)
                        ) {
                            if (pagesLoaded < totalPages && totalPages > 1) {
                                binding.commentBottomRefresh.visibility = View.VISIBLE
                                loadMoreComments()
                                lifecycleScope.launch {
                                    kotlinx.coroutines.delay(1000)
                                    withContext(Dispatchers.Main) {
                                        binding.commentBottomRefresh.visibility = View.GONE
                                    }
                                }
                            } else {
                                //snackString("No more comments") fix spam?
                                Logger.log("No more comments")
                            }
                        }
                    }
                    return false
                }

                private fun loadMoreComments() {
                    isFetching = true
                    lifecycleScope.launch {
                        val comments = fetchComments()
                        comments?.comments?.filter(::passesFilters)?.forEach { comment ->
                            updateUIWithComment(comment)
                        }
                        totalPages = comments?.totalPages ?: 1
                        pagesLoaded++
                        isFetching = false
                    }
                }

                private suspend fun fetchComments(): CommentResponse? {
                    return withContext(Dispatchers.IO) {
                        CommentsAPI.getCommentsForId(
                            mediaId,
                            pagesLoaded + 1,
                            filterTag,
                            PrefManager.getVal(PrefName.CommentSortOrder, "newest")
                        )
                    }
                }

                //adds additional comments to the section
                private suspend fun updateUIWithComment(comment: Comment) {
                    withContext(Dispatchers.Main) {
                        section.add(
                            CommentItem(
                                comment,
                                buildMarkwon(activity, fragment = this@CommentsFragment),
                                section,
                                this@CommentsFragment,
                                backgroundColor,
                                0
                            )
                        )
                    }
                }
            })

        limitWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: android.text.Editable?) {
                if ((activity.binding.commentInput.text.length) > 300) {
                    activity.binding.commentInput.text.delete(
                        300,
                        activity.binding.commentInput.text.length
                    )
                    snackString(getString(R.string.comment_too_long))
                }
            }
        }.also { activity.binding.commentInput.addTextChangedListener(it) }

        // Focusing the box brings in its tools — spoiler, GIF, tag, send — beside it. The box is a
        // weighted field, so the row makes room by itself; the transition animates the change.
        activity.binding.commentInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            TransitionManager.beginDelayedTransition(activity.binding.commentInputLayout)
            activity.binding.commentSpoiler.visibility = View.VISIBLE
            activity.binding.commentGif.isVisible = GifPickerBottomDialog.isAvailable
            activity.binding.commentLabel.visibility = View.VISIBLE
            activity.binding.commentSend.visibility = View.VISIBLE
        }

        // 3. Spoiler: the comment goes out wrapped in ||…||, which the comments render hidden.
        updateSpoilerButton()
        activity.binding.commentSpoiler.setOnClickListener {
            spoilerMode = !spoilerMode
            updateSpoilerButton()
        }

        // 5. GIF: Klipy's search, inserted as a markdown image the comments already render.
        activity.binding.commentGif.setOnClickListener {
            GifPickerBottomDialog().apply {
                onGifSelected = { url ->
                    val input = this@CommentsFragment.activity.binding.commentInput
                    val current = input.text.toString()
                    val gif = "![gif]($url)"
                    val text = if (current.isBlank()) gif else "$current\n$gif"
                    if (text.length <= MAX_COMMENT_LENGTH) {
                        input.setText(text)
                        input.setSelection(text.length)
                    } else snackString(getString(R.string.comment_too_long))
                }
            }.show(childFragmentManager, "gifPicker")
        }

        activity.binding.commentLabel.setOnClickListener {
            //alert dialog to enter a number, with a cancel and ok button
            activity.customAlertDialog().apply {
                val customView = DialogEdittextBinding.inflate(layoutInflater)
                setTitle(getString(R.string.enter_chapter_episode_tag))
                setCustomView(customView.root)
                setPosButton(getString(R.string.ok)) {
                    val text = customView.dialogEditText.text.toString()
                    tag = text.toIntOrNull()
                    if (tag == null) {
                        activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                            resources,
                            R.drawable.ic_label_off_24,
                            null
                        )
                    } else {
                        activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                            resources,
                            R.drawable.ic_label_24,
                            null
                        )
                    }
                }
                setNeutralButton(getString(R.string.comment_tag_clear)) {
                    tag = null
                    activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                        resources,
                        R.drawable.ic_label_off_24,
                        null
                    )
                }
                setNegButton(getString(R.string.cancel)) {
                    tag = null
                    activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                        resources,
                        R.drawable.ic_label_off_24,
                        null
                    )
                }
                show()
            }
        }

        activity.binding.commentSend.setOnClickListener {
            if (CommentsAPI.isBanned) {
                snackString("You are banned from commenting :(")
                return@setOnClickListener
            }

            if (PrefManager.getVal(PrefName.FirstComment)) {
                showCommentRulesDialog()
            } else {
                processComment()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    /**
     * 7. The comment box is the activity's, so what this view put on it has to come off with the
     * view. The text watchers were added on every view creation and never removed: after a few
     * visits to the tab, each keystroke ran the markdown highlighter and the length check several
     * times over, for views long gone.
     */
    override fun onDestroyView() {
        (requireActivity() as? MediaDetailsActivity)?.binding?.let { b ->
            markwonWatcher?.let { b.commentInput.removeTextChangedListener(it) }
            limitWatcher?.let { b.commentInput.removeTextChangedListener(it) }
            b.commentInput.onFocusChangeListener = null
            b.commentSpoiler.setOnClickListener(null)
            b.commentGif.setOnClickListener(null)
        }
        markwonWatcher = null
        limitWatcher = null
        super.onDestroyView()
    }

    private fun updateSpoilerButton() {
        val button = activity.binding.commentSpoiler
        button.alpha = if (spoilerMode) 1f else 0.6f
        button.setImageResource(if (spoilerMode) R.drawable.ic_round_visibility_off_24 else R.drawable.ic_round_visibility_24)
        button.imageTintList = ColorStateList.valueOf(
            activity.getThemeColor(
                if (spoilerMode) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnBackground
            )
        )
    }

    /** Shows [messageRes] in place of the list, or nothing when null. */
    private fun showMessage(messageRes: Int?) {
        binding.commentsMessage.isVisible = messageRes != null
        messageRes?.let { binding.commentsMessage.setText(it) }
    }

    private val isAnimeMedia: Boolean
        get() = activityViewModel.getMedia().value?.anime != null

    /**
     * 2. A comment's chapter or episode tag opens that chapter or episode from the source picked on
     * the read or watch tab — the chapter list loaded first if it hasn't been yet.
     */
    fun onTagClicked(tag: Int) {
        val media = activityViewModel.getMedia().value ?: return
        val key = tag.toString()
        if (media.anime != null) {
            if (media.anime?.episodes?.get(key) != null) {
                activityViewModel.onEpisodeClick(media, key, activity.supportFragmentManager)
            } else snackString(getString(R.string.comment_episode_not_loaded, tag))
            return
        }
        val manga = media.manga ?: return
        val selected = media.selected ?: activityViewModel.loadSelected(media)
        if (media.format == "NOVEL") {
            snackString(getString(R.string.comment_open_on_read_tab, tag))
            return
        }
        lifecycleScope.launch {
            val source = selected.sourceIndex
            if (manga.chapters.isNullOrEmpty()) {
                snackString(getString(R.string.comment_loading_chapter, tag))
                activityViewModel.loadMangaChapters(media, source)
                activityViewModel.getMangaChapters().value?.get(source)?.let { manga.chapters = it }
            }
            val chapter = manga.chapters?.values?.firstOrNull { chapterMatches(it.number, tag) }
            if (chapter == null) {
                snackString(getString(R.string.comment_chapter_not_found, tag))
                return@launch
            }
            activityViewModel.continueMedia = false
            manga.selectedChapter = chapter
            media.selected = selected
            activityViewModel.saveSelected(media.id, selected)
            ChapterLoaderDialog.newInstance(chapter, true)
                .show(activity.supportFragmentManager, "dialog")
        }
    }

    private fun chapterMatches(number: String, tag: Int): Boolean =
        number == tag.toString() || MediaNameAdapter.findChapterNumber(number)?.let { it == tag.toFloat() } == true

    /**
     * 4. A timestamp in an anime comment opens the episode it's about — its tag, else the episode
     * the list is filtered to — at that point. The position goes where the player keeps its own
     * resume point, so the player picks it up as it would any other.
     */
    fun onTimestampClicked(tag: Int?, positionMs: Long) {
        val episode = (tag ?: filterTag)?.toString()
        val media = activityViewModel.getMedia().value
        if (episode == null || media?.anime == null) {
            snackString(getString(R.string.comment_timestamp_no_episode))
            return
        }
        if (media.anime?.episodes?.get(episode) == null) {
            snackString(getString(R.string.comment_episode_not_loaded, episode.toInt()))
            return
        }
        PrefManager.setCustomVal("${media.id}_$episode", positionMs)
        activityViewModel.onEpisodeClick(media, episode, activity.supportFragmentManager)
    }

    fun timestampsLinkable() = isAnimeMedia

    override fun onResume() {
        super.onResume()
        tag = null
        section.groups.forEach {
            if (it is CommentItem && it.containsGif()) {
                it.notifyChanged()
            }
        }
    }

    enum class InteractionState {
        NONE, EDIT, REPLY
    }

    /**
     * Loads and displays the comments
     * Called when the activity is created
     * Or when the user refreshes the comments
     */
    private suspend fun loadAndDisplayComments() {
        binding.commentsProgressBar.visibility = View.VISIBLE
        binding.commentsList.visibility = View.GONE
        adapter.clear()
        section.clear()

        showMessage(null)
        // 6. Offline, say so rather than spin and show nothing.
        if (!isOnline(activity)) {
            binding.commentsProgressBar.visibility = View.GONE
            showMessage(R.string.comments_offline)
            return
        }
        val comments = withContext(Dispatchers.IO) {
            CommentsAPI.getCommentsForId(
                mediaId,
                tag = filterTag,
                sort = PrefManager.getVal(PrefName.CommentSortOrder, "newest")
            )
        }

        val sortedComments = sortComments(comments?.comments).filter(::passesFilters)
        sortedComments.forEach {
            withContext(Dispatchers.Main) {
                section.add(
                    CommentItem(
                        it,
                        buildMarkwon(activity, fragment = this@CommentsFragment),
                        section,
                        this@CommentsFragment,
                        backgroundColor,
                        0
                    )
                )
            }
        }

        totalPages = comments?.totalPages ?: 1
        binding.commentsProgressBar.visibility = View.GONE
        binding.commentsList.visibility = View.VISIBLE
        adapter.add(section)
        showMessage(
            when {
                comments == null -> R.string.comments_failed
                sortedComments.isEmpty() && filtersActive() -> R.string.comments_none_filtered
                sortedComments.isEmpty() -> R.string.comments_none
                else -> null
            }
        )
    }

    /**
     * 1. The comment a notification is about, shown in its thread: the chain from its top-level
     * comment down to it, each step's replies open. Shown alone it had no context — a reply to
     * nothing anyone could see. Falls back to the whole list when the comment is gone.
     */
    private suspend fun loadCommentThread(commentId: Int) {
        binding.commentsProgressBar.visibility = View.VISIBLE
        binding.commentsList.visibility = View.GONE
        showMessage(null)
        adapter.clear()
        section.clear()

        val target = withContext(Dispatchers.IO) { CommentsAPI.getSingleComment(commentId) }
        if (target == null) {
            loadAndDisplayComments()
            return
        }
        val chain = mutableListOf(target)
        var parentId = target.parentCommentId
        while (parentId != null && parentId > 0 && chain.size <= MAX_THREAD_HOPS) {
            val parent = withContext(Dispatchers.IO) { CommentsAPI.getSingleComment(parentId!!) } ?: break
            chain += parent
            parentId = parent.parentCommentId
        }
        val markwon = buildMarkwon(activity, fragment = this@CommentsFragment)
        var parentItem: CommentItem? = null
        chain.asReversed().forEachIndexed { index, comment ->
            val last = index == chain.lastIndex
            val parent = parentItem
            val item = if (parent == null) {
                CommentItem(comment, markwon, section, this@CommentsFragment, backgroundColor, 0)
                    .also { section.add(it) }
            } else {
                val nested = parent.commentDepth + 1 <= parent.MAX_DEPTH
                val depth = if (nested) parent.commentDepth + 1 else parent.commentDepth
                val target2 = if (nested) parent.repliesSection else parent.parentSection
                if (depth >= parent.MAX_DEPTH) parent.registerSubComment(comment.commentId)
                CommentItem(comment, markwon, target2, this@CommentsFragment, backgroundColor, depth)
                    .also { target2.add(it) }
            }
            // Open on the way down, so the chain to the notified comment is all in view.
            if (!last) item.markRepliesOpen()
            parentItem = item
        }
        binding.commentsProgressBar.visibility = View.GONE
        binding.commentsList.visibility = View.VISIBLE
        adapter.add(section)
    }


    /** Leave-outs applied here: the server only filters by tag. Replies are never filtered. */
    private var hideDownvoted = false
    private var onlyWithReplies = false
    private var onlyMine = false
    private var onlyFriends = false

    /** AniList ids of the people the user follows, read when the friends filter is first used. */
    private var friendIds: Set<Int>? = null

    private fun passesFilters(comment: Comment): Boolean {
        if (hideDownvoted && comment.upvotes - comment.downvotes < 0) return false
        if (onlyWithReplies && (comment.replyCount ?: 0) == 0) return false
        if (onlyMine && comment.userId != CommentsAPI.userId) return false
        // A comment's user id is the author's AniList id, the same one profiles open with.
        if (onlyFriends && comment.userId.toIntOrNull() !in friendIds.orEmpty()) return false
        return true
    }

    private fun filtersActive() =
        filterTag != null || hideDownvoted || onlyWithReplies || onlyMine || onlyFriends

    /** The filter button lights up while anything is filtered, so an empty list explains itself. */
    private fun updateFilterButton() {
        binding.commentFilter.imageTintList = ColorStateList.valueOf(
            activity.getThemeColor(
                if (filtersActive()) androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnBackground
            )
        )
    }

    /** The sheet's view of the current filters; see [CommentFilterBottomSheet]. */
    fun currentFilters() = CommentFilters(
        tag = filterTag,
        hideDownvoted = hideDownvoted,
        onlyWithReplies = onlyWithReplies,
        onlyFriends = onlyFriends,
        onlyMine = onlyMine,
    )

    /** Whether this is an anime, and the user's progress on it, for the sheet's shortcuts. */
    fun mediaProgress(): Pair<Boolean, Int?> {
        val media = activityViewModel.getMedia().value
        return (media?.anime != null) to media?.userProgress?.takeIf { it > 0 }
    }

    /** Takes the sheet's filters and reloads; the people followed are read the first time they're needed. */
    fun applyFilters(filters: CommentFilters) {
        filterTag = filters.tag
        hideDownvoted = filters.hideDownvoted
        onlyWithReplies = filters.onlyWithReplies
        onlyFriends = filters.onlyFriends
        onlyMine = filters.onlyMine
        updateFilterButton()
        lifecycleScope.launch {
            if (onlyFriends && friendIds == null) {
                val me = Anilist.userid
                friendIds = me?.let { withContext(Dispatchers.IO) { Anilist.query.followingIds(it) } }
                if (friendIds == null) {
                    onlyFriends = false
                    updateFilterButton()
                    snackString(getString(R.string.comment_filter_friends_failed))
                }
            }
            loadAndDisplayComments()
            activity.binding.commentReplyToContainer.visibility = View.GONE
        }
    }

    private fun sortComments(comments: List<Comment>?): List<Comment> {
        if (comments == null) return emptyList()
        return when (PrefManager.getVal(PrefName.CommentSortOrder, "newest")) {
            "newest" -> comments.sortedByDescending { CommentItem.timestampToMillis(it.timestamp) }
            "oldest" -> comments.sortedBy { CommentItem.timestampToMillis(it.timestamp) }
            "highest_rated" -> comments.sortedByDescending { it.upvotes - it.downvotes }
            "lowest_rated" -> comments.sortedBy { it.upvotes - it.downvotes }
            else -> comments
        }
    }

    /**
     * Resets the old state of the comment input
     * @return the old state
     */
    private fun resetOldState(): InteractionState {
        val oldState = interactionState
        interactionState = InteractionState.NONE
        return when (oldState) {
            InteractionState.EDIT -> {
                activity.binding.commentReplyToContainer.visibility = View.GONE
                activity.binding.commentInput.setText("")
                val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(activity.binding.commentInput.windowToken, 0)
                commentWithInteraction?.editing(false)
                InteractionState.EDIT
            }

            InteractionState.REPLY -> {
                activity.binding.commentReplyToContainer.visibility = View.GONE
                activity.binding.commentInput.setText("")
                val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(activity.binding.commentInput.windowToken, 0)
                commentWithInteraction?.replying(false)
                InteractionState.REPLY
            }

            else -> {
                InteractionState.NONE
            }
        }
    }

    /**
     * Callback from the comment item to edit the comment
     * Called every time the edit button is clicked
     * @param comment the comment to edit
     */
    fun editCallback(comment: CommentItem) {
        if (resetOldState() == InteractionState.EDIT) return
        commentWithInteraction = comment
        activity.binding.commentInput.setText(comment.comment.content)
        activity.binding.commentInput.requestFocus()
        activity.binding.commentInput.setSelection(activity.binding.commentInput.text.length)
        val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(activity.binding.commentInput, InputMethodManager.SHOW_IMPLICIT)
        interactionState = InteractionState.EDIT
    }

    /**
     * Callback from the comment item to reply to the comment
     * Called every time the reply button is clicked
     * @param comment the comment to reply to
     */
    fun replyCallback(comment: CommentItem) {
        if (resetOldState() == InteractionState.REPLY) return
        commentWithInteraction = comment
        activity.binding.commentReplyToContainer.visibility = View.VISIBLE
        activity.binding.commentInput.requestFocus()
        activity.binding.commentInput.setSelection(activity.binding.commentInput.text.length)
        val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(activity.binding.commentInput, InputMethodManager.SHOW_IMPLICIT)
        interactionState = InteractionState.REPLY

    }

    fun replyTo(comment: CommentItem, username: String) {
        if (comment.isReplying) {
            activity.binding.commentReplyToContainer.visibility = View.VISIBLE
            activity.binding.commentReplyTo.text = getString(R.string.replying_to, username)
            activity.binding.commentReplyToCancel.setOnClickListener {
                comment.replying(false)
                replyCallback(comment)
                activity.binding.commentReplyToContainer.visibility = View.GONE
            }
        } else {
            activity.binding.commentReplyToContainer.visibility = View.GONE
        }
    }

    /**
     * Callback from the comment item to view the replies to the comment
     * @param comment the comment to view the replies of
     */
    fun viewReplyCallback(comment: CommentItem) {
        lifecycleScope.launch {
            val replies = withContext(Dispatchers.IO) {
                CommentsAPI.getRepliesFromId(comment.comment.commentId)
            }

            replies?.comments?.forEach {
                val depth =
                    if (comment.commentDepth + 1 > comment.MAX_DEPTH) comment.commentDepth else comment.commentDepth + 1
                val section =
                    if (comment.commentDepth + 1 > comment.MAX_DEPTH) comment.parentSection else comment.repliesSection
                if (depth >= comment.MAX_DEPTH) comment.registerSubComment(it.commentId)
                val newCommentItem = CommentItem(
                    it,
                    buildMarkwon(activity, fragment = this@CommentsFragment),
                    section,
                    this@CommentsFragment,
                    backgroundColor,
                    depth
                )

                section.add(newCommentItem)
            }
        }
    }


    /**
     * Shows the comment rules dialog
     * Called when the user tries to comment for the first time
     */
    private fun showCommentRulesDialog() {
        activity.customAlertDialog().apply {
            setTitle(getString(R.string.commenting_rules))
                .setMessage(getString(R.string.comment_rules_text))
            setPosButton(getString(R.string.comment_rules_accept)) {
                PrefManager.setVal(PrefName.FirstComment, false)
                processComment()
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private fun processComment() {
        val typed = activity.binding.commentInput.text.toString()
        if (typed.isBlank()) {
            snackString(getString(R.string.comment_empty))
            return
        }
        val commentText = if (spoilerMode) "||${typed.trim()}||" else typed
        spoilerMode = false
        updateSpoilerButton()

        activity.binding.commentInput.text.clear()
        lifecycleScope.launch {
            if (interactionState == InteractionState.EDIT) {
                handleEditComment(commentText)
            } else {
                handleNewComment(commentText)
                tag = null
                activity.binding.commentLabel.background = ResourcesCompat.getDrawable(
                    resources,
                    R.drawable.ic_label_off_24,
                    null
                )
            }
            resetOldState()
        }
    }

    private suspend fun handleEditComment(commentText: String) {
        val success = withContext(Dispatchers.IO) {
            CommentsAPI.editComment(
                commentWithInteraction?.comment?.commentId ?: return@withContext false, commentText
            )
        }
        if (success) {
            updateCommentInSection(commentText)
        }
    }

    private fun updateCommentInSection(commentText: String) {
        val groups = section.groups
        groups.forEach { item ->
            if (item is CommentItem && item.comment.commentId == commentWithInteraction?.comment?.commentId) {
                updateCommentItem(item, commentText)
                snackString(getString(R.string.comment_edited))
            }
        }
    }

    private fun updateCommentItem(item: CommentItem, commentText: String) {
        item.comment.content = commentText
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        item.comment.timestamp = dateFormat.format(System.currentTimeMillis())
        item.notifyChanged()
    }

    /**
     * Handles the new user-added comment
     * @param commentText the text of the comment
     */

    private suspend fun handleNewComment(commentText: String) {
        val success = withContext(Dispatchers.IO) {
            CommentsAPI.comment(
                mediaId,
                if (interactionState == InteractionState.REPLY) commentWithInteraction?.comment?.commentId else null,
                commentText,
                tag
            )
        }
        success?.let {
            if (interactionState == InteractionState.REPLY) {
                if (commentWithInteraction == null) return@let
                val section =
                    if (commentWithInteraction!!.commentDepth + 1 > commentWithInteraction!!.MAX_DEPTH) commentWithInteraction?.parentSection else commentWithInteraction?.repliesSection
                val depth =
                    if (commentWithInteraction!!.commentDepth + 1 > commentWithInteraction!!.MAX_DEPTH) commentWithInteraction!!.commentDepth else commentWithInteraction!!.commentDepth + 1
                if (depth >= commentWithInteraction!!.MAX_DEPTH) commentWithInteraction!!.registerSubComment(
                    it.commentId
                )
                section?.add(
                    if (commentWithInteraction!!.commentDepth + 1 > commentWithInteraction!!.MAX_DEPTH) 0 else section.itemCount,
                    CommentItem(
                        it,
                        buildMarkwon(activity, fragment = this@CommentsFragment),
                        section,
                        this@CommentsFragment,
                        backgroundColor,
                        depth
                    )
                )
            } else {
                section.add(
                    0,
                    CommentItem(
                        it,
                        buildMarkwon(activity, fragment = this@CommentsFragment),
                        section,
                        this@CommentsFragment,
                        backgroundColor,
                        0
                    )
                )
            }
        }
    }
}
