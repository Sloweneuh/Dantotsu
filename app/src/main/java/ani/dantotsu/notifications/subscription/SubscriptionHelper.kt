package ani.dantotsu.notifications.subscription

import ani.dantotsu.R
import ani.dantotsu.currContext
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.Selected
import ani.dantotsu.parsers.AnimeParser
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.BaseParser
import ani.dantotsu.parsers.Episode
import ani.dantotsu.parsers.MangaChapter
import ani.dantotsu.parsers.MangaParser
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.withTimeoutOrNull

class SubscriptionHelper {
    companion object {
        private fun loadSelected(
            mediaId: Int
        ): Selected {
            val data =
                PrefManager.getNullableCustomVal("Selected-${mediaId}", null, Selected::class.java)
                    ?: Selected().let {
                        it.sourceIndex = 0
                        it.preferDub = PrefManager.getVal(PrefName.SettingsPreferDub)
                        it
                    }
            return data
        }

        private fun saveSelected(mediaId: Int, data: Selected) {
            PrefManager.setCustomVal("Selected-${mediaId}", data)
        }

        // A subscription is pinned to the source that was selected when it was created
        // (sourceName), matching what the "subscribed" snackbar tells the user. Resolve
        // that name to an index in the current list so installing/removing/reordering
        // extensions can't point us at the wrong source.
        //
        // Returns null when the source can't be trusted right now and the check should be
        // skipped rather than guessed:
        //  - the source type hasn't finished loading yet (the worker's init race can leave
        //    one of Anime/Manga uninitialised), or
        //  - the pinned source is no longer installed.
        // Deliberately never falls back to a different source: it almost certainly doesn't
        // have this title and would fail or notify about the wrong series. The subscription
        // is kept either way, so it resumes if the extension returns and can still be
        // removed manually from settings.
        private fun resolveSourceIndex(
            media: SubscribeMedia,
            names: List<String>,
            initialized: Boolean
        ): Int? {
            if (!initialized || names.isEmpty()) return null
            val snapshotName = media.sourceName
                ?: PrefManager.getNullableCustomVal(
                    "SelectedSource-${media.id}", null, String::class.java
                )
            // Legacy subscriptions with no recorded source keep the old default-to-first
            // behaviour (there's nothing better to go on).
            if (snapshotName == null) return 0
            return names.indexOf(snapshotName).takeIf { it >= 0 }
        }

        fun getAnimeParser(media: SubscribeMedia): AnimeParser? {
            val sources = AnimeSources
            Logger.log("getAnimeParser size: ${sources.list.size}")
            val index = resolveSourceIndex(media, sources.names, sources.isInitialized)
                ?: return null
            val parser = sources[index]
            parser.selectDub = loadSelected(media.id).preferDub
            return parser
        }

        suspend fun getEpisode(
            parser: AnimeParser,
            subscribeMedia: SubscribeMedia
        ): Episode? {

            val selected = loadSelected(subscribeMedia.id)
            val ep = withTimeoutOrNull(10 * 1000) {
                tryWithSuspend {
                    val show = parser.loadSavedShowResponse(subscribeMedia.id)
                        ?: forceLoadShowResponse(subscribeMedia, selected, parser)
                        ?: throw Exception(
                            currContext()?.getString(
                                R.string.failed_to_load_data,
                                subscribeMedia.id
                            )
                        )
                    show.sAnime?.let {
                        parser.getLatestEpisode(
                            show.link, show.extra,
                            it, selected.latest
                        )
                    }
                }
            }

            return ep?.apply {
                selected.latest = number.toFloat()
                saveSelected(subscribeMedia.id, selected)
            }
        }

        fun getMangaParser(media: SubscribeMedia): MangaParser? {
            val sources = MangaSources
            Logger.log("getMangaParser size: ${sources.list.size}")
            val index = resolveSourceIndex(media, sources.names, sources.isInitialized)
                ?: return null
            return sources[index]
        }

        // Display name of the source a subscription is pinned to, for grouping in the
        // settings list. Uses the stored snapshot so a subscription still shows under its
        // real source even when that source isn't installed (or hasn't loaded yet).
        fun getSubscriptionSourceName(media: SubscribeMedia): String {
            media.sourceName?.let { return it }
            val names: List<String>
            val index: Int?
            if (media.isAnime) {
                names = AnimeSources.names
                index = resolveSourceIndex(media, names, AnimeSources.isInitialized)
            } else {
                names = MangaSources.names
                index = resolveSourceIndex(media, names, MangaSources.isInitialized)
            }
            return names.getOrNull(index ?: 0) ?: ""
        }

        suspend fun getChapter(
            parser: MangaParser,
            subscribeMedia: SubscribeMedia
        ): MangaChapter? {
            val selected = loadSelected(subscribeMedia.id)
            val chp = withTimeoutOrNull(10 * 1000) {
                tryWithSuspend {
                    val show = parser.loadSavedShowResponse(subscribeMedia.id)
                        ?: forceLoadShowResponse(subscribeMedia, selected, parser)
                        ?: throw Exception(
                            currContext()?.getString(
                                R.string.failed_to_load_data,
                                subscribeMedia.id
                            )
                        )
                    show.sManga?.let {
                        parser.getLatestChapter(
                            show.link, show.extra,
                            it, selected.latest
                        )
                    }
                }
            }

            return chp?.apply {
                selected.latest = MediaNameAdapter.findChapterNumber(number) ?: 0f
                saveSelected(subscribeMedia.id, selected)
            }
        }

        private suspend fun forceLoadShowResponse(
            subscribeMedia: SubscribeMedia,
            selected: Selected,
            parser: BaseParser
        ): ShowResponse? {
            val tempMedia = Media(
                id = subscribeMedia.id,
                name = null,
                nameRomaji = subscribeMedia.name,
                userPreferredName = subscribeMedia.name,
                isAdult = subscribeMedia.isAdult,
                isFav = false,
                isListPrivate = false,
                userScore = 0,
                userRepeat = 0,
                format = null,
                selected = selected
            )
            parser.autoSearch(tempMedia)
            return parser.loadSavedShowResponse(subscribeMedia.id)
        }

        data class SubscribeMedia(
            val isAnime: Boolean,
            val isAdult: Boolean,
            val id: Int,
            val name: String,
            val image: String?,
            val banner: String? = null,
            // Source selected when the subscription was created. Null for subscriptions
            // saved before this was tracked (they fall back to the current selection).
            val sourceName: String? = null
        ) : java.io.Serializable {
            companion object {
                private const val serialVersionUID = 1L
            }
        }

        private const val SUBSCRIPTIONS = "subscriptions"

        @Suppress("UNCHECKED_CAST")
        fun getSubscriptions(): Map<Int, SubscribeMedia> =
            (PrefManager.getNullableCustomVal(
                SUBSCRIPTIONS,
                null,
                Map::class.java
            ) as? Map<Int, SubscribeMedia>)
                ?: mapOf<Int, SubscribeMedia>().also { PrefManager.setCustomVal(SUBSCRIPTIONS, it) }

        @Suppress("UNCHECKED_CAST")
        fun deleteSubscription(id: Int, showSnack: Boolean = false) {
            val data = PrefManager.getNullableCustomVal(
                SUBSCRIPTIONS,
                null,
                Map::class.java
            ) as? MutableMap<Int, SubscribeMedia>
                ?: mutableMapOf()
            data.remove(id)
            PrefManager.setCustomVal(SUBSCRIPTIONS, data)
            if (showSnack) toast(R.string.subscription_deleted)
        }

        @Suppress("UNCHECKED_CAST")
        fun saveSubscription(media: Media, subscribed: Boolean, sourceName: String? = null) {
            val data = PrefManager.getNullableCustomVal(
                SUBSCRIPTIONS,
                null,
                Map::class.java
            ) as? MutableMap<Int, SubscribeMedia>
                ?: mutableMapOf()
            if (subscribed) {
                if (!data.containsKey(media.id)) {
                    val new = SubscribeMedia(
                        media.anime != null,
                        media.isAdult,
                        media.id,
                        media.userPreferredName,
                        media.cover,
                        media.banner,
                        sourceName
                    )
                    data[media.id] = new
                }
            } else {
                data.remove(media.id)
            }
            PrefManager.setCustomVal(SUBSCRIPTIONS, data)
        }
    }
}