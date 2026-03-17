package ani.dantotsu.media

import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Rect
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickCover
import ani.dantotsu.databinding.ItemComickCoverBinding
import ani.dantotsu.loadImage
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
// removed zoom gesture imports (zoom controls disabled)
import androidx.appcompat.widget.AppCompatImageView

class ComickCoverAdapter(
    private val covers: List<ComickCover>
) : RecyclerView.Adapter<ComickCoverAdapter.ViewHolder>() {

    /** Simple ImageView with pinch-to-zoom and pan support using Matrix and ScaleGestureDetector. */
    // Zoom controls removed: plain image view that lets ViewPager handle gestures
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
        // Display covers in reverse order: oldest first
        val cover = covers[covers.size - 1 - position]
        holder.binding.apply {
            val b2key = cover.b2key
            if (!b2key.isNullOrBlank()) {
                val thumbUrl = buildThumbUrl(b2key)
                val fullUrl = "https://meo.comick.pictures/$b2key"
                Glide.with(comickCoverImage.context)
                    .load(thumbUrl)
                    .transition(withCrossFade())
                    .error(
                        Glide.with(comickCoverImage.context)
                            .load(fullUrl)
                            .transition(withCrossFade())
                    )
                    .into(comickCoverImage)
            } else {
                comickCoverImage.setImageResource(R.drawable.ic_round_menu_book_24)
            }

            if (!cover.vol.isNullOrBlank()) {
                comickCoverVolume.visibility = View.VISIBLE
                comickCoverVolume.text = cover.vol
            } else {
                comickCoverVolume.visibility = View.GONE
            }

            root.setOnClickListener {
                if (!b2key.isNullOrBlank()) {
                    showFullscreenCover(root, position)
                }
            }
        }
    }

    override fun getItemCount(): Int = covers.size

    /** Converts a b2key like "GXZal7.jpg" into its small-thumbnail variant "GXZal7-s.jpg". */
    private fun buildThumbUrl(b2key: String): String {
        val dotIdx = b2key.lastIndexOf('.')
        return if (dotIdx >= 0) {
            val base = b2key.substring(0, dotIdx)
            val ext = b2key.substring(dotIdx)
            "https://meo.comick.pictures/${base}-s${ext}"
        } else {
            "https://meo.comick.pictures/$b2key"
        }
    }

    fun showGallery(anchor: View, title: String) {
        val context = anchor.context
        val dm = context.resources.displayMetrics

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Root layout (gallery) — use fully opaque surface color for background
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurface, typedValue, true
            )
            // Force alpha to 100% while preserving RGB
            val opaqueSurface = (typedValue.data and 0x00FFFFFF) or (0xFF shl 24)
            setBackgroundColor(opaqueSurface)
        }

        // Title bar
        val titleBar = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = (16 * dm.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val titleView = android.widget.TextView(context).apply {
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
            setImageResource(ani.dantotsu.R.drawable.ic_round_close_24)
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

        // Grid RecyclerView
        val recyclerView = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            )
            // item_comick_cover: 135dp image + 4dp marginStart + 4dp marginEnd = 143dp per cell
            val itemWidthDp = 143f
            val screenWidthDp = dm.widthPixels / dm.density
            val cols = (screenWidthDp / itemWidthDp).toInt().coerceAtLeast(2)
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, cols)
            adapter = ComickCoverAdapter(covers)
            // Center the grid by equalising the horizontal padding
            val totalContentPx = (cols * itemWidthDp * dm.density).toInt()
            val sidePx = ((dm.widthPixels - totalContentPx) / 2).coerceAtLeast(0)
            val bottomPad = (8 * dm.density).toInt()
            setPadding(sidePx, 0, sidePx, bottomPad)
            clipToPadding = false
        }
        root.addView(recyclerView)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
        }
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

        val root = FrameLayout(context).apply {
            setBackgroundColor(0xDD000000.toInt())
        }

        // ViewPager2 for swiping between covers
        val pager = androidx.viewpager2.widget.ViewPager2(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            offscreenPageLimit = 1
        }

        // Compute bottom offsets so we can position volume label above the page counter
        val counterBottomMargin = (24 * dm.density).toInt()
        val volBottomMargin = counterBottomMargin + (20 * dm.density).toInt()

        // Page counter (e.g. "3 / 12") — centered at bottom; volume label will sit above it
        val counterView = android.widget.TextView(context).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL; setMargins(0, 0, 0, counterBottomMargin) }
            text = "${startIndex + 1} / ${covers.size}"
        }

        // Pager adapter (uses ZoomImageView for pinch-to-zoom)
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
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
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
                // Pager should use the same display order (oldest first)
                val cover = covers[covers.size - 1 - position]
                val b2key = cover.b2key
                if (!b2key.isNullOrBlank()) {
                    val fullUrl = "https://meo.comick.pictures/$b2key"
                    zoomView.loadImage(fullUrl)
                } else {
                    zoomView.setImageResource(R.drawable.ic_round_menu_book_24)
                }
                if (!cover.vol.isNullOrBlank()) {
                    volLabel.visibility = View.VISIBLE
                    volLabel.text = anchor.context.getString(R.string.cover_volume_label, cover.vol)
                } else {
                    volLabel.visibility = View.GONE
                }
                // Do not dismiss on image tap — background overlay handles outside taps
            }

            override fun getItemCount(): Int = covers.size
        }

        pager.adapter = pagerAdapter
        pager.setCurrentItem(startIndex, false)

        // Top overlay: intercept taps outside the visible image to dismiss; let touches pass through to pager when inside image
        val touchOverlay = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Fully transparent
            setBackgroundColor(0x00000000)
            setOnTouchListener { _, ev ->
                // Find current page's ZoomImageView
                val rv = pager.getChildAt(0) as? RecyclerView
                val vh = rv?.findViewHolderForAdapterPosition(pager.currentItem)
                val image = (vh?.itemView as? ViewGroup)?.getChildAt(0) as? ZoomImageView
                if (image != null) {
                    val loc = IntArray(2)
                    image.getLocationOnScreen(loc)
                    val drawable = image.drawable
                    val x = ev.rawX.toInt()
                    val y = ev.rawY.toInt()
                    if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                        val dw = drawable.intrinsicWidth.toFloat()
                        val dh = drawable.intrinsicHeight.toFloat()
                        val vw = image.width.toFloat()
                        val vh = image.height.toFloat()
                        val scale = kotlin.math.min(vw / dw, vh / dh)
                        val dispW = (dw * scale).toInt()
                        val dispH = (dh * scale).toInt()
                        val left = loc[0] + ((vw - dispW) / 2).toInt()
                        val top = loc[1] + ((vh - dispH) / 2).toInt()
                        val rect = Rect(left, top, left + dispW, top + dispH)
                        if (!rect.contains(x, y)) {
                            dialog.dismiss()
                            return@setOnTouchListener true
                        }
                        return@setOnTouchListener false
                    } else {
                        // Fallback to view bounds
                        val rect = Rect(loc[0], loc[1], loc[0] + image.width, loc[1] + image.height)
                        if (!rect.contains(x, y)) {
                            dialog.dismiss()
                            return@setOnTouchListener true
                        }
                        return@setOnTouchListener false
                    }
                } else {
                    // No image found — dismiss on overlay touch
                    dialog.dismiss()
                    return@setOnTouchListener true
                }
            }
        }

        // Add pager and counter first, then overlay on top so overlay can decide whether to consume taps
        root.addView(pager)
        root.addView(counterView)
        root.addView(touchOverlay)

        // Update dots on page changes — when there are more covers than dots, slide the window
        pager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Update counter text
                counterView.text = "${position + 1} / ${covers.size}"
            }
        })

        dialog.setContentView(root)
        dialog.window?.apply { setBackgroundDrawableResource(android.R.color.transparent) }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
