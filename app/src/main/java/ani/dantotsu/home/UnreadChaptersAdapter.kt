package ani.dantotsu.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.databinding.ItemMediaLargeBinding
import ani.dantotsu.databinding.ItemUnreadChapterBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.media.MediaNameAdapter

class UnreadChaptersAdapter(
    private val mediaList: List<Media>,
    private val unreadInfo: Map<Int, UnreadChapterInfo>,
    private var type: Int = 0 // 0 = grid/compact, 1 = list/large
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
        val media = mediaList[position]
        val info = unreadInfo[media.id] ?: run {
            // Try to derive the last known chapter from the manga chapters list if available
            val derivedLast = media.manga?.chapters?.values
                ?.mapNotNull { MediaNameAdapter.findChapterNumber(it.number)?.toInt() }
                ?.maxOrNull()
                ?: media.manga?.totalChapters
                ?: 0
            ani.dantotsu.connections.malsync.UnreadChapterInfo(
                mediaId = media.id,
                lastChapter = derivedLast,
                source = "",
                userProgress = media.userProgress ?: 0
            )
        }

        when (holder) {
            is CompactViewHolder -> bindCompactView(holder.binding, media, info)
            is LargeViewHolder -> bindLargeView(holder.binding, media, info)
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

                ContextCompat.startActivity(root.context, intent, options)
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
                val parsed = androidx.core.text.HtmlCompat.fromHtml(rawDesc, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                val synopsis = if (parsed.isNullOrBlank() || parsed == "null") root.context.getString(R.string.no_description_available) else parsed
                itemCompactSynopsis.text = synopsis
                itemCompactSynopsis.movementMethod = android.text.method.ScrollingMovementMethod()
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
            val sourceDisplay = info.source
                if (!sourceDisplay.isNullOrBlank()) {
                    // Show full source name in badge and hide icon
                    itemCompactLanguageIcon.visibility = View.GONE
                    itemCompactLanguageCode.text = sourceDisplay
                    itemCompactLanguageBG.visibility = View.VISIBLE
                    itemCompactType.visibility = View.GONE
                } else {
                    itemCompactType.visibility = View.GONE
                }

            // Show media status between title and synopsis when available
            itemCompactStatus.text = media.status ?: ""
            itemCompactStatus.visibility = if (!itemCompactStatus.text.isNullOrBlank()) View.VISIBLE else View.GONE

            // Hide ongoing indicator (not applicable here)
            itemCompactOngoing.visibility = View.GONE

            // Set score - divide by 10.0 to match Anilist format, fallback to mean score
            val score = if (media.userScore == 0) (media.meanScore ?: 0) else media.userScore
            if (score > 0) {
                itemCompactScore.text = (score / 10.0).toString()
                itemCompactScoreBG.background = ContextCompat.getDrawable(
                    root.context,
                    if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score
                )
                itemCompactScoreBG.visibility = View.VISIBLE
            } else {
                itemCompactScoreBG.visibility = View.GONE
            }

            // Handle click to open media details
            val clickAction = {
                ContextCompat.startActivity(
                    root.context,
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

    override fun getItemCount(): Int = mediaList.size
}

