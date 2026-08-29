package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.connections.simkl.SimklApi
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.databinding.ItemMediaLargeBinding
import ani.dantotsu.setSafeOnClickListener
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade

class SimklSearchAdapter(
    private val results: List<SimklApi.SimklMedia>,
    var type: Int = 0,
    private val onItemClick: (SimklApi.SimklMedia) -> Unit
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
        val media = results[position]
        when (holder) {
            is LargeViewHolder -> bindLarge(holder.binding, media)
            is CompactViewHolder -> bindCompact(holder.binding, media)
        }
    }

    private fun title(media: SimklApi.SimklMedia): String =
        media.title ?: media.titleRomaji ?: ""

    private fun loadImage(imageView: android.widget.ImageView, media: SimklApi.SimklMedia) {
        val url = SimklApi.posterUrl(media.poster) ?: return
        Glide.with(imageView.context).load(url).transition(withCrossFade()).into(imageView)
    }

    private fun openInBrowser(media: SimklApi.SimklMedia, view: View) {
        val id = media.simklId ?: return
        view.context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://simkl.com/anime/$id"))
        )
    }

    private fun bindCompact(b: ItemMediaCompactBinding, media: SimklApi.SimklMedia) {
        loadImage(b.itemCompactImage, media)

        b.itemCompactTitle.text = title(media)
        b.itemCompactTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
        b.itemCompactTitle.marqueeRepeatLimit = -1
        b.itemCompactTitle.isSingleLine = true
        b.itemCompactTitle.isSelected = true

        val rating = media.ratings?.simkl?.rating
        if (rating != null && rating > 0) {
            b.itemCompactScoreBG.visibility = View.VISIBLE
            b.itemCompactScore.text = String.format("%.1f", rating)
        } else {
            b.itemCompactScoreBG.visibility = View.GONE
        }
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE
        b.itemCompactProgressContainer.visibility = View.GONE

        b.root.setSafeOnClickListener { onItemClick(media) }
        b.itemCompactTitle.setSafeOnClickListener { b.root.performClick() }
        b.itemCompactImage.setSafeOnClickListener { b.root.performClick() }
        b.itemCompactImage.setOnLongClickListener { openInBrowser(media, b.root); true }
        b.root.setOnLongClickListener { openInBrowser(media, b.root); true }
    }

    private fun bindLarge(b: ItemMediaLargeBinding, media: SimklApi.SimklMedia) {
        loadImage(b.itemCompactImage, media)
        loadImage(b.itemCompactBanner, media)

        b.itemCompactTitle.text = title(media)
        b.itemCompactTitle.maxLines = 3

        b.itemCompactStatus.text = listOfNotNull(
            media.animeType?.uppercase(),
            media.year?.toString(),
        ).joinToString(" • ")
        b.itemCompactStatus.visibility =
            if (b.itemCompactStatus.text.isNotBlank()) View.VISIBLE else View.GONE

        b.itemCompactSynopsis.visibility = View.GONE

        val rating = media.ratings?.simkl?.rating
        if (rating != null && rating > 0) {
            b.itemCompactScoreBG.visibility = View.VISIBLE
            b.itemCompactScore.text = String.format("%.1f", rating)
        } else {
            b.itemCompactScoreBG.visibility = View.GONE
        }
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE
        b.itemInfoButton.visibility = View.GONE

        b.itemUserProgressLarge.visibility = View.GONE
        b.itemProgressSeparator.visibility = View.GONE
        b.itemCompactTotal.visibility = View.GONE
        b.itemTotal.visibility = View.GONE

        b.itemContainer.setSafeOnClickListener { onItemClick(media) }
        b.itemContainer.setOnLongClickListener { openInBrowser(media, b.root); true }
    }

    override fun getItemCount(): Int = results.size
}
