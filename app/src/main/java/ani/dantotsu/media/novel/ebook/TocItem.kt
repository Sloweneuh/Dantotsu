package ani.dantotsu.media.novel.ebook

import kotlinx.serialization.Serializable

@Serializable
data class TocItem(
    val id: String = "",
    val href: String,
    val label: String? = null,
    val subitems: List<TocItem>? = null,
    val parent: String? = null,
)
