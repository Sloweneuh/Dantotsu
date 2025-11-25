package ani.dantotsu.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.databinding.ItemUnreadChapterBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.setSafeOnClickListener

class UnreadChaptersAdapter(
    private val mediaList: List<Media>,
    private val unreadInfo: Map<Int, UnreadChapterInfo>
) : RecyclerView.Adapter<UnreadChaptersAdapter.UnreadChapterViewHolder>() {

    inner class UnreadChapterViewHolder(val binding: ItemUnreadChapterBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UnreadChapterViewHolder {
        val binding = ItemUnreadChapterBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return UnreadChapterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UnreadChapterViewHolder, position: Int) {
        val media = mediaList[position]
        val info = unreadInfo[media.id] ?: return

        holder.binding.apply {
            // Load cover image
            itemCompactImage.loadImage(media.cover)

            // Set title
            itemCompactTitle.text = media.userPreferredName

            // Set progress text in format: progress | lastEp | totalChapters (or ~)
            itemCompactUserProgress.text = info.userProgress.toString()

            val totalChapters = media.manga?.totalChapters ?: "~"
            itemCompactTotal.text = " | ${info.lastChapter} | $totalChapters"

            // Set source text
            itemCompactSource.text = info.source

            // Set score if available
            if (media.userScore > 0) {
                itemCompactScore.text = media.userScore.toString()
                itemCompactScoreBG.visibility = android.view.View.VISIBLE
            } else {
                itemCompactScoreBG.visibility = android.view.View.GONE
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

