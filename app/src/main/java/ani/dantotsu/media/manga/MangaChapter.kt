package ani.dantotsu.media.manga

import ani.dantotsu.download.findValidName
import ani.dantotsu.parsers.MangaChapter
import ani.dantotsu.parsers.MangaImage
import eu.kanade.tachiyomi.source.model.SChapter
import java.io.Serializable
import kotlin.math.floor

data class MangaChapter(
    val number: String,
    var link: String,
    var title: String? = null,
    var description: String? = null,
    var sChapter: SChapter,
    val scanlator: String? = null,
    val date: Long? = null,
    var progress: String? = ""
) : Serializable {
    constructor(chapter: MangaChapter) : this(
        chapter.number,
        chapter.link,
        chapter.title,
        chapter.description,
        chapter.sChapter,
        chapter.scanlator,
        chapter.date
    )

    private val images = mutableListOf<MangaImage>()
    fun images(): List<MangaImage> = images
    fun addImages(image: List<MangaImage>) {
        if (images.isNotEmpty()) return
        image.forEach { images.add(it) }
        (0..floor((images.size.toFloat() - 1f) / 2).toInt()).forEach {
            val i = it * 2
            dualPages.add(images[i] to images.getOrNull(i + 1))
        }
    }

    private val dualPages = mutableListOf<Pair<MangaImage, MangaImage?>>()
    fun dualPages(): List<Pair<MangaImage, MangaImage?>> = dualPages

    // Sanitized the same way DownloadedType.chapterName is (both feed the same on-disk folder
    // name), so this identity key matches what's persisted even when `number` contains characters
    // like ':' or '/' that aren't valid in a folder name.
    fun uniqueNumber(): String = "${number.findValidName()}-${scanlator ?: "Unknown"}"

    /**
     * Some sources mark subscriber/purchase-only chapters by putting a lock emoji in the title or
     * number rather than a structured flag. There's still no page content behind these, so they
     * can't be read or downloaded — only opened in the browser to purchase/unlock.
     */
    fun isPremium(): Boolean = title?.contains("🔒") == true || number.contains("🔒")

}
