package ani.dantotsu.media.novel.ebook

import kotlinx.serialization.Serializable

/** One chapter's matches from EbookReaderView.search, in reading order. */
@Serializable
data class SearchResults(
    /** The chapter the matches are in, from the book's table of contents; blank if it has none. */
    val label: String = "",
    val items: List<SearchHit> = emptyList(),
)

/** A match: where it is, and the text around it. */
@Serializable
data class SearchHit(
    val cfi: String,
    val pre: String = "",
    val match: String = "",
    val post: String = "",
)
