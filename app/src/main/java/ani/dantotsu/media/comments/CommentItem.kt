package ani.dantotsu.media.comments

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.widget.TextView
import ani.dantotsu.others.SpoilerPlugin
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.settings.SectionCard
import com.google.android.material.color.MaterialColors
import ani.dantotsu.connections.comments.Comment
import ani.dantotsu.connections.comments.CommentsAPI
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ItemCommentsBinding
import ani.dantotsu.getAppString
import ani.dantotsu.getThemeColor
import ani.dantotsu.loadImage
import ani.dantotsu.openImage
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.setAnimation
import ani.dantotsu.snackString
import ani.dantotsu.util.ColorEditor.Companion.adjustColorForContrast
import ani.dantotsu.util.ColorEditor.Companion.getContrastRatio
import ani.dantotsu.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.Section
import com.xwray.groupie.viewbinding.BindableItem
import io.noties.markwon.Markwon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.sqrt

class CommentItem(
    val comment: Comment,
    private val markwon: Markwon,
    val parentSection: Section,
    private val commentsFragment: CommentsFragment,
    private val backgroundColor: Int,
    val commentDepth: Int
) :
    BindableItem<ItemCommentsBinding>() {
    lateinit var binding: ItemCommentsBinding
    val adapter = GroupieAdapter()
    private var subCommentIds: MutableList<Int> = mutableListOf()
    val repliesSection = Section()
    private var isEditing = false
    var isReplying = false
    private var repliesVisible = false
    private var expanded = false
    var MAX_DEPTH = 3

    init {
        adapter.add(repliesSection)
    }

    override fun bind(viewBinding: ItemCommentsBinding, position: Int) {
        binding = viewBinding
        setAnimation(binding.root.context, binding.root)
        val item = this
        viewBinding.apply {
            commentRepliesList.layoutManager =
                LinearLayoutManager(commentsFragment.activity)
            commentRepliesList.adapter = adapter
            // Only a top-level comment is a card; its replies sit inside it, set off by the thread
            // line rather than a card in a card.
            val topLevel = commentDepth == 0
            // The list editor's card fill (SectionCard), so comments read as the same kind of
            // surface; the action pills take the same blend a step stronger to stand out on it.
            val dpx = root.resources.displayMetrics.density
            root.background = if (topLevel) GradientDrawable().apply {
                cornerRadius = 18 * dpx
                setColor(SectionCard.fill(root, open = false))
            } else null
            val pill = ColorStateList.valueOf(
                MaterialColors.layer(
                    MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface),
                    MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface),
                    if (topLevel) 0.10f else 0.07f,
                )
            )
            listOf(commentVotes, commentReply, commentEdit).forEach { it.backgroundTintList = pill }
            (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val density = root.resources.displayMetrics.density
                lp.marginStart = if (topLevel) (8 * density).toInt() else 0
                lp.marginEnd = if (topLevel) (8 * density).toInt() else 0
                lp.topMargin = if (topLevel) (4 * density).toInt() else (2 * density).toInt()
                lp.bottomMargin = if (topLevel) (4 * density).toInt() else 0
                root.layoutParams = lp
            }
            // Explicit values: views are recycled between the two kinds.
            val dp = root.resources.displayMetrics.density
            root.setPaddingRelative(
                if (topLevel) (12 * dp).toInt() else 0, (12 * dp).toInt(),
                if (topLevel) (10 * dp).toInt() else 0, (8 * dp).toInt(),
            )
            val isUserComment = CommentsAPI.userId == comment.userId
            val levelColor = getAvatarColor(comment.totalVotes, backgroundColor)
            markwon.setMarkdown(commentText, comment.content)
            if (commentsFragment.timestampsLinkable()) linkTimestamps(commentText)
            commentEdit.visibility = if (isUserComment) View.VISIBLE else View.GONE
            if (comment.tag == null) {
                commentUserTagLayout.visibility = View.GONE
            } else {
                commentUserTagLayout.visibility = View.VISIBLE
                commentUserTag.text = comment.tag.toString()
                commentUserTagLayout.setOnClickListener { commentsFragment.onTagClicked(comment.tag) }
            }
            replying(isReplying) //sets default text
            editing(isEditing)
            if ((comment.replyCount ?: 0) > 0) {
                commentRepliesToggle.visibility = View.VISIBLE
                commentTotalReplies.context.run {
                    commentTotalReplies.text = if (repliesVisible)
                        getString(R.string.hide_replies)
                    else
                        if (comment.replyCount == 1)
                            getString(R.string.view_reply)
                        else
                            getString(R.string.view_replies_count, comment.replyCount)
                }
            } else {
                commentRepliesToggle.visibility = View.GONE
            }
            updateThreadLine()
            commentReply.visibility = View.VISIBLE
            commentTotalReplies.setOnClickListener {
                if (repliesVisible) {
                    repliesSection.clear()
                    removeSubCommentIds()
                    commentTotalReplies.context.run {
                        commentTotalReplies.text = if (comment.replyCount == 1)
                            getString(R.string.view_reply)
                        else
                            getString(R.string.view_replies_count, comment.replyCount)
                    }
                    repliesVisible = false
                } else {
                    commentTotalReplies.setText(R.string.hide_replies)
                    repliesSection.clear()
                    commentsFragment.viewReplyCallback(item)
                    repliesVisible = true
                }
                updateThreadLine()
            }
            commentRepliesToggle.setOnClickListener { commentTotalReplies.performClick() }

            commentUserName.setOnClickListener { openProfile() }
            commentUserAvatar.setOnClickListener { openProfile() }
            commentText.setOnLongClickListener {
                copyToClipboard(comment.content)
                true
            }
            // Long comments open up in full on a tap, rather than staying cut at eight lines.
            commentText.maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES
            commentText.setOnClickListener {
                expanded = !expanded
                commentText.maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES
            }

            commentEdit.setOnClickListener {
                editing(!isEditing)
                commentsFragment.editCallback(item)
            }
            commentReply.setOnClickListener {
                replying(!isReplying)
                commentsFragment.replyTo(item, comment.username)
                commentsFragment.replyCallback(item)
            }
            modBadge.visibility = if (comment.isMod == true) View.VISIBLE else View.GONE
            adminBadge.visibility =
                if (comment.isAdmin == true) View.VISIBLE else View.GONE
            commentInfo.setOnClickListener {
                val popup = PopupMenu(commentsFragment.requireContext(), commentInfo)
                popup.menuInflater.inflate(R.menu.profile_details_menu, popup.menu)
                popup.menu.findItem(R.id.commentDelete)?.isVisible =
                    isUserComment || CommentsAPI.isAdmin || CommentsAPI.isMod
                popup.menu.findItem(R.id.commentBanUser)?.isVisible =
                    (CommentsAPI.isAdmin || CommentsAPI.isMod) && !isUserComment
                popup.menu.findItem(R.id.commentReport)?.isVisible = !isUserComment
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.commentCopy -> {
                            copyToClipboard(comment.content)
                            true
                        }

                        R.id.commentProfile -> {
                            openProfile()
                            true
                        }

                        R.id.commentReport -> {
                            dialogBuilder(
                                getAppString(R.string.report_comment),
                                getAppString(R.string.report_comment_confirm)
                            ) {
                                CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                                    val success = CommentsAPI.reportComment(
                                        comment.commentId,
                                        comment.username,
                                        commentsFragment.mediaName,
                                        comment.userId
                                    )
                                    if (success) {
                                        snackString(R.string.comment_reported)
                                    }
                                }
                            }
                            true
                        }

                        R.id.commentDelete -> {
                            dialogBuilder(
                                getAppString(R.string.delete_comment),
                                getAppString(R.string.delete_comment_confirm)
                            ) {
                                CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                                    val success = CommentsAPI.deleteComment(comment.commentId)
                                    if (success) {
                                        snackString(R.string.comment_deleted)
                                        parentSection.remove(this@CommentItem)
                                    }
                                }
                            }
                            true
                        }

                        R.id.commentBanUser -> {
                            dialogBuilder(
                                getAppString(R.string.ban_user),
                                getAppString(R.string.ban_user_confirm)
                            ) {
                                CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                                    val success = CommentsAPI.banUser(comment.userId)
                                    if (success) {
                                        snackString(R.string.user_banned)
                                    }
                                }
                            }
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }
                popup.show()
            }
            //fill the icon if the user has liked the comment
            setVoteButtons(viewBinding)
            commentUpVote.setOnClickListener {
                val voteType = if (comment.userVoteType == 1) 0 else 1
                val previousVoteType = comment.userVoteType
                val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
                scope.launch {
                    val success = CommentsAPI.vote(comment.commentId, voteType)
                    if (success) {
                        comment.userVoteType = voteType

                        if (previousVoteType == -1) {
                            comment.downvotes -= 1
                        }
                        comment.upvotes += if (voteType == 1) 1 else -1
                        // Only the vote pill changes. notifyChanged() rebound the whole comment —
                        // replaying its entry animation, re-rendering the text, reloading the avatar.
                        showVotes(viewBinding)
                    }
                }
            }

            commentDownVote.setOnClickListener {
                val voteType = if (comment.userVoteType == -1) 0 else -1
                val previousVoteType = comment.userVoteType
                val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
                scope.launch {
                    val success = CommentsAPI.vote(comment.commentId, voteType)
                    if (success) {
                        comment.userVoteType = voteType
                        if (previousVoteType == 1) {
                            comment.upvotes -= 1
                        }
                        comment.downvotes += if (voteType == -1) 1 else -1
                        // Only the vote pill changes. notifyChanged() rebound the whole comment —
                        // replaying its entry animation, re-rendering the text, reloading the avatar.
                        showVotes(viewBinding)
                    }
                }
            }
            commentTotalVotes.text = (comment.upvotes - comment.downvotes).toString()
            commentUserAvatar.openImage(
                commentsFragment.activity.getString(R.string.avatar, comment.username),
                comment.profilePictureUrl ?: ""
            )
            comment.profilePictureUrl?.let { commentUserAvatar.loadImage(it) }
            commentUserName.text = comment.username
            val userColor = "[${levelColor.second}]"
            commentUserLevel.text = userColor
            commentUserLevel.setTextColor(levelColor.first)
            commentUserTime.text = "· " + formatTimestamp(comment.timestamp)
        }
    }

    override fun getLayout(): Int {
        return R.layout.item_comments
    }

    fun containsGif(): Boolean {
        return comment.content.contains(".gif")
    }

    override fun initializeViewBinding(view: View): ItemCommentsBinding {
        return ItemCommentsBinding.bind(view)
    }

    fun replying(isReplying: Boolean) {
        binding.commentReply.text =
            commentsFragment.activity.getString(if (isReplying) R.string.cancel else R.string.reply)
        this.isReplying = isReplying
    }

    fun editing(isEditing: Boolean) {
        binding.commentEdit.text =
            if (isEditing) commentsFragment.activity.getString(R.string.cancel) else commentsFragment.activity.getString(
                R.string.edit
            )
        this.isEditing = isEditing
    }

    /**
     * Marks the replies as open before they're bound — for a thread shown down to one comment,
     * whose replies are put in place by the fragment rather than fetched by a tap.
     */
    fun markRepliesOpen() {
        repliesVisible = true
    }

    /**
     * Turns every timestamp in an anime comment ("12:34", "1:02:03") into a link that opens the
     * episode at that point. One inside an unrevealed spoiler stays hidden: the first tap reveals
     * the spoiler, as it would anywhere else in it.
     */
    private fun linkTimestamps(textView: TextView) {
        val spannable = textView.text as? Spannable ?: SpannableStringBuilder(textView.text)
        val matches = TIMESTAMP.findAll(spannable).toList()
        if (matches.isEmpty()) return
        val accent = textView.context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        matches.forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            if (spannable.getSpans(start, end, URLSpan::class.java).isNotEmpty()) return@forEach
            val hours = match.groupValues[1].toLongOrNull() ?: 0L
            val minutes = match.groupValues[2].toLongOrNull() ?: 0L
            val seconds = match.groupValues[3].toLongOrNull() ?: 0L
            val positionMs = (hours * 3600 + minutes * 60 + seconds) * 1000
            fun spoiler() = spannable.getSpans(start, end, SpoilerPlugin.RedditSpoilerSpan::class.java)
                .firstOrNull()?.takeIf { !it.revealed }
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val hidden = spoiler()
                    if (hidden != null) {
                        hidden.setRevealed(true)
                        widget.postInvalidateOnAnimation()
                    } else commentsFragment.onTimestampClicked(comment.tag, positionMs)
                }

                override fun updateDrawState(ds: TextPaint) {
                    if (spoiler() != null) return
                    ds.color = accent
                    ds.typeface = Typeface.create(ds.typeface, Typeface.BOLD)
                    ds.isUnderlineText = true
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textView.text = spannable
        textView.movementMethod = LinkMovementMethod.getInstance()
    }

    /** The thread line beside the replies shows only while they do. */
    private fun updateThreadLine() {
        binding.commentThreadLine.visibility = if (repliesVisible) View.VISIBLE else View.GONE
    }

    private fun openProfile() {
        ContextCompat.startActivity(
            commentsFragment.activity,
            Intent(commentsFragment.activity, ProfileActivity::class.java)
                .putExtra("userId", comment.userId.toInt()),
            null
        )
    }

    fun registerSubComment(id: Int) {
        subCommentIds.add(id)
    }

    private fun removeSubCommentIds() {
        subCommentIds.forEach { id ->
            @Suppress("UNCHECKED_CAST")
            val parentComments = parentSection.groups as? List<CommentItem> ?: emptyList()
            val commentToRemove = parentComments.find { it.comment.commentId == id }
            commentToRemove?.let {
                it.removeSubCommentIds()
                parentSection.remove(it)
            }
        }
        subCommentIds.clear()
    }

    private fun showVotes(viewBinding: ItemCommentsBinding) {
        setVoteButtons(viewBinding)
        viewBinding.commentTotalVotes.text = (comment.upvotes - comment.downvotes).toString()
    }

    private fun setVoteButtons(viewBinding: ItemCommentsBinding) {
        val context = viewBinding.root.context
        viewBinding.commentTotalVotes.setTextColor(
            if (comment.userVoteType == 1 || comment.userVoteType == -1) {
                context.getThemeColor(androidx.appcompat.R.attr.colorPrimary)
            } else context.getThemeColor(com.google.android.material.R.attr.colorOnBackground)
        )
        viewBinding.commentUpVote.alpha = 0.7f
        viewBinding.commentDownVote.alpha = 0.7f
        when (comment.userVoteType) {
            1 -> {
                viewBinding.commentUpVote.setImageResource(R.drawable.ic_round_upvote_active_24)
                viewBinding.commentUpVote.alpha = 1f
                viewBinding.commentDownVote.setImageResource(R.drawable.ic_round_upvote_inactive_24)
            }

            -1 -> {
                viewBinding.commentUpVote.setImageResource(R.drawable.ic_round_upvote_inactive_24)
                viewBinding.commentDownVote.setImageResource(R.drawable.ic_round_upvote_active_24)
                viewBinding.commentDownVote.alpha = 1f
            }

            else -> {
                viewBinding.commentUpVote.setImageResource(R.drawable.ic_round_upvote_inactive_24)
                viewBinding.commentDownVote.setImageResource(R.drawable.ic_round_upvote_inactive_24)
            }
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun formatTimestamp(timestamp: String): String {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            val parsedDate = dateFormat.parse(timestamp)
            val currentDate = Date()

            val diff = currentDate.time - (parsedDate?.time ?: 0)

            val days = diff / (24 * 60 * 60 * 1000)
            val hours = diff / (60 * 60 * 1000) % 24
            val minutes = diff / (60 * 1000) % 60

            val context = commentsFragment.activity
            return when {
                days >= 365 -> context.getString(R.string.comment_age_years, (days / 365).toInt())
                days >= 30 -> context.getString(R.string.comment_age_months, (days / 30).toInt())
                days >= 7 -> context.getString(R.string.comment_age_weeks, (days / 7).toInt())
                days > 0 -> context.getString(R.string.comment_age_days, days.toInt())
                hours > 0 -> context.getString(R.string.comment_age_hours, hours.toInt())
                minutes > 0 -> context.getString(R.string.comment_age_minutes, minutes.toInt())
                else -> context.getString(R.string.comment_age_now)
            }
        } catch (e: Exception) {
            commentsFragment.activity.getString(R.string.comment_age_now)
        }
    }

    companion object {
        private const val COLLAPSED_LINES = 8

        /** h:mm:ss or mm:ss, as a whole word. */
        private val TIMESTAMP = Regex("""\b(?:([0-1]?\d|2[0-3]):)?([0-5]?\d):([0-5]\d)\b""")

        @SuppressLint("SimpleDateFormat")
        fun timestampToMillis(timestamp: String): Long {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            val parsedDate = dateFormat.parse(timestamp)
            return parsedDate?.time ?: 0
        }
    }

    private fun getAvatarColor(voteCount: Int, backgroundColor: Int): Pair<Int, Int> {
        val level = if (voteCount < 0) 0 else sqrt(abs(voteCount.toDouble()) / 0.8).toInt()
        val colorString =
            if (level > usernameColors.size - 1) usernameColors[usernameColors.size - 1] else usernameColors[level]
        var color = Color.parseColor(colorString)
        val ratio = getContrastRatio(color, backgroundColor)
        if (ratio < 4.5) {
            color = adjustColorForContrast(color, backgroundColor)
        }

        return Pair(color, level)
    }

    /**
     * Builds the dialog for yes/no confirmation
     * no doesn't do anything, yes calls the callback
     * @param title the title of the dialog
     * @param message the message of the dialog
     * @param callback the callback to call when the user clicks yes
     */
    private fun dialogBuilder(title: String, message: String, callback: () -> Unit) {
        commentsFragment.activity.customAlertDialog().apply {
            setTitle(title)
            setMessage(message)
            setPosButton("Yes") {
                callback()
            }
            setNegButton("No") {}
        }.show()
    }

    private val usernameColors: Array<String> = arrayOf(
        "#9932cc",
        "#a020f0",
        "#8b008b",
        "#7b68ee",
        "#da70d6",
        "#dda0dd",
        "#ffe4b5",
        "#f0e68c",
        "#ffb6c1",
        "#fa8072",
        "#b03060",
        "#ff1493",
        "#ff00ff",
        "#ff69b4",
        "#dc143c",
        "#8b0000",
        "#ff0000",
        "#a0522d",
        "#f4a460",
        "#b8860b",
        "#ffa500",
        "#d2691e",
        "#ff6347",
        "#808000",
        "#ffd700",
        "#ffff54",
        "#8fbc8f",
        "#3cb371",
        "#008000",
        "#00fa9a",
        "#98fb98",
        "#00ff00",
        "#adff2f",
        "#32cd32",
        "#556b2f",
        "#9acd32",
        "#7fffd4",
        "#2f4f4f",
        "#5f9ea0",
        "#87ceeb",
        "#00bfff",
        "#00ffff",
        "#1e90ff",
        "#4682b4",
        "#0000ff",
        "#0000cd",
        "#00008b",
        "#191970",
        "#ffffff",
    )

}