package ani.dantotsu.media

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.api.MediaTag
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.chip.Chip

/**
 * The AniList tag list as a chip group: ranked, with spoilers masked until they're asked for and
 * each tag's AniList description a long press away. Shared by [AniListInfoFragment] and the
 * tracker pages that borrow AniList's tags ([SimklMediaRenderer]), so a tag that spoils is hidden
 * the same way wherever it shows up.
 */
object AnilistTagChips {

    /**
     * A tag reduced to what a chip needs. AniList's own [MediaTag] maps straight onto it via
     * [of]; callers holding nothing but the old flat "name : rank%" strings (media cached for
     * offline before tag details were stored) build one with [ofLabel].
     */
    data class Tag(
        val name: String,
        val rank: Int? = null,
        val description: String? = null,
        val category: String? = null,
        val isSpoiler: Boolean = false,
    ) {
        /** What the chip reads once it's no longer hiding anything. */
        val label: String get() = rank?.let { "$name : $it%" } ?: name
    }

    /**
     * AniList marks a tag as spoiling either this media in particular (`isMediaSpoiler`) or
     * anything it's applied to (`isGeneralSpoiler`); the site hides both, and so do we.
     */
    fun of(tag: MediaTag) = Tag(
        name = tag.name,
        rank = tag.rank,
        description = tag.description,
        category = tag.category,
        isSpoiler = tag.isMediaSpoiler == true || tag.isGeneralSpoiler == true,
    )

    /** Parses a stored "Name : 85%" entry back into a tag - no description or spoiler flag known. */
    fun ofLabel(label: String): Tag {
        val name = label.substringBeforeLast(" : ").trim()
        val rank = label.substringAfterLast(" : ", "").trim().removeSuffix("%").toIntOrNull()
        return Tag(name = name.ifBlank { label.trim() }, rank = rank)
    }

    /**
     * Adds the tag section to [parent], returning false (having added nothing) when there are no
     * tags to show. [onClick] fires for a chip whose tag is on show - a masked spoiler spends its
     * first tap revealing itself instead.
     */
    fun render(
        context: Context,
        parent: ViewGroup,
        tags: List<Tag>,
        onClick: ((Tag) -> Unit)? = null,
    ): Boolean {
        // Rank order, but spoilers last: the masked chips would otherwise sit among the ranked
        // ones as a wall of blocks, with the tags that actually say something pushed off-screen.
        val list = tags
            .filter { it.name.isNotBlank() }
            .sortedWith(compareBy({ it.isSpoiler }, { -(it.rank ?: 0) }))
        if (list.isEmpty()) return false

        val inflater = LayoutInflater.from(context)
        val bind = ItemTitleChipgroupBinding.inflate(inflater, parent, false)
        bind.itemTitle.setText(R.string.tags)

        val spoilers = mutableListOf<Pair<Tag, Chip>>()
        val revealed = mutableSetOf<Chip>()

        fun reveal(tag: Tag, chip: Chip) {
            revealed.add(chip)
            chip.text = tag.label
            chip.contentDescription = null
        }

        fun hide(tag: Tag, chip: Chip) {
            revealed.remove(chip)
            chip.text = ChipSections.mask(tag.name)
            // Blocks read as nothing at all to a screen reader, so say what the chip is instead.
            chip.contentDescription = context.getString(R.string.spoiler_tag)
        }

        list.forEach { tag ->
            val chip = ItemChipBinding.inflate(inflater, bind.itemChipGroup, false).root
            if (tag.isSpoiler) {
                hide(tag, chip)
                spoilers.add(tag to chip)
            } else {
                chip.text = tag.label
            }
            // A chip with nowhere to go (no search wired up, nothing left to reveal) is left
            // unclickable rather than given a listener that does nothing.
            if (tag.isSpoiler || onClick != null) chip.setSafeOnClickListener {
                if (tag.isSpoiler && chip !in revealed) reveal(tag, chip)
                else onClick?.invoke(tag)
            }
            chip.setOnLongClickListener {
                // Long pressing a masked chip reveals it rather than opening the details, so a
                // spoiler never gets given away by a press meant for the menu.
                if (tag.isSpoiler && chip !in revealed) reveal(tag, chip)
                else showDetails(context, tag)
                true
            }
            bind.itemChipGroup.addView(chip)
        }

        if (spoilers.isNotEmpty()) ChipSections.spoilerToggle(bind.itemTitleAction) { showing ->
            spoilers.forEach { (tag, chip) -> if (showing) reveal(tag, chip) else hide(tag, chip) }
        }

        parent.addView(bind.root)
        return true
    }

    /**
     * AniList writes a description for every tag - what "Time Skip" or "Anti-Hero" is taken to
     * mean when someone votes for it - which is the only place the ranks and categories make
     * sense. It has nowhere to live on a chip, so it gets a dialog.
     */
    private fun showDetails(context: Context, tag: Tag) {
        val meta = listOfNotNull(
            tag.rank?.let { context.getString(R.string.tag_rank, it) },
            tag.category?.trim()?.takeIf { it.isNotBlank() },
            if (tag.isSpoiler) context.getString(R.string.spoiler_tag) else null,
        )
        val description = tag.description?.trim()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.no_tag_description)
        context.customAlertDialog().apply {
            setTitle(tag.name)
            setMessage(if (meta.isEmpty()) description else meta.joinToString("  •  ") + "\n\n" + description)
            setPosButton(R.string.close)
            setNegButton(R.string.copy) { copyToClipboard(tag.name) }
            show()
        }
    }
}
