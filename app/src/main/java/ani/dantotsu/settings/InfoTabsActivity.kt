package ani.dantotsu.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.buildSpannedString
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityInfoTabsBinding
import ani.dantotsu.databinding.ItemInfoTabChoiceBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.media.CustomInfoTab
import ani.dantotsu.media.InfoTabContext
import ani.dantotsu.media.InfoTabEntry
import ani.dantotsu.media.InfoTabIcons
import ani.dantotsu.media.InfoTabStore
import ani.dantotsu.media.iconsRes
import ani.dantotsu.media.label
import ani.dantotsu.media.orderedEntries
import ani.dantotsu.media.saveEntryOrder
import ani.dantotsu.media.setBuiltinVisible
import ani.dantotsu.navBarHeight
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.capContentWidth
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout

/**
 * Every media context's info tabs: drag to reorder, tick to show, tap to edit. Changes apply as they
 * are made, like the rest of the settings.
 *
 * The checkbox only controls whether a tab appears. It does not affect whether a connection fetches
 * (see [ani.dantotsu.media.InfoTabType.fetchEnabled]); a tab whose connection is off is listed but
 * inert, at the bottom, so that it explains itself instead of being mysteriously absent.
 */
class InfoTabsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInfoTabsBinding
    private lateinit var current: InfoTabContext
    private var touchHelper: ItemTouchHelper? = null

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityInfoTabsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        capContentWidth()
        binding.infoTabsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.infoTabsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.infoTabsRecycler.layoutManager = LinearLayoutManager(this)

        val sections = listOf(
            InfoTabContext.ANILIST_ANIME, InfoTabContext.ANILIST_MANGA, InfoTabContext.ANILIST_NOVEL,
            InfoTabContext.MANGAUPDATES_MANGA, InfoTabContext.MANGAUPDATES_NOVEL,
        ).map { it to it.label(this) }
        sections.forEach { (context, label) ->
            binding.infoTabsContexts.addTab(
                binding.infoTabsContexts.newTab()
                    .setCustomView(contextTabView(context))
                    .setContentDescription(label)
            )
        }
        current = savedInstanceState?.getString(STATE_CONTEXT)
            ?.let { saved -> sections.firstOrNull { it.first.name == saved }?.first }
            ?: sections.first().first
        binding.infoTabsContexts.getTabAt(sections.indexOfFirst { it.first == current })?.select()
        binding.infoTabsContexts.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                current = sections[tab.position].first
                showTabs()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.infoTabsAdd.setOnClickListener { addCustomTab() }
    }

    /** A custom view because a tab's own icon slot is one icon wide, too small to hold a pair. */
    private fun contextTabView(context: InfoTabContext): View {
        // Follows the tab's selected state, as a real tab icon's tint would.
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(
                getThemeColor(androidx.appcompat.R.attr.colorPrimary),
                getThemeColor(com.google.android.material.R.attr.colorOnBackground),
            )
        )
        val density = resources.displayMetrics.density
        val size = (24 * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            context.iconsRes.forEachIndexed { index, res ->
                addView(ImageView(this@InfoTabsActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        if (index > 0) marginStart = (2 * density).toInt()
                    }
                    setImageResource(res)
                    imageTintList = tint
                })
            }
        }
    }

    // The editor may have renamed, re-iconed or deleted any of these.
    override fun onResume() {
        super.onResume()
        showTabs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CONTEXT, current.name)
    }

    private fun addCustomTab() {
        val context = current
        val existing = InfoTabStore.get(context).customTabs.size
        val tab = CustomInfoTab(
            id = InfoTabStore.newTabId(),
            name = getString(R.string.info_tab_default_name, existing + 1),
            icon = InfoTabIcons.DEFAULT_CUSTOM,
        )
        InfoTabStore.update(context) { it.copy(customTabs = it.customTabs + tab) }
        openEditor(context, tab.id)
    }

    private fun openEditor(context: InfoTabContext, key: String) {
        startActivity(
            Intent(this, InfoTabEditorActivity::class.java)
                .putExtra(InfoTabEditorActivity.EXTRA_CONTEXT, context.name)
                .putExtra(InfoTabEditorActivity.EXTRA_TAB, key)
        )
    }

    private fun showTabs() {
        val context = current
        touchHelper?.attachToRecyclerView(null)
        val entries = context.orderedEntries().sortedBy { !it.isFetching }.toMutableList()
        val adapter = InfoTabRowAdapter(
            items = entries,
            onOpen = { openEditor(context, it.key) },
            onStartDrag = { touchHelper?.startDrag(it) },
            onVisibleChanged = { entry, visible ->
                when (entry) {
                    is InfoTabEntry.Builtin -> context.setBuiltinVisible(entry.type, visible)
                    is InfoTabEntry.Custom -> InfoTabStore.update(context) { config ->
                        config.copy(customTabs = config.customTabs.map {
                            if (it.id == entry.tab.id) it.copy(visible = visible) else it
                        })
                    }
                }
            },
        )
        binding.infoTabsRecycler.adapter = adapter
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            // Inert rows sit at the bottom and stay there: a tab that cannot appear has no place
            // ahead of tabs that can.
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (adapter.isActionable(vh.bindingAdapterPosition)) super.getMovementFlags(rv, vh) else 0

            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                if (!adapter.isActionable(target.bindingAdapterPosition)) return false
                adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                context.saveEntryOrder(adapter.keys())
            }
        }).apply { attachToRecyclerView(binding.infoTabsRecycler) }
    }

    private companion object {
        const val STATE_CONTEXT = "context"
    }
}

