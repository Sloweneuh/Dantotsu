package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickListComic
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.loadImage
import ani.dantotsu.setSafeOnClickListener

class ComickListComicAdapter(
    private val onItemClick: (ComickListComic) -> Unit
) : RecyclerView.Adapter<ComickListComicAdapter.ViewHolder>() {

    private val items = mutableListOf<ComickListComic>()

    inner class ViewHolder(val binding: ItemMediaCompactBinding) : RecyclerView.ViewHolder(binding.root)

    fun appendItems(newItems: List<ComickListComic>) {
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val comic = items[position]
        val b = holder.binding

        val b2key = comic.md_covers?.firstOrNull()?.b2key
        val thumbUrl = b2key?.let { key ->
            val lastDot = key.lastIndexOf('.')
            val thumbKey = if (lastDot > 0) key.substring(0, lastDot) + "-s" + key.substring(lastDot) else "$key-s"
            "https://meo.comick.pictures/$thumbKey"
        }
        if (thumbUrl != null) b.itemCompactImage.loadImage(thumbUrl)
        else b.itemCompactImage.setImageResource(R.drawable.ic_round_menu_book_24)

        // Regular tap on image opens comic; long-tap opens in browser
        b.itemCompactImage.setSafeOnClickListener { onItemClick(comic) }
        b.itemCompactImage.setOnLongClickListener {
            val slug = comic.slug
            if (!slug.isNullOrBlank()) {
                b.root.context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://comick.dev/comic/$slug"))
                )
            }
            true
        }

        val englishTitle = comic.md_titles
            ?.firstOrNull { it.lang?.equals("en", ignoreCase = true) == true && !it.title.isNullOrBlank() }
            ?.title
        b.itemCompactTitle.text = englishTitle ?: comic.title ?: b.root.context.getString(R.string.unknown)
        b.itemCompactTitle.maxLines = 2
        // onClick on title so regular taps aren't swallowed by the long-click listener
        b.itemCompactTitle.setSafeOnClickListener { onItemClick(comic) }
        b.itemCompactTitle.setOnLongClickListener {
            b.itemCompactTitle.maxLines = if (b.itemCompactTitle.maxLines == 2) Int.MAX_VALUE else 2
            true
        }

        val lastCh = comic.last_chapter
        if (lastCh != null && lastCh > 0) {
            val chStr = if (lastCh == lastCh.toLong().toDouble()) lastCh.toLong().toString() else lastCh.toString()
            b.itemCompactProgressContainer.visibility = View.VISIBLE
            b.itemCompactUserProgress.text = chStr
            b.itemCompactTotal.text = " ${b.root.context.getString(R.string.comick_chapters_count)}"
            b.itemCompactTotal.visibility = View.VISIBLE
        } else {
            b.itemCompactProgressContainer.visibility = View.GONE
        }

        b.itemCompactScoreBG.visibility = View.GONE
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE

        b.root.setSafeOnClickListener { onItemClick(comic) }
    }
}
