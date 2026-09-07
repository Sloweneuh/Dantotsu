package ani.dantotsu.spike

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The page with its blocks drawn over it, editable by hand.
 *
 * The sliders in [MangaOcrSpikeActivity] tune the thresholds; this tunes intuition about what is
 * being thresholded. Drag a block over a speech bubble and then off onto artwork and the ring
 * statistics move with it in real time, which is a far faster way to learn what separates the two
 * than guessing a variance number. Drawing a fresh block is the other half: the region is sent back
 * through the recognizer on its own, so a bubble the full-page pass missed can be probed directly —
 * if OCR reads it from a crop, the failure was detection rather than recognition, and that
 * distinction decides whether a bubble-segmentation model would buy anything.
 *
 * All geometry crossing this boundary is in **image** coordinates. The view scales the page to its
 * own width and converts on the way in and out, so nothing outside has to know about the display
 * scale.
 *
 * Throwaway, with the rest of `ani.dantotsu.spike`.
 */
class BlockEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * One block as it should be drawn. [id] is what edits are reported against.
     *
     * [translation] and [fill] are only set in [previewMode]: the fill is the median luminance of
     * the block's own ring, so an inverted bubble is repainted black and a toned one grey rather
     * than everything being papered over in white.
     */
    data class Box(
        val id: Int,
        val rect: Rect,
        val color: Int,
        val translation: String? = null,
        val fill: Int? = null,
    )

    /**
     * Draw the translation over the page instead of the diagnostic outlines.
     *
     * Editing is off while this is on. Preview boxes are drawn at rects the activity has already
     * widened for legibility, so dragging one would move something that is not where the block
     * actually is.
     */
    var previewMode: Boolean = false
        set(value) {
            field = value
            if (value) addMode = false
            invalidate()
        }

    /** A drag finished and [id] now occupies [rect]. Fires continuously while dragging. */
    var onBoxChanged: ((id: Int, rect: Rect) -> Unit)? = null

    /** A block was drawn on empty space. */
    var onBoxAdded: ((rect: Rect) -> Unit)? = null

    /** Selection changed, to null when the user tapped empty space. */
    var onSelectionChanged: ((id: Int?) -> Unit)? = null

    private var page: Bitmap? = null
    private var boxes: List<Box> = emptyList()

    var selectedId: Int? = null
        private set

    /**
     * Whether an empty-space drag draws a new block rather than scrolling the page.
     *
     * Off by default, and it has to be: a page shown at full width fills the screen, so if every
     * drag on empty space drew a box there would be no way left to scroll. Dragging a block or a
     * corner handle is unambiguous and needs no mode — only creation collides with scrolling, so
     * only creation is armed explicitly.
     */
    var addMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var scale = 1f
    private val destination = Rect()

    private enum class Mode { NONE, MOVE, RESIZE, CREATE }

    private var mode = Mode.NONE

    /** Which corner is being dragged in [Mode.RESIZE]: 0 = TL, 1 = TR, 2 = BR, 3 = BL. */
    private var corner = 0
    private var downX = 0f
    private var downY = 0f
    private var startRect = Rect()
    private var draft: Rect? = null

    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val density = resources.displayMetrics.density
    private val handleRadius = HANDLE_DP * density
    private val touchSlop = TOUCH_DP * density

    /** Breathing room between a repainted block and the text written into it. */
    private val textInset = (3f * density).toInt()

    /** Fitted layouts, keyed by box id. Cleared whenever a box or the display scale changes. */
    private val layoutCache = mutableMapOf<Int, StaticLayout>()

    fun setPage(bitmap: Bitmap?) {
        layoutCache.clear()
        page = bitmap
        selectedId = null
        draft = null
        requestLayout()
        invalidate()
    }

    fun setBoxes(newBoxes: List<Box>) {
        layoutCache.clear()
        boxes = newBoxes
        if (boxes.none { it.id == selectedId }) selectedId = null
        invalidate()
    }

    fun select(id: Int?) {
        selectedId = id
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val bitmap = page
        val height = if (bitmap == null || bitmap.width == 0) {
            0
        } else {
            (width.toFloat() / bitmap.width * bitmap.height).roundToInt()
        }
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Fitted sizes are in view pixels, so a width change invalidates every one of them.
        if (w != oldw) layoutCache.clear()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = page ?: return
        scale = width.toFloat() / bitmap.width
        destination.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, destination, pagePaint)

        boxPaint.strokeWidth = max(2f, 1.5f * density)
        labelPaint.textSize = 11f * density

        if (previewMode) {
            boxes.forEach { box -> drawTranslated(canvas, box) }
            return
        }

        boxes.forEach { box ->
            val selected = box.id == selectedId
            boxPaint.color = box.color
            boxPaint.strokeWidth = if (selected) 3f * density else 1.5f * density
            val view = box.rect.toView()
            canvas.drawRect(view, boxPaint)

            val tag = box.id.toString()
            val tagWidth = labelPaint.measureText(tag)
            labelBackgroundPaint.color = box.color
            canvas.drawRect(
                view.left.toFloat(),
                view.top - labelPaint.textSize * 1.3f,
                view.left + tagWidth + 8f * density,
                view.top.toFloat(),
                labelBackgroundPaint,
            )
            canvas.drawText(
                tag,
                view.left + 4f * density,
                view.top - labelPaint.textSize * 0.35f,
                labelPaint,
            )

            if (selected) {
                handlePaint.color = box.color
                cornersOf(view).forEach { (x, y) ->
                    canvas.drawCircle(x, y, handleRadius, handlePaint)
                }
            }
        }

        draft?.let {
            boxPaint.color = DRAFT_COLOR
            boxPaint.strokeWidth = 2f * density
            canvas.drawRect(it.toView(), boxPaint)
        }

        // Armed drawing changes what a drag does, so it must never be invisible state.
        if (addMode) {
            boxPaint.color = DRAFT_COLOR
            boxPaint.strokeWidth = 3f * density
            val inset = 1.5f * density
            canvas.drawRect(
                inset,
                inset,
                width - inset,
                height - inset,
                boxPaint,
            )
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = page ?: return false
        if (bitmap.width == 0) return false
        // Preview draws widened rects, so a drag would move a box away from the block it stands
        // for. Declining the gesture entirely also leaves the page scrollable, which is what
        // someone reading a translation wants from it anyway.
        if (previewMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y

                val selected = boxes.firstOrNull { it.id == selectedId }
                val grabbed = selected?.let { grabCorner(it.rect.toView(), event.x, event.y) }
                // Topmost first, so a block drawn over another can still be picked up.
                val hit = boxes.lastOrNull { it.rect.toView().contains(event.x, event.y) }
                mode = when {
                    grabbed != null -> {
                        corner = grabbed
                        startRect = Rect(selected!!.rect)
                        Mode.RESIZE
                    }

                    hit != null -> {
                        startRect = Rect(hit.rect)
                        if (hit.id != selectedId) {
                            selectedId = hit.id
                            onSelectionChanged?.invoke(hit.id)
                        }
                        Mode.MOVE
                    }

                    addMode -> Mode.CREATE

                    else -> Mode.NONE
                }
                // Claim the gesture only when it is going to be used for something. Requesting it
                // unconditionally is what made the page unscrollable: the scroll view was being
                // told to keep its hands off every touch, including the ones meant for it.
                if (mode != Mode.NONE) parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ((event.x - downX) / scale).roundToInt()
                val dy = ((event.y - downY) / scale).roundToInt()
                when (mode) {
                    Mode.MOVE -> {
                        val id = selectedId ?: return true
                        val moved = Rect(startRect).apply { offset(dx, dy) }
                        onBoxChanged?.invoke(id, moved.clampTo(bitmap))
                    }

                    Mode.RESIZE -> {
                        val id = selectedId ?: return true
                        onBoxChanged?.invoke(id, resized(startRect, dx, dy).clampTo(bitmap))
                    }

                    Mode.CREATE -> {
                        draft = Rect(
                            min(downX, event.x).toImage(),
                            min(downY, event.y).toImage(),
                            max(downX, event.x).toImage(),
                            max(downY, event.y).toImage(),
                        ).clampTo(bitmap)
                        invalidate()
                    }

                    Mode.NONE -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val moved = abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
                when {
                    mode == Mode.CREATE -> {
                        val drawn = draft
                        draft = null
                        if (moved && drawn != null && drawn.width() > MIN_SIZE &&
                            drawn.height() > MIN_SIZE
                        ) {
                            onBoxAdded?.invoke(drawn)
                        }
                    }

                    // A tap that went nowhere on empty space clears the selection. A CANCEL means
                    // the scroll view took the gesture over, which is not a tap.
                    mode == Mode.NONE && !moved && selectedId != null &&
                        event.actionMasked == MotionEvent.ACTION_UP -> {
                        selectedId = null
                        onSelectionChanged?.invoke(null)
                    }
                }
                mode = Mode.NONE
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Paints out a block and writes its translation into the space.
     *
     * The type size is found by bisection rather than computed, because text wrapping is not a
     * function you can invert: how tall a string lays out at a given size depends on where the
     * wrapping happens to fall, so the only reliable question is "does this size fit", asked
     * repeatedly. Twelve steps take it well below one pixel of resolution.
     *
     * Ink colour is chosen from the fill rather than fixed, which is what makes a white-on-black
     * bubble come out white-on-black instead of unreadable black-on-black.
     */
    private fun drawTranslated(canvas: Canvas, box: Box) {
        val view = box.rect.toView()
        val fill = box.fill ?: Color.WHITE
        fillPaint.color = fill
        canvas.drawRect(view, fillPaint)

        val text = box.translation
        if (text.isNullOrBlank()) return

        val width = (view.right - view.left).toInt() - textInset * 2
        val height = (view.bottom - view.top).toInt() - textInset * 2
        if (width <= 0 || height <= 0) return

        val ink = if (isDark(fill)) Color.WHITE else Color.BLACK
        // Fitting is twelve trial layouts per block, and onDraw can run for reasons that have
        // nothing to do with the text changing. Cached against the box, which is enough because
        // every path that alters a box goes through setBoxes.
        val layout = layoutCache.getOrPut(box.id) { layoutAtBestSize(text, width, height, ink) }

        // Centred vertically in whatever is left over, so a short line sits in the middle of the
        // bubble rather than clinging to its top edge.
        canvas.withTranslation(
            view.left + textInset,
            view.top + textInset + max(0f, (height - layout.height) / 2f),
        ) {
            layout.draw(this)
        }
    }

    /**
     * Bisects to the largest type size whose wrapped text still fits, and returns a layout at it.
     *
     * The returned layout gets a **paint of its own**, not the shared one. A [StaticLayout] holds a
     * reference to the paint it was built with and reads it again at draw time, so layouts sharing
     * one paint would every one of them draw at whichever size happened to be set last — with line
     * breaks computed for a different size entirely. Caching the layouts is what made that
     * reachable; a copy per layout is what makes caching safe.
     */
    private fun layoutAtBestSize(text: String, width: Int, height: Int, ink: Int): StaticLayout {
        val paint = TextPaint(textPaint).apply { color = ink }
        var low = MIN_TEXT_SP * density
        var high = MAX_TEXT_SP * density
        var best = low
        repeat(BISECTION_STEPS) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            if (layoutOf(text, width, paint).height <= height) {
                best = mid
                low = mid
            } else {
                high = mid
            }
        }
        paint.textSize = best
        return layoutOf(text, width, paint)
    }

    private fun layoutOf(text: String, width: Int, paint: TextPaint): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

    private fun isDark(color: Int): Boolean =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) < 128

    /** Applies a corner drag, then normalises so a crossed-over corner still yields a sane rect. */
    private fun resized(start: Rect, dx: Int, dy: Int): Rect {
        var left = start.left
        var top = start.top
        var right = start.right
        var bottom = start.bottom
        when (corner) {
            0 -> { left += dx; top += dy }
            1 -> { right += dx; top += dy }
            2 -> { right += dx; bottom += dy }
            3 -> { left += dx; bottom += dy }
        }
        return Rect(min(left, right), min(top, bottom), max(left, right), max(top, bottom))
    }

    private fun grabCorner(view: RectFView, x: Float, y: Float): Int? {
        val slop = handleRadius * 2f
        cornersOf(view).forEachIndexed { index, (cx, cy) ->
            if (abs(x - cx) <= slop && abs(y - cy) <= slop) return index
        }
        return null
    }

    private fun cornersOf(view: RectFView): List<Pair<Float, Float>> = listOf(
        view.left to view.top,
        view.right to view.top,
        view.right to view.bottom,
        view.left to view.bottom,
    )

    private fun Rect.clampTo(bitmap: Bitmap) = Rect(
        left.coerceIn(0, bitmap.width),
        top.coerceIn(0, bitmap.height),
        right.coerceIn(0, bitmap.width),
        bottom.coerceIn(0, bitmap.height),
    )

    private fun Float.toImage() = (this / scale).roundToInt()

    /** Image-space rect projected into view space. */
    private fun Rect.toView() = RectFView(
        left * scale,
        top * scale,
        right * scale,
        bottom * scale,
    )

    /**
     * A rect in view space. Deliberately not [android.graphics.RectF] — keeping the two coordinate
     * spaces in different types is what stops one being passed where the other belongs, which is
     * the only bug this class is really exposed to.
     */
    private data class RectFView(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
    }

    private fun Canvas.drawRect(view: RectFView, paint: Paint) =
        drawRect(view.left, view.top, view.right, view.bottom, paint)

    private companion object {
        const val HANDLE_DP = 7f
        const val TOUCH_DP = 6f

        /** Smallest block worth creating, in image pixels. */
        const val MIN_SIZE = 8

        /** Type-size search bounds, in sp before density scaling. */
        const val MIN_TEXT_SP = 4f
        const val MAX_TEXT_SP = 96f

        /** Bisection steps for the fit; twelve resolves the size to well under a pixel. */
        const val BISECTION_STEPS = 12

        val DRAFT_COLOR = "#2196F3".toColorInt()
    }
}
