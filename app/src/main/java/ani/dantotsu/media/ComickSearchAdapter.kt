package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickComic
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.loadImage
import ani.dantotsu.setSafeOnClickListener

class ComickSearchAdapter(
    private val results: List<ComickComic>,
    private val onItemClick: (ComickComic) -> Unit
) : RecyclerView.Adapter<ComickSearchAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemMediaCompactBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMediaCompactBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val comic = results[position]
        holder.binding.apply {
            // Load cover image from Comick
            val coverUrl = comic.md_covers?.firstOrNull()?.b2key?.let { b2key ->
                "https://meo.comick.pictures/$b2key"
            }

            if (coverUrl != null) {
                itemCompactImage.loadImage(coverUrl)
            } else {
                // Fallback to manga icon if no cover available
                itemCompactImage.setImageResource(R.drawable.ic_round_menu_book_24)
            }

            // Set title with marquee scrolling for long titles
            itemCompactTitle.text = comic.title
            itemCompactTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
            itemCompactTitle.marqueeRepeatLimit = -1 // Infinite scroll
            itemCompactTitle.isSingleLine = true
            itemCompactTitle.isSelected = true // Enable marquee

            // Title tap: select result
            itemCompactTitle.setSafeOnClickListener {
                comic.slug?.let { slug ->
                    onItemClick(comic)
                }
            }

            // Title long tap: toggle between marquee scrolling and full title display
            itemCompactTitle.setOnLongClickListener {
                if (itemCompactTitle.isSingleLine) {
                    // Switch to full title display
                    itemCompactTitle.isSingleLine = false
                    itemCompactTitle.ellipsize = null
                    itemCompactTitle.maxLines = Int.MAX_VALUE
                } else {
                    // Switch back to marquee scrolling
                    itemCompactTitle.isSingleLine = true
                    itemCompactTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
                    itemCompactTitle.isSelected = true
                }
                true
            }

            // Cover tap: select result
            itemCompactImage.setSafeOnClickListener {
                comic.slug?.let { slug ->
                    onItemClick(comic)
                }
            }

            // Cover long tap: open in browser
            itemCompactImage.setOnLongClickListener {
                comic.slug?.let { slug ->
                    val url = "https://comick.io/comic/$slug"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    root.context.startActivity(intent)
                }
                true
            }

            // Hide score and ongoing indicators
            itemCompactScoreBG.visibility = android.view.View.GONE
            itemCompactOngoing.visibility = android.view.View.GONE
            itemCompactType.visibility = android.view.View.GONE
        }
    }

    override fun getItemCount(): Int = results.size
}







