package ani.dantotsu.media.screenshot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Free-form crop overlay for the screenshot sheet: a draggable/resizable rectangle over whatever
 * the view covers, with everything outside it dimmed.
 *
 * The overlay assumes it exactly overlaps the image it crops (the preview `ImageView` uses
 * `adjustViewBounds`, so its drawable fills its bounds), which lets [cropRect] map the rectangle to
 * bitmap pixels with a plain proportional scale.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val crop = RectF()

    private val scrimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(1.5f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66FFFFFF
        strokeWidth = dp(1f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }

    /**
     * Drawn underneath [borderPaint]/[handlePaint] as a wider dark stroke so the white grips stay
     * legible over pale screenshots (a shadow layer isn't reliable for lines on a HW canvas).
     */
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x80000000.toInt()
        strokeWidth = dp(7f)
        strokeCap = Paint.Cap.ROUND
    }
    private val borderOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66000000
        strokeWidth = dp(3f)
    }

    private val touchSlop = dp(24f)
    private val minSize = dp(48f)
    private val cornerLength = dp(26f)
    private val edgeLength = dp(32f)

    /** Handle geometry, rebuilt on each draw: 8 corner strokes + 4 edge strokes, 4 floats each. */
    private val handleLines = FloatArray(12 * 4)

    private var dragLeft = false
    private var dragTop = false
    private var dragRight = false
    private var dragBottom = false
    private var dragMove = false
    private var lastX = 0f
    private var lastY = 0f
    private var resetPending = false

    /**
     * Expands the crop back out to the whole image. Safe to call before the overlay has been laid
     * out (e.g. right as it's made visible) — the selection is then opened in [onSizeChanged].
     */
    fun reset() {
        if (width <= 0 || height <= 0) {
            resetPending = true
            return
        }
        resetPending = false
        crop.set(0f, 0f, width.toFloat(), height.toFloat())
        invalidate()
    }

    /** True while the rectangle still covers (near enough) the entire image. */
    fun isFullFrame(): Boolean {
        val e = 1f
        return crop.left <= e && crop.top <= e &&
            crop.right >= width - e && crop.bottom >= height - e
    }

    /** The current selection mapped onto a bitmap of [bitmapWidth] x [bitmapHeight] pixels. */
    fun cropRect(bitmapWidth: Int, bitmapHeight: Int): Rect {
        if (width <= 0 || height <= 0) return Rect(0, 0, bitmapWidth, bitmapHeight)
        val scaleX = bitmapWidth.toFloat() / width
        val scaleY = bitmapHeight.toFloat() / height
        val left = (crop.left * scaleX).roundToInt().coerceIn(0, bitmapWidth - 1)
        val top = (crop.top * scaleY).roundToInt().coerceIn(0, bitmapHeight - 1)
        val right = (crop.right * scaleX).roundToInt().coerceIn(left + 1, bitmapWidth)
        val bottom = (crop.bottom * scaleY).roundToInt().coerceIn(top + 1, bitmapHeight)
        return Rect(left, top, right, bottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Keep the current selection proportionally when the view is re-laid out (e.g. rotation).
        if (resetPending || crop.isEmpty || oldw <= 0 || oldh <= 0) {
            resetPending = false
            crop.set(0f, 0f, w.toFloat(), h.toFloat())
        } else {
            val scaleX = w.toFloat() / oldw
            val scaleY = h.toFloat() / oldh
            crop.set(
                crop.left * scaleX, crop.top * scaleY,
                crop.right * scaleX, crop.bottom * scaleY
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (crop.isEmpty) return
        val w = width.toFloat()
        val h = height.toFloat()

        // Dim everything outside the selection (four bands; clipOutRect needs API 26).
        canvas.drawRect(0f, 0f, w, crop.top, scrimPaint)
        canvas.drawRect(0f, crop.bottom, w, h, scrimPaint)
        canvas.drawRect(0f, crop.top, crop.left, crop.bottom, scrimPaint)
        canvas.drawRect(crop.right, crop.top, w, crop.bottom, scrimPaint)

        // Rule-of-thirds guides.
        val thirdX = crop.width() / 3f
        val thirdY = crop.height() / 3f
        for (i in 1..2) {
            val x = crop.left + thirdX * i
            val y = crop.top + thirdY * i
            canvas.drawLine(x, crop.top, x, crop.bottom, gridPaint)
            canvas.drawLine(crop.left, y, crop.right, y, gridPaint)
        }

        canvas.drawRect(crop, borderOutlinePaint)
        canvas.drawRect(crop, borderPaint)

        // Corner and mid-edge grips, each laid down as a dark halo then the white stroke.
        val count = buildHandles()
        canvas.drawLines(handleLines, 0, count, outlinePaint)
        canvas.drawLines(handleLines, 0, count, handlePaint)
    }

    /**
     * Fills [handleLines] with the grip strokes for the current selection and returns how many
     * floats are in use. Lengths shrink with the selection so the grips never meet on a small crop.
     */
    private fun buildHandles(): Int {
        val shortest = min(crop.width(), crop.height())
        val corner = min(cornerLength, shortest / 3f)
        var i = 0
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            handleLines[i++] = x1; handleLines[i++] = y1
            handleLines[i++] = x2; handleLines[i++] = y2
        }
        // Corners: an L on the inside of each one.
        line(crop.left, crop.top, crop.left + corner, crop.top)
        line(crop.left, crop.top, crop.left, crop.top + corner)
        line(crop.right - corner, crop.top, crop.right, crop.top)
        line(crop.right, crop.top, crop.right, crop.top + corner)
        line(crop.left, crop.bottom, crop.left + corner, crop.bottom)
        line(crop.left, crop.bottom - corner, crop.left, crop.bottom)
        line(crop.right - corner, crop.bottom, crop.right, crop.bottom)
        line(crop.right, crop.bottom - corner, crop.right, crop.bottom)

        // Mid-edge bars, only while there's room for them clear of the corner grips.
        val centerX = crop.centerX()
        val centerY = crop.centerY()
        val barH = min(edgeLength, crop.width() - corner * 2f - dp(16f))
        val barV = min(edgeLength, crop.height() - corner * 2f - dp(16f))
        if (barH > 0f) {
            line(centerX - barH / 2f, crop.top, centerX + barH / 2f, crop.top)
            line(centerX - barH / 2f, crop.bottom, centerX + barH / 2f, crop.bottom)
        }
        if (barV > 0f) {
            line(crop.left, centerY - barV / 2f, crop.left, centerY + barV / 2f)
            line(crop.right, centerY - barV / 2f, crop.right, centerY + barV / 2f)
        }
        return i
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val nearY = y >= crop.top - touchSlop && y <= crop.bottom + touchSlop
                val nearX = x >= crop.left - touchSlop && x <= crop.right + touchSlop
                dragLeft = nearY && abs(x - crop.left) <= touchSlop
                dragRight = nearY && !dragLeft && abs(x - crop.right) <= touchSlop
                dragTop = nearX && abs(y - crop.top) <= touchSlop
                dragBottom = nearX && !dragTop && abs(y - crop.bottom) <= touchSlop
                dragMove = !dragLeft && !dragRight && !dragTop && !dragBottom &&
                    crop.contains(x, y)
                if (!dragLeft && !dragRight && !dragTop && !dragBottom && !dragMove) return false
                lastX = x
                lastY = y
                // The sheet scrolls; hold on to the gesture once a grip is taken.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                if (dragMove) {
                    val clampedX = dx.coerceIn(-crop.left, width - crop.right)
                    val clampedY = dy.coerceIn(-crop.top, height - crop.bottom)
                    crop.offset(clampedX, clampedY)
                } else {
                    if (dragLeft) crop.left = (crop.left + dx).coerceIn(0f, crop.right - minSize)
                    if (dragRight) crop.right =
                        (crop.right + dx).coerceIn(crop.left + minSize, width.toFloat())
                    if (dragTop) crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - minSize)
                    if (dragBottom) crop.bottom =
                        (crop.bottom + dy).coerceIn(crop.top + minSize, height.toFloat())
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragLeft = false; dragRight = false; dragTop = false; dragBottom = false
                dragMove = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
