package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import com.bumptech.glide.Glide
import ani.dantotsu.connections.mal.MALStack
import ani.dantotsu.connections.mal.MALQueries
import ani.dantotsu.connections.anilist.Anilist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StackAdapter(private val items: List<MALStack>, private val isAnime: Boolean) : RecyclerView.Adapter<StackAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val coversContainer: FrameLayout = view.findViewById(R.id.itemStackCoversContainer)
        val image: ImageView = view.findViewById(R.id.itemCompactImage)
        val title: TextView = view.findViewById(R.id.itemCompactTitle)
        val userProgress: TextView = view.findViewById(R.id.itemCompactUserProgress)
        val total: TextView? = view.findViewById(R.id.itemCompactTotal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stack_compact, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        // Populate stacked covers (up to 3) in container, newer covers on top
        val container = holder.coversContainer
        container.removeAllViews()
        val covers = item.covers
        val count = covers.size.coerceAtMost(3)
        val offsetDp = 12f
        val dm = ctx.resources.displayMetrics
        // Determine base width/height for compact container (fallback to 102dp/154dp)
        val baseW = if (container.width > 0) container.width else TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 102f, dm).toInt()
        val baseH = if (container.height > 0) container.height else TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 154f, dm).toInt()

        // Add from bottom (right) to top (left) so cover[0] becomes top/left
        for (i in count - 1 downTo 0) {
            val img = ImageView(ctx)
            val offsetPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, offsetDp, dm).toInt()
            // width reduces for top images so topmost shows left and underlying ones peek on the right
            val widthForIndex = baseW - ((count - 1 - i) * offsetPx)
            val lp = FrameLayout.LayoutParams(widthForIndex, baseH)
            lp.marginStart = i * offsetPx
            img.layoutParams = lp
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.elevation = (count - i).toFloat()
            val url = covers.getOrNull(i)
            if (!url.isNullOrEmpty()) Glide.with(ctx).load(url).centerCrop().into(img) else img.setImageResource(R.drawable.ic_round_collections_bookmark_24)
            container.addView(img)

            // If this is the topmost cover (i == 0), add a right-edge shadow overlay positioned at the top cover's right edge
            if (i == 0) {
                val overlay = View(ctx)
                val ovWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, dm).toInt()
                val ovLp = FrameLayout.LayoutParams(ovWidth, baseH)
                // position overlay so it straddles the right edge of the top cover (half over the top, half over the one beneath)
                val overlayMargin = i * offsetPx + (widthForIndex - ovWidth / 2)
                ovLp.marginStart = overlayMargin
                overlay.layoutParams = ovLp
                overlay.setBackgroundResource(R.drawable.stack_right_shadow)
                // ensure shadow sits beneath the top cover: set elevation just below the top image's elevation
                overlay.elevation = kotlin.math.max(0f, (count - i - 1).toFloat())
                overlay.isClickable = false
                container.addView(overlay)
                // do not bring to front; keep shadow under top cover
            }
        }
        holder.title.text = item.name
        holder.userProgress.text = item.entries.toString()
        holder.total?.text = ctx.getString(R.string.stack_entries_suffix)

        // Click: open an internal list view with AniList matches for the stack entries
        holder.itemView.setOnClickListener {
            val activity = ctx as? AppCompatActivity
            if (activity == null) {
                Toast.makeText(ctx, R.string.anilist_down, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
                activity.lifecycleScope.launch {
                Toast.makeText(ctx, "Loading stack...", Toast.LENGTH_SHORT).show()
                val entries = withContext(Dispatchers.IO) {
                    MALQueries().getStackEntries(item.url)
                }
                val malIds = entries.map { it.id }
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
                // attach MAL intro text to matched Media objects when available
                try {
                    for (m in fetched) {
                        val malId = m.idMAL
                        val entry = entries.find { it.id == malId }
                        if (entry != null) m.malIntro = entry.intro
                    }
                } catch (e: Exception) {
                    // ignore mapping errors
                }

                // Start MediaListViewActivity with AniList media
                MediaListViewActivity.passedMedia = ArrayList(fetched)
                val i = Intent(ctx, MediaListViewActivity::class.java)
                i.putExtra("title", item.name)
                ctx.startActivity(i)
            }
        }

        // Long click: open stack in browser
        holder.itemView.setOnLongClickListener {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
            } catch (e: Exception) {
                // ignore
            }
            true
        }
    }

    override fun getItemCount(): Int = items.size
}
