package ani.dantotsu.media.anime

import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.SourceAdapter
import ani.dantotsu.media.SourceSearchDialogFragment
import ani.dantotsu.parsers.BaseParser
import ani.dantotsu.parsers.ShowResponse
import kotlinx.coroutines.CoroutineScope

class AnimeSourceAdapter(
    sources: List<ShowResponse>,
    val model: MediaDetailsViewModel,
    val i: Int,
    val id: Int,
    fragment: SourceSearchDialogFragment,
    scope: CoroutineScope,
    hostUrl: String = "",
    parser: BaseParser? = null
) : SourceAdapter(sources, fragment, scope, hostUrl, parser) {

    override suspend fun onItemClick(source: ShowResponse) {
        model.overrideEpisodes(i, source, id)
    }
}