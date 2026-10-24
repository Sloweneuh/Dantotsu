package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickMatches
import ani.dantotsu.connections.mangaupdates.MUSeriesRecord
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.muMediaKey
import ani.dantotsu.databinding.ActivityComickMatchesBinding
import ani.dantotsu.databinding.ItemComickMatchBinding
import ani.dantotsu.databinding.ItemComickMatchHeaderBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.media.ComickMediaActivity
import ani.dantotsu.media.Media
import ani.dantotsu.navBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * How the user's titles are matched to Comick, where it took a person to do it: the titles list
 * sync couldn't match, then the ones matched by hand, then the ones marked not on Comick.
 *
 * - **Waiting** — the misses sync records ([ComickMatches.unmatched]). "Find" searches Comick with
 *   the title's names, and a pick is saved exactly like one made on a media page's Comick tab, so
 *   the next sync uses it; the title then moves down to the matched ones. "Not on Comick" sets a
 *   title aside (with undo).
 * - **Matched by hand** — every pick ([ComickMatches.picks]), from this screen or from a media page,
 *   which otherwise could only be reviewed title by title, so a wrong one went unnoticed until sync
 *   wrote progress to the wrong Comick entry. Each shows the Comick entry it points at; "Change"
 *   searches again, and removing the match (with undo) has the title matched afresh next time.
 * - **Marked not on Comick** — set aside, but still fully usable: Comick may add them later.
 *
 * Titles and covers come from AniList, or MangaUpdates for series AniList doesn't have.
 */
class ComickMatchesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityComickMatchesBinding

    /** The title a row is about, looked up on AniList ([media]) or MangaUpdates ([mu]). */
    private data class Title(
        val name: String,
        val cover: String?,
        val info: String,
        val searchTitles: List<String>,
        val media: Media? = null,
        val mu: MUSeriesRecord? = null,
    )

    private data class Waiting(val unmatched: ComickMatches.Unmatched, val title: Title)

    /**
     * A pick and the Comick entry's title ([comickTitle]; the slug stands in when it couldn't be
     * fetched). [title] has neither media nor series when the title itself couldn't be looked up —
     * an older MangaUpdates pick knows no series id — and is then named after the Comick entry.
     */
    private data class Matched(val pick: ComickMatches.Pick, val title: Title, val comickTitle: String?)

    private val waiting = mutableListOf<Waiting>()
    private val matched = mutableListOf<Matched>()

    private enum class Section { MATCHED, NOT_ON_COMICK }

    /** Which sections are open. The matched ones start closed while there is something to match. */
    private val expanded = mutableSetOf<Section>()

    private sealed interface Item {
        data class WaitingTitle(val row: Waiting) : Item
        data class MatchedTitle(val row: Matched) : Item
        data class Header(val section: Section, val count: Int, val expanded: Boolean) : Item
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityComickMatchesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.comickMatchLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.comickMatchBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.comickMatchRecycler.layoutManager = LinearLayoutManager(this)
        binding.comickMatchRecycler.adapter = adapter
        load()
    }

    private fun load() {
        val unmatched = ComickMatches.unmatched()
        val picks = ComickMatches.picks()
        lifecycleScope.launch {
            val anilistIds = (unmatched.mapNotNull { it.anilistId } + picks.mapNotNull { it.anilistId }).distinct()
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
            val seriesIds = (unmatched.mapNotNull { it.muSeriesId } + picks.mapNotNull { it.muSeriesId }).distinct()
            val series = withContext(Dispatchers.IO) {
                seriesIds.mapNotNull { id ->
                    runCatching { MangaUpdates.getSeriesDetails(id) }.getOrNull()?.let { id to it }
                }.toMap()
            }
            // The Comick side of each match, a few at a time: the details are cached once fetched.
            val gate = Semaphore(COMICK_PARALLEL)
            val comickTitles = withContext(Dispatchers.IO) {
                picks.map { pick ->
                    async { gate.withPermit { pick to comickTitle(pick) } }
                }.awaitAll().toMap()
            }

            waiting.clear()
            unmatched.mapNotNullTo(waiting) { u ->
                val title = u.anilistId?.let { id ->
                    // Comick doesn't list anime before they air, so those aren't misses yet.
                    media[id]?.takeUnless { u.isAnime && isUnreleased(it) }?.let { anilistTitle(it, u.isAnime) }
                } ?: u.muSeriesId?.let { id -> series[id]?.let(::mangaUpdatesTitle) }
                title?.let { Waiting(u, it) }
            }
            waiting.sortBy { it.title.name.lowercase() }
            matched.clear()
            picks.mapTo(matched) { pick ->
                val comickTitle = comickTitles[pick]
                val title = pick.anilistId?.let { media[it] }?.let { anilistTitle(it, pick.isAnime) }
                    ?: pick.muSeriesId?.let { series[it] }?.let(::mangaUpdatesTitle)
                    ?: unknownTitle(pick, comickTitle)
                Matched(pick, title, comickTitle)
            }
            matched.sortBy { it.title.name.lowercase() }
            if (waiting.none { !it.unmatched.notOnComick }) expanded += Section.MATCHED
            binding.comickMatchProgress.isVisible = false
            refresh()
        }
    }

    private suspend fun comickTitle(pick: ComickMatches.Pick): String? = runCatching {
        val type = if (pick.isAnime) ComickApi.MEDIA_TYPE_ANIME else ComickApi.MEDIA_TYPE_MANGA
        ComickApi.getComicDetails(pick.slug, mediaType = type)?.comic?.title
    }.getOrNull()

    /** Media.status is the display label (MediaStatus.toString), or the raw name without a context. */
    private fun isUnreleased(media: Media) =
        media.status == "NOT_YET_RELEASED" || media.status == getString(R.string.status_not_yet_released)

    private fun anilistTitle(media: Media, isAnime: Boolean) = Title(
        name = media.userPreferredName,
        cover = media.cover,
        info = listOfNotNull(
            getString(if (isAnime) R.string.anime else R.string.manga),
            anilistFormatLabel(media.format),
            media.startDate?.year?.toString(),
        ).joinToString(" · "),
        searchTitles = comickSearchTitles(media),
        media = media,
    )

    private fun mangaUpdatesTitle(record: MUSeriesRecord) = Title(
        name = record.title ?: record.seriesId.toString(),
        cover = record.image?.url?.run { original ?: thumb },
        info = listOfNotNull(
            getString(R.string.mangaupdates),
            record.type?.takeIf { it.isNotBlank() },
            record.year?.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        searchTitles = comickSearchTitles(record),
        mu = record,
    )

    /** A pick whose title was looked up neither way: named after its Comick entry, to still be fixable. */
    private fun unknownTitle(pick: ComickMatches.Pick, comickTitle: String?) = Title(
        name = comickTitle ?: pick.slug,
        cover = null,
        info = getString(
            when {
                pick.anilistId == null -> R.string.mangaupdates
                pick.isAnime -> R.string.anime
                else -> R.string.manga
            }
        ),
        searchTitles = listOfNotNull(comickTitle),
    )

    private fun refresh() {
        val active = waiting.filter { !it.unmatched.notOnComick }
        val hidden = waiting.filter { it.unmatched.notOnComick }
        adapter.submitList(buildList {
            active.mapTo(this) { Item.WaitingTitle(it) }
            if (matched.isNotEmpty()) {
                val open = Section.MATCHED in expanded
                add(Item.Header(Section.MATCHED, matched.size, open))
                if (open) matched.mapTo(this) { Item.MatchedTitle(it) }
            }
            if (hidden.isNotEmpty()) {
                val open = Section.NOT_ON_COMICK in expanded
                add(Item.Header(Section.NOT_ON_COMICK, hidden.size, open))
                if (open) hidden.mapTo(this) { Item.WaitingTitle(it) }
            }
        })
        val empty = waiting.isEmpty() && matched.isEmpty()
        binding.comickMatchEmpty.isVisible = empty
        binding.comickMatchSummary.isVisible = !empty
        binding.comickMatchSummary.text = if (active.isNotEmpty()) {
            resources.getQuantityString(R.plurals.comick_unmatched_summary, active.size, active.size)
        } else getString(R.string.comick_unmatched_none_waiting)
    }

    private val adapter = object : ListAdapter<Item, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(a: Item, b: Item) = when {
            a is Item.WaitingTitle && b is Item.WaitingTitle ->
                a.row.unmatched.anilistId == b.row.unmatched.anilistId &&
                    a.row.unmatched.muSeriesId == b.row.unmatched.muSeriesId &&
                    a.row.unmatched.isAnime == b.row.unmatched.isAnime
            // The title, not the pick: changing which Comick entry it points at keeps the row.
            a is Item.MatchedTitle && b is Item.MatchedTitle ->
                a.row.pick.anilistId == b.row.pick.anilistId && a.row.pick.muKey == b.row.pick.muKey &&
                    a.row.pick.isAnime == b.row.pick.isAnime
            a is Item.Header && b is Item.Header -> a.section == b.section
            else -> false
        }

        override fun areContentsTheSame(a: Item, b: Item) = a == b
    }) {
        override fun getItemViewType(position: Int) = if (getItem(position) is Item.Header) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) HeaderHolder(ItemComickMatchHeaderBinding.inflate(inflater, parent, false))
            else RowHolder(ItemComickMatchBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is Item.Header -> bindHeader((holder as HeaderHolder).binding, item)
                is Item.WaitingTitle -> bindWaiting((holder as RowHolder).binding, item.row)
                is Item.MatchedTitle -> bindMatched((holder as RowHolder).binding, item.row)
            }
        }
    }

    private class RowHolder(val binding: ItemComickMatchBinding) : RecyclerView.ViewHolder(binding.root)
    private class HeaderHolder(val binding: ItemComickMatchHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    private fun bindHeader(b: ItemComickMatchHeaderBinding, header: Item.Header) {
        when (header.section) {
            Section.MATCHED -> {
                b.comickMatchHeaderTitle.text = getString(R.string.comick_matches_manual_title, header.count)
                b.comickMatchHeaderDesc.setText(R.string.comick_matches_manual_desc)
            }
            Section.NOT_ON_COMICK -> {
                b.comickMatchHeaderTitle.text = getString(R.string.comick_unmatched_hidden_title, header.count)
                b.comickMatchHeaderDesc.setText(R.string.comick_unmatched_hidden_desc)
            }
        }
        b.comickMatchHeaderChevron.animate().rotation(if (header.expanded) 180f else 0f).setDuration(200).start()
        b.root.setOnClickListener {
            if (!expanded.add(header.section)) expanded.remove(header.section)
            refresh()
        }
    }

    /** What both kinds of row show of the title, and its page behind the cover and name. */
    private fun bindTitle(b: ItemComickMatchBinding, title: Title, openInstead: (() -> Unit)? = null) {
        b.comickMatchCover.loadImage(title.cover)
        b.comickMatchName.text = title.name
        b.comickMatchInfo.text = title.info
        // The card itself isn't clickable (it drew a second hover layer); the cover and title
        // open the media page, the buttons do the rest.
        b.comickMatchOpen.setOnClickListener {
            if (title.media == null && title.mu == null) openInstead?.invoke()
            else openTitlePage(title.media, title.mu, title.cover)
        }
    }

    private fun bindWaiting(b: ItemComickMatchBinding, row: Waiting) {
        val hidden = row.unmatched.notOnComick
        bindTitle(b, row.title)
        b.comickMatchMatch.isVisible = false
        // Marked titles read as set aside, but stay fully usable: Comick may have added them since.
        b.comickMatchOpen.alpha = if (hidden) 0.6f else 1f
        b.comickMatchSearch.setText(R.string.comick_unmatched_find)
        b.comickMatchSearch.setOnClickListener { find(row) }
        b.comickMatchDismiss.setIconResource(if (hidden) R.drawable.ic_round_undo_24 else R.drawable.ic_round_close_24)
        val label = getString(if (hidden) R.string.comick_unmatched_restore else R.string.comick_unmatched_not_on_comick)
        b.comickMatchDismiss.contentDescription = label
        b.comickMatchDismiss.tooltipText = label
        b.comickMatchDismiss.setOnClickListener {
            if (hidden) {
                setNotOnComick(row, false)
                return@setOnClickListener
            }
            setNotOnComick(row, true)
            Snackbar.make(binding.root, getString(R.string.comick_unmatched_dismissed, row.title.name), Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) { setNotOnComick(row, false) }
                .show()
        }
    }

    private fun bindMatched(b: ItemComickMatchBinding, row: Matched) {
        // A title that couldn't be looked up has no page of its own; its Comick entry stands in.
        bindTitle(b, row.title) { openComick(row.pick) }
        b.comickMatchOpen.alpha = 1f
        b.comickMatchMatch.isVisible = true
        b.comickMatchMatch.text = getString(R.string.comick_matches_entry, row.comickTitle ?: row.pick.slug)
        b.comickMatchMatch.setOnClickListener { openComick(row.pick) }
        b.comickMatchSearch.setText(R.string.comick_matches_change)
        b.comickMatchSearch.setOnClickListener { change(row) }
        val label = getString(R.string.comick_matches_remove)
        b.comickMatchDismiss.setIconResource(R.drawable.ic_round_close_24)
        b.comickMatchDismiss.contentDescription = label
        b.comickMatchDismiss.tooltipText = label
        b.comickMatchDismiss.setOnClickListener { remove(row) }
    }

    private fun openComick(pick: ComickMatches.Pick) {
        startActivity(
            Intent(this, ComickMediaActivity::class.java)
                .putExtra(ComickMediaActivity.EXTRA_SLUG, pick.slug)
                .putExtra(
                    ComickMediaActivity.EXTRA_MEDIA_TYPE,
                    if (pick.isAnime) ComickApi.MEDIA_TYPE_ANIME else ComickApi.MEDIA_TYPE_MANGA,
                )
        )
    }

    /** Moves a waiting title into or out of the "not on Comick" section. */
    private fun setNotOnComick(row: Waiting, notOnComick: Boolean) {
        val index = waiting.indexOfFirst { it.unmatched == row.unmatched }
        if (index < 0) return
        val moved = row.copy(unmatched = row.unmatched.copy(notOnComick = notOnComick))
        if (notOnComick) ComickMatches.markNotOnComick(moved.unmatched)
        else ComickMatches.unmarkNotOnComick(moved.unmatched)
        waiting[index] = moved
        refresh()
    }

    /** Searches Comick for a waiting title; picking a result links it, and it joins the matched ones. */
    private fun find(row: Waiting) {
        val titles = row.title.searchTitles.ifEmpty { listOf(row.title.name) }
        showComickPickSheet(titles, row.unmatched.isAnime) { slug, hid, comickTitle ->
            val u = row.unmatched
            val pick = u.anilistId?.let {
                ComickMatches.link(it, u.isAnime, slug, hid)
                ComickMatches.Pick(slug, anilistId = it, isAnime = u.isAnime)
            } ?: u.muSeriesId?.let {
                ComickMatches.linkMangaUpdates(it, slug, hid)
                ComickMatches.Pick(slug, muSeriesId = it, muKey = muMediaKey(it))
            } ?: return@showComickPickSheet
            waiting.removeAll { it.unmatched == u }
            matched += Matched(pick, row.title, comickTitle)
            matched.sortBy { it.title.name.lowercase() }
            refresh()
            Snackbar.make(
                binding.root,
                getString(R.string.comick_unmatched_linked, row.title.name, comickTitle),
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    /** Searches Comick again for a matched title; picking a result moves the match there. */
    private fun change(row: Matched) {
        val titles = row.title.searchTitles.ifEmpty { listOf(row.title.name) }
        showComickPickSheet(titles, row.pick.isAnime) { slug, hid, comickTitle ->
            val index = matched.indexOfFirst { it.pick == row.pick }
            if (index < 0) return@showComickPickSheet
            matched[index] = row.copy(pick = ComickMatches.repick(row.pick, slug, hid), comickTitle = comickTitle)
            refresh()
            Snackbar.make(
                binding.root,
                getString(R.string.comick_unmatched_linked, row.title.name, comickTitle),
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private fun remove(row: Matched) {
        val index = matched.indexOfFirst { it.pick == row.pick }
        if (index < 0) return
        ComickMatches.unpick(row.pick)
        matched.removeAt(index)
        refresh()
        Snackbar.make(binding.root, getString(R.string.comick_matches_removed, row.title.name), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                ComickMatches.restore(row.pick)
                matched.add(index.coerceAtMost(matched.size), row)
                refresh()
            }
            .show()
    }

    private companion object {
        /** Comick lookups in flight at once while the list loads. */
        const val COMICK_PARALLEL = 4
    }
}
