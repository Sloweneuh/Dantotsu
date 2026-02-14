package ani.dantotsu.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.databinding.ItemMediaLargeBinding
import ani.dantotsu.databinding.ItemUnreleasedEpisodeBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.setSafeOnClickListener

class UnreleasedEpisodesAdapter(
    private val mediaList: List<Media>,
    private val unreleasedInfo: Map<Int, ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo>,
    private var type: Int = 0 // 0 = grid/compact, 1 = list/large
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    inner class CompactViewHolder(val binding: ItemUnreleasedEpisodeBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class LargeViewHolder(val binding: ItemMediaLargeBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            0 -> CompactViewHolder(
                ItemUnreleasedEpisodeBinding.inflate(
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
        val info = unreleasedInfo[media.id] // May be null if no MALSync data

        when (holder) {
            is CompactViewHolder -> bindCompactView(holder.binding, media, info)
            is LargeViewHolder -> bindLargeView(holder.binding, media, info)
        }
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private fun bindCompactView(binding: ItemUnreleasedEpisodeBinding, media: Media, info: ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo?) {
        binding.apply {
            // Load cover image
            itemCompactImage.loadImage(media.cover)

            // Set title
            itemCompactTitle.text = media.userPreferredName

            // Set progress text
            itemCompactUserProgress.text = (media.userProgress ?: 0).toString()

            val totalEpisodes = media.anime?.totalEpisodes

            // Don't show MALSync episode count if it matches the total (all episodes released)
            if (info != null) {
                if (totalEpisodes != null && info.lastEpisode == totalEpisodes) {
                    // Don't show middle number when language episode count matches total
                    itemCompactTotal.text = " | $totalEpisodes"
                } else {
                    // Show language episode count when it differs from total
                    itemCompactTotal.text = " | ${info.lastEpisode} | ${totalEpisodes ?: "~"}"
                }
            } else {
                // Standard display without MALSync data
                itemCompactTotal.text = " | ${totalEpisodes ?: "~"}"
            }

            // Set language badge with icon and code - hide if empty
            if (info != null && info.languageDisplay.isNotEmpty()) {
                val languageOption = ani.dantotsu.connections.malsync.LanguageMapper.mapLanguage(info.languageId)
                itemCompactLanguageIcon.setImageResource(languageOption.iconRes)
                // Extract short language code (e.g., "EN" from "English", or first 2 chars from languageId)
                val languageCode = when {
                    info.languageId.startsWith("en") -> "EN"
                    info.languageId.startsWith("ja") || info.languageId.startsWith("jp") -> "JP"
                    info.languageId.startsWith("de") -> "DE"
                    info.languageId.startsWith("fr") -> "FR"
                    info.languageId.startsWith("es") -> "ES"
                    info.languageId.startsWith("pt") -> "PT"
                    info.languageId.startsWith("it") -> "IT"
                    info.languageId.startsWith("ru") -> "RU"
                    info.languageId.startsWith("ar") -> "AR"
                    info.languageId.startsWith("zh") -> "ZH"
                    info.languageId.startsWith("ko") -> "KO"
                    info.languageId.startsWith("id") -> "ID"
                    info.languageId.startsWith("ms") -> "MS"
                    info.languageId.startsWith("th") -> "TH"
                    info.languageId.startsWith("vi") -> "VI"
                    else -> info.languageId.split("/")[0].take(2).uppercase()
                }
                itemCompactLanguageCode.text = languageCode
                itemCompactLanguageBG.visibility = View.VISIBLE
            } else {
                itemCompactLanguageBG.visibility = View.GONE
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

            // Show the "currently airing" indicator only for anime with status RELEASING
            if (media.status == root.context.getString(R.string.status_releasing)) {
                itemCompactScoreContainer.visibility = View.VISIBLE
            } else {
                itemCompactScoreContainer.visibility = View.GONE
            }

            // Handle click to open media details
            val clickAction = {
                ContextCompat.startActivity(
                    root.context,
                    Intent(root.context, ani.dantotsu.media.MediaDetailsActivity::class.java)
                        .putExtra("media", media),
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

    @android.annotation.SuppressLint("SetTextI18n")
    private fun bindLargeView(binding: ItemMediaLargeBinding, media: Media, info: ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo?) {
        binding.apply {
            // Load cover and banner images
            itemCompactImage.loadImage(media.cover)
            blurImage(itemCompactBanner, media.banner ?: media.cover)

            // Set title
            itemCompactTitle.text = media.userPreferredName

            // Set progress info: userProgress / (lastEpisode | totalEpisodes)
            val userProgress = media.userProgress ?: 0
            val totalEpisodes = media.anime?.totalEpisodes
            itemUserProgressLarge.text = userProgress.toString()
            itemProgressSeparator.visibility = View.VISIBLE

            // Don't show MALSync episode count if it matches the total (all episodes released)
            if (info != null) {
                if (totalEpisodes != null && info.lastEpisode == totalEpisodes) {
                    // Only show total
                    itemCompactTotal.text = (totalEpisodes ?: "~").toString()
                } else {
                    // Show language episode count when it differs from total
                    itemCompactTotal.text = "${info.lastEpisode} | ${totalEpisodes ?: "~"}"
                }
            } else {
                // Standard display without MALSync data
                itemCompactTotal.text = (totalEpisodes ?: "~").toString()
            }
            itemTotal.text = ""

            // Synopsis preview (strip HTML) and make it scrollable
            try {
                val synopsis = androidx.core.text.HtmlCompat.fromHtml(media.description ?: "", androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
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
                itemCompactSynopsis.text = ""
                itemCompactSynopsis.scrollTo(0, 0)
            }

            // Show language badge on top of cover (icon + short code) when available
            if (info != null && info.languageDisplay.isNotEmpty()) {
                val languageOption = ani.dantotsu.connections.malsync.LanguageMapper.mapLanguage(info.languageId)
                itemCompactLanguageBG.visibility = View.VISIBLE
                itemCompactLanguageIcon.setImageResource(languageOption.iconRes)
                val languageCode = when {
                    info.languageId.startsWith("en") -> "EN"
                    info.languageId.startsWith("ja") || info.languageId.startsWith("jp") -> "JP"
                    info.languageId.startsWith("de") -> "DE"
                    info.languageId.startsWith("fr") -> "FR"
                    info.languageId.startsWith("es") -> "ES"
                    info.languageId.startsWith("pt") -> "PT"
                    info.languageId.startsWith("it") -> "IT"
                    info.languageId.startsWith("ru") -> "RU"
                    info.languageId.startsWith("ar") -> "AR"
                    info.languageId.startsWith("zh") -> "ZH"
                    info.languageId.startsWith("ko") -> "KO"
                    info.languageId.startsWith("id") -> "ID"
                    info.languageId.startsWith("ms") -> "MS"
                    info.languageId.startsWith("th") -> "TH"
                    info.languageId.startsWith("vi") -> "VI"
                    else -> info.languageId.split("/")[0].take(2).uppercase()
                }
                itemCompactLanguageCode.text = languageCode
                // Hide relation/type column to avoid duplication
                itemCompactType.visibility = View.GONE
            } else {
                itemCompactLanguageBG.visibility = View.GONE
            }

            // Hide ongoing indicator (not applicable here)
            itemCompactOngoing.visibility = View.GONE

            // Show media status between title and synopsis when available
            itemCompactStatus.text = media.status ?: ""
            itemCompactStatus.visibility = if (!itemCompactStatus.text.isNullOrBlank()) View.VISIBLE else View.GONE

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
                        .putExtra("media", media),
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
