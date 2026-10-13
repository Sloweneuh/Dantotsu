package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import java.io.Serializable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickMatches
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
import ani.dantotsu.connections.mangaupdates.MUSeriesRecord
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.databinding.ActivityComickUnmatchedBinding
import ani.dantotsu.databinding.ItemComickUnmatchedBinding
import ani.dantotsu.databinding.ItemComickUnmatchedHeaderBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.media.ComickSearchAdapter
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The titles Comick list sync couldn't match, with a way to match each by hand.
 *
 * Built from the misses sync records ([ComickMatches.unmatched]); titles and covers come from
 * AniList. "Find" searches Comick with the title's names, and a pick is saved exactly like one
 * made on a media page's Comick tab, so the next sync uses it. "Not on Comick" takes a title off
 * the list for good (with undo).
 */
class ComickUnmatchedActivity : AppCompatActivity() {
    private lateinit var binding: ActivityComickUnmatchedBinding
    private val rows = mutableListOf<Row>()

    /**
     * One title, from AniList ([media]) or MangaUpdates ([mu], for series AniList doesn't have):
     * what the row shows, what "Find" searches by, and which media page the cover and title open.
     */
    private data class Row(
        val unmatched: ComickMatches.Unmatched,
        val title: String,
        val cover: String?,
        val info: String,
        val searchTitles: List<String>,
        val media: Media? = null,
        val mu: MUSeriesRecord? = null,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityComickUnmatchedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.comickUnmatchedLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.comickUnmatchedBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.comickUnmatchedRecycler.layoutManager = LinearLayoutManager(this)
        binding.comickUnmatchedRecycler.adapter = adapter
        load()
    }

    private fun load() {
        val unmatched = ComickMatches.unmatched()
        lifecycleScope.launch {
            val anilistIds = unmatched.mapNotNull { it.anilistId }.distinct()
            val media = withContext(Dispatchers.IO) {
                runCatching {
                    // AniList pages hold 50; ids not returned (deleted entries) just drop out.
                    anilistIds.chunked(50).flatMap {
                        // Synonyms for the search dropdown, the start date for the year.
                        Anilist.query.getMediaBatch(it, extraFields = "synonyms startDate { year month day }")
                    }
                }.onFailure { Logger.log(it) }.getOrDefault(emptyList())
            }.associateBy { it.id }
            // MangaUpdates has no batch lookup; one request per series, usually only a few.
            val series = withContext(Dispatchers.IO) {
                unmatched.mapNotNull { it.muSeriesId }.distinct().mapNotNull { id ->
                    runCatching { MangaUpdates.getSeriesDetails(id) }.getOrNull()?.let { id to it }
                }.toMap()
            }
            rows.clear()
            unmatched.mapNotNullTo(rows) { u ->
                u.anilistId?.let { id ->
                    // Comick doesn't list anime before they air, so those aren't misses yet.
                    media[id]?.takeUnless { u.isAnime && isUnreleased(it) }
                        ?.let { anilistRow(u, it) }
                }
                    ?: u.muSeriesId?.let { id -> series[id]?.let { mangaUpdatesRow(u, it) } }
            }
            rows.sortBy { it.title.lowercase() }
            binding.comickUnmatchedProgress.isVisible = false
            refresh()
        }
    }

    /** Media.status is the display label (MediaStatus.toString), or the raw name without a context. */
    private fun isUnreleased(media: Media) =
        media.status == "NOT_YET_RELEASED" || media.status == getString(R.string.status_not_yet_released)

    private fun anilistRow(unmatched: ComickMatches.Unmatched, media: Media) = Row(
        unmatched = unmatched,
        title = media.userPreferredName,
        cover = media.cover,
        info = listOfNotNull(
            getString(if (unmatched.isAnime) R.string.anime else R.string.manga),
            formatLabel(media.format),
            media.startDate?.year?.toString(),
        ).joinToString(" · "),
        searchTitles = searchTitles(media),
        media = media,
    )

