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
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.ImageViewState
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.launch

open class ImageAdapter(
    activity: MangaReaderActivity,
    chapter: MangaChapter,
    pageMap: ChapterPageMap? = null,
) : BaseImageAdapter(activity, chapter, pageMap) {

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
        pageAt(position)?.let { return composePage(it, null, renderZoomOf(parent)) }
        val link = images.getOrNull(position)?.url ?: return null
        if (link.url.isEmpty()) return null

        return activity.loadBitmap(
            link, activity.pageTransforms(images[position]), zoom = renderZoomOf(parent)
        )
    }

    override suspend fun loadImage(position: Int, parent: View, quiet: Boolean): Boolean {
        val page = pageKey(position) ?: return false
        val imageView = parent.findViewById<SubsamplingScaleImageView>(R.id.imgProgImageNoGestures)
            ?: return false
        val progress = parent.findViewById<View>(R.id.imgProgProgress) ?: return false
        val errorLayout = parent.findViewById<View>(R.id.imgProgError) ?: return false

        // Claim the view for this page; whatever starts later on the same view supersedes it.
        val load = parent.beginPageLoad(page)
        if (!quiet) {
            imageView.recycle()
            imageView.visibility = View.GONE
            errorLayout.visibility = View.GONE
            progress.visibility = View.VISIBLE
        }

        val decoded = loadBitmap(position, parent)

        // A newer load owns the view now — it was recycled onto another page, or reloaded — so
        // this result is stale and the newer one is the image that belongs on screen.
        if (!load.stillOwns(parent)) return false

        if (decoded == null) {
            failedPages.showError(parent, page, retryPage)
            return false
        }
        failedPages.clearError(parent, page)

        // This page's edges are known now, so its gap can be judged for real before it is sized.
        if (settings.layout != PAGED && activity.smartSpacing) {
            judgeStitched(position)
            if (!load.stillOwns(parent)) return false
            val (leading, trailing) = pageGaps(position)
            setPageGaps(parent, settings.direction, leading, trailing)
        }
        // A page drawn from slices is profiled under its own key, so the gaps either side of it
        // can be judged like any other page's.
        pageUrl(position)?.let { if (pageAt(position)?.single == false) PageEdges.record(it, decoded) }
        val bitmap = withStripGaps(activity, pageUrl(position), decoded, parent)

        val (viewportWidth, viewportHeight) = pageViewport(parent)
        var sWidth = viewportWidth
        var sHeight = viewportHeight

        // A zoomed re-render is a larger bitmap of the same page, and must not resize its slot —
        // so the layout works from the fitted dimensions and only the scale uses the real ones.
        val renderZoom = renderZoomOf(parent)
        val fittedW = (bitmap.width / renderZoom).toInt().coerceAtLeast(1)
        val fittedH = (bitmap.height / renderZoom).toInt().coerceAtLeast(1)

        if (settings.layout != PAGED)
            parent.updateLayoutParams {
                if (settings.direction != LEFT_TO_RIGHT && settings.direction != RIGHT_TO_LEFT) {
                    sHeight =
                        if (settings.wrapImages) fittedH else (sWidth * fittedH * 1f / fittedW).toInt()
                    height = sHeight + parent.paddingTop + parent.paddingBottom
                } else {
                    sWidth =
                        if (settings.wrapImages) fittedW else (sHeight * fittedW * 1f / fittedH).toInt()
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

        if (!quiet) ObjectAnimator.ofFloat(parent, "alpha", 0f, 1f)
            .setDuration((400 * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong())
            .start()
        progress.visibility = View.GONE
        load.loaded = true
        refreshNextGap(position)
        // The page is on screen, so whatever has been translated for it belongs on top of it.
        activity.applyTranslationOverlay(parent, position)

        return true
    }

    override fun getItemCount(): Int = pageCount

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
