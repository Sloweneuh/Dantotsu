package ani.dantotsu.home

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.connections.mangaupdates.MUListEditorFragment
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
import ani.dantotsu.connections.mangaupdates.MUDetailsCache
import ani.dantotsu.databinding.ItemMediaLargeBinding
import ani.dantotsu.databinding.ItemUnreadChapterBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.media.MediaNameAdapter
import java.io.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class UnreadChaptersAdapter(
    private val items: List<Any>,  // List<Media | MUMedia>
    private val unreadInfo: Map<Int, UnreadChapterInfo>,
    private var type: Int = 0 // 0 = grid/compact, 1 = list/large
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val muIds = items.filterIsInstance<MUMedia>().filter { it.coverUrl == null }.map { it.id }
        MUDetailsCache.prefetch(scope, muIds) { id ->
            val pos = items.indexOfFirst { it is MUMedia && it.id == id }
            if (pos != -1) notifyItemChanged(pos)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope.cancel()
    }

    inner class CompactViewHolder(val binding: ItemUnreadChapterBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class LargeViewHolder(val binding: ItemMediaLargeBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            0 -> CompactViewHolder(
                ItemUnreadChapterBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
            1 -> LargeViewHolder(
                ItemMediaLargeBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is MUMedia -> when (holder) {
                is CompactViewHolder -> bindMuCompactView(holder.binding, item)
                is LargeViewHolder -> bindMuLargeView(holder.binding, item)
            }
            is Media -> {
                val info = unreadInfo[item.id] ?: run {
                    // Try to derive the last known chapter from the manga chapters list if available
                    val derivedLast = item.manga?.chapters?.values
                        ?.mapNotNull { MediaNameAdapter.findChapterNumber(it.number)?.toInt() }
                        ?.maxOrNull()
                        ?: item.manga?.totalChapters
                        ?: 0
                    ani.dantotsu.connections.malsync.UnreadChapterInfo(
                        mediaId = item.id,
                        lastChapter = derivedLast,
                        source = "",
                        userProgress = item.userProgress ?: 0
                    )
                }
                when (holder) {
                    is CompactViewHolder -> bindCompactView(holder.binding, item, info)
                    is LargeViewHolder -> bindLargeView(holder.binding, item, info)
                }
            }
        }
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private fun bindCompactView(binding: ItemUnreadChapterBinding, media: Media, info: UnreadChapterInfo) {
        binding.apply {
            // Load cover image
            itemCompactImage.loadImage(media.cover)

            // Set title
            itemCompactTitle.text = media.userPreferredName

            // Set progress text in format: progress | lastEp | totalChapters (or ~)
            itemCompactUserProgress.text = info.userProgress.toString()

            val totalChapters = media.manga?.totalChapters ?: "~"
            val lastChapterDisplay = if (info.lastChapter > 0) info.lastChapter.toString() else "?"
            itemCompactTotal.text = " | $lastChapterDisplay | $totalChapters"

            // Show source as badge on top of cover (icon + short code) to match anime badge
            itemCompactSourceBadge.visibility = View.GONE
                if (!info.source.isNullOrBlank()) {
                    // Show full source name in badge and hide icon
                    itemCompactLanguageIcon.visibility = View.GONE
                    itemCompactLanguageCode.text = info.source
                    itemCompactLanguageBG.visibility = View.VISIBLE
                    itemCompactSource.visibility = View.GONE
                } else {
                    itemCompactLanguageBG.visibility = View.GONE
                    itemCompactSource.visibility = View.VISIBLE
                }

            // Set score - divide by 10.0 to match Anilist format, fallback to mean score
            val score = if (media.userScore == 0) (media.meanScore ?: 0) else media.userScore
            if (score > 0) {
                itemCompactScore.text = (score / 10.0).toString()
                itemCompactScoreBG.background = ContextCompat.getDrawable(
                    root.context,
                    if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score
                )
                // use badge variant that matches score corners when user score exists
                try {
                    val isUser = media.userScore != 0
                    itemCompactLanguageBG.setBackgroundResource(
                        if (isUser) R.drawable.item_language_badge_user else R.drawable.item_language_badge
                    )
                    itemCompactLanguageBG.backgroundTintList = itemCompactScoreBG.backgroundTintList
                } catch (e: Exception) {}
                try {
                    val isUser = media.userScore != 0
                    itemCompactSourceBadge.setBackgroundResource(
                        if (isUser) R.drawable.item_language_badge_user else R.drawable.item_language_badge
                    )
                    itemCompactSourceBadge.backgroundTintList = itemCompactScoreBG.backgroundTintList
                } catch (e: Exception) {}
                itemCompactScoreBG.visibility = View.VISIBLE
            } else {
                itemCompactScoreBG.visibility = View.GONE
            }

            // Handle click to open media details
            val clickAction = {
                val intent = Intent(root.context, ani.dantotsu.media.MediaDetailsActivity::class.java)
                    .putExtra("media", media)
                    .putExtra("source", info.source)
                    .putExtra("lastChapter", info.lastChapter)
                val activity = (root.context as? androidx.fragment.app.FragmentActivity)
                val options = if (activity != null) {
                    ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity,
                        itemCompactImage,
                        ViewCompat.getTransitionName(itemCompactImage)!!
                    ).toBundle()
                } else null

                root.context.startActivity(intent, options)
            }
            root.setSafeOnClickListener { clickAction() }
            itemCompactImage.setSafeOnClickListener { clickAction() }
            itemCompactTitle.setSafeOnClickListener { clickAction() }

            // Handle long click to open list editor
            itemCompactImage.setOnLongClickListener {
                val activity = it.context as? androidx.fragment.app.FragmentActivity
                if (activity != null && activity.supportFragmentManager.findFragmentByTag("list") == null) {
                    ani.dantotsu.media.MediaListDialogSmallFragment.newInstance(media)
                        .show(activity.supportFragmentManager, "list")
                }
                true
            }
        }
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private fun bindLargeView(binding: ItemMediaLargeBinding, media: Media, info: UnreadChapterInfo) {
        binding.apply {
            // Load cover and banner images
            itemCompactImage.loadImage(media.cover)
            blurImage(itemCompactBanner, media.banner ?: media.cover)

            // Set title
            itemCompactTitle.text = media.userPreferredName

            // Set progress info - show: userProgress / (lastChapter | totalChapters)
            val totalChapters = media.manga?.totalChapters ?: "~"
            val lastChapterDisplay = if (info.lastChapter > 0) info.lastChapter.toString() else "?"
            itemUserProgressLarge.text = (info.userProgress).toString()
            itemProgressSeparator.visibility = View.VISIBLE
            itemCompactTotal.text = "$lastChapterDisplay | $totalChapters"
            itemTotal.text = ""

            // Synopsis preview (strip HTML) and make it scrollable
            try {
                val rawDesc = media.description ?: ""
                val parsed = androidx.core.text.HtmlCompat.fromHtml(rawDesc, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
                itemCompactSynopsis.text = if (parsed.isBlank() || parsed.toString() == "null") root.context.getString(R.string.no_description_available) else parsed
                Linkify.addLinks(itemCompactSynopsis, Linkify.WEB_URLS)
                itemCompactSynopsis.movementMethod = LinkMovementMethod.getInstance()
                itemCompactSynopsis.scrollTo(0, 0)
                itemCompactSynopsis.setOnTouchListener { v, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
            } catch (e: Exception) {
                itemCompactSynopsis.text = root.context.getString(R.string.no_description_available)
                itemCompactSynopsis.scrollTo(0, 0)
            }

            // Show source as a badge on top of the cover (icon + short code), hide side relation
            itemCompactSourceBadge.visibility = View.GONE
            val sourceDisplay = info.source
                if (!sourceDisplay.isNullOrBlank()) {
                    // Show full source name in badge and hide icon
                    itemCompactLanguageIcon.visibility = View.GONE
                    itemCompactLanguageCode.text = sourceDisplay
                    itemCompactLanguageBG.visibility = View.VISIBLE
                    itemCompactType.visibility = View.GONE
                } else {
                    itemCompactLanguageBG.visibility = View.GONE
                    itemCompactType.visibility = View.GONE
                }

            // Show media status between title and synopsis when available
            itemCompactStatus.text = media.status ?: ""
            itemCompactStatus.visibility = if (!itemCompactStatus.text.isNullOrBlank()) View.VISIBLE else View.GONE

            // Show releasing/hiatus status dot
            run {
                val releasingStr = root.context.getString(R.string.status_releasing)
                val hiatusStr = root.context.getString(R.string.status_hiatus)
                val st = media.status ?: ""
                val isReleasing = st == releasingStr
                val isHiatus = st.equals(hiatusStr, ignoreCase = true)
                if (isReleasing || isHiatus) {
                    itemCompactOngoing.visibility = View.VISIBLE
                    try {
                        val dot = itemCompactOngoing.getChildAt(0)
                        if (isHiatus) dot?.setBackgroundResource(R.drawable.item_hiatus)
                        else dot?.setBackgroundResource(R.drawable.item_ongoing)
                    } catch (e: Exception) { }
                } else {
                    itemCompactOngoing.visibility = View.GONE
                }
            }

            // Set score - divide by 10.0 to match Anilist format, fallback to mean score
            val score = if (media.userScore == 0) (media.meanScore ?: 0) else media.userScore
            if (score > 0) {
                itemCompactScore.text = (score / 10.0).toString()
                itemCompactScoreBG.background = ContextCompat.getDrawable(
                    root.context,
                    if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score
                )
                // use badge variant that matches score corners when user score exists
                try {
                    val isUser = media.userScore != 0
                    itemCompactLanguageBG.setBackgroundResource(
                        if (isUser) R.drawable.item_language_badge_user else R.drawable.item_language_badge
                    )
                    itemCompactLanguageBG.backgroundTintList = itemCompactScoreBG.backgroundTintList
                } catch (e: Exception) {}
                try {
                    val isUser = media.userScore != 0
                    itemCompactSourceBadge.setBackgroundResource(
                        if (isUser) R.drawable.item_language_badge_user else R.drawable.item_language_badge
                    )
                    itemCompactSourceBadge.backgroundTintList = itemCompactScoreBG.backgroundTintList
                } catch (e: Exception) {}
                itemCompactScoreBG.visibility = View.VISIBLE
            } else {
                itemCompactScoreBG.visibility = View.GONE
            }

            // Handle click to open media details
            val clickAction = {
                root.context.startActivity(
                    Intent(root.context, ani.dantotsu.media.MediaDetailsActivity::class.java)
                        .putExtra("media", media)
                        .putExtra("source", info.source)
                        .putExtra("lastChapter", info.lastChapter),
                    null
                )
            }
            root.setSafeOnClickListener { clickAction() }
            itemCompactImage.setSafeOnClickListener { clickAction() }
            itemCompactTitle.setSafeOnClickListener { clickAction() }

            // Handle long click to open list editor
            itemCompactImage.setOnLongClickListener {
                val activity = it.context as? androidx.fragment.app.FragmentActivity
                if (activity != null && activity.supportFragmentManager.findFragmentByTag("list") == null) {
                    ani.dantotsu.media.MediaListDialogSmallFragment.newInstance(media)
                        .show(activity.supportFragmentManager, "list")
                }
                true
            }
        }
    }

    override fun getItemCount(): Int = items.size

    @SuppressLint("SetTextI18n")
    private fun bindMuCompactView(binding: ItemUnreadChapterBinding, item: MUMedia) {
        binding.apply {
            itemCompactImage.loadImage(item.coverUrl ?: MUDetailsCache.get(item.id)?.coverUrl)
            itemCompactTitle.text = item.title ?: ""

            val userChapter = item.userChapter
            itemCompactUserProgress.text = userChapter?.toString() ?: "~"
            val latest = item.latestChapter
            val showLatest = latest != null && latest > 0 && (userChapter == null || latest > userChapter)
            itemCompactTotal.text = if (showLatest) " | $latest | ~" else " | ~"

            // MU logo badge
            itemCompactLanguageBG.visibility = View.GONE
            itemCompactSourceBadge.visibility = View.VISIBLE
            itemCompactSource.visibility = View.GONE

            val rating = item.bayesianRating
            if (rating != null && rating > 0.0) {
                itemCompactScore.text = String.format("%.1f", rating)
                itemCompactScoreBG.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                try {
                    itemCompactSourceBadge.setBackgroundResource(R.drawable.item_language_badge)
                    itemCompactSourceBadge.backgroundTintList = itemCompactScoreBG.backgroundTintList
                } catch (e: Exception) {}
                itemCompactScoreBG.visibility = View.VISIBLE
            } else {
                itemCompactScoreBG.visibility = View.GONE
            }

            val clickAction = {
                root.context.startActivity(
                    Intent(root.context, MUMediaDetailsActivity::class.java)
                        .putExtra("muMedia", item as Serializable)
                )
            }
            root.setSafeOnClickListener { clickAction() }
            itemCompactImage.setSafeOnClickListener { clickAction() }
            itemCompactTitle.setSafeOnClickListener { clickAction() }

            itemCompactImage.setOnLongClickListener {
                val fm = (it.context as? FragmentActivity)?.supportFragmentManager
                if (fm != null && fm.findFragmentByTag("muListEditor") == null) {
                    MUListEditorFragment.newInstance(item).show(fm, "muListEditor")
                }
                true
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bindMuLargeView(binding: ItemMediaLargeBinding, item: MUMedia) {
        binding.apply {
            val cached = MUDetailsCache.get(item.id)
            val coverUrl = item.coverUrl ?: cached?.coverUrl
            itemCompactImage.loadImage(coverUrl)
            blurImage(itemCompactBanner, coverUrl)
            itemCompactTitle.text = item.title ?: ""
            itemCompactOngoing.visibility = View.GONE
            itemCompactType.visibility = View.GONE
            itemCompactStatus.text = ""
            itemCompactStatus.visibility = View.GONE
            val desc = cached?.description
            if (desc != null) {
                itemCompactSynopsis.text = androidx.core.text.HtmlCompat.fromHtml(desc, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
                Linkify.addLinks(itemCompactSynopsis, Linkify.WEB_URLS)
                itemCompactSynopsis.movementMethod = LinkMovementMethod.getInstance()
            } else {
                itemCompactSynopsis.text = ""
            }

            val userChapter = item.userChapter
            itemUserProgressLarge.text = userChapter?.toString() ?: "~"
            itemProgressSeparator.visibility = View.VISIBLE
            val latest = item.latestChapter
            itemCompactTotal.text = latest?.toString() ?: "??"
            itemTotal.text = " " + root.context.getString(R.string.chapter_plural)

            // MU logo badge
            itemCompactLanguageBG.visibility = View.GONE
            itemCompactSourceBadge.visibility = View.VISIBLE

            val rating = item.bayesianRating
            if (rating != null && rating > 0.0) {
                itemCompactScore.text = String.format("%.1f", rating)
                itemCompactScoreBG.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                try {
                    itemCompactSourceBadge.setBackgroundResource(R.drawable.item_language_badge)
                    itemCompactSourceBadge.backgroundTintList = itemCompactScoreBG.backgroundTintList
                } catch (e: Exception) {}
                itemCompactScoreBG.visibility = View.VISIBLE
            } else {
                itemCompactScoreBG.visibility = View.GONE
            }

            root.setSafeOnClickListener {
                root.context.startActivity(
                    Intent(root.context, MUMediaDetailsActivity::class.java)
                        .putExtra("muMedia", item as Serializable)
                )
            }
            itemCompactImage.setOnLongClickListener {
                val fm = (it.context as? FragmentActivity)?.supportFragmentManager
                if (fm != null && fm.findFragmentByTag("muListEditor") == null) {
                    MUListEditorFragment.newInstance(item).show(fm, "muListEditor")
                }
                true
            }
        }
    }
}

