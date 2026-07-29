package ani.dantotsu.media.manga.mangareader

import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.PointF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemImageBinding
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.settings.CurrentReaderSettings.Directions.LEFT_TO_RIGHT
import ani.dantotsu.settings.CurrentReaderSettings.Directions.RIGHT_TO_LEFT
import ani.dantotsu.settings.CurrentReaderSettings.Layouts.PAGED
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.ImageViewState
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.launch

open class ImageAdapter(
    activity: MangaReaderActivity,
    chapter: MangaChapter
) : BaseImageAdapter(activity, chapter) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (settings.layout == PAGED) {
            binding.root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        return ImageViewHolder(binding)
    }

    inner class ImageViewHolder(binding: ItemImageBinding) : RecyclerView.ViewHolder(binding.root)

    private val failedPages = FailedPageTracker { position -> pageKey(position) }
    private val retryPage: (Int, View) -> Unit = { position, view ->
        activity.lifecycleScope.launch { loadImage(position, view) }
    }

    open suspend fun loadBitmap(position: Int, parent: View): Bitmap? {
        val link = images.getOrNull(position)?.url ?: return null
        if (link.url.isEmpty()) return null

        val transforms = mutableListOf<BitmapTransformation>()
        val parserTransformation = activity.getTransformation(images[position])

        if (parserTransformation != null) transforms.add(parserTransformation)
        if (settings.cropBorders) {
            transforms.add(RemoveBordersTransformation(true, settings.cropBorderThreshold))
            transforms.add(RemoveBordersTransformation(false, settings.cropBorderThreshold))
        }

        return activity.loadBitmap(link, transforms)
    }

    override suspend fun loadImage(position: Int, parent: View): Boolean {
        val page = pageKey(position) ?: return false
        val imageView = parent.findViewById<SubsamplingScaleImageView>(R.id.imgProgImageNoGestures)
            ?: return false
        val progress = parent.findViewById<View>(R.id.imgProgProgress) ?: return false
        val errorLayout = parent.findViewById<View>(R.id.imgProgError) ?: return false

        // Claim the view for this page; whatever starts later on the same view supersedes it.
        val load = parent.beginPageLoad(page)
        imageView.recycle()
        imageView.visibility = View.GONE
        errorLayout.visibility = View.GONE
        progress.visibility = View.VISIBLE

        val bitmap = loadBitmap(position, parent)

        // A newer load owns the view now — it was recycled onto another page, or reloaded — so
        // this result is stale and the newer one is the image that belongs on screen.
        if (!load.stillOwns(parent)) return false

        if (bitmap == null) {
            failedPages.showError(parent, page, retryPage)
            return false
        }
        failedPages.clearError(parent, page)

        val (viewportWidth, viewportHeight) = pageViewport(parent)
        var sWidth = viewportWidth
        var sHeight = viewportHeight

        if (settings.layout != PAGED)
            parent.updateLayoutParams {
                if (settings.direction != LEFT_TO_RIGHT && settings.direction != RIGHT_TO_LEFT) {
                    sHeight =
                        if (settings.wrapImages) bitmap.height else (sWidth * bitmap.height * 1f / bitmap.width).toInt()
                    height = sHeight + parent.paddingTop + parent.paddingBottom
                } else {
                    sWidth =
                        if (settings.wrapImages) bitmap.width else (sHeight * bitmap.width * 1f / bitmap.height).toInt()
                    width = sWidth + parent.paddingLeft + parent.paddingRight
                }
            }

        val scaleX = sWidth * 1f / bitmap.width
        val scaleY = sHeight * 1f / bitmap.height
        val scale = when {
            settings.layout == PAGED -> minOf(scaleX, scaleY)
            settings.direction != LEFT_TO_RIGHT && settings.direction != RIGHT_TO_LEFT -> scaleX
            else -> scaleY
        }

        imageView.maxScale = scale * 1.1f
        imageView.minScale = scale

        // Pass an explicit initial state so the SSIV doesn't auto-fit against
        // stale (placeholder) view dimensions while a layout pass is still pending.
        imageView.visibility = View.VISIBLE
        imageView.setImage(
            ImageSource.cachedBitmap(bitmap),
            ImageViewState(scale, PointF(bitmap.width / 2f, bitmap.height / 2f), 0)
        )

        ObjectAnimator.ofFloat(parent, "alpha", 0f, 1f)
            .setDuration((400 * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong())
            .start()
        progress.visibility = View.GONE
        load.loaded = true

        return true
    }

    override fun getItemCount(): Int = images.size

    override fun isZoomed(): Boolean {
        val imageView =
            activity.findViewById<SubsamplingScaleImageView>(R.id.imgProgImageNoGestures)
        return imageView.scale > imageView.minScale
    }

    override fun setZoom(zoom: Float) {
        val imageView =
            activity.findViewById<SubsamplingScaleImageView>(R.id.imgProgImageNoGestures)
        imageView.setScaleAndCenter(zoom, imageView.center)
    }
}
