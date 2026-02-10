package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.connections.mangaupdates.MUSearchResult
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.loadImage
import ani.dantotsu.setSafeOnClickListener

class MangaUpdatesSearchAdapter(
    private val results: List<MUSearchResult>,
    private val onItemClick: (MUSearchResult) -> Unit
) : RecyclerView.Adapter<MangaUpdatesSearchAdapter.ViewHolder>() {

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
        val series = results[position]
        holder.binding.apply {
            // Load cover image
            itemCompactImage.loadImage(series.record?.image?.url?.original)

            // Set title with marquee scrolling for long titles
            val title = series.record?.title ?: series.hit_title
            itemCompactTitle.text = title
            itemCompactTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
            itemCompactTitle.marqueeRepeatLimit = -1 // Infinite scroll
            itemCompactTitle.isSingleLine = true
            itemCompactTitle.isSelected = true // Enable marquee

            // Long click on title to show full title
            itemCompactTitle.setOnLongClickListener {
                title?.let { titleText ->
                    Toast.makeText(root.context, titleText, Toast.LENGTH_LONG).show()
                }
                true
            }

            // Long click on cover to open in browser
            itemCompactImage.setOnLongClickListener {
                series.record?.url?.let { url ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    root.context.startActivity(intent)
                }
                true
            }

            // Hide score and ongoing indicators
            itemCompactScoreBG.visibility = android.view.View.GONE
            itemCompactOngoing.visibility = android.view.View.GONE
            itemCompactType.visibility = android.view.View.GONE

            // Set click listener
            root.setSafeOnClickListener {
                onItemClick(series)
            }
        }
    }

    override fun getItemCount(): Int = results.size
}



