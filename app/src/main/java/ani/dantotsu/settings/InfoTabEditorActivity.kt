package ani.dantotsu.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityInfoTabEditorBinding
import ani.dantotsu.databinding.BottomSheetInfoTabIconBinding
import ani.dantotsu.databinding.ItemInfoPickerHeaderBinding
import ani.dantotsu.databinding.ItemInfoSectionRowBinding
import ani.dantotsu.databinding.ItemInfoTabEditorFooterBinding
import ani.dantotsu.databinding.ItemInfoTabEditorHeaderBinding
import ani.dantotsu.databinding.ItemInfoTabIconBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.media.BuiltinTabOverride
import ani.dantotsu.media.CustomInfoTab
import ani.dantotsu.media.InfoField
import ani.dantotsu.media.InfoFieldLine
import ani.dantotsu.media.InfoSectionEntry
import ani.dantotsu.media.InfoSectionKind
import ani.dantotsu.media.InfoSectionMerge
import ani.dantotsu.media.InfoSourceMode
import ani.dantotsu.media.InfoTabContext
import ani.dantotsu.media.InfoTabEntry
import ani.dantotsu.media.InfoTabIcons
import ani.dantotsu.media.InfoTabLongPress
import ani.dantotsu.media.InfoTabStore
import ani.dantotsu.media.InfoTabType
import ani.dantotsu.media.fieldLabelRes
import ani.dantotsu.media.fieldsOf
import ani.dantotsu.media.label
import ani.dantotsu.media.orderedEntries
import ani.dantotsu.media.sectionRules
import ani.dantotsu.media.sectionsOf
import ani.dantotsu.media.unavailableReason
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.capContentWidth
import ani.dantotsu.util.materialDialog
import com.google.android.material.color.MaterialColors

/**
 * Edits one tab. A source's own tab can have its sections reordered and hidden, and its icon
 * changed. A custom tab also has a name and takes sections from any source of the context, and
 * picking several sources for a section merges them.
 *
 * Saves as it goes, so leaving the screen is all it takes.
 */
class InfoTabEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInfoTabEditorBinding
    private lateinit var header: ItemInfoTabEditorHeaderBinding
    private lateinit var footer: ItemInfoTabEditorFooterBinding
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var tabContext: InfoTabContext
    private lateinit var tabKey: String
    private var builtin: InfoTabType? = null

    private val rows = ArrayList<SectionRow>()
    private lateinit var adapter: SectionRowAdapter

    /** A custom tab's own fields. */
    private var icon: String? = null
    private var name = ""
    private var showSources = true
    var longPress = InfoTabLongPress.NAME
        private set

    /** Set when a tab is deleted, so the pending save doesn't bring it back. */
    private var gone = false

    data class SectionRow(
        val kind: InfoSectionKind,
        val sources: List<InfoTabType>,
        val visible: Boolean = true,
        val hiddenFields: Set<String> = emptySet(),
        val fieldOrder: List<String>? = null,
        val mode: InfoSourceMode = InfoSourceMode.MERGE,
        val fieldLines: Map<String, InfoFieldLine> = emptyMap(),
    ) {
        /** Several sources that really do end up combined. */
        val merged: Boolean
            get() = sources.size > 1 && mode == InfoSourceMode.MERGE && kind.merge != InfoSectionMerge.STACK

        val fallback: Boolean get() = sources.size > 1 && mode == InfoSourceMode.FALLBACK
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityInfoTabEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        capContentWidth()
        binding.infoTabEditorList.updatePadding(top = statusBarHeight, bottom = navBarHeight)

        tabContext = InfoTabContext.valueOf(intent.getStringExtra(EXTRA_CONTEXT)!!)
        tabKey = intent.getStringExtra(EXTRA_TAB)!!
        builtin = InfoTabType.forKey(tabKey)
        load()

        // First: inflating the rows below asks the list for layout params, which needs its manager.
        binding.infoTabEditorList.layoutManager = LinearLayoutManager(this)
        header = ItemInfoTabEditorHeaderBinding.inflate(layoutInflater, binding.infoTabEditorList, false)
        footer = ItemInfoTabEditorFooterBinding.inflate(layoutInflater, binding.infoTabEditorList, false)
        adapter = SectionRowAdapter()
        // One scrolling list, the rest of the page included, so a section dragged towards the edge of
        // the screen scrolls the page: a tab can have some twenty of them.
        binding.infoTabEditorList.adapter = ConcatAdapter(
            ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build(),
            SingleViewAdapter(header.root), adapter, SingleViewAdapter(footer.root),
        )
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (vh is SectionRowAdapter.VH) super.getMovementFlags(rv, vh) else 0

            override fun canDropOver(
                rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ) = target is SectionRowAdapter.VH

            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                rows.add(to, rows.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) liftRow(vh?.itemView, true)
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                liftRow(vh.itemView, false)
                save()
            }
        }).also { it.attachToRecyclerView(binding.infoTabEditorList) }

        val custom = builtin == null
        header.infoTabEditorBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        header.infoTabEditorCard.setCardBackgroundColor(SectionCard.fill(header.root, false))
        header.infoTabEditorIconTile.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.compositeARGBWithAlpha(getThemeColor(androidx.appcompat.R.attr.colorPrimary), 36)
        )
        header.infoTabEditorNameLayout.isVisible = custom
        header.infoTabEditorIconLabel.isVisible = !custom
        header.infoTabEditorShowSourcesDivider.isVisible = custom
        header.infoTabEditorShowSources.isVisible = custom
        footer.infoTabEditorAddSection.isVisible = custom
        footer.infoTabEditorCopy.isVisible = custom
        header.infoTabEditorHint.setText(
            if (custom) R.string.info_tab_custom_hint else R.string.info_tab_builtin_hint
        )
        footer.infoTabEditorDestructive.setText(if (custom) R.string.info_tab_delete else R.string.info_tab_reset)
        footer.infoTabEditorDestructive.setIconResource(
            if (custom) R.drawable.ic_round_delete_24 else R.drawable.ic_round_refresh_24
        )
        footer.infoTabEditorDestructive.setOnClickListener { if (custom) confirmDelete() else resetBuiltin() }

        header.infoTabEditorTitle.text = if (custom) name else getString(builtin!!.labelRes)
        header.infoTabEditorContext.text = tabContext.label(this)
        header.infoTabEditorName.setText(name)
        header.infoTabEditorName.doAfterTextChanged { text ->
            name = text?.toString().orEmpty()
            header.infoTabEditorTitle.text = name
            save()
        }
        header.infoTabEditorShowSources.isChecked = showSources
        header.infoTabEditorShowSources.setOnCheckedChangeListener { _, checked ->
            showSources = checked
            save()
        }
        // A source's own tab shows both of these too, as facts rather than as controls.
        if (custom) {
            header.infoTabEditorIconTile.setOnClickListener {
                InfoTabIconSheet().show(supportFragmentManager, InfoTabIconSheet.TAG)
            }
            header.infoTabEditorLongPressRow.setOnClickListener {
                InfoSectionPickerSheet.forLongPress().show(supportFragmentManager, InfoSectionPickerSheet.TAG)
            }
        } else {
            header.infoTabEditorIconTile.isClickable = false
            header.infoTabEditorIconTile.isFocusable = false
            header.infoTabEditorIconTile.foreground = null
            header.infoTabEditorIconEdit.isVisible = false
            header.infoTabEditorLongPressRow.isClickable = false
            header.infoTabEditorLongPressRow.isFocusable = false
            header.infoTabEditorLongPressRow.background = null
            header.infoTabEditorLongPressChevron.isVisible = false
        }
        refreshLongPress()
        footer.infoTabEditorAddSection.setOnClickListener {
            InfoSectionPickerSheet.forAdding().show(supportFragmentManager, InfoSectionPickerSheet.TAG)
        }
        footer.infoTabEditorCopy.setOnClickListener {
            InfoSectionPickerSheet.forCopying().show(supportFragmentManager, InfoSectionPickerSheet.TAG)
        }
        refreshIcon()
    }

    private fun liftRow(view: View?, lifted: Boolean) {
        view ?: return
        view.animate().translationZ(if (lifted) 8f.px.toFloat() else 0f)
            .scaleX(if (lifted) 1.02f else 1f).scaleY(if (lifted) 1.02f else 1f)
            .setDuration(120).start()
    }

    /** The parts of the page around the sections, as rows of the one list. */
    private class SingleViewAdapter(private val view: View) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            // A focused text field can stop its holder being reused, so the view may come back here.
            (view.parent as? ViewGroup)?.removeView(view)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    private fun load() {
        val config = InfoTabStore.get(tabContext)
        rows.clear()
        val source = builtin
        if (source != null) {
            val override = config.builtins[source.key]
            val rules = tabContext.sectionRules(source)
            rules.order.forEach { kind ->
                rows.add(
                    SectionRow(
                        kind = kind,
                        sources = listOf(source),
                        visible = kind !in rules.hidden,
                        hiddenFields = if (kind == InfoSectionKind.DETAILS) override?.hiddenFields.orEmpty() else emptySet(),
                        fieldOrder = if (kind == InfoSectionKind.DETAILS) override?.fieldOrder else null,
                    )
                )
            }
        } else {
            val tab = config.customTabs.find { it.id == tabKey }
            if (tab == null) {
                finish()
                return
            }
            icon = tab.icon
            name = tab.name
            showSources = tab.showSources
            longPress = tab.longPress
            tab.entries.forEach { entry ->
                val kind = InfoSectionKind.forKey(entry.kind) ?: return@forEach
                // Also drops what a source no longer supplies, such as MAL's pictures.
                val sources = entry.sources.mapNotNull { InfoTabType.forKey(it) }
                    .filter { it in tabContext.tabs && kind in tabContext.sectionsOf(it) }
                // Layouts saved before some kinds were folded together can name one twice.
                val taken = rows.filter { it.kind == kind }.flatMap { it.sources }
                if (sources.isNotEmpty() && sources.none { it in taken }) rows.add(SectionRow(kind, sources, true, entry.hiddenFields, entry.fieldOrder, entry.mode, entry.resolvedLines))
            }
        }
    }

    private fun save() {
        if (gone) return
        val source = builtin
        InfoTabStore.update(tabContext) { config ->
            if (source != null) {
                val details = rows.firstOrNull { it.kind == InfoSectionKind.DETAILS }
                config.copy(
                    builtins = config.builtins + (source.key to BuiltinTabOverride(
                        sectionOrder = rows.map { it.kind.key },
                        hiddenSections = rows.filter { !it.visible }.map { it.kind.key }.toSet(),
                        hiddenFields = details?.hiddenFields.orEmpty(),
                        fieldOrder = details?.fieldOrder,
                    ))
                )
            } else {
                val updated = CustomInfoTab(
                    id = tabKey,
                    name = name.trim().ifEmpty { getString(R.string.info_tab_default_name, 1) },
                    icon = icon ?: InfoTabIcons.DEFAULT_CUSTOM,
                    entries = rows.map { row ->
                        InfoSectionEntry(
                            kind = row.kind.key,
                            sources = row.sources.map { it.key },
                            hiddenFields = row.hiddenFields,
                            fieldOrder = row.fieldOrder,
                            mode = row.mode,
                            fieldLines = row.fieldLines,
                        )
                    },
                    visible = config.customTabs.find { it.id == tabKey }?.visible ?: true,
                    showSources = showSources,
                    longPress = longPress,
                )
                config.copy(customTabs = config.customTabs.map { if (it.id == tabKey) updated else it })
            }
        }
    }

    private fun refreshIcon() {
        val res = InfoTabIcons.res(icon) ?: builtin?.iconRes ?: R.drawable.ic_round_star_24
        header.infoTabEditorIcon.setImageResource(res)
    }

    private fun refreshLongPress() {
        builtin?.let {
            header.infoTabEditorLongPressValue.text =
                getString(R.string.info_tab_long_press_open, getString(it.labelRes))
            return
        }
        header.infoTabEditorLongPressValue.text = when (longPress) {
            InfoTabLongPress.NAME -> getString(R.string.info_tab_long_press_name)
            InfoTabLongPress.EDIT -> getString(R.string.info_tab_long_press_edit)
            InfoTabLongPress.NONE -> getString(R.string.info_tab_long_press_none)
            else -> InfoTabType.forKey(longPress)?.let {
                getString(R.string.info_tab_long_press_open, getString(it.labelRes))
            }.orEmpty()
        }
    }

    fun onLongPressPicked(action: String) {
        longPress = action
        refreshLongPress()
        save()
    }

    fun onIconPicked(key: String?) {
        icon = key
        refreshIcon()
        save()
    }

    private fun resetBuiltin() {
        val source = builtin ?: return
        InfoTabStore.update(tabContext) { it.copy(builtins = it.builtins - source.key) }
        load()
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
        refreshIcon()
    }

    private fun confirmDelete() {
        materialDialog()
            .setTitle(getString(R.string.info_tab_delete_confirm, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                gone = true
                InfoTabStore.update(tabContext) { config ->
                    config.copy(
                        customTabs = config.customTabs.filter { it.id != tabKey },
                        tabOrder = config.tabOrder?.filter { it != tabKey },
                    )
                }
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- choosing sections -----------------------------------------------------------------------

    /** Sources as a row of their logos, which stays short however many a section has. */
    fun showSourceIcons(row: ViewGroup, kind: InfoSectionKind, sources: List<InfoTabType>) {
        row.removeAllViews()
        val size = (18 * resources.displayMetrics.density).toInt()
        val tint = ColorStateList.valueOf(
            getThemeColor(com.google.android.material.R.attr.colorOnBackground)
        )
        // A logo only answers "which one?", and a section with a single possible source doesn't ask it.
        if (sourcesOf(kind).size > 1) sources.forEach { source ->
            val icon = kind.providerIconRes(tabContext, source) ?: return@forEach
            row.addView(ImageView(this).apply {
                layoutParams = ViewGroup.MarginLayoutParams(size, size).apply { marginEnd = size / 3 }
                setImageResource(icon)
                imageTintList = tint
                contentDescription = getString(kind.providerLabelRes(tabContext, source))
            })
        }
        row.isVisible = row.childCount > 0
    }

    fun contextLabel(): String = tabContext.label(this)
    fun rowAt(index: Int): SectionRow? = rows.getOrNull(index)

    /** The details lines [row] can show right now, going by the sources that can supply anything. */
    fun fieldsFor(row: SectionRow): Set<InfoField> = tabContext.fieldsOf(
        row.sources.filter { unavailableReason(it, row.kind) == null }.ifEmpty { row.sources }
    )

    /** The sources of merged [row] that can fill [field]. */
    fun providersOf(row: SectionRow, field: InfoField): List<InfoTabType> = row.sources.filter {
        unavailableReason(it, row.kind) == null && field in tabContext.fieldsOf(listOf(it))
    }

    @StringRes
    fun fieldLabel(field: InfoField): Int = tabContext.fieldLabelRes(field)

    fun copySources(): List<InfoTabType> = tabContext.tabs

    /** Every source of this context that can supply [kind]. */
    fun sourcesOf(kind: InfoSectionKind): List<InfoTabType> =
        tabContext.tabs.filter { kind in tabContext.sectionsOf(it) }

    @StringRes
    fun unavailableReason(source: InfoTabType, kind: InfoSectionKind): Int? =
        tabContext.unavailableReason(source, kind)

    /** The sources of [kind] that can show anything right now. */
    fun usableSources(kind: InfoSectionKind): List<InfoTabType> =
        sourcesOf(kind).filter { unavailableReason(it, kind) == null }

    /**
     * Usable sources of [kind] not yet on a row. Two rows can't draw the same source's section:
     * there is only the one view to show.
     */
    fun freeSources(kind: InfoSectionKind): List<InfoTabType> {
        val taken = rows.filter { it.kind == kind }.flatMap { it.sources }.toSet()
        return usableSources(kind).filter { it !in taken }
    }

    /** What editing [row] offers: everything usable, and what it already holds so it can be unticked. */
    fun pickerSources(row: SectionRow): List<InfoTabType> =
        sourcesOf(row.kind).filter { unavailableReason(it, row.kind) == null || it in row.sources }

    /** A tab to copy from: [rows] is what would be added, [emptyReason] why that is nothing. */
    class CopySource(
        val name: String,
        @param:DrawableRes val iconRes: Int,
        val rows: List<SectionRow>,
        @param:StringRes val emptyReason: Int?,
    )

    /** The sections a tab shows, as rows, whichever kind of tab it is and wherever it lives. */
    private fun sectionsOf(context: InfoTabContext, entry: InfoTabEntry): List<SectionRow> = when (entry) {
        is InfoTabEntry.Builtin -> {
            val rules = context.sectionRules(entry.type)
            rules.order.filter { it !in rules.hidden }.map { kind ->
                val details = kind == InfoSectionKind.DETAILS
                SectionRow(
                    kind, listOf(entry.type),
                    hiddenFields = if (details) rules.hiddenFields else emptySet(),
                    fieldOrder = if (details) rules.fieldOrder else null,
                )
            }
        }

        is InfoTabEntry.Custom -> entry.tab.entries.mapNotNull { section ->
            val kind = InfoSectionKind.forKey(section.kind) ?: return@mapNotNull null
            SectionRow(
                kind, section.sources.mapNotNull { InfoTabType.forKey(it) }, true,
                section.hiddenFields, section.fieldOrder, section.mode, section.resolvedLines,
            )
        }
    }

    private fun copySource(context: InfoTabContext, entry: InfoTabEntry): CopySource {
        val wanted = sectionsOf(context, entry)
        val taken = HashSet<Pair<InfoSectionKind, InfoTabType>>()
        // Each section with only the sources that work here and aren't on this tab yet.
        val copied = wanted.mapNotNull { row ->
            val free = freeSources(row.kind)
            val sources = row.sources.filter { it in free && taken.add(row.kind to it) }
            if (sources.isEmpty()) null else row.copy(sources = sources)
        }
        val fits = wanted.flatMap { row -> row.sources.filter { it in sourcesOf(row.kind) }.map { row.kind to it } }
        val reason = when {
            copied.isNotEmpty() -> null
            fits.isEmpty() -> R.string.info_tab_copy_nothing_fits
            // Nothing usable at all, as opposed to everything being here already.
            fits.none { (kind, source) -> unavailableReason(source, kind) == null } ->
                fits.firstNotNullOf { (kind, source) -> unavailableReason(source, kind) }

            else -> R.string.info_tab_section_used
        }
        return when (entry) {
            is InfoTabEntry.Builtin -> CopySource(getString(entry.type.labelRes), entry.iconRes, copied, reason)
            is InfoTabEntry.Custom -> CopySource(entry.tab.name, entry.iconRes, copied, reason)
        }
    }

    /** Every other tab of every kind of page, this kind first. */
    fun copyChoices(): List<Pair<InfoTabContext, List<CopySource>>> =
        (listOf(tabContext) + (InfoTabContext.displayOrder - tabContext)).map { context ->
            context to context.orderedEntries()
                .filterNot { context == tabContext && it.key == tabKey }
                .map { copySource(context, it) }
        }.filter { (_, tabs) -> tabs.isNotEmpty() }

    fun copyFrom(source: CopySource) {
        val before = rows.size
        rows.addAll(source.rows)
        adapter.notifyItemRangeInserted(before, rows.size - before)
        save()
    }

    /**
     * Sets [picked] as a row's sources, or as a new row when [rowIndex] is the picker's NEW_ROW. A
     * source taken from another row of this kind moves here: that is how two separate rows become
     * one merged row.
     */
    fun applySources(
        kind: InfoSectionKind, rowIndex: Int, picked: List<InfoTabType>,
        mode: InfoSourceMode = InfoSourceMode.MERGE,
    ) {
        if (picked.isEmpty()) return
        val row = rows.getOrNull(rowIndex)
        if (row == null) {
            rows.add(SectionRow(kind, picked, mode = mode))
        } else {
            rows[rowIndex] = row.copy(sources = picked, mode = mode)
            val edited = rows[rowIndex]
            rows.filter { it.kind == kind && it !== edited }.forEach { other ->
                val left = other.sources - picked.toSet()
                val at = rows.indexOf(other)
                if (left.isEmpty()) rows.removeAt(at) else rows[at] = other.copy(sources = left)
            }
        }
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
        save()
    }

    fun updateFields(
        index: Int, order: List<String>, hidden: Set<String>, lines: Map<String, InfoFieldLine>,
    ) {
        val row = rows.getOrNull(index) ?: return
        rows[index] = row.copy(hiddenFields = hidden, fieldOrder = order, fieldLines = lines)
        adapter.notifyItemChanged(index)
        save()
    }

    private inner class SectionRowAdapter : RecyclerView.Adapter<SectionRowAdapter.VH>() {
        inner class VH(val bind: ItemInfoSectionRowBinding) : RecyclerView.ViewHolder(bind.root)

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemInfoSectionRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            val bind = holder.bind
            val context = bind.root.context
            val isCustom = builtin == null

            bind.root.setCardBackgroundColor(SectionCard.fill(bind.root, false))
            bind.infoSectionHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                false
            }

            showSourceIcons(bind.infoSectionIcons, row.kind, if (isCustom) row.sources else emptyList())
            bind.infoSectionTitle.text = row.kind.label(context, row.sources.singleOrNull())
            // Details have no mode of their own: each line has one.
            bind.infoSectionMerged.isVisible = isCustom && (row.merged || row.fallback) &&
                    row.kind.merge != InfoSectionMerge.FIELDS
            bind.infoSectionMerged.setText(if (row.fallback) R.string.info_tab_fallback else R.string.info_tab_merged)
            bind.infoSectionMerged.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.compositeARGBWithAlpha(
                    context.getThemeColor(androidx.appcompat.R.attr.colorPrimary), 36
                )
            )
            // Kept on the tab, to come back with the connection, but saying why nothing shows.
            val reasons = if (!isCustom) emptyList() else row.sources.mapNotNull { source ->
                unavailableReason(source, row.kind)?.let { reason ->
                    // With several sources, which one it is about.
                    if (row.sources.size > 1) "${context.getString(source.labelRes)}: ${context.getString(reason)}"
                    else context.getString(reason)
                }
            }.distinct()
            // Only lines the sheet lists: one hidden under another source shouldn't count.
            val fieldKeys = fieldsFor(row).map { it.key }
            val hiddenCount = row.hiddenFields.count { it in fieldKeys }
            val hiddenNote = context.getString(R.string.info_tab_hidden_count, hiddenCount)
                .takeIf { hiddenCount > 0 }
            val notes = listOfNotNull(hiddenNote) + reasons
            bind.infoSectionSubtitle.isVisible = notes.isNotEmpty()
            bind.infoSectionSubtitle.setTextColor(
                context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            // Only what's wrong is in red, not the count of lines hidden on purpose.
            bind.infoSectionSubtitle.text = buildSpannedString {
                append(notes.joinToString(" · "))
                if (reasons.isNotEmpty()) setSpan(
                    ForegroundColorSpan(context.getThemeColor(androidx.appcompat.R.attr.colorError)),
                    length - reasons.joinToString(" · ").length, length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            bind.infoSectionMeta.isVisible = bind.infoSectionIcons.childCount > 0 || bind.infoSectionMerged.isVisible
            val inert = isCustom && row.sources.none { unavailableReason(it, row.kind) == null }
            bind.infoSectionContent.alpha = if (row.visible && !inert) 1f else 0.5f

            bind.infoSectionAbout.setOnClickListener {
                val current = rows.getOrNull(holder.bindingAdapterPosition) ?: return@setOnClickListener
                materialDialog()
                    .setTitle(current.kind.label(context, current.sources.singleOrNull()))
                    .setMessage(current.kind.descriptionRes)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }

            bind.infoSectionFields.isVisible = row.kind == InfoSectionKind.DETAILS
            bind.infoSectionFields.setOnClickListener {
                if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    InfoFieldsSheet.newInstance(holder.bindingAdapterPosition)
                        .show(supportFragmentManager, InfoFieldsSheet.TAG)
                }
            }

            bind.infoSectionRemove.isVisible = isCustom
            bind.infoSectionRemove.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                rows.removeAt(index)
                notifyItemRemoved(index)
                save()
            }

            bind.infoSectionSwitch.isVisible = !isCustom
            bind.infoSectionSwitch.contentDescription = bind.infoSectionTitle.text
            bind.infoSectionSwitch.setOnCheckedChangeListener(null)
            bind.infoSectionSwitch.isChecked = row.visible
            bind.infoSectionSwitch.setOnCheckedChangeListener { _, checked ->
                val index = holder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                rows[index] = rows[index].copy(visible = checked)
                bind.infoSectionContent.alpha = if (checked) 1f else 0.5f
                save()
            }

            if (isCustom) bind.root.setOnClickListener {
                val current = rows.getOrNull(holder.bindingAdapterPosition) ?: return@setOnClickListener
                if (pickerSources(current).size > 1) {
                    InfoSectionPickerSheet.forSources(current.kind, holder.bindingAdapterPosition)
                        .show(supportFragmentManager, InfoSectionPickerSheet.TAG)
                }
            } else {
                bind.root.setOnClickListener(null)
                bind.root.isClickable = false
            }
        }
    }

    companion object {
        const val EXTRA_CONTEXT = "context"
        const val EXTRA_TAB = "tab"
    }
}

