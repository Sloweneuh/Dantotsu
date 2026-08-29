package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.kitsu.KitsuApi
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.databinding.ItemMediaLargeBinding
import ani.dantotsu.setSafeOnClickListener
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade

class KitsuSearchAdapter(
    private val results: List<KitsuApi.Item>,
    private val isAnime: Boolean,
    var type: Int = 0,
    private val onItemClick: (KitsuApi.Item) -> Unit
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
        val item = results[position]
        when (holder) {
            is LargeViewHolder -> bindLarge(holder.binding, item)
            is CompactViewHolder -> bindCompact(holder.binding, item)
        }
    }

    private fun title(item: KitsuApi.Item): String =
        item.media.canonicalTitle
            ?: item.media.titles?.values?.firstOrNull { !it.isNullOrBlank() }
            ?: ""

    private fun poster(item: KitsuApi.Item): String? =
        item.media.posterImage?.medium
            ?: item.media.posterImage?.original
            ?: item.media.posterImage?.small

    private fun cover(item: KitsuApi.Item): String? =
        item.media.coverImage?.original ?: item.media.coverImage?.medium ?: poster(item)

    private fun loadImage(imageView: android.widget.ImageView, url: String?) {
        url ?: return
        Glide.with(imageView.context).load(url).transition(withCrossFade()).into(imageView)
    }

    private fun openInBrowser(item: KitsuApi.Item, view: View) {
        val kind = if (isAnime) "anime" else "manga"
        view.context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("${KitsuApi.WEB_URL}/$kind/${item.media.slug ?: item.id}"))
        )
    }

    private fun bindCompact(b: ItemMediaCompactBinding, item: KitsuApi.Item) {
        loadImage(b.itemCompactImage, poster(item))

        b.itemCompactTitle.text = title(item)
        b.itemCompactTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
        b.itemCompactTitle.marqueeRepeatLimit = -1
        b.itemCompactTitle.isSingleLine = true
        b.itemCompactTitle.isSelected = true

        val rating = item.media.averageRating?.toDoubleOrNull()?.let { it / 10.0 }
        if (rating != null) {
            b.itemCompactScoreBG.visibility = View.VISIBLE
            b.itemCompactScore.text = String.format("%.1f", rating)
        } else {
            b.itemCompactScoreBG.visibility = View.GONE
        }
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE
        b.itemCompactProgressContainer.visibility = View.GONE

        b.root.setSafeOnClickListener { onItemClick(item) }
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
        b.itemCompactImage.setOnLongClickListener { openInBrowser(item, b.root); true }
        b.root.setOnLongClickListener { openInBrowser(item, b.root); true }
    }

    private fun bindLarge(b: ItemMediaLargeBinding, item: KitsuApi.Item) {
        loadImage(b.itemCompactImage, poster(item))
        loadImage(b.itemCompactBanner, cover(item))

        b.itemCompactTitle.text = title(item)
        b.itemCompactTitle.maxLines = 3

        b.itemCompactStatus.text = when (item.media.status?.lowercase()) {
            "current" -> "RELEASING"
            "finished" -> "FINISHED"
            "tba" -> "TBA"
            "unreleased" -> "UNRELEASED"
            "upcoming" -> "UPCOMING"
            else -> ""
        }
        b.itemCompactStatus.visibility =
            if (b.itemCompactStatus.text.isNotBlank()) View.VISIBLE else View.GONE

        val rawDesc = item.media.synopsis ?: item.media.description ?: ""
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

        val rating = item.media.averageRating?.toDoubleOrNull()?.let { it / 10.0 }
        if (rating != null) {
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

        b.itemContainer.setSafeOnClickListener { onItemClick(item) }
        b.itemContainer.setOnLongClickListener { openInBrowser(item, b.root); true }
    }

    override fun getItemCount(): Int = results.size
}
