package ani.dantotsu.media

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentCustomInfoTabBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemInfoMatchActionBinding
import ani.dantotsu.databinding.ItemInfoSectionSourceBinding
import ani.dantotsu.databinding.ItemTagsSectionBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.util.materialDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A tab the user assembled from other tabs' sections.
 *
 * The sources are not re-implemented: each one's normal fragment runs in a hidden host below, with
 * its login checks, id resolution and fetching intact, and its [InfoSectionContainer] hands each
 * section over here as it is drawn. Those views stay wired to the fragment that made them. A source
 * with no match or no login contributes no sections, its placeholder page staying in the hidden
 * host; one that can be matched by hand says so through [offerMatchAction].
 */
class CustomInfoTabFragment : Fragment(), InfoSectionSink {

    private var _binding: FragmentCustomInfoTabBinding? = null
    private val binding get() = _binding!!
    private val model: MediaDetailsViewModel by activityViewModels()

    private lateinit var tabContext: InfoTabContext
    private var tab: CustomInfoTab? = null
    private var slots: Map<Pair<InfoTabType, InfoSectionKind>, Slot> = emptyMap()
    private val matchRows = HashMap<InfoTabType, View>()
    private var emptyTimer: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabContext = InfoTabContext.valueOf(requireArguments().getString(ARG_CONTEXT)!!)
        tab = InfoTabStore.get(tabContext).customTabs
            .find { it.id == requireArguments().getString(ARG_TAB) }

        childFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?,
                ) {
                    val source = InfoTabType.ofFragment(f) ?: return
                    v.findViewById<InfoSectionContainer>(R.id.mediaInfoContainer)?.attachSink(
                        source, this@CustomInfoTabFragment,
                        detailsEntryOf(source)?.hiddenFields.orEmpty(),
                        detailsEntryOf(source)?.fieldOrder,
                    )
                }
            },
            false
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCustomInfoTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        slots = emptyMap()
        matchRows.clear()
        _binding = null
    }

    private fun usableEntries(): List<Pair<InfoSectionEntry, InfoSectionKind>> =
        tab?.entries.orEmpty().mapNotNull { entry ->
            val kind = InfoSectionKind.forKey(entry.kind) ?: return@mapNotNull null
            val enabled = entry.sources.filter { key ->
                val source = InfoTabType.forKey(key)
                source != null && source in tabContext.tabs && kind in tabContext.sectionsOf(source) &&
                        tabContext.unavailableReason(source, kind) == null
            }
            if (enabled.isEmpty()) null else entry.copy(sources = enabled) to kind
        }

    private fun detailsEntryOf(source: InfoTabType): InfoSectionEntry? =
        usableEntries().firstOrNull { (entry, kind) ->
            kind == InfoSectionKind.DETAILS && source.key in entry.sources
        }?.first

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.customInfoContent.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += 128f.px + navBarHeight
        }
        // Its sections belong to several fragments, whose ids repeat; none of it restores cleanly.
        binding.customInfoContent.isSaveFromParentEnabled = false
        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.customInfoScroll.scrollTo(0, 0)
        }
        buildTab()
    }

    private fun buildTab() {
        val entries = usableEntries()
        if (entries.isEmpty()) {
            showEmpty()
            return
        }

        val mixed = entries.flatMap { it.first.sources }.distinct().size > 1
        val showSources = tab?.showSources == true
        val built = LinkedHashMap<Pair<InfoTabType, InfoSectionKind>, Slot>()
        entries.forEach { (entry, kind) ->
            val sources = entry.sources.mapNotNull { InfoTabType.forKey(it) }
            val free = sources.filter { (it to kind) !in built }
            if (free.isEmpty()) return@forEach
            val slot: Slot = when {
                free.size == 1 -> StackSlot(entry, kind, free, withCaption = showSources && mixed)
                kind.merge == InfoSectionMerge.FIELDS -> FieldLinesSlot(entry, free, showSources)
                entry.mode == InfoSourceMode.FALLBACK ->
                    FallbackSlot(entry, kind, free, withCaption = showSources && mixed)

                entry.mode == InfoSourceMode.LIST -> StackSlot(entry, kind, free, withCaption = showSources)
                kind == InfoSectionKind.RELATIONS -> RelationSlot(entry, kind, free, showSources)
                kind.merge == InfoSectionMerge.MEDIA -> MediaSlot(entry, kind, free, showSources)
                kind.merge == InfoSectionMerge.CHIPS -> ChipSlot(entry, kind, free, showSources)
                kind.merge == InfoSectionMerge.ROW -> RowSlot(entry, kind, free, showSources)
                kind.merge == InfoSectionMerge.LIST -> RowSlot(entry, kind, free, showSources, vertical = true)
                kind.merge == InfoSectionMerge.LINES -> LinesSlot(entry, free, showSources)
                else -> StackSlot(entry, kind, free, withCaption = showSources)
            }
            // Before it is added: the column leaves room for the heading of a kind that has none.
            slot.root.infoSectionKind = kind
            InfoSectionHeadings.setHeading(slot.root, entry.title)
            binding.customInfoContent.addView(slot.root)
            free.forEach { built[it to kind] = slot }
        }
        slots = built

        // Added once: the manager brings them back by itself when the view returns.
        val manager = childFragmentManager
        built.keys.map { it.first }.distinct().forEach { source ->
            val fragmentTag = "$SOURCE_TAG_PREFIX${source.key}"
            if (manager.findFragmentByTag(fragmentTag) == null) {
                manager.commit {
                    add(R.id.customInfoSources, tabContext.createSourceFragment(source), fragmentTag)
                }
            }
        }

        // No source signals that it has given up, so the wait for one that never answers is bounded.
        emptyTimer?.cancel()
        emptyTimer = viewLifecycleOwner.lifecycleScope.launch {
            delay(EMPTY_AFTER_MILLIS)
            if (_binding != null && binding.customInfoProgress.isVisible) showEmpty()
        }
    }

    private fun showEmpty() {
        binding.customInfoProgress.isVisible = false
        binding.customInfoEmpty.isVisible = true
    }

    private fun onContentArrived() {
        val b = _binding ?: return
        b.customInfoProgress.isVisible = false
        b.customInfoEmpty.isVisible = false
    }

    // ---- sink ------------------------------------------------------------------------------------

    override fun wantsSection(source: InfoTabType, kind: InfoSectionKind) = (source to kind) in slots

    override fun sectionsAdjacent(
        source: InfoTabType, first: InfoSectionKind, second: InfoSectionKind,
    ): Boolean {
        val order = slots.values.distinct()
        val at = order.indexOf(slots[source to first] ?: return false)
        return order.getOrNull(at + 1) != null && order[at + 1] === slots[source to second]
    }

    override fun publishSection(source: InfoTabType, kind: InfoSectionKind, view: View) {
        slots[source to kind]?.publish(source, view)
    }

    override fun retractSection(source: InfoTabType, kind: InfoSectionKind, view: View) {
        slots[source to kind]?.retract(source, view)
    }

    override fun offerMedia(source: InfoTabType, kind: InfoSectionKind, media: List<Media>) {
        (slots[source to kind] as? MediaSlot)?.offer(source, media)
    }

    override fun offerChips(source: InfoTabType, kind: InfoSectionKind, chips: List<InfoChip>) {
        (slots[source to kind] as? ChipSlot)?.offer(source, chips)
    }

    override fun offerRelations(source: InfoTabType, cards: List<InfoExtraSections.RelationCard>) {
        (slots[source to InfoSectionKind.RELATIONS] as? RelationSlot)?.offer(source, cards)
    }

    override fun offerFeed(source: InfoTabType, kind: InfoSectionKind, feed: MergedFeed) {
        (slots[source to kind] as? RowSlot)?.offerFeed(source, feed)
    }

    override fun offerMatchAction(source: InfoTabType, action: InfoMatchAction) {
        val content = _binding?.customInfoContent ?: return
        matchRows.remove(source)?.let { content.removeView(it) }
        val row = ItemInfoMatchActionBinding.inflate(layoutInflater, content, false)
        row.infoMatchIcon.setImageResource(source.iconRes)
        row.infoMatchMessage.setText(action.messageRes)
        row.infoMatchButton.setText(action.buttonRes)
        row.infoMatchButton.setSafeOnClickListener { action.onClick() }
        // Last, like the unlink button on a source's own tab: a title often just isn't on a source.
        content.addView(row.root)
        matchRows[source] = row.root
        onContentArrived()
    }

    override fun sourceRendered(source: InfoTabType) {
        slots.values.distinct().forEach { it.sourceRendered(source) }
    }

    /** A source picked in [ChipSearchSheet]. */
    internal fun runChipSearch(kindKey: String, name: String, sourceKey: String) {
        val kind = InfoSectionKind.forKey(kindKey) ?: return
        val source = InfoTabType.forKey(sourceKey) ?: return
        (slots[source to kind] as? ChipSlot)?.search(name, source)
    }

    override fun reloadSources() {
        val b = _binding ?: return
        val manager = childFragmentManager
        manager.commitNow {
            manager.fragments.filter { it.tag?.startsWith(SOURCE_TAG_PREFIX) == true }
                .forEach { remove(it) }
        }
        slots = emptyMap()
        matchRows.clear()
        b.customInfoContent.removeAllViews()
        b.customInfoEmpty.isVisible = false
        b.customInfoProgress.isVisible = true
        buildTab()
    }

    // ---- slots -----------------------------------------------------------------------------------

    private fun newColumn() = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Starts this text with the logos of who it came from. Inline, so they stay with the text wherever
     * the view puts it.
     */
    private fun TextView.addSourceIcons(icons: List<Int>, names: List<String>) {
        if ((text as? Spanned)?.getSpans(0, text.length, ImageSpan::class.java)?.isNotEmpty() == true) return
        val size = textSize.toInt()
        val align = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ImageSpan.ALIGN_CENTER
        else ImageSpan.ALIGN_BASELINE
        val plain = text
        text = SpannableStringBuilder().apply {
            icons.forEach { res ->
                val icon = ContextCompat.getDrawable(context, res)?.mutate() ?: return@forEach
                icon.setBounds(0, 0, size, size)
                icon.setTint(currentTextColor)
                val start = length
                append("￼")
                setSpan(ImageSpan(icon, align), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(" ")
            }
            append(plain)
        }
        contentDescription = (listOf(plain.toString()).filter { it.isNotEmpty() } + names).joinToString(" · ")
    }

    private fun TextView.addSourceIcons(sources: List<InfoTabType>) =
        addSourceIcons(sources.map { it.iconRes }, sources.map { getString(it.labelRes) })

    /** The sources of a section with no title of its own to put them beside. */
    private fun caption(parent: ViewGroup, sources: List<InfoTabType>): View {
        val bind = ItemInfoSectionSourceBinding.inflate(layoutInflater, parent, false)
        bind.infoSectionSourceIcon.isVisible = false
        bind.infoSectionSourceName.addSourceIcons(sources)
        return bind.root
    }

    /** The logo for a section with no title, or the name of a provider that has no logo here. */
    private fun providerCaption(parent: ViewGroup, kind: InfoSectionKind, source: InfoTabType): View {
        val bind = ItemInfoSectionSourceBinding.inflate(layoutInflater, parent, false)
        val icon = kind.providerIconRes(tabContext, source)
        bind.infoSectionSourceIcon.isVisible = icon != null
        icon?.let { bind.infoSectionSourceIcon.setImageResource(it) }
        bind.infoSectionSourceIcon.contentDescription = getString(kind.providerLabelRes(tabContext, source))
        bind.infoSectionSourceName.isVisible = icon == null
        bind.infoSectionSourceName.setText(kind.providerLabelRes(tabContext, source))
        return bind.root
    }

    /** An empty view group is a renderer's slot still waiting for its request to come back. */
    private fun isPendingSlot(view: View) = view is ViewGroup && view.childCount == 0

    private fun findRecycler(view: View): RecyclerView? = when (view) {
        is RecyclerView -> view
        is ViewGroup -> (0 until view.childCount)
            .firstNotNullOfOrNull { findRecycler(view.getChildAt(it)) }

        else -> null
    }

    /** A section's titles, of which a source's section of several groups has one each. */
    private fun sectionTitles(view: View): List<TextView> = when {
        view.visibility != View.VISIBLE -> emptyList()
        view is TextView && view.id in TITLE_IDS -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { sectionTitles(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun findChipGroup(view: View): ChipGroup? = when (view) {
        is ChipGroup -> view
        is ViewGroup -> (0 until view.childCount)
            .firstNotNullOfOrNull { findChipGroup(view.getChildAt(it)) }

        else -> null
    }

    /**
     * Hides what's under this section's title until the title is tapped. The section is split at the
     * first level with more than the title's branch in it, so a header row's buttons stay with the
     * title, and whatever a source's layout puts below goes with the rest.
     */
    private fun startCollapsed(view: View) {
        val title = sectionTitles(view).firstOrNull() ?: return
        if (title.getTag(R.id.info_section_collapsible) != null) return
        var group = view as? ViewGroup ?: return
        while (group.childCount == 1) group = group.getChildAt(0) as? ViewGroup ?: return
        fun holdsTitle(child: View): Boolean {
            var at: View? = title
            while (at != null && at !== group) {
                if (at === child) return true
                at = at.parent as? View
            }
            return false
        }
        val shown = HashMap<View, Int>()
        var collapsed = false
        fun apply(collapse: Boolean) {
            collapsed = collapse
            (0 until group.childCount).map { group.getChildAt(it) }.filterNot { holdsTitle(it) }.forEach { child ->
                if (collapse) {
                    shown[child] = child.visibility
                    child.visibility = View.GONE
                } else child.visibility = shown[child] ?: View.VISIBLE
            }
            title.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, if (collapse) R.drawable.ic_round_arrow_drop_down_24 else R.drawable.ic_round_arrow_drop_up_24, 0,
            )
            TextViewCompat.setCompoundDrawableTintList(title, ColorStateList.valueOf(title.currentTextColor))
        }
        title.setTag(R.id.info_section_collapsible, true)
        title.setOnClickListener { apply(!collapsed) }
        apply(true)
    }

    /**
     * Cuts a source's own section down to [limit] items. A section with an arrow of its own keeps
     * what that opens, which is the rest; one without gets a way to show them here.
     */
    private fun limitItems(view: View, limit: Int) {
        if (view.getTag(R.id.info_section_limited) != null) return
        val recycler = findRecycler(view)
        val adapter = recycler?.adapter
        if (adapter != null) {
            view.setTag(R.id.info_section_limited, true)
            val limited = LimitAdapter(adapter, limit)
            recycler.adapter = limited
            val more = view.findViewById<ImageView>(R.id.itemMore)
            when {
                more?.isVisible == true -> Unit
                more != null -> expandWith(more, limited)
                else -> (view as? LinearLayout)?.let { showAllButton(it, limited) }
            }
            return
        }
        val chips = findChipGroup(view) ?: return
        view.setTag(R.id.info_section_limited, true)
        val extra = (limit until chips.childCount).map { chips.getChildAt(it) }
        if (extra.isEmpty()) return
        extra.forEach { it.visibility = View.GONE }
        chips.addView(overflowChip(chips, extra.size) {
            extra.forEach { it.visibility = View.VISIBLE }
        })
    }

    /** The arrow in a section's header, for showing the rest where nothing else does. */
    private fun expandWith(more: View, limited: LimitAdapter) {
        fun update() {
            more.isVisible = limited.hiddenCount > 0
        }
        limited.onChanged = { update() }
        more.setSafeOnClickListener { limited.expand() }
        update()
    }

    private fun showAllButton(parent: LinearLayout, limited: LimitAdapter) {
        val button = MaterialButton(
            requireContext(), null, androidx.appcompat.R.attr.borderlessButtonStyle
        ).apply {
            setText(R.string.info_section_show_all)
            setOnClickListener { limited.expand() }
        }
        fun update() {
            button.isVisible = limited.hiddenCount > 0
        }
        limited.onChanged = { update() }
        parent.addView(button)
        update()
    }

    /** "+12", standing in for the chips a limit leaves out. Tapping it takes itself away with them. */
    private fun overflowChip(group: ChipGroup, count: Int, onExpand: () -> Unit): View {
        val chip = ItemChipBinding.inflate(layoutInflater, group, false).root
        chip.text = getString(R.string.info_section_more_count, count)
        chip.setOnClickListener {
            group.removeView(chip)
            onExpand()
        }
        return chip
    }

    /**
     * With a [providerCaption] as [holder]'s first child: moves its logo beside the section's own
     * title once there is one, or onto each line of the details and titles, which have no title. The
     * caption is left only for a section with neither.
     */
    private fun placeProviderIcon(holder: LinearLayout, kind: InfoSectionKind, source: InfoTabType) {
        val icon = kind.providerIconRes(tabContext, source) ?: return
        val caption = holder.getChildAt(0) ?: return
        val sections = (1 until holder.childCount).map { holder.getChildAt(it) }
        val titles = sections.flatMap { sectionTitles(it) }
        val lines = sections.filter { it.id == R.id.mediaInfoNames || it.id == R.id.mediaInfoTable }
            .filterIsInstance<ViewGroup>()
            .flatMap { lines -> (0 until lines.childCount).mapNotNull { lines.getChildAt(it) as? ViewGroup } }
        caption.isVisible = titles.isEmpty() && lines.isEmpty()
        val names = listOf(getString(kind.providerLabelRes(tabContext, source)))
        titles.forEach { it.addSourceIcons(listOf(icon), names) }
        lines.forEach { (it.getChildAt(0) as? TextView)?.addSourceIcons(listOf(icon), names) }
    }

    private fun isUnknownValue(value: String) = value.isEmpty() || value == "~" || value == "??" ||
            value == getString(R.string.unknown_value)

    /** Whether a drawn section says anything, as sources also draw some with nothing in them. */
    private fun hasContent(view: View): Boolean = when {
        isPendingSlot(view) -> false
        view.id == R.id.mediaInfoDescriptionSection -> {
            val text = view.findViewById<TextView>(R.id.mediaInfoDescription)?.text?.toString()?.trim().orEmpty()
            text.isNotEmpty() && text != "null" && text != getString(R.string.no_description_available)
        }

        // AniList's reviews say "no reviews" over an empty list.
        else -> findRecycler(view)?.adapter?.let { it.itemCount > 0 } ?: true
    }

    private abstract inner class Slot(protected val entry: InfoSectionEntry) {
        val root = newColumn().apply { visibility = View.GONE }
        private val renamed = HashSet<InfoTabType>()

        /**
         * What the user set on this section, on a view a source drew. Before any source icon goes on
         * the title, as a new text would take it off. A source can draw several views for one section
         * (Comick's genre groups): only its first is renamed, as they can't all have that one name.
         */
        protected fun applyOptions(source: InfoTabType, view: View) {
            val titles = sectionTitles(view)
            entry.title?.let { name ->
                if (titles.isNotEmpty() && renamed.add(source)) {
                    titles.first().text = name
                    view.setTag(R.id.info_section_renamed, true)
                }
            }
            // First, for a button this adds to go under the collapsing too.
            entry.itemLimit?.let { limitItems(view, it) }
            if (entry.startCollapsed) startCollapsed(view)
        }

        /** A source drawing this view again will have to rename its new one. */
        protected fun forgetOptions(source: InfoTabType, view: View) {
            if (view.getTag(R.id.info_section_renamed) != null) renamed.remove(source)
        }

        /** For a merged section's own view, whose title and rows the slot draws itself. */
        protected fun limited(adapter: RecyclerView.Adapter<*>): RecyclerView.Adapter<*> =
            entry.itemLimit?.let { LimitAdapter(adapter, it) } ?: adapter


        protected fun reveal() {
            root.visibility = View.VISIBLE
            onContentArrived()
        }

        abstract fun publish(source: InfoTabType, view: View)
        open fun retract(source: InfoTabType, view: View) {}
        open fun sourceRendered(source: InfoTabType) {}
    }


    /** Sections shown as they are, in source order; also what merging means for most kinds. */
    private inner class StackSlot(
        entry: InfoSectionEntry,
        private val kind: InfoSectionKind,
        sources: List<InfoTabType>,
        private val withCaption: Boolean,
    ) : Slot(entry) {
        private val holders = sources.associateWith { source ->
            newColumn().also { holder ->
                holder.visibility = View.GONE
                if (withCaption) holder.addView(providerCaption(holder, kind, source))
                root.addView(holder)
            }
        }

        private fun refresh(source: InfoTabType, holder: LinearLayout) {
            val first = if (withCaption) 1 else 0
            val hasContent = (first until holder.childCount).any { !isPendingSlot(holder.getChildAt(it)) }
            holder.isVisible = hasContent
            if (withCaption) placeProviderIcon(holder, kind, source)
            if (hasContent) reveal()
        }

        override fun publish(source: InfoTabType, view: View) {
            val holder = holders[source] ?: return
            holder.addView(view)
            if (isPendingSlot(view)) {
                (view as ViewGroup).setOnHierarchyChangeListener(
                    object : ViewGroup.OnHierarchyChangeListener {
                        override fun onChildViewAdded(parent: View?, child: View?) {
                            child?.let { applyOptions(source, it) }
                            refresh(source, holder)
                        }

                        override fun onChildViewRemoved(parent: View?, child: View?) = refresh(source, holder)
                    }
                )
            } else applyOptions(source, view)
            refresh(source, holder)
        }

        override fun retract(source: InfoTabType, view: View) {
            val holder = holders[source] ?: return
            forgetOptions(source, view)
            holder.removeView(view)
            refresh(source, holder)
        }
    }

    /**
     * The first source in order that has something to show. Every source's section is kept here, as
     * its fragment may still fill it in, and only the one that counts is visible.
     *
     * No source says when it has given up, so a source that is next in line waits for those before
     * it only as long as they haven't finished drawing, or until [FALLBACK_WAIT_MILLIS] is up. One
     * that answers even later still takes its place back.
     */
    private inner class FallbackSlot(
        entry: InfoSectionEntry,
        private val kind: InfoSectionKind,
        private val sources: List<InfoTabType>,
        private val withCaption: Boolean,
    ) : Slot(entry) {
        private val rendered = HashSet<InfoTabType>()
        private var waitOver = false
        private val holders = sources.associateWith { source ->
            newColumn().also { holder ->
                holder.visibility = View.GONE
                if (withCaption) holder.addView(providerCaption(holder, kind, source))
                root.addView(holder)
            }
        }

        init {
            viewLifecycleOwner.lifecycleScope.launch {
                delay(FALLBACK_WAIT_MILLIS)
                waitOver = true
                update()
            }
        }

        private fun sections(holder: LinearLayout): List<View> =
            (if (withCaption) 1 else 0).until(holder.childCount).map { holder.getChildAt(it) }

        private fun has(source: InfoTabType) = holders[source]?.let { holder ->
            sections(holder).any { hasContent(it) }
        } == true

        /** Finished drawing, and none of what it drew is still waiting for a request. */
        private fun settled(source: InfoTabType) = source in rendered &&
                holders[source]?.let { holder -> sections(holder).none { isPendingSlot(it) } } == true

        private fun update() {
            val first = sources.firstOrNull { has(it) }
            val shown = first?.takeIf { winner ->
                waitOver || sources.takeWhile { it != winner }.all { settled(it) }
            }
            holders.forEach { (source, holder) -> holder.isVisible = source == shown }
            if (withCaption && shown != null) holders[shown]?.let { placeProviderIcon(it, kind, shown) }
            root.isVisible = shown != null
            if (shown != null) reveal()
        }

        override fun sourceRendered(source: InfoTabType) {
            rendered.add(source)
            update()
        }

        override fun publish(source: InfoTabType, view: View) {
            val holder = holders[source] ?: return
            holder.addView(view)
            if (isPendingSlot(view)) {
                (view as ViewGroup).setOnHierarchyChangeListener(
                    object : ViewGroup.OnHierarchyChangeListener {
                        override fun onChildViewAdded(parent: View?, child: View?) {
                            child?.let { applyOptions(source, it) }
                            update()
                        }

                        override fun onChildViewRemoved(parent: View?, child: View?) = update()
                    }
                )
            } else applyOptions(source, view)
            update()
        }

        override fun retract(source: InfoTabType, view: View) {
            forgetOptions(source, view)
            holders[source]?.removeView(view)
            update()
        }
    }

    /**
     * One details table from several sources, each line using them its own way: each different value
     * once, the first source with a value (waiting on those before it, as in [FallbackSlot]), or
     * every source's value. When the tab shows its sources, each line wears its source's icon.
     */
    private inner class FieldLinesSlot(
        entry: InfoSectionEntry,
        private val sources: List<InfoTabType>,
        private val showSources: Boolean,
    ) : Slot(entry) {
        private val lineOrder = orderedInfoFields(entry.fieldOrder).map { it.key }
        private val lines = entry.resolvedLines

        // Not a TableLayout: its aligned columns let one long label squeeze every value below it,
        // and an icon on a line makes for longer labels.
        private val table = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32f.px, 16f.px, 32f.px, 16f.px)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        private val rows = HashMap<InfoTabType, HashMap<String, InfoFieldRow>>()
        private val rendered = HashSet<InfoTabType>()
        private var waitOver = false

        init {
            root.addView(table)
            viewLifecycleOwner.lifecycleScope.launch {
                delay(FALLBACK_WAIT_MILLIS)
                waitOver = true
                update()
            }
        }

        private fun orderOf(line: String): List<InfoTabType> =
            lines[line]?.sources?.mapNotNull { InfoTabType.forKey(it) }?.filter { it in sources }
                ?.takeIf { it.isNotEmpty() } ?: sources

        private fun valueOf(row: InfoFieldRow) =
            (row.getChildAt(1) as? TextView)?.text?.toString()?.trim().orEmpty()

        /**
         * Two values with the same key say the same thing, whichever way a source writes it:
         * "24 mins" and "24 min per ep", or "Aki Hagiu" and "HAGIU Aki".
         */
        private fun sameValueKey(line: String, value: String): String =
            if (line == InfoField.DURATION.key) value.filter { it.isDigit() }
            else value.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
                .sorted().joinToString(" ")

        /** What [line] shows right now, each row with the source it came from. */
        private fun picked(line: String): List<Pair<InfoTabType, InfoFieldRow>> {
            val order = orderOf(line)
            val filled = order.mapNotNull { source ->
                rows[source]?.get(line)
                    ?.takeIf { it.visibility == View.VISIBLE && !isUnknownValue(valueOf(it)) }
                    ?.let { source to it }
            }
            return when (lines[line]?.mode ?: entry.mode) {
                InfoSourceMode.MERGE -> filled.distinctBy { (_, row) -> sameValueKey(line, valueOf(row)) }
                InfoSourceMode.LIST -> filled
                InfoSourceMode.FALLBACK -> listOfNotNull(filled.firstOrNull()?.takeIf { (first, _) ->
                    waitOver || order.takeWhile { it != first }.all { it in rendered }
                })
            }
        }

        private fun update() {
            val shown = lineOrder.flatMap { line ->
                picked(line).map { (source, row) ->
                    if (showSources) (row.getChildAt(0) as? TextView)?.addSourceIcons(listOf(source))
                    row
                }
            }
            table.removeAllViews()
            shown.forEach { table.addView(it) }
            if (shown.isNotEmpty()) reveal()
        }

        override fun sourceRendered(source: InfoTabType) {
            rendered.add(source)
            update()
        }

        override fun publish(source: InfoTabType, view: View) {
            val from = view as? ViewGroup ?: return
            val found = (0 until from.childCount).mapNotNull { from.getChildAt(it) as? InfoFieldRow }
            found.forEach { row ->
                from.removeView(row)
                (row.tag as? String)?.let { line -> rows.getOrPut(source) { HashMap() }[line] = row }
            }
            update()
        }
    }

    /** One column of title lines, each once. When the tab shows its sources, each wears its icon. */
    private inner class LinesSlot(
        entry: InfoSectionEntry,
        private val sources: List<InfoTabType>,
        private val showSources: Boolean,
    ) : Slot(entry) {
        private inner class Line(val source: InfoTabType, val label: String, val value: String)

        private val lines = ArrayList<Line>()
        private val column = newColumn().apply {
            setPadding(32f.px, 0, 32f.px, 16f.px)
        }

        init {
            root.addView(column)
        }

        override fun publish(source: InfoTabType, view: View) {
            val from = view as? ViewGroup ?: return
            (0 until from.childCount).mapNotNull { from.getChildAt(it) as? ViewGroup }.forEach { row ->
                if (row.visibility != View.VISIBLE) return@forEach
                val label = (row.getChildAt(0) as? TextView)?.text?.toString().orEmpty()
                val value = (row.getChildAt(1) as? TextView)?.text?.toString()?.trim().orEmpty()
                if (isUnknownValue(value)) return@forEach
                if (lines.any { it.value.equals(value, ignoreCase = true) }) return@forEach
                from.removeView(row)
                val line = Line(source, label, value)
                lines.add(line)
                if (showSources) (row.getChildAt(0) as? TextView)?.addSourceIcons(listOf(source))
                // Each label together, in source order within it.
                val sorted = lines.sortedWith(
                    compareBy({ l -> lines.indexOfFirst { it.label == l.label } }, { sources.indexOf(it.source) })
                )
                column.addView(row, sorted.indexOf(line))
            }
            if (lines.isNotEmpty()) reveal()
        }
    }

    /** One row of media cards. Sources recommending the same title push it towards the front. */
    private inner class MediaSlot(
        entry: InfoSectionEntry,
        private val kind: InfoSectionKind,
        private val sources: List<InfoTabType>,
        showSources: Boolean,
    ) : Slot(entry) {
        private val current = model.getMedia().value
        private val merged = ArrayList<Media>()
        private val offers = HashMap<InfoTabType, List<Media>>()
        private val bind = ItemTitleRecyclerBinding.inflate(layoutInflater, root, false)
        private val adapter = MediaAdaptor(0, merged, requireActivity(), currentMedia = current)

        init {
            bind.itemTitle.text = entry.title ?: getString(kind.labelRes)
            if (showSources) bind.itemTitle.addSourceIcons(sources)
            bind.itemRecycler.adapter = limited(adapter)
            bind.itemRecycler.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            bind.itemMore.visibility = View.VISIBLE
            bind.itemMore.setSafeOnClickListener {
                MediaListViewActivity.passedMedia = ArrayList(merged)
                current?.let { MediaListViewActivity.passedRecommendationSource = it }
                startActivity(
                    Intent(requireContext(), MediaListViewActivity::class.java)
                        .putExtra("title", entry.title ?: getString(kind.labelRes))
                )
            }
            root.addView(bind.root)
            if (entry.startCollapsed) startCollapsed(bind.root)
        }

        // Its own card row replaces whatever view each source drew for this section.
        override fun publish(source: InfoTabType, view: View) {}

        fun offer(source: InfoTabType, media: List<Media>) {
            offers[source] = media
            rebuild()
            if (merged.isNotEmpty()) reveal()
        }

        private fun rebuild() {
            class Card(val media: Media, var votes: Int = 1)

            val cards = ArrayList<Card>()
            val byId = HashMap<String, Card>()
            val byTitle = HashMap<String, Card>()
            sources.forEach { source ->
                val seenFromSource = HashSet<Card>()
                offers[source].orEmpty().forEach add@{ media ->
                    if (current != null && sameMedia(media, current)) return@add
                    val ids = mediaIds(media)
                    val title = titleOf(media)
                    val existing = ids.firstNotNullOfOrNull { byId[it] }
                        ?: byTitle[title]?.takeIf { sameMedia(media, it.media) }
                    if (existing != null) {
                        if (seenFromSource.add(existing)) existing.votes++
                    } else {
                        val card = Card(media)
                        cards.add(card)
                        seenFromSource.add(card)
                        ids.forEach { byId[it] = card }
                        byTitle.putIfAbsent(title, card)
                    }
                }
            }
            merged.clear()
            merged.addAll(cards.sortedByDescending { it.votes }.map { it.media })
            @Suppress("NotifyDataSetChanged")
            adapter.notifyDataSetChanged()
        }

        /** Sources disagree on which ids they know, so a card is the same if any one matches. */
        private fun mediaIds(media: Media): List<String> = buildList {
            val mu = media.muSeriesId
            if (mu != null) add("mu$mu") else add("al${media.id}")
            media.idMAL?.let { add("mal$it") }
        }

        private fun titleOf(media: Media) = media.userPreferredName.trim().lowercase()

        /**
         * The title only decides when one side has nothing but a MangaUpdates id to go on. Between
         * two entries with ids of their own it would merge a remake into its original.
         */
        private fun sameMedia(a: Media, b: Media): Boolean =
            mediaIds(a).any { it in mediaIds(b) } ||
                    ((a.muSeriesId != null) != (b.muSeriesId != null) && titleOf(a) == titleOf(b))
    }

    /**
     * One chip row with each tag once, drawn here from what the sources say about it. A tag several
     * sources list keeps all of it: AniList's percentage, MangaBaka's weight, and spoiler masking if
     * any of them flags it, and a tap asks which source to search if more than one can. Sources
     * that describe their chips ([offer]) are taken at their word; from the rest, the chips they
     * drew are read ([publish]) and tapping does what those did.
     */
    private inner class ChipSlot(
        entry: InfoSectionEntry,
        private val kind: InfoSectionKind,
        private val sources: List<InfoTabType>,
        showSources: Boolean,
    ) : Slot(entry) {
        private val bind = ItemTagsSectionBinding.inflate(layoutInflater, root, false)
        private val entries = LinkedHashMap<String, Entry>()
        private val described = HashSet<InfoTabType>()
        private var spoilersShown = false
        private var minWeight = 0
        private var expanded = false

        private inner class Entry(val name: String, var sourceRank: Int) {
            /** For links, where it leads ([linkKey]). */
            var link: String? = null
            val chip = ItemChipBinding.inflate(layoutInflater, bind.tagsChipGroup, false).root
            var detail: String? = null
            var spoiler = false
            var revealed = false
            var weightLevel: Int? = null
            var listedUngraded = false
            val searches = HashMap<InfoTabType, () -> Unit>()
            val confirmedTaps = HashMap<InfoTabType, () -> Unit>()
            var onLongClick: (() -> Unit)? = null

            val masked get() = spoiler && !revealed

            /** Only ever compared within itself: votes on AniList say nothing of curated weights. */
            val percent get() = detail?.takeIf { it.endsWith("%") }?.removeSuffix("%")?.toIntOrNull()

            /** The weight filter narrows MangaBaka's tags; one another source lists stays. */
            val passesFilter get() = listedUngraded || (weightLevel ?: 0) >= minWeight

            init {
                chip.setSafeOnClickListener {
                    if (masked) {
                        revealed = true
                        showText()
                    } else openSearch()
                }
                chip.setOnLongClickListener {
                    when {
                        // Never a press meant for the details gives the tag away by surprise.
                        masked -> {
                            revealed = true
                            showText()
                        }

                        else -> onLongClick?.invoke() ?: copyToClipboard(name)
                    }
                    true
                }
            }

            private fun openSearch() {
                val options = sources.filter { it in searches }
                when {
                    options.isEmpty() -> Unit
                    options.size == 1 -> options.single().let { (confirmedTaps[it] ?: searches[it])?.invoke() }
                    !childFragmentManager.isStateSaved -> ChipSearchSheet
                        .newInstance(kind, name, options, confirmedTaps.keys.toList())
                        .show(childFragmentManager, "chip_search")
                }
            }

            fun showText() {
                chip.text = when {
                    masked -> ChipSections.mask(name)
                    else -> detail?.let { "$name : $it" } ?: name
                }
                chip.contentDescription = if (masked) getString(R.string.spoiler_tag) else null
            }
        }

        init {
            bind.tagsTitle.text = entry.title ?: kind.label(requireContext(), sources.singleOrNull())
            if (showSources) {
                bind.tagsTitle.addSourceIcons(sources)
                moveActionsBelowTitle()
            }
            bind.tagsFilterButton.isVisible = false
            ChipSections.spoilerToggle(bind.tagsSpoilerAction) { showing ->
                spoilersShown = showing
                entries.values.filter { it.spoiler }.forEach { it.revealed = showing }
                render()
            }
            bind.tagsSpoilerAction.isVisible = false
            root.addView(bind.root)
            (activity as? AppCompatActivity)?.let { host ->
                MangaBakaMediaRenderer.setupWeightFilter(host, bind) { threshold ->
                    minWeight = threshold
                    render()
                }
            }
            if (entry.startCollapsed) startCollapsed(bind.root)
        }

        /** The source icons need the title's row to themselves, or they push the title onto a second line. */
        private fun moveActionsBelowTitle() {
            val header = bind.tagsTitle.parent as? LinearLayout ?: return
            val parent = header.parent as? ViewGroup ?: return
            val actions = LinearLayout(requireContext()).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(32f.px, 0, 26f.px, 0) }
            }
            listOf(bind.tagsSpoilerAction, bind.tagsFilterButton).forEach { action ->
                header.removeView(action)
                actions.addView(action)
            }
            parent.addView(actions, parent.indexOfChild(header) + 1)
        }

        private fun add(source: InfoTabType, item: InfoChip) {
            val name = item.label.trim()
            if (name.isEmpty()) return
            val rank = sources.indexOf(source)
            val link = item.link?.let { linkKey(it) }
            val label = labelKey(name)
            // A link is the same link by its address, whatever each source calls it ("Official Site",
            // "Official Website"; AniList adds a language flag). Two addresses under one name stay two.
            val sameLink = link?.let { byLink[it] }
            val entry = sameLink ?: entries[label]?.takeIf { it.link == null || link == null || it.link == link }
                ?: Entry(name, rank).also { entries[if (entries.containsKey(label)) "$label|$link" else label] = it }
            if (link != null) {
                if (entry.link == null) entry.link = link
                byLink[link] = entry
            }
            entry.sourceRank = minOf(entry.sourceRank, rank)
            // The same address from another source opens nothing new: no asking which one to open.
            if (sameLink != null && entry.searches.isNotEmpty()) {
                if (entry.onLongClick == null) entry.onLongClick = item.onLongClick
                return
            }
            if (entry.detail == null) entry.detail = item.detail
            if (item.isSpoiler && !entry.spoiler) {
                entry.spoiler = true
                entry.revealed = spoilersShown
            }
            if (item.weightLevel != null) {
                entry.weightLevel = item.weightLevel
                item.weightIcon?.let { MangaBakaMediaRenderer.showWeightIcon(entry.chip, it) }
            } else entry.listedUngraded = true
            item.onClick?.let { open ->
                entry.searches[source] = open
                if (item.opensWebsite) entry.confirmedTaps[source] = MalGenreLinks.confirmingTap(open)
            }
            if (entry.onLongClick == null) entry.onLongClick = item.onLongClick
        }

        fun search(name: String, source: InfoTabType) {
            entries.values.firstOrNull { it.name == name }?.searches?.get(source)?.invoke()
        }

        /** Chips by their link's [linkKey]. */
        private val byLink = HashMap<String, Entry>()

        /** A name as compared across sources: no case, flags, spaces or punctuation ("Sci-Fi" is "SciFi"). */
        private fun labelKey(name: String) = name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { name.lowercase() }

        /** An address as compared across sources: no scheme, "www." or "m.", trailing slash or case. */
        private fun linkKey(url: String): String {
            val uri = runCatching { android.net.Uri.parse(url.trim()) }.getOrNull() ?: return url.trim().lowercase()
            var host = uri.host?.lowercase().orEmpty()
            listOf("www.", "m.", "mobile.").forEach { host = host.removePrefix(it) }
            if (host == "twitter.com") host = "x.com"
            val path = uri.path.orEmpty().trimEnd('/').lowercase()
            val query = uri.query?.let { "?$it" }.orEmpty()
            return host + path + query
        }

        /**
         * Spoilers last, or their blocks would sit among the tags that say something. Otherwise by
         * MangaBaka's weight, then AniList's percentage: the two measure different things, so a
         * percentage never stands in for a weight. A tag with neither follows, by source.
         */
        private fun render() {
            val shown = entries.values.filter { it.passesFilter }.sortedWith(
                compareBy(
                    { it.spoiler },
                    { -(it.weightLevel ?: 0) },
                    { -(it.percent ?: -1) },
                    { it.sourceRank },
                )
            )
            bind.tagsChipGroup.removeAllViews()
            val limit = entry.itemLimit?.takeUnless { expanded }
            (limit?.let { shown.take(it) } ?: shown).forEach { chipEntry ->
                chipEntry.showText()
                bind.tagsChipGroup.addView(chipEntry.chip)
            }
            if (limit != null && shown.size > limit) {
                bind.tagsChipGroup.addView(overflowChip(bind.tagsChipGroup, shown.size - limit) {
                    expanded = true
                    render()
                })
            }
            bind.tagsSpoilerAction.isVisible = shown.any { it.spoiler }
            bind.tagsFilterButton.isVisible = entries.values.any { it.weightLevel != null }
            if (entries.isNotEmpty()) reveal()
        }

        /** Takes a source's drawn chips at face value: "Time Skip : 80%" is a name and a detail. */
        private fun harvest(source: InfoTabType, group: ChipGroup) {
            if (source in described) return
            (0 until group.childCount).map { group.getChildAt(it) }.forEach { chip ->
                val text = (chip as? TextView)?.text?.toString() ?: return@forEach
                add(
                    source,
                    InfoChip(
                        label = text.substringBefore(" : "),
                        detail = text.substringAfter(" : ", "").ifBlank { null },
                        onClick = if (chip.hasOnClickListeners()) ({ chip.performClick(); Unit }) else null,
                        link = chip.getTag(R.id.info_chip_link) as? String,
                    )
                )
            }
            render()
        }

        override fun publish(source: InfoTabType, view: View) {
            val group = findChipGroup(view)
            when {
                group != null -> harvest(source, group)
                // A slot still waiting for its request.
                isPendingSlot(view) -> (view as ViewGroup).setOnHierarchyChangeListener(
                    object : ViewGroup.OnHierarchyChangeListener {
                        override fun onChildViewAdded(parent: View?, child: View?) {
                            child?.let { findChipGroup(it) }?.let { harvest(source, it) }
                        }

                        override fun onChildViewRemoved(parent: View?, child: View?) {}
                    }
                )
            }
        }

        fun offer(source: InfoTabType, chips: List<InfoChip>) {
            described.add(source)
            chips.forEach { add(source, it) }
            render()
        }
    }

    /**
     * One row of cards from several sources. Each source's adapter moves over as it is, so its cards
     * still open what they did. Its header buttons are passed on too: a source may hold more than
     * the row shows, and MangaBaka's add button has nowhere else to live.
     */
    private inner class RowSlot(
        entry: InfoSectionEntry,
        kind: InfoSectionKind,
        private val sources: List<InfoTabType>,
        showSources: Boolean,
        private val vertical: Boolean = false,
    ) : Slot(entry) {
        private val bind = ItemTitleRecyclerBinding.inflate(layoutInflater, root, false)
        private val row = ConcatAdapter(ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build())
        private val placed = HashMap<InfoTabType, RecyclerView.Adapter<*>>()
        private val more = LinkedHashMap<InfoTabType, View>()
        private val limitedRow = entry.itemLimit?.let { LimitAdapter(row, it) }

        /** Reviews and stacks: the sources that have some, for a page of everyone's. */
        private val feeds = HashMap<InfoTabType, MergedFeed>()

        /** What each source that has none says about it ("No reviews yet"), icon and text. */
        private val empties = HashMap<InfoTabType, Pair<Drawable?, CharSequence>>()

        init {
            bind.itemTitle.text = entry.title ?: kind.label(requireContext(), null)
            if (showSources) bind.itemTitle.addSourceIcons(sources)
            bind.itemRecycler.adapter = limitedRow ?: row
            limitedRow?.onChanged = { updateMore() }
            // Sources fill their adapters in after handing them over.
            row.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = updateEmpty()
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = updateEmpty()
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = updateEmpty()
            })
            bind.itemRecycler.layoutManager = LinearLayoutManager(
                requireContext(),
                if (vertical) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL,
                false,
            )
            bind.itemMore.setSafeOnClickListener { openMore() }
            root.addView(bind.root)
            if (entry.startCollapsed) startCollapsed(bind.root)
        }

        fun offerFeed(source: InfoTabType, feed: MergedFeed) {
            feeds[source] = feed
            updateMore()
        }

        /** Sources whose own page the arrow can open; a source with no feed has nothing to add. */
        private fun pages() = sources.filter { it in more && (feeds.isEmpty() || it in feeds) }

        private fun updateMore() {
            bind.itemMore.isVisible =
                feeds.size > 1 || pages().isNotEmpty() || (limitedRow?.hiddenCount ?: 0) > 0
        }

        private fun openMore() {
            if (feeds.size > 1) {
                val ordered = sources.mapNotNull { feeds[it] }
                val stacks = ordered.filterIsInstance<StackFeed>()
                startActivity(
                    if (stacks.isNotEmpty()) StackListViewActivity.mergedIntent(
                        requireContext(), entry.title ?: getString(R.string.info_section_stacks_lists), stacks,
                    ) else Intent(requireContext(), MergedReviewActivity::class.java).putExtra(
                        MergedReviewActivity.EXTRA_FEEDS,
                        ArrayList(ordered.filterIsInstance<ReviewFeed>()),
                    )
                )
                return
            }
            val options = pages()
            // Where no source has a page of its own for the rest, they come here.
            if (options.isEmpty()) {
                limitedRow?.expand()
                return
            }
            options.singleOrNull()?.let {
                more[it]?.performClick()
                return
            }
            requireContext().materialDialog()
                .setItems(options.map { getString(it.labelRes) }.toTypedArray()) { _, which ->
                    more[options[which]]?.performClick()
                }
                .show()
        }

        private fun take(source: InfoTabType, view: View) {
            val recycler = findRecycler(view)
            val taken = recycler?.adapter
            if (taken != null) {
                recycler.adapter = null
                // Cards added ahead of what's showing would leave the row opened part way along.
                val atStart = !bind.itemRecycler.canScrollHorizontally(-1)
                placed.remove(source)?.let { row.removeAdapter(it) }
                row.addAdapter(sources.takeWhile { it != source }.count { it in placed }, taken)
                placed[source] = taken
                if (atStart) bind.itemRecycler.scrollToPosition(0)
            }
            view.findViewById<View>(R.id.itemMore)?.takeIf { it.isVisible }?.let {
                more[source] = it
                updateMore()
            }
            val add = view.findViewById<ImageView>(R.id.itemAdd)?.takeIf { it.isVisible }
            if (add != null) {
                bind.itemAdd.setImageDrawable(add.drawable)
                bind.itemAdd.contentDescription = add.contentDescription
                bind.itemAdd.isVisible = true
                bind.itemAdd.setSafeOnClickListener { add.performClick() }
            }
            // A source with nothing says so in its own empty slot, which the merged row speaks for
            // while no source has anything.
            val empty = view.findViewById<View>(R.id.itemEmptyContainer)?.takeIf { it.isVisible }
            if (empty != null) {
                val text: CharSequence = view.findViewById<TextView>(R.id.itemEmpty)?.text ?: ""
                empties[source] = view.findViewById<ImageView>(R.id.itemEmptyIcon)?.drawable to text
            } else empties.remove(source)
            updateEmpty()
            // A list with no entries is a source saying it has none: AniList's reviews, for one.
            val hasEntries = taken != null && (!vertical || taken.itemCount > 0)
            if (hasEntries || add != null || empty != null) reveal()
        }

        private fun updateEmpty() {
            val said = sources.firstNotNullOfOrNull { empties[it] }
            val none = row.itemCount == 0 && said != null
            bind.itemEmptyContainer.isVisible = none
            bind.itemRecycler.isVisible = !none
            if (none && said != null) {
                said.first?.let { bind.itemEmptyIcon.setImageDrawable(it.constantState?.newDrawable()?.mutate() ?: it) }
                bind.itemEmpty.text = said.second
            }
        }

        override fun publish(source: InfoTabType, view: View) {
            if (isPendingSlot(view)) (view as ViewGroup).setOnHierarchyChangeListener(
                object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View?, child: View?) {
                        child?.let { take(source, it) }
                    }

                    override fun onChildViewRemoved(parent: View?, child: View?) {}
                }
            ) else if (findRecycler(view) != null) take(source, view) else {
                // Not cards at all (MangaUpdates' relations are chips), so it goes under the row.
                root.addView(view)
                reveal()
            }
        }

        override fun retract(source: InfoTabType, view: View) {
            root.removeView(view)
            placed.remove(source)?.let { row.removeAdapter(it) }
            more.remove(source)
            feeds.remove(source)
            empties.remove(source)
            updateMore()
            updateEmpty()
        }
    }

    /**
     * One row of relations with each work once, in source order: see [InfoExtraSections.distinctRelations].
     * Drawn here, from what the sources describe, as no one source's cards could take the others' in.
     */
    private inner class RelationSlot(
        entry: InfoSectionEntry,
        kind: InfoSectionKind,
        private val sources: List<InfoTabType>,
        showSources: Boolean,
    ) : Slot(entry) {
        private val bind = ItemTitleRecyclerBinding.inflate(layoutInflater, root, false)
        private val offers = HashMap<InfoTabType, List<InfoExtraSections.RelationCard>>()
        private val cards = ArrayList<InfoExtraSections.RelationCard>()
        private val adapter = InfoExtraSections.RelationCardAdapter(cards)

        init {
            bind.itemTitle.text = entry.title ?: kind.label(requireContext(), null)
            if (showSources) bind.itemTitle.addSourceIcons(sources)
            val row = limited(adapter)
            bind.itemRecycler.adapter = row
            bind.itemRecycler.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            (row as? LimitAdapter)?.let { expandWith(bind.itemMore, it) }
            root.addView(bind.root)
            if (entry.startCollapsed) startCollapsed(bind.root)
        }

        // Its own card row replaces whatever view each source drew for this section.
        override fun publish(source: InfoTabType, view: View) {}

        fun offer(source: InfoTabType, offered: List<InfoExtraSections.RelationCard>) {
            offers[source] = offered
            cards.clear()
            cards.addAll(InfoExtraSections.distinctRelations(sources.map { offers[it].orEmpty() }))
            @Suppress("NotifyDataSetChanged")
            adapter.notifyDataSetChanged()
            if (cards.isNotEmpty()) reveal()
        }
    }

    companion object {
        /** The views that can hold a section's title. */
        private val TITLE_IDS get() = InfoSectionHeadings.TITLE_IDS

        private const val ARG_CONTEXT = "context"
        private const val ARG_TAB = "tab"
        private const val EMPTY_AFTER_MILLIS = 12_000L
        private const val FALLBACK_WAIT_MILLIS = 2_500L
        private const val SOURCE_TAG_PREFIX = "source_"

        fun newInstance(context: InfoTabContext, tabId: String) = CustomInfoTabFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_CONTEXT, context.name)
                putString(ARG_TAB, tabId)
            }
        }
    }
}
