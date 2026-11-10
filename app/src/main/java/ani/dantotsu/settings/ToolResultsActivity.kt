package ani.dantotsu.settings

import ani.dantotsu.util.capContentWidth
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
import ani.dantotsu.connections.anilist.AnilistQueries
import ani.dantotsu.connections.anilist.api.MediaRelation
import ani.dantotsu.connections.anilist.api.MediaType
import ani.dantotsu.databinding.ActivityToolResultsBinding
import ani.dantotsu.databinding.ItemToolResultBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A toolbox check over the user's AniList library: titles AniList relates to theirs that they don't
 * have on a list yet. What sets one apart from another is all here; [ToolResultsActivity] runs any.
 *
 * Each has its own ignore list ([ignorePref]), in a General pref so it syncs; entries are "id||cover||title" so [MediaExcludeBottomDialog] manages
 * them as it does the unread exclusion list.
 */
enum class LibraryCheck(
    val titleRes: Int,
    val iconRes: Int,
    /** Whose list the sources come from. */
    val sourcesFromAnime: Boolean,
    val relation: MediaRelation,
    /** The kind of title looked for; relations to anything else are ignored. */
    val targetType: MediaType,
    val ignorePref: PrefName,
    /** "Sequel to %1$s": how a finding is tied to the title it was found from. */
    val sourceRes: Int,
    val summaryRes: Int,
    val emptyRes: Int,
) {
    ANIME_SEQUELS(
        R.string.missed_anime_sequels, R.drawable.ic_round_movie_filter_24,
        sourcesFromAnime = true, MediaRelation.SEQUEL, MediaType.ANIME, PrefName.IgnoredSequels,
        R.string.missed_sequels_source, R.plurals.missed_sequels_summary, R.string.missed_sequels_none,
    ),
    MANGA_SEQUELS(
        R.string.missed_manga_sequels, R.drawable.ic_round_playlist_add_24,
        sourcesFromAnime = false, MediaRelation.SEQUEL, MediaType.MANGA, PrefName.IgnoredMangaSequels,
        R.string.missed_sequels_source, R.plurals.missed_sequels_summary, R.string.missed_sequels_none,
    ),
    ANIME_ADAPTATIONS(
        R.string.anime_adaptations, R.drawable.ic_round_movie_edit_outline_24,
        sourcesFromAnime = false, MediaRelation.ADAPTATION, MediaType.ANIME, PrefName.IgnoredAdaptations,
        R.string.anime_adaptations_source, R.plurals.anime_adaptations_summary, R.string.anime_adaptations_none,
    );

    /**
     * The titles on the list worth checking: for sequels, the ones finished (or being rewatched,
     * which means finished once) — a sequel to something still in progress isn't missed yet. For
     * adaptations, every manga and novel not dropped: an anime is worth knowing about from the
     * first chapter, but not of something the user gave up on.
     */
    fun sources(list: List<Media>): List<Media> = when (relation) {
        MediaRelation.SEQUEL -> list.filter { it.userStatus == "COMPLETED" || it.userStatus == "REPEATING" }
        else -> list.filter { it.format?.replace(' ', '_') in PRINT_FORMATS && it.userStatus != "DROPPED" }
    }

    fun ignoredIds(): Set<Int> = PrefManager.getVal<Set<String>>(ignorePref)
        .mapNotNullTo(HashSet()) { it.substringBefore("||").toIntOrNull() }

    companion object {
        private val PRINT_FORMATS = setOf("MANGA", "NOVEL", "ONE_SHOT")
        private const val SEQUELS_SPLIT_KEY = "ignored_sequels_split"

        /** Whether [splitSequelIgnores] still has to run. */
        fun sequelIgnoresNeedSplit(): Boolean =
            !PrefManager.getCustomVal(SEQUELS_SPLIT_KEY, false) &&
                PrefManager.getVal<Set<String>>(PrefName.IgnoredSequels).isNotEmpty()

        /**
         * Older builds kept the anime and manga sequel checks' ignores in one list, [PrefName.IgnoredSequels],
         * which made both cards show the same count. The entries carry no type, so AniList is asked
         * for each one's, and the manga ones move to [PrefName.IgnoredMangaSequels]. Runs once;
         * true when something moved. Left for next time if AniList doesn't answer.
         */
        suspend fun splitSequelIgnores(): Boolean = withContext(Dispatchers.IO) {
            if (!sequelIgnoresNeedSplit()) return@withContext false
            val entries = PrefManager.getVal<Set<String>>(PrefName.IgnoredSequels)
            val ids = entries.mapNotNull { it.substringBefore("||").toIntOrNull() }.distinct()
            val mangaIds = HashSet<Int>()
            for (chunk in ids.chunked(50)) {
                val query = "{ page0: Page(perPage: ${chunk.size}) { media(id_in: [${chunk.joinToString(",")}]) { id type } } }"
                val pages = runCatching {
                    Anilist.executeQuery<ani.dantotsu.connections.anilist.api.Query.BatchedPageResponse>(query, force = true)?.data
                }.getOrNull() ?: return@withContext false
                pages.values.forEach { page ->
                    page.media.orEmpty().filter { it.type == MediaType.MANGA }.mapTo(mangaIds) { it.id }
                }
            }
            val manga = entries.filterTo(HashSet()) { it.substringBefore("||").toIntOrNull() in mangaIds }
            if (manga.isNotEmpty()) {
                PrefManager.setVal(PrefName.IgnoredSequels, entries - manga)
                PrefManager.setVal(PrefName.IgnoredMangaSequels, PrefManager.getVal<Set<String>>(PrefName.IgnoredMangaSequels) + manga)
            }
            PrefManager.setCustomVal(SEQUELS_SPLIT_KEY, true)
            manga.isNotEmpty()
        }
    }
}

