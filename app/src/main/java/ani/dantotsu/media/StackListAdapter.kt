package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.mal.MALStack
import ani.dantotsu.connections.mal.MALQueries
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.malsync.MalSyncApi
import ani.dantotsu.connections.malsync.LanguageMapper
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo
import ani.dantotsu.databinding.ItemStackLargeBinding
import ani.dantotsu.getAppString
import ani.dantotsu.loadImage
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.Log
import ani.dantotsu.getThemeColor
import androidx.core.text.HtmlCompat
import android.content.Context
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import ani.dantotsu.media.MediaDetailsActivity
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StackListAdapter(private val items: List<MALStack>, private val isAnime: Boolean) : RecyclerView.Adapter<StackListAdapter.VH>() {

    inner class VH(val binding: ItemStackLargeBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in items.indices) {
                    val item = items[pos]
                    val ctx = binding.root.context
                    val activity = ctx as? AppCompatActivity
                    if (activity == null) {
                        Toast.makeText(ctx, getAppString(R.string.anilist_down), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    activity.lifecycleScope.launch {
                        Toast.makeText(ctx, "Loading stack...", Toast.LENGTH_SHORT).show()
                        val entries = withContext(Dispatchers.IO) {
                            MALQueries().getStackEntries(item.url)
                        }
                        val malIds = entries.map { it.id }
                        if (malIds.isEmpty()) {
                            Toast.makeText(ctx, "No entries found", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val fetched = withContext(Dispatchers.IO) {
                            try {
                                Anilist.query.getMediaBatch(malIds, mal = true, mediaType = if (isAnime) "ANIME" else "MANGA")
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                        if (fetched.isEmpty()) {
                            Toast.makeText(ctx, "No AniList matches found", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                            // attach MAL intro text to matched Media objects when available
                            try {
                                for (m in fetched) {
                                    val malId = m.idMAL
                                    val entry = entries.find { it.id == malId }
                                    if (entry != null) m.malIntro = entry.intro
                                }
                            } catch (e: Exception) {
                                // ignore mapping errors
                            }

                            // Fetch MALSync progress/source data if enabled
                            if (PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled)) {
                                val mediaIds = fetched.map { Pair(it.id, it.idMAL) }
                                if (isAnime) {
                                    val batchResults = withContext(Dispatchers.IO) {
                                        try { MalSyncApi.getBatchAnimeEpisodes(mediaIds) } catch (e: Exception) { emptyMap() }
                                    }
                                    val infoMap = mutableMapOf<Int, UnreleasedEpisodeInfo>()
                                    for (m in fetched) {
                                        val result = batchResults[m.id] ?: continue
                                        val lastEp = result.lastEp ?: continue
                                        val langOption = LanguageMapper.mapLanguage(result.id)
                                        infoMap[m.id] = UnreleasedEpisodeInfo(
                                            mediaId = m.id,
                                            lastEpisode = lastEp.total,
                                            languageId = result.id,
                                            languageDisplay = langOption.displayName,
                                            userProgress = m.userProgress ?: 0
                                        )
                                    }
                                    if (infoMap.isNotEmpty()) MediaListViewActivity.passedUnreleasedInfo = infoMap
                                } else {
                                    val batchResults = withContext(Dispatchers.IO) {
                                        try { MalSyncApi.getBatchProgressByMedia(mediaIds) } catch (e: Exception) { emptyMap() }
                                    }
                                    val infoMap = mutableMapOf<Int, UnreadChapterInfo>()
                                    for (m in fetched) {
                                        val result = batchResults[m.id] ?: continue
                                        val lastEp = result.lastEp ?: continue
                                        infoMap[m.id] = UnreadChapterInfo(
                                            mediaId = m.id,
                                            lastChapter = lastEp.total,
                                            source = result.source,
                                            userProgress = m.userProgress ?: 0
                                        )
                                    }
                                    if (infoMap.isNotEmpty()) MediaListViewActivity.passedUnreadInfo = infoMap
                                }
                            }

                            MediaListViewActivity.passedMedia = ArrayList(fetched)
                        val i = Intent(ctx, MediaListViewActivity::class.java)
                        i.putExtra("title", item.name)
                        ctx.startActivity(i)
                    }
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
        // Populate stacked covers in container and set banner
        // prefer generated binding property when possible
        val container = (try {
            b::class.java.getMethod("getItemStackCoversContainer").invoke(b) as? android.widget.FrameLayout
        } catch (e: Exception) {
            null
        }) ?: b.root.findViewById<android.widget.FrameLayout>(R.id.itemStackCoversContainer)
        container?.removeAllViews()
        val covers = item.covers
        Log.d("StackListAdapter", "onBind position=$position covers=${covers.size} containerFound=${container!=null}")
        val count = covers.size.coerceAtMost(3)
        val offsetDp = 12f
        val dm = b.root.context.resources.displayMetrics
        // Determine base width/height for large container (fallback to 108dp/160dp)
        val baseW = if (container != null && container.width > 0) container.width else TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 108f, dm).toInt()
        val baseH = if (container != null && container.height > 0) container.height else TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 160f, dm).toInt()

        // Add from bottom (right) to top (left) so cover[0] becomes top/left
        for (i in count - 1 downTo 0) {
            val offsetPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, offsetDp, dm).toInt()
            val widthForIndex = baseW - ((count - 1 - i) * offsetPx)
            val lp = android.widget.FrameLayout.LayoutParams(widthForIndex, baseH)
            lp.marginStart = i * offsetPx

            // compute image elevation value so we can place the shadow just beneath the top image
            val imgElevation = (count - i + 20).toFloat()

            // If this is the topmost cover (i == 0), add a right-edge shadow overlay BEFORE adding the top image
            if (i == 0) {
                val overlay = android.view.View(b.root.context)
                val ovWidth = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f, dm).toInt()
                val ovLp = android.widget.FrameLayout.LayoutParams(ovWidth, baseH)
                // position overlay so it straddles the right edge of the top cover (half over the top, half over the one beneath)
                val overlayMargin = i * offsetPx + (widthForIndex - ovWidth / 2)
                ovLp.marginStart = overlayMargin
                overlay.layoutParams = ovLp
                overlay.setBackgroundResource(R.drawable.stack_right_shadow)
                // set overlay elevation just below the top image elevation
                overlay.elevation = kotlin.math.max(0f, imgElevation - 1f)
                overlay.isClickable = false
                container?.addView(overlay)
            }

            val img = android.widget.ImageView(b.root.context)
            img.layoutParams = lp
            img.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            // ensure cover images render above the placeholder/background
            img.elevation = imgElevation
            val url = covers.getOrNull(i)
            if (!url.isNullOrEmpty()) Glide.with(b.root.context).load(url).centerCrop().into(img) else img.setImageResource(R.drawable.ic_round_collections_bookmark_24)
            container?.addView(img)
            Log.d("StackListAdapter", "added cover i=$i url=$url widthForIndex=$widthForIndex lpStart=${lp.marginStart}")
            // bring the cover image to front to ensure visibility in this CardView layout
            img.bringToFront()
        }
        // banner still uses first cover
        b.itemCompactBanner.loadImage(item.covers.firstOrNull())
        // title
        b.itemCompactTitle.text = item.name
        // synopsis / description (show placeholder when blank, parse HTML links and enable scrolling)
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
            b.root.context.getThemeColor(com.google.android.material.R.attr.colorPrimary),
            isAnime
        )
        // Allow inner TextView to handle vertical scroll inside RecyclerView
        b.itemCompactSynopsis.setOnTouchListener { v, ev ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            if (ev.action == MotionEvent.ACTION_UP) v.parent?.requestDisallowInterceptTouchEvent(false)
            false
        }
        // entries -> user progress large
        b.itemUserProgressLarge.text = item.entries.toString()
        b.itemCompactTotal.text = b.root.context.getString(R.string.stack_entries_suffix)
        b.itemCompactType.visibility = android.view.View.GONE
    }

    override fun getItemCount(): Int = items.size

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
            // MAL interest stack page — fetch entries and open in-app
            val stackMatch = malStackRegex.find(url)
            if (stackMatch != null) {
                val activity = context as? AppCompatActivity
                if (activity != null) {
                    activity.lifecycleScope.launch {
                        Toast.makeText(context, "Loading stack...", Toast.LENGTH_SHORT).show()
                        val queries = MALQueries()
                        val (entries, stackName) = withContext(Dispatchers.IO) {
                            Pair(
                                try { queries.getStackEntries(url) } catch (e: Exception) { emptyList() },
                                try { queries.getStackName(url) } catch (e: Exception) { null }
                            )
                        }
                        val malIds = entries.map { it.id }
                        if (malIds.isEmpty()) {
                            Toast.makeText(context, "No entries found", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val fetched = withContext(Dispatchers.IO) {
                            try {
                                Anilist.query.getMediaBatch(malIds, mal = true, mediaType = if (isAnime) "ANIME" else "MANGA")
                            } catch (e: Exception) { emptyList() }
                        }
                        if (fetched.isEmpty()) {
                            Toast.makeText(context, "No AniList matches found", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        MediaListViewActivity.passedMedia = ArrayList(fetched)
                        context.startActivity(
                            Intent(context, MediaListViewActivity::class.java)
                                .putExtra("title", stackName ?: "Stack")
                        )
                    }
                    return
                }
            }
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {}
        }
    }
}
