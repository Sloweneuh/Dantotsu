package ani.dantotsu.media

import ani.dantotsu.blurImage
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.mangabaka.MangaBakaStacks
import ani.dantotsu.buildMarkwon
import ani.dantotsu.openLinkInAppOrBrowser
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import ani.dantotsu.databinding.ItemStackLargeBinding
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import ani.dantotsu.getThemeColor
import androidx.core.text.HtmlCompat
import android.content.Context
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import ani.dantotsu.media.MediaDetailsActivity

class StackListAdapter(private val items: List<InterestStack>, private val isAnime: Boolean) : RecyclerView.Adapter<StackListAdapter.VH>() {

    inner class VH(val binding: ItemStackLargeBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in items.indices) {
                    val item = items[pos]
                    StackResolver.open(binding.root.context, item, isAnime)
                }
            }

            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos in items.indices) {
                    val item = items[pos]
                    try {
                        binding.root.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                    } catch (e: Exception) {
                        // ignore
                    }
                }
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemStackLargeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        val container = b.root.findViewById<android.widget.FrameLayout>(R.id.itemStackCoversContainer)
        if (container != null) StackCoverCollage.bind(container, item.covers, item.entries)
        // banner still uses first cover
        blurImage(b.itemCompactBanner, item.covers.firstOrNull())
        // title
        b.itemCompactTitle.text = item.name
        // synopsis / description (show placeholder when blank, parse links and enable scrolling)
        val mbStack = item as? MangaBakaStacks.StackSummary
        if (mbStack != null) {
            bindMangaBakaDescription(b, mbStack)
        } else {
            val rawDesc = item.description
            if (rawDesc.isNullOrBlank()) {
                b.itemCompactSynopsis.text = b.root.context.getString(R.string.no_description_available)
            } else {
                b.itemCompactSynopsis.text = HtmlCompat.fromHtml(rawDesc, HtmlCompat.FROM_HTML_MODE_LEGACY)
            }
            // Apply Linkify for bare URLs that aren't wrapped in <a> tags
            Linkify.addLinks(b.itemCompactSynopsis, Linkify.WEB_URLS)
            // Replace URLSpans with custom spans that open in-app when possible
            interceptLinks(
                b.itemCompactSynopsis,
                b.root.context.getThemeColor(androidx.appcompat.R.attr.colorPrimary),
                isAnime
            )
        }
        // Allow inner TextView to handle vertical scroll inside RecyclerView
        b.itemCompactSynopsis.setOnTouchListener { v, ev ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            if (ev.action == MotionEvent.ACTION_UP) v.parent?.requestDisallowInterceptTouchEvent(false)
            false
        }
        // entries -> user progress large
        b.itemUserProgressLarge.text = item.entries.toString()
        b.itemCompactTotal.text = b.root.context.getString(R.string.stack_entries_suffix)
        // MangaBaka rows add who made the stack and its votes, and flag drafts and spoiler stacks,
        // on the entry count's own line so the description keeps the room.
        val mb = item as? MangaBakaStacks.StackSummary
        if (mb != null) {
            val ctx = b.root.context
            val parts = buildList {
                add(ctx.getString(R.string.stack_entries_suffix).trim())
                mb.owner?.name?.takeIf { it.isNotBlank() }?.let { add(ctx.getString(R.string.stack_by_owner, it)) }
                add(ctx.resources.getQuantityString(R.plurals.stack_votes, mb.voteCount, mb.voteCount))
                if (mb.visibility == "draft") add(ctx.getString(R.string.stack_visibility_draft))
                if (mb.visibility == "unlisted") add(ctx.getString(R.string.stack_visibility_unlisted))
                if (mb.isSpoiler) add(ctx.getString(R.string.stack_spoiler))
            }
            b.itemCompactTotal.text = parts.joinToString(" · ")
            b.itemCompactTotal.maxLines = 1
            b.itemCompactTotal.ellipsize = android.text.TextUtils.TruncateAt.END
            // Take the rest of the row so a long line ellipsizes instead of running off the card.
            (b.itemCompactTotal.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                it.width = 0
                it.weight = 1f
                b.itemCompactTotal.layoutParams = it
            }
        }
        b.itemCompactType.visibility = android.view.View.GONE
    }

    override fun getItemCount(): Int = items.size

    private var markwon: Markwon? = null

    /**
     * MangaBaka list rows carry no description, so each one is read from the stack itself the first
     * time its row is drawn (and cached, see [MangaBakaStacks.getDescription]), then rendered as
     * the Markdown it is.
     */
    private fun bindMangaBakaDescription(b: ItemStackLargeBinding, stack: MangaBakaStacks.StackSummary) {
        val ctx = b.root.context
        val known = stack.description ?: MangaBakaStacks.cachedDescription(stack.id)
        if (known == null) {
            b.itemCompactSynopsis.text = ""
            val owner = ctx as? LifecycleOwner ?: return
            owner.lifecycleScope.launch {
                MangaBakaStacks.getDescription(stack.id)
                // Redraw every row of this stack that's still in the list; the holder that asked
                // may have been recycled for another stack by now.
                items.forEachIndexed { i, it ->
                    if (it is MangaBakaStacks.StackSummary && it.id == stack.id) notifyItemChanged(i)
                }
            }
            return
        }
        if (known.isBlank()) {
            b.itemCompactSynopsis.text = ctx.getString(R.string.no_description_available)
            return
        }
        val md = markwon ?: buildMarkwon(ctx, userInputContent = true, linkResolver = { openLinkInAppOrBrowser(it) })
            .also { markwon = it }
        md.setMarkdown(b.itemCompactSynopsis, known)
        b.itemCompactSynopsis.movementMethod = LinkMovementMethod.getInstance()
    }

    companion object {
        private val anilistMediaRegex = Regex("""https://anilist\.co/(anime|manga)/(\d+)""")
        private val malMediaRegex = Regex("""https://myanimelist\.net/(anime|manga)/(\d+)""")
        private val malStackRegex = Regex("""https://myanimelist\.net/stacks/(\d+)""")

        /** Replaces all URLSpan instances with custom ClickableSpans that open in-app for known URLs. */
        fun interceptLinks(textView: TextView, linkColor: Int, isAnime: Boolean) {
            val spannable = SpannableString(textView.text)
            val urlSpans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
            for (urlSpan in urlSpans) {
                val start = spannable.getSpanStart(urlSpan)
                val end = spannable.getSpanEnd(urlSpan)
                val flags = spannable.getSpanFlags(urlSpan)
                val url = urlSpan.url
                spannable.removeSpan(urlSpan)
                spannable.setSpan(ForegroundColorSpan(linkColor), start, end, flags)
                spannable.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = openLink(widget.context, url, isAnime)
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = linkColor
                        ds.isUnderlineText = true
                    }
                }, start, end, flags)
            }
            textView.text = spannable
            textView.movementMethod = LinkMovementMethod.getInstance()
        }

        fun openLink(context: Context, url: String, isAnime: Boolean = false) {
            // AniList media page
            val anilistMatch = anilistMediaRegex.find(url)
            if (anilistMatch != null) {
                val id = anilistMatch.groupValues[2].toIntOrNull()
                if (id != null) {
                    context.startActivity(
                        Intent(context, MediaDetailsActivity::class.java).putExtra("mediaId", id)
                    )
                    return
                }
            }
            // MAL media page
            val malMatch = malMediaRegex.find(url)
            if (malMatch != null) {
                val id = malMatch.groupValues[2].toIntOrNull()
                if (id != null) {
                    context.startActivity(
                        Intent(context, MediaDetailsActivity::class.java)
                            .putExtra("mediaId", id)
                            .putExtra("mal", true)
                    )
                    return
                }
            }
            // MAL interest stack page — open in-app; the list screen scrapes the stack's name,
            // description and entries itself.
            if (malStackRegex.containsMatchIn(url)) {
                StackResolver.open(context, url, isAnime, title = null, description = null)
                return
            }
            // MangaBaka stack, series and the other pages the app has screens for.
            if (ani.dantotsu.openLinkInApp(url)) return
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {}
        }
    }
}