/**
 * What a [LibraryCheck] finds, one row each: the title, the user's title it was found from, "Add to
 * Planning" (on AniList), and "Ignore" (with undo), which keeps it out of every later run.
 *
 * The check runs here, behind the spinner, rather than on the screen it was started from: it reads
 * the whole list and every title's relations, which takes a while on a large library.
 */
class ToolResultsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityToolResultsBinding
    private lateinit var check: LibraryCheck
    private val rows = mutableListOf<AnilistQueries.RelatedTitle>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        check = intent.getStringExtra(EXTRA_CHECK)
            ?.let { name -> LibraryCheck.entries.firstOrNull { it.name == name } }
            ?: run { finish(); return }
        binding = ActivityToolResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        capContentWidth()
        binding.toolResultsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.toolResultsHeading.setText(check.titleRes)
        binding.toolResultsIcon.setImageResource(check.iconRes)
        binding.toolResultsEmpty.setText(check.emptyRes)
        binding.toolResultsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolResultsRecycler.layoutManager = LinearLayoutManager(this)
        binding.toolResultsRecycler.adapter = adapter
        load()
    }

    private fun load() {
        val userId = Anilist.userid
        if (userId == null) {
            fail()
            return
        }
        lifecycleScope.launch {
            // So a manga sequel ignored on an older build isn't suggested again.
            if (check.relation == MediaRelation.SEQUEL) LibraryCheck.splitSequelIgnores()
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    // Null rather than empty when AniList didn't answer, so an outage can't read as
                    // "nothing missed".
                    val list = Anilist.query.getMediaListsOrNull(check.sourcesFromAnime, userId)
                        ?.get("All") ?: return@runCatching null
                    Anilist.query.getRelatedNotOnList(
                        check.sources(list).map { it.id }, check.relation, check.targetType, check.ignoredIds(),
                    )
                }.getOrNull()
            }
            binding.toolResultsProgress.isVisible = false
            if (found == null) {
                fail()
                return@launch
            }
            rows.clear()
            rows += found.sortedBy { it.related.userPreferredName.lowercase() }
            refresh()
        }
    }

    private fun fail() {
        binding.toolResultsProgress.isVisible = false
        binding.toolResultsEmpty.setText(R.string.tool_results_failed)
        binding.toolResultsEmpty.isVisible = true
    }

    private fun refresh() {
        adapter.submitList(rows.toList())
        binding.toolResultsEmpty.isVisible = rows.isEmpty()
        binding.toolResultsSummary.isVisible = rows.isNotEmpty()
        binding.toolResultsSummary.text = resources.getQuantityString(check.summaryRes, rows.size, rows.size)
    }

    private val adapter = object : ListAdapter<AnilistQueries.RelatedTitle, RowHolder>(
        object : DiffUtil.ItemCallback<AnilistQueries.RelatedTitle>() {
            override fun areItemsTheSame(a: AnilistQueries.RelatedTitle, b: AnilistQueries.RelatedTitle) =
                a.related.id == b.related.id

            override fun areContentsTheSame(a: AnilistQueries.RelatedTitle, b: AnilistQueries.RelatedTitle) =
                a.related.id == b.related.id && a.source.id == b.source.id
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(ItemToolResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) = bind(holder.binding, getItem(position))
    }

    private class RowHolder(val binding: ItemToolResultBinding) : RecyclerView.ViewHolder(binding.root)

    private fun bind(b: ItemToolResultBinding, row: AnilistQueries.RelatedTitle) {
        val media = row.related
        b.toolResultCover.loadImage(media.cover)
        b.toolResultName.text = media.userPreferredName
        b.toolResultInfo.text = listOfNotNull(
            anilistFormatLabel(media.format?.replace(' ', '_')),
            media.startDate?.year?.toString(),
            media.status?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        b.toolResultSource.text = getString(check.sourceRes, row.source.userPreferredName)
        b.toolResultOpen.setOnClickListener { openMedia(media) }
        b.toolResultSource.setOnClickListener { openMedia(row.source) }
        b.toolResultIgnore.setOnClickListener { ignore(row) }
        b.toolResultPlan.isEnabled = true
        b.toolResultPlan.setOnClickListener {
            it.isEnabled = false
            plan(row)
        }
    }

    /** Puts [row]'s title on the user's AniList Planning list, which takes it out of the results. */
    private fun plan(row: AnilistQueries.RelatedTitle) {
        val media = row.related
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { Anilist.mutation.editList(media.id, status = "PLANNING") }.getOrDefault(false)
            }
            val index = rows.indexOfFirst { it.related.id == media.id }
            if (ok && index >= 0) {
                rows.removeAt(index)
                refresh()
            } else if (!ok) {
                // Rebinding turns the button back on.
                adapter.notifyItemChanged(adapter.currentList.indexOfFirst { it.related.id == media.id })
            }
            val message = if (ok) R.string.tool_results_planned else R.string.tool_results_plan_failed
            Snackbar.make(binding.root, getString(message, media.userPreferredName), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun openMedia(media: Media) {
        startActivity(Intent(this, MediaDetailsActivity::class.java).putExtra("mediaId", media.id))
    }

    /** Stops suggesting [row]'s title, here and in every later run; the snackbar undoes it. */
    private fun ignore(row: AnilistQueries.RelatedTitle) {
        val index = rows.indexOfFirst { it.related.id == row.related.id }
        if (index < 0) return
        val media = row.related
        val entry = "${media.id}||${media.cover.orEmpty()}||${media.userPreferredName}"
        PrefManager.setVal(check.ignorePref, PrefManager.getVal<Set<String>>(check.ignorePref) + entry)
        rows.removeAt(index)
        refresh()
        Snackbar.make(binding.root, getString(R.string.tool_results_ignored, media.userPreferredName), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                PrefManager.setVal(check.ignorePref, PrefManager.getVal<Set<String>>(check.ignorePref) - entry)
                rows.add(index.coerceAtMost(rows.size), row)
                refresh()
            }
            .show()
    }

    companion object {
        /** The [LibraryCheck] to run, by name. */
        const val EXTRA_CHECK = "check"
    }
}
