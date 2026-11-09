package ani.dantotsu.media

import android.view.View
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding

private const val INDENT = "\t\t\t"

/**
 * Fills the Titles section the same way for every source. "Name" is the source's main title,
 * English where it has one; "Name Romaji" is the romanization. Either may be null.
 *
 * A line that only repeats the other (letter case aside) is left out, so the romaji line never
 * restates a romaji-only name. On a standalone page, whose header already shows [headerTitle], a
 * line repeating that is left out too; info tabs pass null, as their header is AniList's title.
 */
fun FragmentMediaInfoBinding.setInfoNames(name: String?, romaji: String?, headerTitle: String? = null) {
    val n = name?.trim()?.takeIf { it.isNotBlank() }
    val r = romaji?.trim()?.takeIf { it.isNotBlank() && !it.equals(n, ignoreCase = true) }
    val header = headerTitle?.trim()
    val shownName = n?.takeIf { !it.equals(header, ignoreCase = true) }
    val shownRomaji = r?.takeIf { !it.equals(header, ignoreCase = true) }

    mediaInfoNameContainer.visibility = if (shownName != null) View.VISIBLE else View.GONE
    shownName?.let { title ->
        mediaInfoName.text = INDENT + title
        mediaInfoName.setOnLongClickListener { copyToClipboard(title); true }
    }
    mediaInfoNameRomajiContainer.visibility = if (shownRomaji != null) View.VISIBLE else View.GONE
    shownRomaji?.let { title ->
        mediaInfoNameRomaji.text = INDENT + title
        mediaInfoNameRomaji.setOnLongClickListener { copyToClipboard(title); true }
    }
}
