package ani.dantotsu.media.manga.mangareader

import android.animation.ObjectAnimator
import android.content.res.Resources.getSystem
import android.graphics.Bitmap
import android.graphics.PointF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.px
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.alexvasilkov.gestures.views.GestureFrameLayout
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.ImageViewState
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import ani.dantotsu.media.manga.mangareader.BaseImageAdapter.Companion.loadBitmap
import kotlinx.coroutines.launch

/**
 * Adapter for the continuous multi-chapter reader mode.
 * Displays images from multiple chapters sequentially, with transition dividers between them.
 */
class ContinuousChapterAdapter(
    val activity: MangaReaderActivity,
    initialChapter: MangaChapter,
    private val initialChapterIndex: Int,
    private val chaptersTitleArr: ArrayList<String>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_IMAGE = 0
        const val TYPE_TRANSITION = 1
        const val TYPE_BOUNDARY = 2
    }

    /**
     * Represents a single item in the flat list: an image, a chapter transition, or a boundary message.
     */
    sealed class ReaderItem {
        data class Image(
            val image: MangaImage,
            val chapterIndex: Int,
            val chapter: MangaChapter,
            val pageIndex: Int
        ) : ReaderItem()

        data class Transition(
            val currentChapterIndex: Int,
            val nextChapterIndex: Int,
            val currentTitle: String,
            val nextTitle: String,
            val missingChapters: Int
        ) : ReaderItem()

        data class Boundary(
            val message: String
        ) : ReaderItem()
    }

    val items = mutableListOf<ReaderItem>()
    val settings get() = activity.defaultSettings

    /** Tracks which chapter unique numbers have been loaded so we don't duplicate */
    private val loadedChapterUniqueNumbers = mutableSetOf<String>()
    private val loadedChapterIndices = mutableSetOf<Int>()
    
    var lastLoadedChapterIdx: Int = initialChapterIndex
        private set
    var firstLoadedChapterIdx: Int = initialChapterIndex
        private set

    /** Prevent duplicate boundary items */
    private var hasStartBoundary = false
    private var hasEndBoundary = false

    init {
        appendChapterImages(initialChapter, initialChapterIndex)
    }

    /**
     * Append a chapter's images (and optionally a preceding transition) to the item list.
     */
    fun appendChapter(chapter: MangaChapter, chapterIndex: Int, missingChapters: Int = 0) {
        if (loadedChapterIndices.contains(chapterIndex) || loadedChapterUniqueNumbers.contains(chapter.uniqueNumber())) return

        val prevChapterIndex = if (items.isNotEmpty()) {
            // Find the last image's chapter index
            items.filterIsInstance<ReaderItem.Image>().lastOrNull()?.chapterIndex ?: initialChapterIndex
        } else initialChapterIndex

        val currentTitle = activity.getChapterTitle(prevChapterIndex)
        val nextTitle = activity.getChapterTitle(chapterIndex)

        // Add transition
        val transitionPos = items.size
        items.add(ReaderItem.Transition(
            currentChapterIndex = prevChapterIndex,
            nextChapterIndex = chapterIndex,
            currentTitle = currentTitle,
            nextTitle = nextTitle,
            missingChapters = missingChapters
        ))
        notifyItemInserted(transitionPos)

        // Add images
        appendChapterImages(chapter, chapterIndex)
        lastLoadedChapterIdx = chapterIndex
    }

    /**
     * Prepend a chapter's images (and a trailing transition) to the beginning of the item list.
     */
    fun prependChapter(chapter: MangaChapter, chapterIndex: Int, missingChapters: Int = 0) {
        if (loadedChapterIndices.contains(chapterIndex) || loadedChapterUniqueNumbers.contains(chapter.uniqueNumber())) return

        val nextChapterIndex = if (items.isNotEmpty()) {
            items.filterIsInstance<ReaderItem.Image>().firstOrNull()?.chapterIndex ?: initialChapterIndex
        } else initialChapterIndex

        val currentTitle = activity.getChapterTitle(chapterIndex)
        val nextTitle = activity.getChapterTitle(nextChapterIndex)

        val newItems = mutableListOf<ReaderItem>()

        // Add images for the prepended chapter
        val images = chapter.images()
        loadedChapterIndices.add(chapterIndex)
        loadedChapterUniqueNumbers.add(chapter.uniqueNumber())
        images.forEachIndexed { pageIdx, img ->
            newItems.add(ReaderItem.Image(img, chapterIndex, chapter, pageIdx))
        }

        // Add transition after the prepended chapter
        newItems.add(ReaderItem.Transition(
            currentChapterIndex = chapterIndex,
            nextChapterIndex = nextChapterIndex,
            currentTitle = currentTitle,
            nextTitle = nextTitle,
            missingChapters = missingChapters
        ))

        items.addAll(0, newItems)
        notifyItemRangeInserted(0, newItems.size)
        firstLoadedChapterIdx = chapterIndex
    }

    private fun appendChapterImages(chapter: MangaChapter, chapterIndex: Int) {
        val images = chapter.images()
        loadedChapterIndices.add(chapterIndex)
        loadedChapterUniqueNumbers.add(chapter.uniqueNumber())
        val startPos = items.size
        images.forEachIndexed { pageIdx, img ->
            items.add(ReaderItem.Image(img, chapterIndex, chapter, pageIdx))
        }
        notifyItemRangeInserted(startPos, images.size)
    }

    /**
     * Returns the chapter index for the item at the given adapter position.
     */
    fun getChapterIndexAt(position: Int): Int? {
        return when (val item = items.getOrNull(position)) {
            is ReaderItem.Image -> item.chapterIndex
            is ReaderItem.Transition -> item.nextChapterIndex
            else -> null
        }
    }

    /**
     * Returns the first position in the adapter that belongs to the given chapterIndex.
     */
    fun getChapterStartPosition(chapterIndex: Int): Int? {
        return items.indexOfFirst { it is ReaderItem.Image && it.chapterIndex == chapterIndex }.takeIf { it != -1 }
    }

    /**
     * Returns the chapter for the item at the given adapter position.
     */
    fun getChapterAt(position: Int): MangaChapter? {
        return when (val item = items.getOrNull(position)) {
            is ReaderItem.Image -> item.chapter
            else -> null
        }
    }

    /**
     * Returns the page count for a specific chapter index.
     */
    fun getPageCountForChapter(chapterIndex: Int): Int {
        return items.count { it is ReaderItem.Image && it.chapterIndex == chapterIndex }
    }

    /**
     * Returns the page number (1-based) within the chapter for the given adapter position.
     */
    fun getPageInChapter(position: Int): Int {
        val item = items.getOrNull(position) as? ReaderItem.Image ?: return 0
        return item.pageIndex + 1
    }

    fun isChapterLoaded(chapterIndex: Int): Boolean = loadedChapterIndices.contains(chapterIndex)
    fun isChapterLoaded(uniqueNumber: String): Boolean = loadedChapterUniqueNumbers.contains(uniqueNumber)

    /**
     * Adds a "no previous chapter" boundary at the start if not already present.
     */
    fun addStartBoundary(message: String) {
        if (hasStartBoundary) return
        hasStartBoundary = true
        items.add(0, ReaderItem.Boundary(message))
        notifyItemInserted(0)
    }

    /**
     * Adds a "no next chapter" boundary at the end if not already present.
     */
    fun addEndBoundary(message: String) {
        if (hasEndBoundary) return
        hasEndBoundary = true
        val pos = items.size
        items.add(ReaderItem.Boundary(message))
        notifyItemInserted(pos)
    }

    /** Returns the adapter position of the first image item. */
    fun firstImagePosition(): Int {
        return items.indexOfFirst { it is ReaderItem.Image }.coerceAtLeast(0)
    }

    /**
     * Returns the position of the most recent Image item at or before [position],
     * or -1 if none exists. Used to keep page/chapter tracking stable when the
     * visible item is a Transition or Boundary.
     */
    fun lastImagePositionAtOrBefore(position: Int): Int {
        var p = position.coerceAtMost(items.size - 1)
        while (p >= 0 && items[p] !is ReaderItem.Image) p--
        return p
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ReaderItem.Image -> TYPE_IMAGE
            is ReaderItem.Transition -> TYPE_TRANSITION
            is ReaderItem.Boundary -> TYPE_BOUNDARY
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val isPaged = settings.layout != CurrentReaderSettings.Layouts.CONTINUOUS
        return when (viewType) {
            TYPE_TRANSITION -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chapter_transition, parent, false)
                if (isPaged) {
                    view.layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                TransitionViewHolder(view)
            }
            TYPE_BOUNDARY -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chapter_boundary, parent, false)
                if (isPaged) {
                    view.layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                BoundaryViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_image, parent, false)
                if (isPaged) {
                    view.layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                ImageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (settings.layout == CurrentReaderSettings.Layouts.PAGED) {
            holder.itemView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        when (val item = items[position]) {
            is ReaderItem.Transition -> {
                // Add spacing for continuous layout when "padding" (spaced pages) is enabled
                if (settings.layout == CurrentReaderSettings.Layouts.CONTINUOUS && settings.padding) {
                    when (settings.direction) {
                        CurrentReaderSettings.Directions.TOP_TO_BOTTOM -> holder.itemView.setPadding(0, 0, 0, 16f.px)
                        CurrentReaderSettings.Directions.LEFT_TO_RIGHT -> holder.itemView.setPadding(0, 0, 16f.px, 0)
                        CurrentReaderSettings.Directions.BOTTOM_TO_TOP -> holder.itemView.setPadding(0, 16f.px, 0, 0)
                        CurrentReaderSettings.Directions.RIGHT_TO_LEFT -> holder.itemView.setPadding(16f.px, 0, 0, 0)
                    }
                } else {
                    holder.itemView.setPadding(0, 0, 0, 0)
                }
                bindTransition(holder as TransitionViewHolder, item)
                setupPagedClickListener(holder.itemView)
            }
            is ReaderItem.Image -> bindImage(holder as ImageViewHolder, item, position)
            is ReaderItem.Boundary -> {
                // Match transition spacing/height in continuous mode when padding enabled
                if (settings.layout == CurrentReaderSettings.Layouts.CONTINUOUS && settings.padding) {
                    when (settings.direction) {
                        CurrentReaderSettings.Directions.TOP_TO_BOTTOM -> holder.itemView.setPadding(0, 0, 0, 16f.px)
                        CurrentReaderSettings.Directions.LEFT_TO_RIGHT -> holder.itemView.setPadding(0, 0, 16f.px, 0)
                        CurrentReaderSettings.Directions.BOTTOM_TO_TOP -> holder.itemView.setPadding(0, 16f.px, 0, 0)
                        CurrentReaderSettings.Directions.RIGHT_TO_LEFT -> holder.itemView.setPadding(16f.px, 0, 0, 0)
                    }
                } else {
                    holder.itemView.setPadding(0, 0, 0, 0)
                }
                (holder as BoundaryViewHolder).message.text = item.message
                setupPagedClickListener(holder.itemView)
            }
        }
    }

    private fun setupPagedClickListener(view: View) {
        if (settings.layout == CurrentReaderSettings.Layouts.PAGED) {
            val detector = androidx.core.view.GestureDetectorCompat(view.context, object : ani.dantotsu.GesturesListener() {
                override fun onSingleClick(event: android.view.MotionEvent) =
                    activity.handleController(event = event)
            })
            view.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                true
            }
        }
    }

    private fun bindTransition(holder: TransitionViewHolder, item: ReaderItem.Transition) {
        val ctx = holder.itemView.context
        holder.currentChapter.text = ctx.getString(R.string.chapter_transition_end, item.currentTitle)
        holder.nextChapter.text = ctx.getString(R.string.chapter_transition_next, item.nextTitle)

        if (item.missingChapters > 0) {
            holder.gapWarning.visibility = View.VISIBLE
            holder.gapWarning.text = if (item.missingChapters == 1) {
                "⚠ ${ctx.getString(R.string.chapter_gap_warning_message_single)}"
            } else {
                "⚠ ${ctx.getString(R.string.chapter_gap_warning_message, item.missingChapters)}"
            }
        } else {
            holder.gapWarning.visibility = View.GONE
        }
    }

    private fun bindImage(holder: ImageViewHolder, item: ReaderItem.Image, position: Int) {
        val view = holder.itemView as GestureFrameLayout
        view.controller.also {
            it.settings.isRotationEnabled = settings.rotation
        }

        // Continuous layout sizing
        if (settings.padding) {
            // Always keep the trailing-edge spacing between consecutive pages.
            // If this image is the first page of a chapter and the previous adapter item
            // is a Transition or Boundary, also add leading-edge padding so there's space
            // after the transition/boundary.
            val prevIsDivider = position > 0 && (items.getOrNull(position - 1) is ReaderItem.Transition || items.getOrNull(position - 1) is ReaderItem.Boundary)
            val leading = if (prevIsDivider && (items.getOrNull(position) as? ReaderItem.Image)?.pageIndex == 0) 16f.px else 0
            when (settings.direction) {
                CurrentReaderSettings.Directions.TOP_TO_BOTTOM -> view.setPadding(0, leading, 0, 16f.px)
                CurrentReaderSettings.Directions.LEFT_TO_RIGHT -> view.setPadding(leading, 0, 16f.px, 0)
                CurrentReaderSettings.Directions.BOTTOM_TO_TOP -> view.setPadding(0, 16f.px, 0, leading)
                CurrentReaderSettings.Directions.RIGHT_TO_LEFT -> view.setPadding(16f.px, 0, leading, 0)
            }
        } else {
            view.setPadding(0, 0, 0, 0)
        }
        if (settings.layout != CurrentReaderSettings.Layouts.PAGED) {
            view.updateLayoutParams {
                if (settings.direction != CurrentReaderSettings.Directions.LEFT_TO_RIGHT &&
                    settings.direction != CurrentReaderSettings.Directions.RIGHT_TO_LEFT) {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = 480f.px
                } else {
                    width = 480f.px
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
        } else {
            val detector = androidx.core.view.GestureDetectorCompat(view.context, object : ani.dantotsu.GesturesListener() {
                override fun onSingleClick(event: android.view.MotionEvent) =
                    activity.handleController(event = event)
            })
            view.findViewById<View>(R.id.imgProgCover).apply {
                setOnTouchListener { _, event ->
                    detector.onTouchEvent(event)
                    false
                }
                setOnLongClickListener {
                    activity.onImageLongClicked(position, item.image, null) { dialog ->
                        activity.lifecycleScope.launch {
                            loadImage(position, view)
                        }
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        dialog.dismiss()
                    }
                    true
                }
            }
        }

        activity.lifecycleScope.launch { loadImage(position, view) }
    }

    suspend fun loadImage(position: Int, parent: View): Boolean {
        val item = items.getOrNull(position) as? ReaderItem.Image ?: return false
        val link = item.image.url
        if (link.url.isEmpty()) return false

        val imageView = parent.findViewById<SubsamplingScaleImageView>(R.id.imgProgImageNoGestures) ?: return false
        val progress = parent.findViewById<View>(R.id.imgProgProgress) ?: return false
        val errorLayout = parent.findViewById<View>(R.id.imgProgError) ?: return false
        val retryButton = parent.findViewById<View>(R.id.imgProgRetry)

        imageView.recycle()
        imageView.visibility = View.GONE
        errorLayout.visibility = View.GONE
        progress.visibility = View.VISIBLE

        val transforms = mutableListOf<BitmapTransformation>()
        val parserTransformation = activity.getTransformation(item.image)
        if (parserTransformation != null) transforms.add(parserTransformation)
        if (settings.cropBorders) {
            transforms.add(RemoveBordersTransformation(true, settings.cropBorderThreshold))
            transforms.add(RemoveBordersTransformation(false, settings.cropBorderThreshold))
        }

        val bitmap: Bitmap? = with(activity) { loadBitmap(link, transforms) }

        if (bitmap == null) {
            progress.visibility = View.GONE
            errorLayout.visibility = View.VISIBLE
            retryButton?.setOnClickListener {
                activity.lifecycleScope.launch {
                    loadImage(position, parent)
                }
            }
            return false
        }

        val bitmapW = bitmap.width
        val bitmapH = bitmap.height
        var sWidth = getSystem().displayMetrics.widthPixels
        var sHeight = getSystem().displayMetrics.heightPixels

        if (settings.layout != CurrentReaderSettings.Layouts.PAGED) {
            parent.updateLayoutParams {
                if (settings.direction != CurrentReaderSettings.Directions.LEFT_TO_RIGHT &&
                    settings.direction != CurrentReaderSettings.Directions.RIGHT_TO_LEFT) {
                    sWidth -= parent.paddingLeft + parent.paddingRight
                    sHeight = if (settings.wrapImages) bitmapH
                              else (sWidth * bitmapH * 1f / bitmapW).toInt()
                    height = sHeight + parent.paddingTop + parent.paddingBottom
                } else {
                    sHeight -= parent.paddingTop + parent.paddingBottom
                    sWidth = if (settings.wrapImages) bitmapW
                             else (sHeight * bitmapW * 1f / bitmapH).toInt()
                    width = sWidth + parent.paddingLeft + parent.paddingRight
                }
            }
        }

        // Prefer filling the primary axis for the current reader direction to avoid
        // visible side bars on vertical (top-to-bottom) layout.
        val scaleX = sWidth * 1f / bitmapW
        val scaleY = sHeight * 1f / bitmapH
        val scale = if (settings.direction != CurrentReaderSettings.Directions.LEFT_TO_RIGHT &&
            settings.direction != CurrentReaderSettings.Directions.RIGHT_TO_LEFT) {
            // Vertical layouts: make image fit width
            scaleX
        } else {
            // Horizontal layouts: make image fit height
            scaleY
        }

        imageView.maxScale = scale * 1.1f
        imageView.minScale = scale

        // Pass an explicit initial state so the SSIV doesn't auto-fit against
        // stale (placeholder) view dimensions while a layout pass is still pending,
        // which would otherwise leave the image displaced until the next re-layout.
        imageView.visibility = View.VISIBLE
        imageView.setImage(
            ImageSource.cachedBitmap(bitmap),
            ImageViewState(scale, PointF(bitmapW / 2f, bitmapH / 2f), 0)
        )

        ObjectAnimator.ofFloat(parent, "alpha", 0f, 1f)
            .setDuration((400 * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong())
            .start()
        progress.visibility = View.GONE
        return true
    }

    inner class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    inner class TransitionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val currentChapter = view.findViewById<android.widget.TextView>(R.id.transitionCurrentChapter)!!
        val nextChapter = view.findViewById<android.widget.TextView>(R.id.transitionNextChapter)!!
        val gapWarning = view.findViewById<android.widget.TextView>(R.id.transitionGapWarning)!!
    }

    inner class BoundaryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val message = view.findViewById<android.widget.TextView>(R.id.boundaryMessage)!!
    }
}
