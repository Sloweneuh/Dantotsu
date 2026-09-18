package ani.dantotsu.media

import android.content.Context
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickComment
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ItemComickCommentBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.databinding.ItemTitleTextBinding
import ani.dantotsu.loadImage
import ani.dantotsu.px
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Comick comments as views: one row, and the preview section the entry pages show.
 *
 * Shared so the section on a page and the full list in [ComickCommentsBottomSheet] render a
 * comment the same way.
 */
object ComickCommentViews {

    /** How many comments an entry page shows before sending the reader to the full list. */
    const val PREVIEW_COUNT = 3

    /**
     * Fills a row. [depth] indents a reply under what it answers; [onClick] replaces the default
     * (opening the comment's full text), which a preview row uses to open the whole list instead.
     */
    fun bind(
        bind: ItemComickCommentBinding,
        comment: ComickComment,
        depth: Int = 0,
        onClick: (() -> Unit)? = null,
    ) {
        val context = bind.root.context
        bind.root.setPadding(
            16f.px + depth * 28f.px, bind.root.paddingTop, 16f.px, bind.root.paddingBottom
        )
        bind.commentAuthor.text =
            comment.author() ?: context.getString(R.string.comick_comment_anonymous)
        bindBody(bind, comment)
        bind.commentAvatar.loadImage(comment.avatarUrl())

        val replyingTo = comment.replyingTo()
        bind.commentReplyingTo.isVisible = replyingTo != null
        replyingTo?.let {
            bind.commentReplyingTo.text =
                context.getString(R.string.comick_comment_replying_to, it)
        }

        val millis = comment.createdAtMillis()
        bind.commentTime.isVisible = millis != null
        millis?.let {
            bind.commentTime.text = DateUtils.getRelativeTimeSpanString(
                it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
        }

        bind.commentScore.text = comment.score().toString()

        val replies = comment.other_comments.orEmpty().size
        bind.commentReplyCount.isVisible = replies > 0
        if (replies > 0) bind.commentReplyCount.text =
            context.resources.getQuantityString(R.plurals.comick_comment_replies, replies, replies)

        val image = comment.attachmentUrl()
        bind.commentImage.isVisible = image != null
        if (image != null) bind.commentImage.loadImage(image)

        bind.root.setOnLongClickListener {
            copyToClipboard(bind.commentBody.text.toString())
            true
        }
        bind.root.setSafeOnClickListener {
            // A comment is never truncated in the list, so the default tap is only useful for
            // reading a long one away from the wall of replies around it. What it shows is what
            // the row shows, so a spoiler still has to be asked for.
            onClick?.invoke() ?: context.customAlertDialog().apply {
                setTitle(comment.author() ?: context.getString(R.string.comick_comment_anonymous))
                setMessage(bind.commentBody.text)
                setPosButton(R.string.close)
                show()
            }
        }
    }

    /** `<details>…</details>`, which is how a Comick comment marks a spoiler. */
    private val SPOILER = Regex("(?is)<details.*?</details>")
    private val SUMMARY = Regex("(?is)<summary.*?</summary>")

    /**
     * Comment bodies are HTML - paragraphs, line breaks, the odd `<strong>`, and spoilers wrapped
     * in `<details>`. Rendering the markup keeps tags out of the text, and the spoiler stays
     * behind a tap the way a spoiler tag does.
     */
    private fun bindBody(bind: ItemComickCommentBinding, comment: ComickComment) {
        val raw = comment.body()
        val spoiler = SPOILER.find(raw)?.value
        val shown = if (spoiler == null) raw else raw.replace(spoiler, "").trim()

        bind.commentBody.text = fromHtml(shown)
        bind.commentBody.isVisible = bind.commentBody.text.isNotBlank()
        bind.commentSpoiler.isVisible = spoiler != null

        if (spoiler != null) bind.commentSpoiler.setOnClickListener {
            val revealed = fromHtml(SUMMARY.replace(spoiler, "").trim())
            bind.commentBody.text = if (bind.commentBody.text.isBlank()) revealed
            else TextUtils.concat(bind.commentBody.text, System.lineSeparator(), revealed)
            bind.commentBody.isVisible = true
            bind.commentSpoiler.isVisible = false
        }
    }

    private fun fromHtml(html: String): CharSequence =
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).trim()

    /**
     * Adds the entry's comments section to [parent]: the top few, and an arrow to the rest.
     *
     * The fetch is its own request, so the section goes in as an empty placeholder and fills when
     * it lands - the page shouldn't wait on comments to render. [isAlive] is checked after the
     * request the way the other sections on these pages do it, since the screen may be gone by
     * then.
     */
    fun addSection(
        context: Context,
        scope: CoroutineScope,
        fragmentManager: FragmentManager,
        parent: ViewGroup,
        hid: String,
        heading: String,
        isAlive: () -> Boolean = { true },
        sectionTag: String = "comments_comick",
    ) {
        if (hid.isBlank() || parent.findViewWithTag<View>(sectionTag) != null) return

        val placeholder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = sectionTag
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(placeholder)

        scope.launch {
            val page = withContext(Dispatchers.IO) { ComickApi.getComicComments(hid) }
            if (!isAlive() || placeholder.childCount > 0) return@launch
            val comments = page?.comments.orEmpty()
            val inflater = LayoutInflater.from(context)

            val header = ItemTitleRecyclerBinding.inflate(inflater, placeholder, false)
            header.itemTitle.text = context.getString(R.string.comick_comments)
            header.itemRecycler.visibility = View.GONE
            header.itemMore.visibility = if (comments.isEmpty()) View.GONE else View.VISIBLE
            header.itemMore.setSafeOnClickListener { openAll(fragmentManager, hid, heading) }
            placeholder.addView(header.root)

            if (comments.isEmpty()) {
                val empty = ItemTitleTextBinding.inflate(inflater, placeholder, false)
                empty.itemTitle.visibility = View.GONE
                empty.itemText.text = context.getString(R.string.comick_no_comments)
                placeholder.addView(empty.root)
                return@launch
            }

            comments.take(PREVIEW_COUNT).forEach { comment ->
                val row = ItemComickCommentBinding.inflate(inflater, placeholder, false)
                // Replies are left to the full list: a preview is for what the entry's readers are
                // saying, not for following a thread.
                bind(row, comment) { openAll(fragmentManager, hid, heading) }
                placeholder.addView(row.root)
            }

            if (comments.size > PREVIEW_COUNT || (page?.total ?: 0) > PREVIEW_COUNT) {
                val more = ItemTitleTextBinding.inflate(inflater, placeholder, false)
                more.itemTitle.visibility = View.GONE
                more.itemText.text = context.resources.getQuantityString(
                    R.plurals.comick_comment_count,
                    page?.total ?: comments.size,
                    page?.total ?: comments.size
                )
                more.root.setSafeOnClickListener { openAll(fragmentManager, hid, heading) }
                placeholder.addView(more.root)
            }
        }
    }

    private fun openAll(fragmentManager: FragmentManager, hid: String, heading: String) {
        ComickCommentsBottomSheet.forComic(hid, heading)
            .show(fragmentManager, "comick_comments")
    }
}