    private fun mangaUpdatesRow(unmatched: ComickMatches.Unmatched, record: MUSeriesRecord) = Row(
        unmatched = unmatched,
        title = record.title ?: record.seriesId.toString(),
        cover = record.image?.url?.run { original ?: thumb },
        info = listOfNotNull(
            getString(R.string.mangaupdates),
            record.type?.takeIf { it.isNotBlank() },
            record.year?.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        // The main title, then every associated one.
        searchTitles = (listOfNotNull(record.title) + record.associated.orEmpty().mapNotNull { it.title })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        mu = record,
    )

    /** Whether the "marked not on Comick" section is open. */
    private var hiddenExpanded = false

    /** What the list shows: the titles waiting, then the marked ones under their header. */
    private sealed interface Item {
        data class Title(val row: Row) : Item
        data class Header(val count: Int, val expanded: Boolean) : Item
    }

    private fun refresh() {
        val active = rows.filter { !it.unmatched.notOnComick }
        val hidden = rows.filter { it.unmatched.notOnComick }
        adapter.submitList(buildList {
            active.mapTo(this) { Item.Title(it) }
            if (hidden.isNotEmpty()) {
                add(Item.Header(hidden.size, hiddenExpanded))
                if (hiddenExpanded) hidden.mapTo(this) { Item.Title(it) }
            }
        })
        binding.comickUnmatchedEmpty.isVisible = rows.isEmpty()
        binding.comickUnmatchedSummary.isVisible = rows.isNotEmpty()
        binding.comickUnmatchedSummary.text = if (active.isNotEmpty()) {
            resources.getQuantityString(R.plurals.comick_unmatched_summary, active.size, active.size)
        } else getString(R.string.comick_unmatched_none_waiting)
    }

    private fun removeRow(row: Row) {
        rows.removeAll { it.unmatched == row.unmatched }
        refresh()
    }

    /** Moves a title into or out of the "not on Comick" section. */
    private fun setNotOnComick(row: Row, notOnComick: Boolean) {
        val index = rows.indexOfFirst { it.unmatched == row.unmatched }
        if (index < 0) return
        val moved = row.copy(unmatched = row.unmatched.copy(notOnComick = notOnComick))
        if (notOnComick) ComickMatches.markNotOnComick(moved.unmatched)
        else ComickMatches.unmarkNotOnComick(moved.unmatched)
        rows[index] = moved
        refresh()
    }

    private val adapter = object : ListAdapter<Item, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(a: Item, b: Item) = when {
            a is Item.Title && b is Item.Title -> a.row.unmatched.anilistId == b.row.unmatched.anilistId &&
                a.row.unmatched.muSeriesId == b.row.unmatched.muSeriesId &&
                a.row.unmatched.isAnime == b.row.unmatched.isAnime
            else -> a is Item.Header && b is Item.Header
        }

        override fun areContentsTheSame(a: Item, b: Item) = a == b
    }) {
        override fun getItemViewType(position: Int) = if (getItem(position) is Item.Header) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) HeaderHolder(ItemComickUnmatchedHeaderBinding.inflate(inflater, parent, false))
            else RowHolder(ItemComickUnmatchedBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is Item.Header -> bindHeader((holder as HeaderHolder).binding, item)
                is Item.Title -> bindRow((holder as RowHolder).binding, item.row)
            }
        }
    }

    private fun bindHeader(b: ItemComickUnmatchedHeaderBinding, header: Item.Header) {
        b.comickUnmatchedHeaderTitle.text = getString(R.string.comick_unmatched_hidden_title, header.count)
        b.comickUnmatchedHeaderChevron.animate().rotation(if (header.expanded) 180f else 0f).setDuration(200).start()
        b.root.setOnClickListener {
            hiddenExpanded = !hiddenExpanded
            refresh()
        }
    }

    private fun bindRow(b: ItemComickUnmatchedBinding, row: Row) {
        val hidden = row.unmatched.notOnComick
        b.comickUnmatchedCover.loadImage(row.cover)
        b.comickUnmatchedName.text = row.title
        b.comickUnmatchedInfo.text = row.info
        // Marked titles read as set aside, but stay fully usable: Comick may have added them since.
        b.comickUnmatchedOpen.alpha = if (hidden) 0.6f else 1f
        // The card itself isn't clickable (it drew a second hover layer); the cover and title
        // open the media page, the buttons do the rest.
        b.comickUnmatchedOpen.setOnClickListener { open(row) }
        b.comickUnmatchedSearch.setOnClickListener { showSearch(row) }
        b.comickUnmatchedDismiss.setIconResource(if (hidden) R.drawable.ic_round_undo_24 else R.drawable.ic_round_close_24)
        val label = getString(if (hidden) R.string.comick_unmatched_restore else R.string.comick_unmatched_not_on_comick)
        b.comickUnmatchedDismiss.contentDescription = label
        b.comickUnmatchedDismiss.tooltipText = label
        b.comickUnmatchedDismiss.setOnClickListener {
            if (hidden) {
                setNotOnComick(row, false)
                return@setOnClickListener
            }
            setNotOnComick(row, true)
            Snackbar.make(binding.root, getString(R.string.comick_unmatched_dismissed, row.title), Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) { setNotOnComick(row, false) }
                .show()
        }
    }

    /** AniList's format as a label; none where it only repeats the type ("Manga · Manga"). */
    private fun formatLabel(format: String?): String? = when (format) {
        null, "MANGA" -> null
        "TV" -> "TV"
        "TV_SHORT" -> "TV short"
        "ONA", "OVA" -> format
        else -> format.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    /** The title's own media page: AniList's, or MangaUpdates' for a series AniList lacks. */
    private fun open(row: Row) {
        row.media?.let {
            startActivity(Intent(this, MediaDetailsActivity::class.java).putExtra("mediaId", it.id))
        }
        row.mu?.let { record ->
            val muMedia = MUMedia(
                id = record.seriesId,
                title = record.title,
                url = record.url,
                coverUrl = row.cover,
                listId = -1,
                userChapter = null,
                userVolume = null,
                latestChapter = record.latest_chapter?.toInt(),
                bayesianRating = record.bayesian_rating?.toDoubleOrNull(),
                priority = null,
                format = record.type,
            )
            startActivity(Intent(this, MUMediaDetailsActivity::class.java).putExtra("muMedia", muMedia as Serializable))
        }
    }

    private class RowHolder(val binding: ItemComickUnmatchedBinding) : RecyclerView.ViewHolder(binding.root)
    private class HeaderHolder(val binding: ItemComickUnmatchedHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    /** The title's names to search Comick by: preferred, romaji, English, then synonyms. */
    private fun searchTitles(media: Media): List<String> = buildList {
        add(media.userPreferredName)
        add(media.nameRomaji)
        media.name?.let { add(it) }
        addAll(media.synonyms)
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Searches Comick for [row]'s title; picking a result links it. */
    private fun showSearch(row: Row) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.fragment_search_page, null)
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        view.minimumHeight = (resources.displayMetrics.heightPixels * 0.85f).toInt()

        val barLayout = view.findViewById<TextInputLayout>(R.id.searchBarLayout)
        val bar = view.findViewById<AutoCompleteTextView>(R.id.searchBarText)
        val progress = view.findViewById<View>(R.id.searchProgress)
        val empty = view.findViewById<TextView>(R.id.emptyMessage)
        val results = view.findViewById<RecyclerView>(R.id.searchRecyclerView)
        results.layoutManager = GridLayoutManager(
            this, (resources.displayMetrics.widthPixels / 124f.px).coerceIn(2, 4)
        )
        val mediaType = if (row.unmatched.isAnime) ComickApi.MEDIA_TYPE_ANIME else ComickApi.MEDIA_TYPE_MANGA

        fun search(query: String) {
            if (query.isBlank()) return
            progress.isVisible = true
            empty.isVisible = false
            results.isVisible = false
            lifecycleScope.launch {
                val found = withContext(Dispatchers.IO) {
                    runCatching {
                        ComickApi.searchComics(query, PrefManager.getVal<Boolean>(PrefName.AdultOnly), mediaType = mediaType)
                    }.getOrNull()
                }
                progress.isVisible = false
                if (found.isNullOrEmpty()) {
                    empty.isVisible = true
                    return@launch
                }
                results.isVisible = true
                results.adapter = ComickSearchAdapter(found) { comic, _ ->
                    val slug = comic.slug ?: return@ComickSearchAdapter
                    lifecycleScope.launch {
                        // The HID is what sync writes to; the slug alone would need a lookup each run.
                        val hid = comic.hid ?: withContext(Dispatchers.IO) {
                            ComickApi.getComicDetails(slug, mediaType = mediaType)?.comic?.hid
                        }
                        if (hid == null) {
                            Snackbar.make(view, R.string.failed_fetch_comick, Snackbar.LENGTH_SHORT).show()
                            return@launch
                        }
                        val u = row.unmatched
                        u.anilistId?.let { ComickMatches.link(it, u.isAnime, slug, hid) }
                        u.muSeriesId?.let { ComickMatches.linkMangaUpdates(it, slug, hid) }
                        dialog.dismiss()
                        removeRow(row)
                        Snackbar.make(
                            binding.root,
                            getString(R.string.comick_unmatched_linked, row.title, comic.title ?: slug),
                            Snackbar.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }

        val titles = row.searchTitles.ifEmpty { listOf(row.title) }
        bar.setText(titles.first())
        if (titles.size > 1) {
            barLayout.endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            barLayout.setEndIconOnClickListener {
                val popup = ListPopupWindow(this)
                popup.anchorView = bar
                popup.setAdapter(ArrayAdapter(this, R.layout.item_titles_dropdown, titles))
                popup.isModal = true
                popup.width = barLayout.width
                popup.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.dropdown_background))
                popup.setOnItemClickListener { _, _, position, _ ->
                    bar.setText(titles[position])
                    popup.dismiss()
                    search(titles[position])
                }
                popup.show()
            }
        }
        bar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            search(bar.text.toString())
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(bar.windowToken, 0)
            true
        }
        dialog.show()
        search(titles.first())
    }
}
