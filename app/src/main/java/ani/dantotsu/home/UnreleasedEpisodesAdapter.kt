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
import ani.dantotsu.databinding.ItemUnreadChapterBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.setSafeOnClickListener

class UnreleasedEpisodesAdapter(
    private val mediaList: List<Media>,
    private val unreleasedInfo: Map<Int, ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo>,
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
        val info = unreleasedInfo[media.id] // May be null if no MALSync data

        when (holder) {
            is CompactViewHolder -> bindCompactView(holder.binding, media, info)
            is LargeViewHolder -> bindLargeView(holder.binding, media, info)
        }
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private fun bindCompactView(binding: ItemUnreadChapterBinding, media: Media, info: ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo?) {
        binding.apply {
            // Load cover image
            itemCompactImage.loadImage(media.cover)

            // Set title
            itemCompactTitle.text = media.userPreferredName

            // Set progress text
            itemCompactUserProgress.text = (media.userProgress ?: 0).toString()

            val totalEpisodes = media.anime?.totalEpisodes ?: "~"

            // Show MALSync lastEp only if info exists (meaning lastEp != total and other conditions)
            if (info != null) {
                itemCompactTotal.text = " | ${info.lastEpisode} | $totalEpisodes"
            } else {
                // Standard display without MALSync data
                itemCompactTotal.text = " | $totalEpisodes"
            }

            // Set source text (language) - hide if empty
            if (info != null && info.languageDisplay.isNotEmpty()) {
                itemCompactSource.text = info.languageDisplay
                itemCompactSource.visibility = View.VISIBLE
            } else {
                itemCompactSource.visibility = View.GONE
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
            root.setSafeOnClickListener {
                ContextCompat.startActivity(
                    it.context,
                    Intent(it.context, ani.dantotsu.media.MediaDetailsActivity::class.java)
                        .putExtra("media", media),
                    null
                )
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

            // Set progress info
            val userProgress = media.userProgress ?: 0
            val totalEpisodes = media.anime?.totalEpisodes ?: "~"

            // Show MALSync lastEp only if info exists
            if (info != null) {
                itemCompactTotal.text = "$userProgress | ${info.lastEpisode} | $totalEpisodes"
            } else {
                // Standard display without MALSync data
                itemCompactTotal.text = "$userProgress | $totalEpisodes"
            }
            itemTotal.text = ""

            // Show language info using the relation/type container - hide if empty
            if (info != null && info.languageDisplay.isNotEmpty()) {
                itemCompactType.visibility = View.VISIBLE
                itemCompactRelation.text = info.languageDisplay
                itemCompactTypeImage.visibility = View.GONE
            } else {
                itemCompactType.visibility = View.GONE
            }

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
            root.setSafeOnClickListener {
                ContextCompat.startActivity(
                    it.context,
                    Intent(it.context, ani.dantotsu.media.MediaDetailsActivity::class.java)
                        .putExtra("media", media),
                    null
                )
            }
        }
    }

    override fun getItemCount(): Int = mediaList.size
}
