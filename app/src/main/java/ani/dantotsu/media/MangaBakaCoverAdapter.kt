package ani.dantotsu.media

import android.app.Dialog
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemComickCoverBinding
import ani.dantotsu.loadImage
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade

/** A single MangaBaka cover: a display thumbnail, the full-resolution URL, and an optional label. */
data class MangaBakaCover(val thumbUrl: String?, val fullUrl: String?, val label: String? = null)

/**
 * Displays MangaBaka cover images in a horizontal strip, with a full-screen swipeable viewer and a
 * grid gallery — mirrors [ComickCoverAdapter] but works off plain image URLs instead of Comick keys.
 */
class MangaBakaCoverAdapter(
    private val covers: List<MangaBakaCover>
) : RecyclerView.Adapter<MangaBakaCoverAdapter.ViewHolder>() {

    private class ZoomImageView(context: android.content.Context) : AppCompatImageView(context) {
        init {
            scaleType = ScaleType.FIT_CENTER
        }
    }

    inner class ViewHolder(val binding: ItemComickCoverBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemComickCoverBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val cover = covers[position]
        holder.binding.apply {
            val thumb = cover.thumbUrl ?: cover.fullUrl
            if (!thumb.isNullOrBlank()) {
                Glide.with(comickCoverImage.context)
                    .load(thumb)
                    .transition(withCrossFade())
                    .into(comickCoverImage)
            } else {
                comickCoverImage.setImageResource(R.drawable.ic_round_menu_book_24)
            }

            if (!cover.label.isNullOrBlank()) {
                comickCoverVolume.visibility = View.VISIBLE
                comickCoverVolume.text = cover.label
            } else {
                comickCoverVolume.visibility = View.GONE
            }

            root.setOnClickListener { showFullscreenCover(root, position) }
        }
    }

    override fun getItemCount(): Int = covers.size

    fun showGallery(anchor: View, title: String) {
        val context = anchor.context
        val dm = context.resources.displayMetrics

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurface, typedValue, true
            )
            val opaqueSurface = (typedValue.data and 0x00FFFFFF) or (0xFF shl 24)
            setBackgroundColor(opaqueSurface)
        }

        val titleBar = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = (16 * dm.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val titleView = TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, typedValue, true
            )
            setTextColor(typedValue.data)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val closeBtn = android.widget.ImageButton(context).apply {
            setImageResource(R.drawable.ic_round_close_24)
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, typedValue, true
            )
            setColorFilter(typedValue.data)
            background = null
            setOnClickListener { dialog.dismiss() }
        }
        titleBar.addView(titleView)
        titleBar.addView(closeBtn)
        root.addView(titleBar)

        val recyclerView = RecyclerView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            )
            val itemWidthDp = 143f
            val screenWidthDp = dm.widthPixels / dm.density
            val cols = (screenWidthDp / itemWidthDp).toInt().coerceAtLeast(2)
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, cols)
            adapter = MangaBakaCoverAdapter(covers)
            val totalContentPx = (cols * itemWidthDp * dm.density).toInt()
            val sidePx = ((dm.widthPixels - totalContentPx) / 2).coerceAtLeast(0)
            val bottomPad = (8 * dm.density).toInt()
            setPadding(sidePx, 0, sidePx, bottomPad)
            clipToPadding = false
        }
        root.addView(recyclerView)

        dialog.setContentView(root)
        dialog.window?.apply { setBackgroundDrawableResource(android.R.color.transparent) }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun showFullscreenCover(anchor: View, startIndex: Int) {
        val context = anchor.context
        val dm = context.resources.displayMetrics

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = FrameLayout(context).apply { setBackgroundColor(0xDD000000.toInt()) }

        val pager = androidx.viewpager2.widget.ViewPager2(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            offscreenPageLimit = 1
        }

        val counterBottomMargin = (24 * dm.density).toInt()
        val volBottomMargin = counterBottomMargin + (20 * dm.density).toInt()

        val counterView = TextView(context).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, counterBottomMargin)
            }
            text = "${startIndex + 1} / ${covers.size}"
        }

        val pagerAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val container = FrameLayout(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                val zoomView = ZoomImageView(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER
                    )
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                container.addView(zoomView)
                val volLabel = TextView(parent.context).apply {
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 16f
                    val pad = (12 * dm.density).toInt()
                    setPadding(pad, pad, pad, pad)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM
                    ).apply { bottomMargin = volBottomMargin }
                    gravity = android.view.Gravity.CENTER
                }
                container.addView(volLabel)
                return object : RecyclerView.ViewHolder(container) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val container = holder.itemView as FrameLayout
                val zoomView = container.getChildAt(0) as ZoomImageView
                val volLabel = container.getChildAt(1) as TextView
                val cover = covers[position]
                val url = cover.fullUrl ?: cover.thumbUrl
                if (!url.isNullOrBlank()) {
                    zoomView.loadImage(url)
                } else {
                    zoomView.setImageResource(R.drawable.ic_round_menu_book_24)
                }
                if (!cover.label.isNullOrBlank()) {
                    volLabel.visibility = View.VISIBLE
                    volLabel.text = cover.label
                } else {
                    volLabel.visibility = View.GONE
                }
            }

            override fun getItemCount(): Int = covers.size
        }

        pager.adapter = pagerAdapter
        pager.setCurrentItem(startIndex, false)

        val touchOverlay = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x00000000)
            setOnTouchListener { _, ev ->
                val rv = pager.getChildAt(0) as? RecyclerView
                val vh = rv?.findViewHolderForAdapterPosition(pager.currentItem)
                val image = (vh?.itemView as? ViewGroup)?.getChildAt(0) as? ZoomImageView
                if (image != null) {
                    val loc = IntArray(2)
                    image.getLocationOnScreen(loc)
                    val x = ev.rawX.toInt()
                    val y = ev.rawY.toInt()
                    val rect = Rect(loc[0], loc[1], loc[0] + image.width, loc[1] + image.height)
                    if (!rect.contains(x, y)) {
                        dialog.dismiss()
                        return@setOnTouchListener true
                    }
                    return@setOnTouchListener false
                } else {
                    dialog.dismiss()
                    return@setOnTouchListener true
                }
            }
        }

        root.addView(pager)
        root.addView(counterView)
        root.addView(touchOverlay)

        pager.registerOnPageChangeCallback(object :
            androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                counterView.text = "${position + 1} / ${covers.size}"
            }
        })

        dialog.setContentView(root)
        dialog.window?.apply { setBackgroundDrawableResource(android.R.color.transparent) }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
