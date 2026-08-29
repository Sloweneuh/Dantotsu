package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.connections.simkl.SimklApi
import ani.dantotsu.openLinkInBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Simkl info tab (anime only). Resolves the AniList entry to a Simkl id, renders it through the
 * shared [SimklMediaRenderer], and previews the newest episodes with a "more" arrow to the
 * standalone [SimklMediaActivity] — like the Comick anime info tab.
 */
class SimklInfoFragment : TrackerInfoFragment<SimklInfoFragment.Data>() {

    data class Data(
        val simklId: Long,
        val full: SimklApi.SimklAnimeFull,
        val episodes: List<SimklApi.SimklEpisode>,
        val anilistRecs: List<Media>,
    )

    override val requiresInternetMessageRes = R.string.simkl_requires_internet

    override fun noData(media: Media) = NoDataConfig(
        logoRes = R.drawable.ic_simkl,
        titleRes = R.string.simkl_no_data_title,
        descRes = R.string.simkl_no_data_desc,
        buttonTextRes = R.string.open_on_simkl,
    ) {
        openLinkInBrowser("https://simkl.com/search/?type=anime&q=${Uri.encode(media.userPreferredName)}")
    }

    override suspend fun resolveAndFetch(media: Media, model: MediaDetailsViewModel): Data? {
        val simklId: Long
        if (model.simklLoaded.value == true) {
            simklId = model.simklId.value ?: return null
        } else {
            val resolved = withContext(Dispatchers.IO) { SimklApi.resolve(media.id, media.idMAL)?.simklId }
            model.simklId.postValue(resolved)
            model.simklLoaded.postValue(true)
            simklId = resolved ?: return null
        }

        val full = model.simklFull.value
            ?: withContext(Dispatchers.IO) { SimklApi.getAnime(simklId) }?.also { model.simklFull.postValue(it) }
            ?: return null

        val episodes = withContext(Dispatchers.IO) { SimklApi.getEpisodes(simklId) }

        // Turn Simkl's recommendation list into in-app AniList media: resolve each rec's simkl id to
        // an AniList id, then batch-fetch. Any that don't map to AniList are dropped.
        val recSimklIds = full.recommendations.orEmpty().mapNotNull { it.simklId }
        val anilistRecs = if (recSimklIds.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            val idMap = SimklApi.resolveAniListIds(recSimklIds)
            val ordered = recSimklIds.mapNotNull { idMap[it] }.distinct().filter { it != media.id }
            if (ordered.isEmpty()) emptyList()
            else runCatching { Anilist.query.getMediaBatch(ordered) }.getOrDefault(emptyList())
        }

        return Data(simklId, full, episodes, anilistRecs)
    }

    override fun render(full: Data, media: Media, model: MediaDetailsViewModel) {
        val activity = requireActivity() as AppCompatActivity
        SimklMediaRenderer.render(
            activity = activity,
            info = binding,
            contentHost = binding.mediaInfoContainer,
            full = full.full,
            // The AniList tab one swipe away already carries the full tag list.
            anilistTags = null,
            onGenreClick = { genre ->
                startActivity(
                    Intent(requireContext(), SearchActivity::class.java)
                        .putExtra("type", SearchType.SIMKL.toAnilistString())
                        .putExtra("query", genre)
                        .putExtra("search", true)
                )
            },
            onSimklMediaClick = { id ->
                startActivity(
                    Intent(requireContext(), SimklMediaActivity::class.java)
                        .putExtra(SimklMediaActivity.EXTRA_SIMKL_ID, id)
                )
            },
            anilistRecs = full.anilistRecs,
            recSource = media,
        )

        if (full.episodes.isNotEmpty()) {
            val rows = SimklMediaRenderer.toEpisodeRows(full.episodes).asReversed()
            TrackerEpisodeRenderer.renderPreview(
                activity, binding.mediaInfoContainer, rows, take = 5, titleRes = R.string.eps,
                coverUrl = SimklApi.posterUrl(full.full.poster, "_ca"),
            ) {
                startActivity(
                    Intent(requireContext(), SimklMediaActivity::class.java)
                        .putExtra(SimklMediaActivity.EXTRA_SIMKL_ID, full.simklId)
                        .putExtra(SimklMediaActivity.EXTRA_OPEN_EPISODES, true)
                )
            }
        }
    }
}
