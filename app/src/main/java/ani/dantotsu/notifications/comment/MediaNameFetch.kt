package ani.dantotsu.notifications.comment

import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.Query

class MediaNameFetch {
    companion object {
        /** AniList's largest page. */
        private const val PER_PAGE = 50

        /**
         * One `Page(id_in)` per [PER_PAGE] ids. AniList counts every aliased root field against the
         * rate limit, so an alias per media cost as many units as there were titles; a page costs one.
         */
        private fun query(ids: List<Int>) =
            "{Page(perPage:${ids.size}){media(id_in:[${ids.joinToString(",")}]){id title{romaji}coverImage{medium color}}}}"

        suspend fun fetchMediaTitles(ids: List<Int>): Map<Int, ReturnedData> {
            val result = mutableMapOf<Int, ReturnedData>()
            for (chunk in ids.distinct().chunked(PER_PAGE)) {
                val media = Anilist.executeQuery<Query.Page>(query(chunk), force = true, useToken = false)
                    ?.data?.page?.media.orEmpty()
                media.forEach {
                    result[it.id] = ReturnedData(
                        it.title?.romaji ?: "Unknown",
                        it.coverImage?.medium ?: "",
                        it.coverImage?.color ?: "#222222",
                    )
                }
            }
            ids.forEach { result.getOrPut(it) { ReturnedData("Unknown", "", "#222222") } }
            return result
        }

        data class ReturnedData(val title: String, val coverImage: String, val color: String)
    }
}
