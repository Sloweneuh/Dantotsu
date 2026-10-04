package ani.dantotsu.media.discover

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.databinding.LayoutDiscoverSheetBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.snackString
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The discovery queue's bar over a media page, laid out like mangabaka.org's queue bar: position
 * and Exit, the three verdict buttons, and the pick's "why" (seed series, positive and negative
 * taste tags).
 *
 * The sheet collapses to just the header and buttons but is never swiped away — the queue is left
 * through Exit or Back. Each verdict saves the queue and hands the next item to [showItem]: the
 * MangaBaka page reloads in place, the AniList page replaces itself with the next pick's page.
 *
 * A pick can also be decided away from the buttons — added from the page's own list editor, or
 * read/watched (which puts it on the list). [recheck] runs on every return to the page and
 * [markOnList] takes the page's own word for it; either marks the pick added without moving on.
 */
class DiscoverQueueSheet(
    private val activity: AppCompatActivity,
    private val source: DiscoverySource,
    private val container: View,
    private val sheet: LayoutDiscoverSheetBinding,
    /**
     * How much of the screen's bottom the page's own chrome covers, system navigation inset
     * included (the AniList page's tab bar). The sheet keeps its content above whichever is taller,
     * this or the bare navigation inset.
     */
    private val bottomChrome: () -> Int = { 0 },
    /** How much of the page the collapsed sheet covers above that chrome and the system bar, to pad by. */
    private val onPeekHeight: (Int) -> Unit,
    private val showItem: (DiscoveryQueue.Item) -> Unit,
    /** [completed] is false when the user left before answering everything. */
    private val finish: (completed: Boolean) -> Unit,
) {
    private val behavior = BottomSheetBehavior.from(container)
    private var queue: DiscoveryQueue? = null
    private var current: DiscoveryQueue.Item? = null

    /** What the page is showing, which decides how much of the sheet it gets. */
    enum class PageMode { INFO, BROWSE, HIDDEN }
    private var pageMode = PageMode.INFO
    /** The user's own expanded/collapsed choice on the info page, restored when they come back to it. */
    private var infoState = BottomSheetBehavior.STATE_EXPANDED

    /** Expanded or collapsed, for carrying across a page replacement. */
    val state: Int get() = if (pageMode == PageMode.INFO) behavior.state else infoState

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
            } else {
                finish(false)
            }
        }
    }

    /**
     * [shownId] is the pick the page already shows (the AniList page is opened for one); without
     * it the first unanswered pick is handed to [showItem].
     */
    fun start(queue: DiscoveryQueue, shownId: Long? = null, initialState: Int = BottomSheetBehavior.STATE_EXPANDED) {
        this.queue = queue
        infoState = initialState
        container.isVisible = true
        behavior.isHideable = false
        behavior.state = initialState
        sheet.discoverSheetExpand.rotation = if (initialState == BottomSheetBehavior.STATE_EXPANDED) 0f else 180f
        sheet.discoverSheetRoot.doOnLayout { applyInsets() }
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (pageMode == PageMode.INFO &&
                    (newState == BottomSheetBehavior.STATE_EXPANDED || newState == BottomSheetBehavior.STATE_COLLAPSED)
                ) infoState = newState
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                sheet.discoverSheetExpand.rotation = 180f * (1f - slideOffset.coerceIn(0f, 1f))
            }
        })
        sheet.discoverSheetHeader.setOnClickListener {
            behavior.state = if (behavior.state == BottomSheetBehavior.STATE_EXPANDED)
                BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_EXPANDED
        }
        sheet.discoverSheetIcon.setImageResource(source.icon)
        sheet.discoverSheetExit.setOnClickListener { finish(false) }
        sheet.discoverSheetReasonHeader.setOnClickListener { showHelp() }
        sheet.discoverSheetAdd.setOnClickListener { addCurrent() }
        sheet.discoverSheetHide.setOnClickListener {
            val item = current ?: return@setOnClickListener
            source.store.exclude(item.id)
            snackString(R.string.discover_hidden)
            answer(item, DiscoveryQueue.Action.HIDDEN)
        }
        sheet.discoverSheetSkip.setOnClickListener {
            val item = current ?: return@setOnClickListener
            // Already decided (added from the page): move on without overwriting that.
            if (item.action != null) advance(afterId = item.id)
            else answer(item, DiscoveryQueue.Action.SKIPPED)
        }
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        // A trip to another screen may have put the pick on a list; ask when the page comes back.
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var stopped = false
            override fun onStop(owner: LifecycleOwner) { stopped = true }
            override fun onStart(owner: LifecycleOwner) {
                if (stopped) { stopped = false; recheck() }
            }
        })

        val shown = shownId?.let { id -> queue.items.firstOrNull { it.id == id } }
        if (shown != null) show(queue, shown, load = false) else advance(afterId = null)
    }

    /**
     * The sheet's padding and collapsed height, which grow by whatever page chrome sits below it.
     * Pages call this again when that chrome changes size (insets arriving, rotation).
     */
    fun applyInsets() {
        val below = maxOf(navBarHeight, bottomChrome())
        sheet.discoverSheetRoot.updatePadding(bottom = below)
        // The root's top is the container's shadow allowance (see the page layouts).
        sheet.discoverSheetRoot.post {
            val peek = sheet.discoverSheetRoot.top + sheet.discoverSheetDetails.top + below
            behavior.peekHeight = peek
            onPeekHeight(peek - below)
        }
    }

    /**
     * The info page gets the sheet as the user left it; the episode/chapter list gets only the
     * collapsed bar; the comments page (whose input sits where the sheet would) gets none of it.
     */
    fun setPageMode(mode: PageMode) {
        if (mode == pageMode || queue == null) return
        pageMode = mode
        when (mode) {
            PageMode.INFO -> { behavior.isHideable = false; behavior.state = infoState }
            PageMode.BROWSE -> { behavior.isHideable = false; behavior.state = BottomSheetBehavior.STATE_COLLAPSED }
            PageMode.HIDDEN -> { behavior.isHideable = true; behavior.state = BottomSheetBehavior.STATE_HIDDEN }
        }
    }

    private fun advance(afterId: Long?) {
        val queue = queue ?: return
        val next = queue.next(afterId)
        if (next == null) {
            finish(true)
            return
        }
        show(queue, next, load = true)
    }

    private fun show(queue: DiscoveryQueue, item: DiscoveryQueue.Item, load: Boolean) {
        current = item
        bind(queue, item)
        bindDecided(item.action == DiscoveryQueue.Action.ADDED)
        if (load) showItem(item)
    }

    private fun answer(item: DiscoveryQueue.Item, action: DiscoveryQueue.Action) {
        val updated = queue?.withAction(item.id, action) ?: return
        queue = updated
        source.store.save(updated)
        advance(afterId = item.id)
    }

    /** Asks the source whether the current pick has been put on a list since it was shown. */
    fun recheck() {
        val item = current?.takeIf { it.action == null } ?: return
        activity.lifecycleScope.launch {
            val onList = withContext(Dispatchers.IO) { source.isOnList(item) }
            if (onList) markOnList(item.id)
        }
    }

    /**
     * The pick is on the user's list now (added from the page, or started): record it as added and
     * show that, but stay on it — they may well still be reading or watching.
     */
    fun markOnList(id: Long) {
        val item = current?.takeIf { it.id == id && it.action == null } ?: return
        val updated = queue?.withAction(item.id, DiscoveryQueue.Action.ADDED) ?: return
        queue = updated
        source.store.save(updated)
        source.store.exclude(item.id)
        current = updated.items.first { it.id == id }
        bindDecided(true)
    }

    /** Swaps the buttons to "On your list" / Next once the pick is decided as added. */
    private fun bindDecided(added: Boolean) {
        setButtonsEnabled(true)
        sheet.discoverSheetAdd.isEnabled = !added
        sheet.discoverSheetHide.isEnabled = !added
        sheet.discoverSheetAdd.setText(if (added) R.string.discover_on_list else source.addLabel)
        sheet.discoverSheetSkip.setText(if (added) R.string.discover_next else R.string.discover_skip)
    }

    private fun addCurrent() {
        val item = current ?: return
        setButtonsEnabled(false)
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { source.add(item) }
            val message = when (result) {
                DiscoverListAdd.Result.AddedToAnilist -> activity.getString(R.string.discover_added_anilist)
                DiscoverListAdd.Result.AddedToMangaUpdates -> activity.getString(R.string.discover_added_mu)
                DiscoverListAdd.Result.AddedToMangaBaka -> activity.getString(R.string.discover_added_mangabaka)
                is DiscoverListAdd.Result.AlreadyOnAnilist ->
                    activity.getString(R.string.discover_already_on_list, result.status.lowercase())
                DiscoverListAdd.Result.Failed -> activity.getString(R.string.discover_add_failed, item.title)
            }
            snackString(message)
            setButtonsEnabled(true)
            if (result == DiscoverListAdd.Result.Failed) return@launch
            source.store.exclude(item.id)
            Refresh.all()
            answer(item, DiscoveryQueue.Action.ADDED)
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        sheet.discoverSheetAdd.isEnabled = enabled
        sheet.discoverSheetHide.isEnabled = enabled
        sheet.discoverSheetSkip.isEnabled = enabled
    }

    // ---- The "why" ----

    private fun bind(queue: DiscoveryQueue, item: DiscoveryQueue.Item) {
        sheet.discoverSheetTitle.text = activity.getString(
            R.string.discover_sheet_title, queue.position(item.id), queue.items.size
        )
        val reason = item.reason
        bindSeeds(reason?.seeds.orEmpty())
        sheet.discoverSheetHiddenGem.isVisible = reason?.hiddenGem == true
        sheet.discoverSheetHiddenGem.text = "– " + activity.getString(R.string.discover_hidden_gem_line)
        sheet.discoverSheetTagBlocks.removeAllViews()
        addTagBlock(R.string.discover_positive_tags, reason?.positiveTags.orEmpty(), negative = false)
        addTagBlock(R.string.discover_negative_tags, reason?.negativeTags.orEmpty(), negative = true)
        // Nothing to explain (a pick with no reason attached): drop the heading with it.
        val hasAny = sheet.discoverSheetSeeds.isVisible || sheet.discoverSheetHiddenGem.isVisible ||
            sheet.discoverSheetTagBlocks.childCount > 0
        sheet.discoverSheetReasonHeader.isVisible = hasAny
    }

    /** "– You read <Title> and 5 others": the title opens that series, "5 others" lists the rest. */
    private fun bindSeeds(seeds: List<DiscoveryQueue.Seed>) {
        val first = seeds.firstOrNull()
        sheet.discoverSheetSeeds.isVisible = first != null
        if (first == null) return
        val onSurface = activity.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val primary = activity.getThemeColor(com.google.android.material.R.attr.colorPrimary)

        val verb = source.seedVerb(first.state)
        val sentence = activity.getString(verb, first.title)
        val text = SpannableStringBuilder("– ").append(sentence)
        val titleStart = 2 + sentence.indexOf(first.title).coerceAtLeast(0)
        text.setSpan(link(onSurface, bold = true) { openSeries(first.id) },
            titleStart, titleStart + first.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val others = seeds.size - 1
        if (others > 0) {
            val more = activity.resources.getQuantityString(R.plurals.discover_seed_others, others, others)
            text.append(" ")
            val start = text.length
            text.append(more)
            text.setSpan(link(primary, bold = false) { showSeeds(seeds) },
                start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sheet.discoverSheetSeeds.text = text
        sheet.discoverSheetSeeds.movementMethod = LinkMovementMethod.getInstance()
    }

    /**
     * "– Positive tags", then one line per weight group with the group's label set small, capitalised
     * and coloured ahead of the tag names, as the site lays it out.
     */
    private fun addTagBlock(label: Int, tags: List<DiscoveryQueue.Tag>, negative: Boolean) {
        if (tags.isEmpty()) return
        val accent = activity.getThemeColor(
            if (negative) com.google.android.material.R.attr.colorError
            else com.google.android.material.R.attr.colorPrimary
        )
        sheet.discoverSheetTagBlocks.addView(textLine("– " + activity.getString(label), topMargin = 6))
        val (defining, other) = tags.partition { it.isDefining }
        listOf(R.string.discover_defining_themes to defining, R.string.discover_other_themes to other)
            .filter { it.second.isNotEmpty() }
            .forEach { (groupLabel, group) ->
                val caption = activity.getString(groupLabel).uppercase()
                val text = SpannableStringBuilder(caption)
                text.setSpan(ForegroundColorSpan(accent), 0, caption.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan(Typeface.BOLD), 0, caption.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(RelativeSizeSpan(0.85f), 0, caption.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.append("  ").append(group.joinToString(", ") { it.name })
                sheet.discoverSheetTagBlocks.addView(textLine(text, topMargin = 2, indent = 12))
            }
    }

    private fun textLine(text: CharSequence, topMargin: Int, indent: Int = 0) = TextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = ResourcesCompat.getFont(activity, R.font.poppins)
        setTextColor(activity.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = topMargin.toFloat().px
            marginStart = indent.toFloat().px
        }
    }

    private fun link(color: Int, bold: Boolean, onClick: () -> Unit) = object : ClickableSpan() {
        override fun onClick(widget: View) = onClick()
        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = false
            if (bold) ds.isFakeBoldText = true
        }
    }

    /**
     * Every seed with its cover — titles alone are often a romanisation that says nothing at a
     * glance. Covers the source didn't send are fetched once the dialog is up and filled in.
     */
    private fun showSeeds(seeds: List<DiscoveryQueue.Seed>) {
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20f.px, 8f.px, 20f.px, 0)
        }
        val ripple = TypedValue().also {
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        val coverViews = HashMap<Long, ShapeableImageView>()
        seeds.forEach { seed ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(4f.px, 6f.px, 4f.px, 6f.px)
                setBackgroundResource(ripple)
                setOnClickListener { openSeries(seed.id) }
            }
            val cover = ShapeableImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(44f.px, 64f.px)
                scaleType = ImageView.ScaleType.CENTER_CROP
                shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(8f.px.toFloat()).build()
                setBackgroundColor(activity.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant))
                seed.cover?.let { loadImage(it) }
            }
            coverViews[seed.id] = cover
            row.addView(cover)
            row.addView(textLine(seed.title, topMargin = 0).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 3
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 14f.px }
            })
            list.addView(row)
        }
        activity.customAlertDialog().apply {
            setTitle(R.string.discover_seeds_dialog_title)
            setCustomView(NestedScrollView(activity).apply { addView(list) })
            setPosButton(R.string.ok)
            show()
        }
        val missing = seeds.filter { it.cover == null }.map { it.id }
        if (missing.isEmpty()) return
        activity.lifecycleScope.launch {
            val covers = withContext(Dispatchers.IO) { source.seedCovers(missing) }
            covers.forEach { (id, url) -> coverViews[id]?.loadImage(url) }
        }
    }

    private fun showHelp() {
        activity.customAlertDialog().apply {
            setTitle(R.string.discover_reason_help_title)
            setMessage(activity.getText(source.helpBody))
            setPosButton(R.string.ok)
            show()
        }
    }

    /** A seed opens as a plain media page on top; Back returns to the queue where it was. */
    private fun openSeries(id: Long) {
        activity.startActivity(source.seedIntent(activity, id))
    }
}
