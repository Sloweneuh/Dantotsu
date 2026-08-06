package eu.kanade.tachiyomi.source.model

/**
 * The result of one combined refresh of a manga: its details and its chapters together.
 *
 * Sources on extensions-lib 1.6 return this from `getMangaUpdate` instead of answering two separate
 * calls, because for an API-backed site both usually come out of the same response — asking twice
 * meant fetching twice.
 *
 * @since tachiyomix 1.6
 */
class SMangaUpdate(val manga: SManga, val chapters: List<SChapter>)
