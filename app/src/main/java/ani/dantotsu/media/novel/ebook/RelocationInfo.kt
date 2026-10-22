package ani.dantotsu.media.novel.ebook

import kotlinx.serialization.Serializable

@Serializable
data class RelocationInfo(
    val cfi: String,
    val fraction: Double,
    val currentLocation: Int,
    val nextLocation: Int,
    val totalLocations: Int,
    val tocItem: TocItem? = null,
    /** The start of the text on the page, for naming a bookmark. */
    val excerpt: String = "",
    /** Whether a bookmark passed to EbookReaderView.setBookmarks falls on this page. */
    val bookmarked: Boolean = false,
)
