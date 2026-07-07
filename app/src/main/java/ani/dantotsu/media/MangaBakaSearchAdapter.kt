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
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.databinding.ItemMediaLargeBinding
import ani.dantotsu.setSafeOnClickListener
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade

class MangaBakaSearchAdapter(
    private val results: List<MangaBakaApi.Series>,
    var type: Int = 0,
    private val onItemClick: (MangaBakaApi.Series) -> Unit
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
        val series = results[position]
        when (holder) {
            is LargeViewHolder -> bindLarge(holder.binding, series)
            is CompactViewHolder -> bindCompact(holder.binding, series)
        }
    }

    private fun loadCover(imageView: android.widget.ImageView, series: MangaBakaApi.Series) {
        val thumb = series.cover?.thumbUrl() ?: return
        Glide.with(imageView.context)
            .load(thumb)
            .transition(withCrossFade())
            .into(imageView)
    }

    private fun openInBrowser(series: MangaBakaApi.Series, view: View) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mangabaka.org/series/${series.id}"))
        view.context.startActivity(intent)
    }

    private fun bindCompact(b: ItemMediaCompactBinding, series: MangaBakaApi.Series) {
        loadCover(b.itemCompactImage, series)

        b.itemCompactTitle.text = series.title
        b.itemCompactTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
        b.itemCompactTitle.marqueeRepeatLimit = -1
        b.itemCompactTitle.isSingleLine = true
        b.itemCompactTitle.isSelected = true

        b.itemCompactScoreBG.visibility = View.GONE
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE

        b.root.setSafeOnClickListener { onItemClick(series) }
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
        b.itemCompactImage.setOnLongClickListener { openInBrowser(series, b.root); true }
        b.root.setOnLongClickListener { openInBrowser(series, b.root); true }
    }

    private fun bindLarge(b: ItemMediaLargeBinding, series: MangaBakaApi.Series) {
        loadCover(b.itemCompactImage, series)
        loadCover(b.itemCompactBanner, series)

        b.itemCompactTitle.text = series.title
        b.itemCompactTitle.maxLines = 3

        b.itemCompactStatus.text = when (series.status?.lowercase()) {
            "releasing" -> "ONGOING"
            "completed" -> "COMPLETED"
            "cancelled" -> "CANCELLED"
            "hiatus" -> "HIATUS"
            "upcoming" -> "UPCOMING"
            else -> ""
        }
        b.itemCompactStatus.visibility =
            if (b.itemCompactStatus.text.isNotBlank()) View.VISIBLE else View.GONE

        val rawDesc = series.description ?: ""
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

        b.itemContainer.setSafeOnClickListener { onItemClick(series) }
        b.itemContainer.setOnLongClickListener { openInBrowser(series, b.root); true }
    }

    override fun getItemCount(): Int = results.size
}
