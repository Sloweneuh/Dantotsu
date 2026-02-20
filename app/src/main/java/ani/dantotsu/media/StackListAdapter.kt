package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.mal.MALStack
import ani.dantotsu.connections.mal.MALQueries
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ItemStackLargeBinding
import ani.dantotsu.getAppString
import ani.dantotsu.loadImage
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.FrameLayout
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StackListAdapter(private val items: List<MALStack>, private val isAnime: Boolean) : RecyclerView.Adapter<StackListAdapter.VH>() {

    inner class VH(val binding: ItemStackLargeBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos in items.indices) {
                    val item = items[pos]
                    val ctx = binding.root.context
                    val activity = ctx as? AppCompatActivity
                    if (activity == null) {
                        Toast.makeText(ctx, getAppString(R.string.anilist_down), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    activity.lifecycleScope.launch {
                        Toast.makeText(ctx, "Loading stack...", Toast.LENGTH_SHORT).show()
                        val malIds = withContext(Dispatchers.IO) {
                            MALQueries().getStackEntries(item.url)
                        }
                        if (malIds.isEmpty()) {
                            Toast.makeText(ctx, "No entries found", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val fetched = withContext(Dispatchers.IO) {
                            try {
                                Anilist.query.getMediaBatch(malIds, mal = true, mediaType = if (isAnime) "ANIME" else "MANGA")
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                        if (fetched.isEmpty()) {
                            Toast.makeText(ctx, "No AniList matches found", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        MediaListViewActivity.passedMedia = ArrayList(fetched)
                        val i = Intent(ctx, MediaListViewActivity::class.java)
                        i.putExtra("title", item.name)
                        ctx.startActivity(i)
                    }
                }
            }

            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos in items.indices) {
                    val item = items[pos]
                    try {
                        binding.root.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                    } catch (e: Exception) {
                        // ignore
                    }
                }
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemStackLargeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        // Populate stacked covers in container and set banner
        val container = b.root.findViewById<android.widget.FrameLayout>(R.id.itemStackCoversContainer)
        container?.removeAllViews()
        val covers = item.covers
        val count = covers.size.coerceAtMost(3)
        val offsetDp = 12f
        val dm = b.root.context.resources.displayMetrics
        // Determine base width/height for large container (fallback to 108dp/160dp)
        val baseW = container?.width ?: TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 108f, dm).toInt()
        val baseH = container?.height ?: TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 160f, dm).toInt()

        // Add from bottom (right) to top (left) so cover[0] becomes top/left
        for (i in count - 1 downTo 0) {
            val img = android.widget.ImageView(b.root.context)
            val offsetPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, offsetDp, dm).toInt()
            val widthForIndex = baseW - ((count - 1 - i) * offsetPx)
            val lp = android.widget.FrameLayout.LayoutParams(widthForIndex, baseH)
            lp.marginStart = i * offsetPx
            img.layoutParams = lp
            img.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            img.elevation = (count - i).toFloat()
            val url = covers.getOrNull(i)
            if (!url.isNullOrEmpty()) Glide.with(b.root.context).load(url).centerCrop().into(img) else img.setImageResource(R.drawable.ic_round_collections_bookmark_24)
            container?.addView(img)

            // If this is the topmost cover (i == 0), add a right-edge shadow overlay positioned at the top cover's right edge
            if (i == 0) {
                val overlay = android.view.View(b.root.context)
                val ovWidth = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f, dm).toInt()
                val ovLp = android.widget.FrameLayout.LayoutParams(ovWidth, baseH)
                // position overlay so it straddles the right edge of the top cover (half over the top, half over the one beneath)
                val overlayMargin = i * offsetPx + (widthForIndex - ovWidth / 2)
                ovLp.marginStart = overlayMargin
                overlay.layoutParams = ovLp
                overlay.setBackgroundResource(R.drawable.stack_right_shadow)
                // ensure shadow sits beneath the top cover: set elevation just below the top image's elevation
                overlay.elevation = kotlin.math.max(0f, (count - i - 1).toFloat())
                overlay.isClickable = false
                container?.addView(overlay)
                // do not bring to front; keep shadow under top cover
            }
        }
        // banner still uses first cover
        b.itemCompactBanner.loadImage(item.covers.firstOrNull())
        // title
        b.itemCompactTitle.text = item.name
        // synopsis / description (show placeholder when blank and enable scrolling)
        val desc = if (item.description.isNullOrBlank()) b.root.context.getString(R.string.no_description_available) else item.description
        b.itemCompactSynopsis.text = desc
        b.itemCompactSynopsis.movementMethod = ScrollingMovementMethod()
        // Allow inner TextView to handle vertical scroll inside RecyclerView
        b.itemCompactSynopsis.setOnTouchListener { v, ev ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            if (ev.action == MotionEvent.ACTION_UP) v.parent?.requestDisallowInterceptTouchEvent(false)
            false
        }
        // entries -> user progress large
        b.itemUserProgressLarge.text = item.entries.toString()
        b.itemCompactTotal.text = b.root.context.getString(R.string.stack_entries_suffix)
        b.itemCompactType.visibility = android.view.View.GONE
    }

    override fun getItemCount(): Int = items.size
}
