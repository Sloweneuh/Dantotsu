package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.connections.kitsu.KitsuApi
import ani.dantotsu.openLinkInBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kitsu info tab. Resolves the AniList entry to a Kitsu media id and renders it through the shared
 * [KitsuMediaRenderer] (same code the standalone [KitsuMediaActivity] uses). For anime it also
 * previews the newest episodes, deferring the full list to the standalone page — like the Comick
 * anime info tab.
 */
class KitsuInfoFragment : TrackerInfoFragment<KitsuInfoFragment.Data>() {

    data class Data(val full: KitsuApi.KitsuMediaFull, val episodes: List<KitsuApi.KitsuEpisode>)

    private val isAnime get() = _media?.anime != null
    private var _media: Media? = null

    override val requiresInternetMessageRes = R.string.kitsu_requires_internet

    override fun noData(media: Media) = NoDataConfig(
        logoRes = R.drawable.ic_kitsu,
        titleRes = R.string.kitsu_no_data_title,
        descRes = R.string.kitsu_no_data_desc,
        buttonTextRes = R.string.open_on_kitsu,
    ) {
        openLinkInBrowser("${KitsuApi.WEB_URL}/search?query=${Uri.encode(media.userPreferredName)}")
    }

    override suspend fun resolveAndFetch(media: Media, model: MediaDetailsViewModel): Data? {
        _media = media
        val anime = media.anime != null

        model.kitsuFull.value?.let { cached ->
            val eps = if (anime) withContext(Dispatchers.IO) { KitsuApi.getEpisodes(cached.id) } else emptyList()
            return Data(cached, eps)
        }

        val id: String
        if (model.kitsuLoaded.value == true) {
            id = model.kitsuId.value ?: return null
        } else {
            val resolved = withContext(Dispatchers.IO) {
                // MangaUpdates media carries no real AniList/MAL id — Kitsu's /mappings has a
                // `mangaupdates` external site, so resolve straight from the MU series id there.
                media.muSeriesId?.let { KitsuApi.resolveMangaFromMangaUpdates(it) }
                    ?: KitsuApi.resolveMediaId(anime, media.id, media.idMAL)
            }
            model.kitsuId.postValue(resolved)
            model.kitsuLoaded.postValue(true)
            id = resolved ?: return null
        }

        val full = withContext(Dispatchers.IO) { KitsuApi.getMediaFull(anime, id) } ?: return null
        model.kitsuFull.postValue(full)

        val episodes = if (anime) withContext(Dispatchers.IO) { KitsuApi.getEpisodes(full.id) } else emptyList()
        return Data(full, episodes)
    }

    override fun render(full: Data, media: Media, model: MediaDetailsViewModel) {
        val activity = requireActivity() as AppCompatActivity
        KitsuMediaRenderer.render(
            activity = activity,
            info = binding,
            contentHost = binding.mediaInfoContainer,
            full = full.full,
            isAnime = isAnime,
            onCategoryClick = { slug, name ->
                KitsuApi.seedCategoryName(slug, name)
                startActivity(
                    Intent(requireContext(), SearchActivity::class.java)
                        .putExtra("type", (if (isAnime) SearchType.KITSU_ANIME else SearchType.KITSU).toAnilistString())
                        .putExtra("category", slug)
                        .putExtra("categoryName", name)
                        .putExtra("search", true)
                )
            },
            onRelationClick = { rel ->
                startActivity(
                    Intent(requireContext(), KitsuMediaActivity::class.java)
                        .putExtra(KitsuMediaActivity.EXTRA_MEDIA_ID, rel.id)
                        .putExtra(KitsuMediaActivity.EXTRA_IS_ANIME, rel.isAnime)
                )
            },
        )

        if (isAnime && full.episodes.isNotEmpty()) {
            val rows = KitsuMediaRenderer.toEpisodeRows(full.episodes).asReversed()
            val poster = full.full.media.posterImage
            val coverUrl = poster?.original ?: poster?.medium ?: poster?.small
            TrackerEpisodeRenderer.renderPreview(
                activity, binding.mediaInfoContainer, rows, take = 5, titleRes = R.string.eps,
                coverUrl = coverUrl,
            ) {
                startActivity(
                    Intent(requireContext(), KitsuMediaActivity::class.java)
                        .putExtra(KitsuMediaActivity.EXTRA_MEDIA_ID, full.full.id)
                        .putExtra(KitsuMediaActivity.EXTRA_IS_ANIME, true)
                        .putExtra(KitsuMediaActivity.EXTRA_OPEN_EPISODES, true)
                )
            }
        }
    }
}
