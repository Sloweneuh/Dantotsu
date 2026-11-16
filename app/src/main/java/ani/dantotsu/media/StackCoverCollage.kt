package ani.dantotsu.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlin.math.max
import kotlin.math.sin

/**
 * A stack's covers, as MangaBaka's own site draws them: the first cover whole in front, and the
 * next two behind it — smaller, tilted and standing out to either side — so the card reads as a pile
 * of titles. A stack with fewer covers shows just those (two make a pair; a Comick list its one
 * cover), and one with none shows its icon.
 *
 * Drawn in one view ([StackCoversView]) rather than as several transformed ImageViews: rotated
 * views aren't anti-aliased, and their combined transforms left the fan lopsided, one back cover
 * pushed under the front and cut short.
 */
object StackCoverCollage {

    @Suppress("UNUSED_PARAMETER") // The count was a badge on the covers; callers still pass it.
    fun bind(container: FrameLayout, covers: List<String>, entries: Int) {
        val view = (0 until container.childCount).map { container.getChildAt(it) }
            .filterIsInstance<StackCoversView>().firstOrNull()
            ?: StackCoversView(container.context).also {
                container.removeAllViews()
                container.addView(
                    it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
            }
        view.setCovers(covers.filter { it.isNotBlank() }.take(3))
        // The back covers' top tips lean past the box: everything up to the list draws them, padding
        // included. A card holding the whole item (the large layout's banner card) keeps its rounded
        // clip, its own padding leaving the tips room.
        var parent: android.view.ViewParent? = container
        while (parent is ViewGroup) {
            if (parent is androidx.cardview.widget.CardView && parent.id != R.id.itemCompactCard) break
            parent.clipChildren = false
            if (parent is androidx.recyclerview.widget.RecyclerView) break
            parent.clipToPadding = false
            if (parent is androidx.cardview.widget.CardView) parent.clipToOutline = false
            parent = parent.parent
        }
    }
}

/** See [StackCoverCollage]. */
class StackCoversView(context: Context) : View(context) {

    private companion object {
        /** How much smaller the back covers are than the front one. */
        const val BACK_SCALE = 0.85f

        /** How far the back covers turn, degrees, about their bottom edge like fanned cards. */
        const val BACK_ROTATION = 6f

        /** How far the back covers stand out past the front one, as a share of its width. */
        const val BACK_SHIFT = 0.32f

        /** The back covers' opacity, so the eye goes to the front one. */
        const val BACK_ALPHA = 225
    }

    private val dp = resources.displayMetrics.density
    private val corner = 10 * dp

    private var urls: List<String> = emptyList()
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private val targets = mutableListOf<CustomTarget<Bitmap>>()

    private val placeholderColor = context.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
    private val placeholderIcon: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_round_collections_bookmark_24)?.mutate()?.apply {
            setTint(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }

    /** Anti-aliased and filtered, so the tilted covers' edges and pixels come out smooth. */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** A soft shadow under each cover; hardware canvases draw shadow layers on shapes from API 28. */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            shadowPaint.setShadowLayer(4 * dp, 0f, 2 * dp, 0x55000000)
        }
    }

    fun setCovers(covers: List<String>) {
        if (covers == urls && targets.isNotEmpty()) return
        targets.forEach { Glide.with(this).clear(it) }
        targets.clear()
        bitmaps.fill(null)
        urls = covers
        covers.forEachIndexed { i, url ->
            val target = object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (urls.getOrNull(i) != url) return
                    bitmaps[i] = resource
                    invalidate()
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    bitmaps[i] = null
                    invalidate()
                }
            }
            targets += target
            Glide.with(this).asBitmap().load(url).into(target)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val backs = (urls.size - 1).coerceIn(0, 2)
        // Sized so everything stays inside the view: a back cover's outer edge is its shift, half
        // its width, and what its tilt swings the top corner out by. Two covers are a centred pair,
        // standing out half as far each.
        val spread = when (backs) {
            2 -> BACK_SHIFT
            1 -> BACK_SHIFT / 2
            else -> 0f
        }
        // Measured to a back cover's bottom outer corner, which sits on the side so it lines up with
        // the name under the card; turning about its bottom, the cover's top tip leans past the side
        // (the views around let it draw there, see [StackCoverCollage.bind]).
        val cosTilt = kotlin.math.cos(Math.toRadians(BACK_ROTATION.toDouble())).toFloat()
        val reach = if (backs == 0) 0.5f else spread + BACK_SCALE / 2 * cosTilt
        // The reach above is to the corner point; a rounded corner's visible edge sits about 0.29 of
        // the radius inside it, so the corner point may go that much past the side.
        val sideMargin = -corner * 0.29f
        val margin = 4 * dp
        val frontWidth = minOf((width / 2f - sideMargin) / reach, (height - 2 * margin) * 2 / 3f)
        val frontHeight = frontWidth * 1.5f
        val centerX = width / 2f
        val centerY = height / 2f
        val shift = frontWidth * spread

        // Back covers first, so the front one is drawn over them; the pair's front cover stands a
        // little right of centre over the other.
        val frontX = if (backs == 1) centerX + shift else centerX
        val sides = if (backs == 1) listOf(-1) else listOf(-1, 1).take(backs)
        sides.forEachIndexed { i, side ->
            val w = frontWidth * BACK_SCALE
            val h = frontHeight * BACK_SCALE
            val x = centerX + side * shift
            canvas.save()
            // Centred on the front cover's middle, turned about the middle of its own bottom edge.
            canvas.rotate(side * BACK_ROTATION, x, centerY + h / 2)
            rect.set(x - w / 2, centerY - h / 2, x + w / 2, centerY + h / 2)
            drawCover(canvas, bitmaps[i + 1], rect, BACK_ALPHA, front = false)
            canvas.restore()
        }
        rect.set(frontX - frontWidth / 2, centerY - frontHeight / 2, frontX + frontWidth / 2, centerY + frontHeight / 2)
        drawCover(canvas, bitmaps[0], rect, 255, front = true)
    }

    /** [bitmap] centre-cropped into [bounds] with rounded corners, or a plain card while it loads. */
    private fun drawCover(canvas: Canvas, bitmap: Bitmap?, bounds: RectF, alpha: Int, front: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            shadowPaint.color = placeholderColor
            shadowPaint.alpha = alpha
            canvas.drawRoundRect(bounds, corner, corner, shadowPaint)
        }
        if (bitmap == null || bitmap.isRecycled) {
            paint.shader = null
            paint.color = placeholderColor
            paint.alpha = alpha
            canvas.drawRoundRect(bounds, corner, corner, paint)
            if (front && urls.isEmpty()) placeholderIcon?.let { icon ->
                val size = (24 * dp).toInt()
                val left = (bounds.centerX() - size / 2).toInt()
                val top = (bounds.centerY() - size / 2).toInt()
                icon.setBounds(left, top, left + size, top + size)
                icon.draw(canvas)
            }
            return
        }
        val scale = max(bounds.width() / bitmap.width, bounds.height() / bitmap.height)
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            bounds.centerX() - bitmap.width * scale / 2,
            bounds.centerY() - bitmap.height * scale / 2,
        )
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        paint.color = 0xFFFFFFFF.toInt()
        paint.alpha = alpha
        canvas.drawRoundRect(bounds, corner, corner, paint)
        paint.shader = null
    }
}
