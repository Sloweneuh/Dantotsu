package ani.dantotsu.media

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.IdCache
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.notifications.unread.ComickUnreadNotificationTask
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Readers also like": what the readers of a series also follow, by overlap in their lists — from
 * MangaBaka (`readers-also-like`) and Comick (`also-followed`). One section kind,
 * [InfoSectionKind.READERS_ALSO_LIKE], so a custom tab can merge the two into one row; each also
 * shows on its own info tab.
 *
 * Titles are shown as their AniList media, else their MangaUpdates series: a card has to open
 * something, and Comick-only titles have no page of that kind. Ones that can't be resolved are left
 * out, as the recommendation rows do.
 */
object ReadersAlsoLike {

    private val KIND = InfoSectionKind.READERS_ALSO_LIKE

    /**
     * How many Comick titles may cost a lookup of their links per visit. Ones looked up before cost
     * nothing; the rest wait for a later visit rather than queue a burst on Comick's rate limit.
     */
    private const val MAX_COMICK_LOOKUPS = 12

    /** A title to show, as each source describes it. */
    private data class Ref(val anilistId: Int?, val muId: Long?, val title: String?, val cover: String?)

    /** MangaBaka's row, for [seriesId], on [parent]. */
    fun addMangaBaka(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        isAlive: () -> Boolean,
        parent: ViewGroup,
        seriesId: Long,
        current: Media?,
    ) {
        val slot = slot(activity, parent, "readers_also_like_mangabaka") ?: return
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                MangaBakaApi.getReadersAlsoLike(seriesId, allowAdult = PrefManager.getVal(PrefName.AdultOnly))
            }
            val refs = items.mapNotNull { it.series }.map { s ->
                Ref(
                    anilistId = s.source?.anilist?.id?.takeIf { it > 0 },
                    muId = s.source?.mangaUpdates?.toMuSeriesId()?.takeIf { it > 0 },
                    title = s.displayTitle(),
                    cover = s.cover?.thumbUrl(),
                )
            }
            val media = withContext(Dispatchers.IO) { resolve(refs, current) }
            show(activity, isAlive, parent, slot, media, current)
        }
    }

    /** Comick's row, for the title with [hid], on [parent]. */
    fun addComick(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        isAlive: () -> Boolean,
        parent: ViewGroup,
        hid: String,
        current: Media?,
    ) {
        val slot = slot(activity, parent, "readers_also_like_comick") ?: return
        scope.launch {
            val allowAdult = PrefManager.getVal<Boolean>(PrefName.AdultOnly)
            val items = withContext(Dispatchers.IO) { ComickApi.getAlsoFollowed(hid) }
                .filter { allowAdult || it.content_rating != "erotica" }
            val refs = withContext(Dispatchers.IO) {
                var lookups = 0
                items.mapNotNull { item ->
                    val itemHid = item.hid ?: return@mapNotNull null
                    val (anilistId, muId) = comickLinks(itemHid, item.slug) { lookups++ < MAX_COMICK_LOOKUPS }
                        ?: return@mapNotNull null
                    Ref(anilistId, muId, item.displayTitle(), item.coverUrl)
                }
            }
            val media = withContext(Dispatchers.IO) { resolve(refs, current) }
            show(activity, isAlive, parent, slot, media, current)
        }
    }

    /**
     * The section's place, claimed now so it doesn't move about with whatever loads first. Null
     * when the tab doesn't want the section, or the source already has its row here.
     */
    private fun slot(activity: AppCompatActivity, parent: ViewGroup, tag: String): FrameLayout? {
        if (!parent.wantsInfoSection(KIND)) return null
        if (parent.findViewWithTag<View>(tag) != null) return null
        return FrameLayout(activity).apply {
            this.tag = tag
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }.also { parent.addInfoSection(KIND, it) }
    }

    /**
     * The AniList and MangaUpdates ids of a Comick title: from the links the Comick checks keep
     * (ComickUnreadNotificationTask's cache), else from its catalog details when [mayLookUp] — the
     * answer then kept in the same cache for them. Null when unknown.
     */
    private suspend fun comickLinks(hid: String, slug: String?, mayLookUp: () -> Boolean): Pair<Int?, Long?>? {
        val keys = ComickUnreadNotificationTask
        IdCache["${keys.LINKS_KEY_AL}$hid"]?.let { al ->
            val mu = IdCache["${keys.LINKS_KEY_MU}$hid"]
            return al.toIntOrNull() to mu?.let(::muSeriesId)
        }
        if (slug.isNullOrBlank() || !mayLookUp()) return null
        val links = runCatching { ComickApi.getComicDetails(slug)?.comic?.links }
            .onFailure { Logger.log("ReadersAlsoLike: Comick details for $slug failed: ${it.message}") }
            .getOrNull() ?: return null
        val anilistId = links.al?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val mu = links.mu?.trim()?.takeIf { it.isNotBlank() }
        // Blank marks "looked up, none", as the checks write it.
        IdCache.putAll(
            mapOf(
                "${keys.LINKS_KEY_AL}$hid" to (anilistId?.toString() ?: ""),
                "${keys.LINKS_KEY_MU}$hid" to (mu ?: ""),
                "${keys.LINKS_KEY_CHECKED}$hid" to System.currentTimeMillis(),
            )
        )
        return anilistId to mu?.let(::muSeriesId)
    }

    /** A MangaUpdates link as Comick and the caches give it: base 36, or the plain number. */
    private fun muSeriesId(link: String): Long? =
        link.takeIf { it.isNotBlank() }?.let { it.toLongOrNull(36) ?: it.toLongOrNull() }?.takeIf { it > 0 }

    /**
     * The cards for [refs], in their order: AniList media fetched in one batch, MangaUpdates series
     * built from what the source gave. The series on screen, and repeats, are left out.
     */
    private suspend fun resolve(refs: List<Ref>, current: Media?): List<Media> {
        val currentAnilist = current?.takeIf { it.muSeriesId == null }?.id
        val currentMu = current?.muSeriesId
        val anilistIds = refs.mapNotNull { it.anilistId }.filter { it != currentAnilist }.distinct()
        val byId = if (anilistIds.isEmpty()) emptyMap() else runCatching {
            Anilist.query.getMediaBatch(anilistIds)
        }.getOrNull().orEmpty().associateBy { it.id }
        return refs.mapNotNull { ref ->
            when {
                ref.anilistId != null -> ref.anilistId.takeIf { it != currentAnilist }?.let { byId[it] }
                ref.muId != null && ref.muId != currentMu && ref.title != null -> Media(
                    id = (ref.muId and 0x7FFFFFFF).toInt(),
                    name = ref.title,
                    nameRomaji = ref.title,
                    userPreferredName = ref.title,
                    cover = ref.cover,
                    banner = ref.cover,
                    isAdult = false,
                    manga = Manga(),
                    format = "MANGA",
                    muSeriesId = ref.muId,
                )
                else -> null
            }
        }.distinctBy { it.muSeriesId ?: it.id.toLong() }
    }

    /** The row itself, once its cards are known; nothing when there are none. */
    private suspend fun show(
        activity: AppCompatActivity,
        isAlive: () -> Boolean,
        parent: ViewGroup,
        slot: FrameLayout,
        media: List<Media>,
        current: Media?,
    ) = withContext(Dispatchers.Main) {
        if (media.isEmpty() || !isAlive() || slot.childCount > 0) return@withContext
        val title = activity.getString(R.string.info_section_readers_also_like)
        ItemTitleRecyclerBinding.inflate(activity.layoutInflater, slot, false).apply {
            itemTitle.text = title
            itemRecycler.adapter = MediaAdaptor(0, ArrayList(media), activity, currentMedia = current)
            itemRecycler.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            itemMore.visibility = View.VISIBLE
            itemMore.setSafeOnClickListener {
                MediaListViewActivity.passedMedia = ArrayList(media)
                current?.let { MediaListViewActivity.passedRecommendationSource = it }
                activity.startActivity(
                    Intent(activity, MediaListViewActivity::class.java).putExtra("title", title)
                )
            }
            slot.addView(root)
        }
        parent.offerInfoMedia(KIND, media)
    }
}