/** One context's tabs. */
private class InfoTabRowAdapter(
    private val items: MutableList<InfoTabEntry>,
    private val onOpen: (InfoTabEntry) -> Unit,
    private val onVisibleChanged: (InfoTabEntry, Boolean) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<InfoTabRowAdapter.VH>() {

    class VH(val bind: ItemInfoTabChoiceBinding) : RecyclerView.ViewHolder(bind.root)

    fun isActionable(position: Int) = items.getOrNull(position)?.isFetching ?: false
    fun keys() = items.map { it.key }

    fun move(from: Int, to: Int) {
        if (from == to) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemInfoTabChoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val bind = holder.bind
        val context = bind.root.context
        bind.infoTabIcon.setImageResource(entry.iconRes)
        // The base drawables carry their own colours; tinted as the settings cards' tiles are.
        val primary = context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        ImageViewCompat.setImageTintList(bind.infoTabIcon, ColorStateList.valueOf(primary))
        bind.infoTabIconTile.backgroundTintList =
            ColorStateList.valueOf(MaterialColors.compositeARGBWithAlpha(primary, 36))
        bind.root.setCardBackgroundColor(SectionCard.fill(bind.root, false))
        val name = when (entry) {
            is InfoTabEntry.Builtin -> context.getString(entry.type.labelRes)
            is InfoTabEntry.Custom -> entry.tab.name
        }
        val note = when {
            !entry.isFetching -> context.getString(R.string.info_tab_fetch_disabled)
            entry is InfoTabEntry.Custom -> context.getString(R.string.info_tab_custom_note)
            else -> null
        }
        bind.infoTabText.text = if (note == null) name else buildSpannedString {
            append(name)
            val start = length
            append("  $note")
            setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (!entry.isFetching) setSpan(
                ForegroundColorSpan(context.getThemeColor(androidx.appcompat.R.attr.colorError)),
                start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        bind.root.alpha = if (entry.isFetching) 1f else 0.5f
        bind.infoTabHandle.visibility = if (entry.isFetching) View.VISIBLE else View.INVISIBLE
        bind.infoTabHandle.setOnTouchListener { _, event ->
            if (entry.isFetching && event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }
        bind.root.setOnClickListener { onOpen(entry) }
        // Cleared before setting the state: a listener left from another row would write to it.
        bind.infoTabSwitch.setOnCheckedChangeListener(null)
        bind.infoTabSwitch.isChecked = entry.isVisible
        bind.infoTabSwitch.isEnabled = entry.isFetching
        bind.infoTabSwitch.setOnCheckedChangeListener { _, checked -> onVisibleChanged(entry, checked) }
    }
}
