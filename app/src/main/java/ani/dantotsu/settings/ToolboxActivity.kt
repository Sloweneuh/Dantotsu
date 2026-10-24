package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comick.Comick
import ani.dantotsu.connections.kitsu.Kitsu
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.simkl.Simkl
import ani.dantotsu.databinding.ActivityToolboxBinding
import ani.dantotsu.databinding.ItemToolboxCardBinding
import ani.dantotsu.databinding.ItemToolboxHeaderBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaEquivalentsActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager

/**
 * Tools that work over the whole library rather than one title: the checks for titles AniList
 * relates to the user's but that aren't on a list yet ([LibraryCheck]), the MangaUpdates → AniList
 * equivalents search, and the cross-tracker fixers that also live in the account settings (Comick
 * matches, list comparison) — gathered here so there is one place to tidy a library up from.
 *
 * A grid of cards rather than settings rows: these are things to run, not options to set. A check
 * that can be told to stop suggesting titles carries how many it has been told on its card, which
 * opens the list to take some back.
 *
 * The sequel and equivalents checks used to be buttons on the list screen, shown only on the tab
 * they read (Completed, MangaUpdates), which hid them unless that tab happened to be open. They
 * read the lists themselves now. Opened from the settings sheet's header.
 */
class ToolboxActivity : AppCompatActivity() {
    private lateinit var binding: ActivityToolboxBinding

    private sealed interface Item {
        data class Header(val titleRes: Int) : Item

        /** [ignored]: the list this tool's "don't suggest" choices live in, if it has one. */
        data class Tool(
            val titleRes: Int,
            val descRes: Int,
            val iconRes: Int,
            val ignored: PrefName? = null,
            val ignoredTitleRes: Int = 0,
            val open: (icon: View) -> Unit,
        ) : Item
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityToolboxBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolboxLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.toolboxBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolboxRecyclerView.layoutManager = GridLayoutManager(this, COLUMNS).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if (items.getOrNull(position) is Item.Header) COLUMNS else 1
            }
        }
        binding.toolboxRecyclerView.adapter = adapter
    }

    // Rebuilt on every return, and when an ignored list's sheet closes: the counts change.
    override fun onResume() {
        super.onResume()
        render()
    }

    private var items: List<Item> = emptyList()

    private fun render() {
        val anilist = Anilist.token != null
        val library = buildList {
            if (anilist) {
                add(check(LibraryCheck.ANIME_SEQUELS, R.string.missed_anime_sequels_desc, R.string.ignored_sequels))
                add(check(LibraryCheck.MANGA_SEQUELS, R.string.missed_manga_sequels_desc, R.string.ignored_sequels))
                add(check(LibraryCheck.ANIME_ADAPTATIONS, R.string.anime_adaptations_desc, R.string.ignored_adaptations))
            }
            if (MangaUpdates.token != null) add(
                Item.Tool(R.string.search_equivalents, R.string.search_equivalents_desc, R.drawable.ic_anilist_search_24) {
                    startActivity(Intent(this@ToolboxActivity, MediaEquivalentsActivity::class.java))
                }
            )
        }
        val matching = buildList {
            if (Comick.token != null) add(
                Item.Tool(R.string.comick_matches_title, R.string.toolbox_comick_desc, R.drawable.ic_round_comick_24) {
                    SettingsPageTransitions.open(this@ToolboxActivity, it, Intent(this@ToolboxActivity, ComickMatchesActivity::class.java))
                }
            )
            if (MAL.token != null || Kitsu.token != null || Simkl.token != null || MangaBaka.token != null) add(
                Item.Tool(R.string.compare_lists, R.string.toolbox_compare_desc, R.drawable.ic_round_compare_arrows_24) {
                    SettingsPageTransitions.open(this@ToolboxActivity, it, Intent(this@ToolboxActivity, ListSyncCompareActivity::class.java))
                }
            )
        }
        // A section with nothing in it (signed out of everything it needs) goes, title and all.
        items = buildList {
            if (library.isNotEmpty()) add(Item.Header(R.string.toolbox_library)).also { addAll(library) }
            if (matching.isNotEmpty()) add(Item.Header(R.string.toolbox_matching)).also { addAll(matching) }
        }
        @Suppress("NotifyDataSetChanged") // A handful of cards, rebuilt whole.
        adapter.notifyDataSetChanged()
    }

    private fun check(check: LibraryCheck, descRes: Int, ignoredTitleRes: Int) = Item.Tool(
        titleRes = check.titleRes,
        descRes = descRes,
        iconRes = check.iconRes,
        ignored = check.ignorePref,
        ignoredTitleRes = ignoredTitleRes,
    ) { icon ->
        SettingsPageTransitions.open(
            this, icon,
            Intent(this, ToolResultsActivity::class.java).putExtra(ToolResultsActivity.EXTRA_CHECK, check.name),
        )
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) HeaderHolder(ItemToolboxHeaderBinding.inflate(inflater, parent, false))
            else CardHolder(ItemToolboxCardBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder as HeaderHolder).binding.toolboxHeader.setText(item.titleRes)
                is Item.Tool -> bindCard((holder as CardHolder).binding, item)
            }
        }
    }

    private class HeaderHolder(val binding: ItemToolboxHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    private class CardHolder(val binding: ItemToolboxCardBinding) : RecyclerView.ViewHolder(binding.root)

    private fun bindCard(b: ItemToolboxCardBinding, tool: Item.Tool) {
        b.toolboxCardIcon.setImageResource(tool.iconRes)
        // The accent colour, faded to a tint of itself: the icon on top is the full colour.
        b.toolboxCardIconTile.background.mutate().alpha = ICON_TILE_ALPHA
        b.toolboxCardTitle.setText(tool.titleRes)
        b.toolboxCardDesc.setText(tool.descRes)
        b.toolboxCard.setOnClickListener { tool.open(b.toolboxCardIcon) }
        // Only once there is something to take back: an empty pill on every check was noise.
        val count = tool.ignored?.let { PrefManager.getVal<Set<String>>(it).size } ?: 0
        b.toolboxCardIgnored.isVisible = count > 0
        if (count == 0) return
        b.toolboxCardIgnored.text = resources.getQuantityString(R.plurals.toolbox_ignored_pill, count, count)
        b.toolboxCardIgnored.setOnClickListener {
            val pref = tool.ignored ?: return@setOnClickListener
            MediaExcludeBottomDialog.newInstance(pref, getString(tool.ignoredTitleRes))
                .apply { onDismissed = { if (!isFinishing) render() } }
                .show(supportFragmentManager, pref.name)
        }
    }

    private companion object {
        const val COLUMNS = 2

        /** How much of the accent colour the icon tiles keep, out of 255. */
        const val ICON_TILE_ALPHA = 46
    }
}
