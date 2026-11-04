package ani.dantotsu.media

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
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
                source != null && source in tabContext.tabs &&
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
                free.size == 1 -> StackSlot(kind, free, withCaption = showSources && mixed)
                kind.merge == InfoSectionMerge.MEDIA -> MediaSlot(kind, free, showSources)
                kind.merge == InfoSectionMerge.CHIPS -> ChipSlot(kind, free, showSources)
                kind.merge == InfoSectionMerge.FIELDS -> FieldSlot(entry, free, showSources)
                kind.merge == InfoSectionMerge.ROW -> RowSlot(kind, free, showSources)
                else -> StackSlot(kind, free, withCaption = true)
            }
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

    private fun caption(parent: ViewGroup, sources: List<InfoTabType>): View {
        val bind = ItemInfoSectionSourceBinding.inflate(layoutInflater, parent, false)
        val single = sources.singleOrNull()
        bind.infoSectionSourceIcon.isVisible = single != null
        single?.let { bind.infoSectionSourceIcon.setImageResource(it.iconRes) }
        bind.infoSectionSourceName.text = sources.joinToString(" · ") { getString(it.labelRes) }
        return bind.root
    }

    private fun providerCaption(parent: ViewGroup, kind: InfoSectionKind, source: InfoTabType): View {
        val bind = ItemInfoSectionSourceBinding.inflate(layoutInflater, parent, false)
        val icon = kind.providerIconRes(tabContext, source)
        bind.infoSectionSourceIcon.isVisible = icon != null
        icon?.let { bind.infoSectionSourceIcon.setImageResource(it) }
        bind.infoSectionSourceName.setText(kind.providerLabelRes(tabContext, source))
        return bind.root
    }

    /** An empty view group is a renderer's slot still waiting for its request to come back. */
    private fun isPendingSlot(view: View) = view is ViewGroup && view.childCount == 0

    private abstract inner class Slot {
        val root = newColumn().apply { visibility = View.GONE }

        protected fun reveal() {
            root.visibility = View.VISIBLE
            onContentArrived()
        }

        abstract fun publish(source: InfoTabType, view: View)
        open fun retract(source: InfoTabType, view: View) {}
    }

    /** Sections shown as they are, in source order; also what merging means for most kinds. */
    private inner class StackSlot(
        kind: InfoSectionKind,
        sources: List<InfoTabType>,
        private val withCaption: Boolean,
    ) : Slot() {
        private val holders = sources.associateWith { source ->
            newColumn().also { holder ->
                holder.visibility = View.GONE
                if (withCaption) holder.addView(providerCaption(holder, kind, source))
                root.addView(holder)
            }
        }

        private fun refresh(holder: LinearLayout) {
            val first = if (withCaption) 1 else 0
            val hasContent = (first until holder.childCount).any { !isPendingSlot(holder.getChildAt(it)) }
            holder.isVisible = hasContent
            if (hasContent) reveal()
        }

        override fun publish(source: InfoTabType, view: View) {
            val holder = holders[source] ?: return
            holder.addView(view)
            if (isPendingSlot(view)) {
                (view as ViewGroup).setOnHierarchyChangeListener(
                    object : ViewGroup.OnHierarchyChangeListener {
                        override fun onChildViewAdded(parent: View?, child: View?) = refresh(holder)
                        override fun onChildViewRemoved(parent: View?, child: View?) = refresh(holder)
                    }
                )
            }
            refresh(holder)
        }

        override fun retract(source: InfoTabType, view: View) {
            val holder = holders[source] ?: return
            holder.removeView(view)
            refresh(holder)
        }
    }

    /** One row of media cards. Sources recommending the same title push it towards the front. */
    private inner class MediaSlot(
        private val kind: InfoSectionKind,
        private val sources: List<InfoTabType>,
        showSources: Boolean,
    ) : Slot() {
        private val current = model.getMedia().value
        private val merged = ArrayList<Media>()
        private val offers = HashMap<InfoTabType, List<Media>>()
        private val bind = ItemTitleRecyclerBinding.inflate(layoutInflater, root, false)
        private val adapter = MediaAdaptor(0, merged, requireActivity(), currentMedia = current)

        init {
            if (showSources) root.addView(caption(root, sources))
            bind.itemTitle.setText(kind.labelRes)
            bind.itemRecycler.adapter = adapter
            bind.itemRecycler.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            bind.itemMore.visibility = View.VISIBLE
            bind.itemMore.setSafeOnClickListener {
                MediaListViewActivity.passedMedia = ArrayList(merged)
                current?.let { MediaListViewActivity.passedRecommendationSource = it }
                startActivity(
                    Intent(requireContext(), MediaListViewActivity::class.java)
                        .putExtra("title", getString(kind.labelRes))
                )
            }
            root.addView(bind.root)
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
        private val kind: InfoSectionKind,
        private val sources: List<InfoTabType>,
        showSources: Boolean,
    ) : Slot() {
        private val bind = ItemTagsSectionBinding.inflate(layoutInflater, root, false)
        private val entries = LinkedHashMap<String, Entry>()
        private val described = HashSet<InfoTabType>()
        private var spoilersShown = false
        private var minWeight = 0

        private inner class Entry(val name: String, var sourceRank: Int) {
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
            if (showSources) root.addView(caption(root, sources))
            bind.tagsTitle.text = kind.label(requireContext(), sources.singleOrNull())
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
        }

        private fun add(source: InfoTabType, item: InfoChip) {
            val name = item.label.trim()
            if (name.isEmpty()) return
            val rank = sources.indexOf(source)
            val entry = entries.getOrPut(name.lowercase()) { Entry(name, rank) }
            entry.sourceRank = minOf(entry.sourceRank, rank)
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
            entries[name.lowercase()]?.searches?.get(source)?.invoke()
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
            shown.forEach { entry ->
                entry.showText()
                bind.tagsChipGroup.addView(entry.chip)
            }
            bind.tagsSpoilerAction.isVisible = shown.any { it.spoiler }
            bind.tagsFilterButton.isVisible = entries.values.any { it.weightLevel != null }
            if (entries.isNotEmpty()) reveal()
        }

        private fun findChipGroup(view: View): ChipGroup? = when (view) {
            is ChipGroup -> view
            is ViewGroup -> (0 until view.childCount)
                .firstNotNullOfOrNull { findChipGroup(view.getChildAt(it)) }

            else -> null
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
        kind: InfoSectionKind,
        private val sources: List<InfoTabType>,
        showSources: Boolean,
    ) : Slot() {
        private val bind = ItemTitleRecyclerBinding.inflate(layoutInflater, root, false)
        private val row = ConcatAdapter(ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build())
        private val placed = HashMap<InfoTabType, RecyclerView.Adapter<*>>()
        private val more = LinkedHashMap<InfoTabType, View>()

        init {
            if (showSources) root.addView(caption(root, sources))
            bind.itemTitle.text = kind.label(requireContext(), null)
            bind.itemRecycler.adapter = row
            bind.itemRecycler.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            bind.itemMore.setSafeOnClickListener { openMore() }
            root.addView(bind.root)
        }

        private fun openMore() {
            val options = sources.filter { it in more }
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

        private fun findRecycler(view: View): RecyclerView? = when (view) {
            is RecyclerView -> view
            is ViewGroup -> (0 until view.childCount)
                .firstNotNullOfOrNull { findRecycler(view.getChildAt(it)) }

            else -> null
        }

        private fun take(source: InfoTabType, view: View) {
            val recycler = findRecycler(view)
            val taken = recycler?.adapter
            if (taken != null) {
                recycler.adapter = null
                placed.remove(source)?.let { row.removeAdapter(it) }
                row.addAdapter(sources.takeWhile { it != source }.count { it in placed }, taken)
                placed[source] = taken
            }
            view.findViewById<View>(R.id.itemMore)?.takeIf { it.isVisible }?.let {
                more[source] = it
                bind.itemMore.isVisible = true
            }
            val add = view.findViewById<ImageView>(R.id.itemAdd)?.takeIf { it.isVisible }
            if (add != null) {
                bind.itemAdd.setImageDrawable(add.drawable)
                bind.itemAdd.contentDescription = add.contentDescription
                bind.itemAdd.isVisible = true
                bind.itemAdd.setSafeOnClickListener { add.performClick() }
            }
            if (taken != null || add != null) reveal()
        }

        override fun publish(source: InfoTabType, view: View) {
            if (isPendingSlot(view)) (view as ViewGroup).setOnHierarchyChangeListener(
                object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View?, child: View?) {
                        child?.let { take(source, it) }
                    }

                    override fun onChildViewRemoved(parent: View?, child: View?) {}
                }
            ) else take(source, view)
        }

        override fun retract(source: InfoTabType, view: View) {
            placed.remove(source)?.let { row.removeAdapter(it) }
            more.remove(source)
            bind.itemMore.isVisible = more.isNotEmpty()
        }
    }

    /** One stats table: each field once, or once per source where they give different values. */
    private inner class FieldSlot(
        private val entry: InfoSectionEntry,
        private val sources: List<InfoTabType>,
        showSources: Boolean,
    ) : Slot() {
        private val lineOrder = orderedInfoFields(entry.fieldOrder).map { it.key }

        // Not a TableLayout: its aligned columns let one long label squeeze every value below it,
        // and labelling a line with its source makes for long labels.
        private val table = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val side = 32f.px
            val vertical = 16f.px
            setPadding(side, vertical, side, vertical)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        private inner class Field(val row: InfoFieldRow, val source: InfoTabType, val value: String) {
            val rank = lineOrder.indexOf(row.tag)
            val order = sources.indexOf(source)
            var labelled = false

            fun label() {
                if (labelled) return
                labelled = true
                (row.getChildAt(0) as? TextView)?.let { it.text = "${it.text} · ${getString(source.labelRes)}" }
            }
        }

        private val fields = ArrayList<Field>()

        init {
            if (showSources) root.addView(caption(root, sources))
            root.addView(table)
        }

        override fun publish(source: InfoTabType, view: View) {
            val from = view as? ViewGroup ?: return
            (0 until from.childCount).mapNotNull { from.getChildAt(it) as? InfoFieldRow }
                .forEach { row ->
                    if (row.visibility != View.VISIBLE) return@forEach
                    // A line given to one source is that source's alone, even if it has no value.
                    val chosen = entry.fieldSources[row.tag]?.takeIf { key -> sources.any { it.key == key } }
                    if (chosen != null && chosen != source.key) return@forEach
                    val value = (row.getChildAt(1) as? TextView)?.text?.toString()?.trim().orEmpty()
                    // Unknown is no answer at all, so it can't disagree with one.
                    if (value.isEmpty() || value == "~" || value == "??" ||
                        value == getString(R.string.unknown_value)
                    ) return@forEach
                    val same = fields.filter { it.row.tag == row.tag }
                    if (same.any { it.value.equals(value, ignoreCase = true) }) return@forEach
                    from.removeView(row)
                    val field = Field(row, source, value)
                    fields.add(field)
                    if (same.isNotEmpty()) (same + field).forEach { it.label() }
                    val sorted = fields.sortedWith(compareBy({ it.rank }, { it.order }))
                    table.addView(row, sorted.indexOf(field))
                }
            if (fields.isNotEmpty()) reveal()
        }
    }

    companion object {
        private const val ARG_CONTEXT = "context"
        private const val ARG_TAB = "tab"
        private const val EMPTY_AFTER_MILLIS = 12_000L
        private const val SOURCE_TAG_PREFIX = "source_"

        fun newInstance(context: InfoTabContext, tabId: String) = CustomInfoTabFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_CONTEXT, context.name)
                putString(ARG_TAB, tabId)
            }
        }
    }
}