/** A grid of icons for a custom tab. */
class InfoTabIconSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetInfoTabIconBinding? = null
    private val binding get() = _binding!!

    override val opensExpanded = true

    override fun onStart() {
        super.onStart()
        capSheetHeight()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetInfoTabIconBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.infoTabIconGrid.setPadding(0, 0, 0, navBarHeight)
        // Headings and icons share one grid; a heading takes the whole row.
        val rows: List<Any> = InfoTabIcons.groups.flatMap { group -> listOf<Any>(group.titleRes) + group.icons }
        binding.infoTabIconGrid.layoutManager = GridLayoutManager(requireContext(), COLUMNS).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (rows[position] is Int) COLUMNS else 1
            }
        }
        binding.infoTabIconGrid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun getItemViewType(position: Int) = if (rows[position] is Int) HEADING else ICON

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val inflater = LayoutInflater.from(parent.context)
                val view = if (viewType == HEADING) ItemInfoPickerHeaderBinding.inflate(inflater, parent, false).root
                else ItemInfoTabIconBinding.inflate(inflater, parent, false).root
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = rows[position]
                if (row is Int) {
                    (holder.itemView as android.widget.TextView).setText(row)
                    return
                }
                @Suppress("UNCHECKED_CAST")
                val icon = row as Pair<String, Int>
                val (key, res) = icon
                val bind = ItemInfoTabIconBinding.bind(holder.itemView)
                bind.infoTabIconImage.setImageResource(res)
                bind.root.setOnClickListener {
                    (activity as? InfoTabEditorActivity)?.onIconPicked(key)
                    dismiss()
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "infoTabIcon"
        private const val COLUMNS = 6
        private const val HEADING = 0
        private const val ICON = 1
    }
}
