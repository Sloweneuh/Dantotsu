package ani.dantotsu.media.manga

sealed class MangaChapterListItem {
    data class Chapter(val chapter: MangaChapter) : MangaChapterListItem()
    data class Gap(val fromNumber: Float, val toNumber: Float, val count: Int) : MangaChapterListItem()
}
