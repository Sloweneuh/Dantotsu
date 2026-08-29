package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.openLinkInBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MangaBaka info tab. Shown for both AniList media (in [MediaInfoFragment]) and MangaUpdates media
 * (in [ani.dantotsu.connections.mangaupdates.MUMediaInfoContainerFragment]). Resolves the current
 * entry to a MangaBaka series, then renders it through the shared [MangaBakaMediaRenderer] (the
 * same code the standalone [MangaBakaMediaActivity] uses).
 */
class MangaBakaInfoFragment : TrackerInfoFragment<MangaBakaApi.Series>() {

    override val requiresInternetMessageRes = R.string.mangabaka_requires_internet

    override fun noData(media: Media) = NoDataConfig(
        logoRes = R.drawable.ic_round_mangabaka_24,
        titleRes = R.string.mangabaka_no_data_title,
        descRes = R.string.mangabaka_no_data_desc,
        buttonTextRes = R.string.open_on_mangabaka,
    ) {
        openLinkInBrowser("https://mangabaka.org/search?q=${Uri.encode(media.userPreferredName)}")
    }

    override suspend fun resolveAndFetch(media: Media, model: MediaDetailsViewModel): MangaBakaApi.Series? {
        // Reuse the series the ViewModel already fetched via the source route (which embeds the full
        // object). Only fetch here when it wasn't preloaded and isn't a confirmed no-match.
        model.mangaBakaSeries.value?.let { return it }
        if (model.mangaBakaLoaded.value == true) return null

        val series = withContext(Dispatchers.IO) {
            MangaBakaApi.getSeriesForMedia(media.muSeriesId, media.id, media.idMAL)
        }
        model.mangaBakaSeries.postValue(series)
        model.mangaBakaId.postValue(series?.id)
        model.mangaBakaLoaded.postValue(true)
        return series
    }

    override fun render(full: MangaBakaApi.Series, media: Media, model: MediaDetailsViewModel) {
        MangaBakaMediaRenderer.render(
            activity = requireActivity() as AppCompatActivity,
            scope = viewLifecycleOwner.lifecycleScope,
            isAlive = { _binding != null },
            info = binding,
            contentHost = binding.mediaInfoContainer,
            series = full,
            seriesId = full.id,
            nameInStats = true,
            markwonFragment = this,
            onSearch = { genreSlug, genreName, tag -> startMangaBakaSearchInApp(genreSlug, genreName, tag) },
            reco = MangaBakaMediaRenderer.RecoConfig(media, model),
        )
    }

    private fun startMangaBakaSearchInApp(genreSlug: String?, genreName: String?, tag: String?) {
        if (!isAdded) return
        val intent = Intent(requireContext(), SearchActivity::class.java)
            .putExtra("type", SearchType.MANGABAKA.toAnilistString())
        if (!genreSlug.isNullOrBlank()) {
            intent.putExtra("genre", genreSlug)
            if (!genreName.isNullOrBlank()) intent.putExtra("genreName", genreName)
        }
        if (!tag.isNullOrBlank()) intent.putExtra("tag", tag)
        if (!genreSlug.isNullOrBlank() || !tag.isNullOrBlank()) intent.putExtra("search", true)
        startActivity(intent)
    }
}
