package ani.dantotsu.media

import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

class ComickCoverAdapter(
    private val covers: List<ComickCover>
) : RecyclerView.Adapter<ComickCoverAdapter.ViewHolder>() {

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
                    showFullscreenCover(root, "https://meo.comick.pictures/$b2key", cover.vol)
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

        // Root layout
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurface, typedValue, true
            )
            setBackgroundColor(typedValue.data)
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

    private fun showFullscreenCover(anchor: View, url: String, vol: String?) {
        val context = anchor.context
        val dm = context.resources.displayMetrics

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val contentLayout = FrameLayout(context).apply {
            setBackgroundColor(0xDD000000.toInt())
            setOnClickListener { dialog.dismiss() }
        }

        val imageView = ImageView(context).apply {
            val bottomReserve = if (!vol.isNullOrBlank()) (56 * dm.density).toInt() else 0
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = bottomReserve }
            scaleType = ImageView.ScaleType.FIT_CENTER
            // No adjustViewBounds — it only works with WRAP_CONTENT and breaks MATCH_PARENT
            setOnClickListener { dialog.dismiss() }
        }
        contentLayout.addView(imageView)

        if (!vol.isNullOrBlank()) {
            val volLabel = TextView(context).apply {
                text = context.getString(R.string.cover_volume_label, vol)
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                val pad = (12 * dm.density).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM
                )
            }
            contentLayout.addView(volLabel)
        }

        dialog.setContentView(contentLayout)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            // setLayout must be called after setContentView and show()
        }
        dialog.show()
        // Apply full-screen dimensions after show() so the window is ready
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Load image after dialog is shown so the ImageView has proper bounds for Glide
        imageView.loadImage(url)
    }
}
