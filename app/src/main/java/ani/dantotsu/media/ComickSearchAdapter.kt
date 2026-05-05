package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickComic
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.databinding.ItemMediaLargeBinding
import ani.dantotsu.loadImage
import ani.dantotsu.setSafeOnClickListener

class ComickSearchAdapter(
    private val results: List<ComickComic>,
    var type: Int = 0,
    private val onItemClick: (ComickComic) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    inner class CompactViewHolder(val binding: ItemMediaCompactBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class LargeViewHolder(val binding: ItemMediaLargeBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            1 -> LargeViewHolder(
                ItemMediaLargeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            else -> CompactViewHolder(
                ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val comic = results[position]
        when (holder) {
            is LargeViewHolder -> bindLarge(holder.binding, comic)
            is CompactViewHolder -> bindCompact(holder.binding, comic)
        }
    }

    private fun coverUrl(comic: ComickComic): String? =
        comic.md_covers?.firstOrNull()?.b2key?.let { "https://meo.comick.pictures/$it" }

    private fun openInBrowser(comic: ComickComic, view: View) {
        comic.slug?.let { slug ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://comick.io/comic/$slug"))
            view.context.startActivity(intent)
        }
    }

    private fun bindCompact(b: ItemMediaCompactBinding, comic: ComickComic) {
        val url = coverUrl(comic)
        if (url != null) b.itemCompactImage.loadImage(url)
        else b.itemCompactImage.setImageResource(R.drawable.ic_round_menu_book_24)

        b.itemCompactTitle.text = comic.title
        b.itemCompactTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
        b.itemCompactTitle.marqueeRepeatLimit = -1
        b.itemCompactTitle.isSingleLine = true
        b.itemCompactTitle.isSelected = true

        b.itemCompactScoreBG.visibility = View.GONE
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE

        b.root.setSafeOnClickListener {
            comic.slug?.let { onItemClick(comic) }
                ?: Toast.makeText(b.root.context, R.string.error_loading_data, Toast.LENGTH_SHORT).show()
        }
        b.itemCompactTitle.setSafeOnClickListener { b.root.performClick() }
        b.itemCompactTitle.setOnLongClickListener {
            if (b.itemCompactTitle.isSingleLine) {
                b.itemCompactTitle.isSingleLine = false
                b.itemCompactTitle.ellipsize = null
                b.itemCompactTitle.maxLines = Int.MAX_VALUE
            } else {
                b.itemCompactTitle.isSingleLine = true
                b.itemCompactTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
                b.itemCompactTitle.isSelected = true
            }
            true
        }
        b.itemCompactImage.setSafeOnClickListener { b.root.performClick() }
        b.itemCompactImage.setOnLongClickListener { openInBrowser(comic, b.root); true }
        b.root.setOnLongClickListener { openInBrowser(comic, b.root); true }
    }

    private fun bindLarge(b: ItemMediaLargeBinding, comic: ComickComic) {
        val url = coverUrl(comic)
        if (url != null) {
            b.itemCompactImage.loadImage(url)
            b.itemCompactBanner.loadImage(url)
        } else {
            b.itemCompactImage.setImageResource(R.drawable.ic_round_menu_book_24)
        }

        b.itemCompactTitle.text = comic.title
        b.itemCompactTitle.maxLines = 3

        b.itemCompactStatus.text = when (comic.status) {
            1 -> "ONGOING"
            2 -> "COMPLETED"
            3 -> "CANCELLED"
            4 -> "HIATUS"
            else -> ""
        }
        b.itemCompactStatus.visibility =
            if (b.itemCompactStatus.text.isNotBlank()) View.VISIBLE else View.GONE

        val rawDesc = comic.desc ?: ""
        b.itemCompactSynopsis.text = if (rawDesc.isBlank()) {
            b.root.context.getString(R.string.no_description_available)
        } else {
            HtmlCompat.fromHtml(rawDesc, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
        b.itemCompactSynopsis.movementMethod = LinkMovementMethod.getInstance()
        b.itemCompactSynopsis.scrollTo(0, 0)
        b.itemCompactSynopsis.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        b.itemCompactScoreBG.visibility = View.GONE
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE
        b.itemInfoButton.visibility = View.GONE

        b.itemUserProgressLarge.visibility = View.GONE
        b.itemProgressSeparator.visibility = View.GONE
        b.itemCompactTotal.visibility = View.GONE
        b.itemTotal.visibility = View.GONE

        b.itemContainer.setSafeOnClickListener {
            comic.slug?.let { onItemClick(comic) }
                ?: Toast.makeText(b.root.context, R.string.error_loading_data, Toast.LENGTH_SHORT).show()
        }
        b.itemContainer.setOnLongClickListener { openInBrowser(comic, b.root); true }
    }

    override fun getItemCount(): Int = results.size
}
