package ani.dantotsu.media.manga.mangareader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityTapZoneEditorBinding
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.media.manga.mangareader.TapZones.Zone
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The custom tap-zone editor: a mock page filling the screen as the reader does, with the back,
 * forward and menu zones over it to drag and resize. Saves to ReaderCustomTapZones; leaving with unsaved
 * changes asks first.
 */
class TapZoneEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTapZoneEditorBinding
    private lateinit var saved: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityTapZoneEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBarsExtendView()

        val zones = binding.tapZoneBoxes
        saved = TapZones.encode(TapZones.parse(PrefManager.getVal(PrefName.ReaderCustomTapZones)))
        zones.setBoxes(TapZones.parse(savedInstanceState?.getString(STATE_BOXES) ?: saved))

        // Clear of a camera cutout; the system bars stay hidden, as in the reader.
        val margin = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            binding.tapZoneHint.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = margin + cutout.top
            }
            binding.tapZoneBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = margin + cutout.bottom
                leftMargin = margin + cutout.left
                rightMargin = margin + cutout.right
            }
            insets
        }

        listOf(binding.tapZonePresets, binding.tapZoneAdd, binding.tapZoneFlip,
            binding.tapZoneDelete, binding.tapZoneSave).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }

        zones.onSelectionChanged = {
            binding.tapZoneFlip.isEnabled = zones.selected >= 0
            binding.tapZoneDelete.isEnabled = zones.selected >= 0
        }
        // The tools fade while a zone is dragged, so the ones under them stay in view; the hint
        // has been read once something moves.
        zones.onDragging = { dragging ->
            binding.tapZoneBar.animate().alpha(if (dragging) 0.15f else 1f).setDuration(150).start()
            if (dragging && binding.tapZoneHint.isVisible) {
                binding.tapZoneHint.animate().alpha(0f).setDuration(150)
                    .withEndAction { binding.tapZoneHint.isVisible = false }.start()
            }
        }

        binding.tapZonePresets.setOnClickListener { anchor ->
            val presets = listOf(
                R.string.reader_tap_zones_edges to TapZones.EDGES,
                R.string.reader_tap_zones_thirds to TapZones.THIRDS,
                R.string.reader_tap_zones_l to TapZones.L_SHAPE,
                R.string.reader_tap_zones_clear to emptyList(),
            )
            PopupMenu(this, anchor).apply {
                presets.forEachIndexed { i, (name, _) -> menu.add(0, i, i, name) }
                setOnMenuItemClickListener { zones.setBoxes(presets[it.itemId].second); true }
                show()
            }
        }
        binding.tapZoneAdd.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add(0, 0, 0, R.string.reader_tap_zones_add_back)
                menu.add(0, 1, 1, R.string.reader_tap_zones_add_forward)
                menu.add(0, 2, 2, R.string.reader_tap_zones_add_menu)
                setOnMenuItemClickListener { zones.add(Zone.entries[it.itemId]); true }
                show()
            }
        }
        binding.tapZoneFlip.setOnClickListener { zones.cycleSelected() }
        binding.tapZoneDelete.setOnClickListener { zones.deleteSelected() }
        binding.tapZoneSave.setOnClickListener {
            PrefManager.setVal(PrefName.ReaderCustomTapZones, TapZones.encode(zones.boxes()))
            finish()
        }

        onBackPressedDispatcher.addCallback(this) {
            if (TapZones.encode(zones.boxes()) == saved) {
                finish()
                return@addCallback
            }
            customAlertDialog().apply {
                setTitle(R.string.reader_tap_zones_discard)
                setPosButton(R.string.reader_tap_zones_discard_yes) { finish() }
                setNegButton(R.string.reader_tap_zones_keep)
                show()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBarsExtendView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_BOXES, TapZones.encode(binding.tapZoneBoxes.boxes()))
    }

    private companion object {
        const val STATE_BOXES = "boxes"
    }
}

/**
 * The zones being edited, over the whole reader area. Touching a zone selects it and brings it to
 * the front (the front one wins where zones overlap); dragging it moves it, and dragging one of the
 * selected zone's handles moves that corner or side. Edges snap to the screen's, its middle and the
 * other zones' edges.
 */
class TapZoneBoxesView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private class Editing(var zone: Zone, val rect: RectF)

    private val boxes = ArrayList<Editing>()

    var selected = -1
        private set
    var onSelectionChanged: (() -> Unit)? = null
    var onDragging: ((Boolean) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val backColour = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val onBackColour = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
    private val forwardColour = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)
    private val onForwardColour = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnTertiary)
    // Neutral, as the menu is the reader's own rather than a page turn.
    private val menuColour = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val onMenuColour = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val handleFill = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14 * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }
    private val px = RectF()
    private val pill = RectF()
    private val backIcon = ContextCompat.getDrawable(context, R.drawable.ic_round_arrow_back_ios_new_24)!!.mutate()
    private val forwardIcon = ContextCompat.getDrawable(context, R.drawable.ic_round_arrow_forward_ios_new_24)!!.mutate()
    private val menuIcon = ContextCompat.getDrawable(context, R.drawable.ic_round_menu_24)!!.mutate()
    private val backLabel = context.getString(R.string.reader_tap_zone_back)
    private val forwardLabel = context.getString(R.string.reader_tap_zone_forward)
    private val menuLabel = context.getString(R.string.reader_tap_zone_menu)

    fun setBoxes(list: List<TapZones.Box>) {
        boxes.clear()
        list.mapTo(boxes) { Editing(it.zone, RectF(it.left, it.top, it.right, it.bottom)) }
        select(-1)
    }

    fun boxes(): List<TapZones.Box> =
        boxes.map { TapZones.Box(it.zone, it.rect.left, it.rect.top, it.rect.right, it.rect.bottom) }

    /** A new zone in the middle, a little off any added before it so they don't stack exactly. */
    fun add(zone: Zone) {
        val shift = (boxes.size % 5) * 0.03f
        boxes += Editing(zone, RectF(0.35f + shift, 0.4f + shift, 0.65f + shift, 0.6f + shift))
        select(boxes.lastIndex)
    }

    /** The selected zone's next type: back → forward → menu → back. */
    fun cycleSelected() {
        val box = boxes.getOrNull(selected) ?: return
        box.zone = Zone.entries[(box.zone.ordinal + 1) % Zone.entries.size]
        invalidate()
    }

    fun deleteSelected() {
        if (selected !in boxes.indices) return
        boxes.removeAt(selected)
        select(-1)
    }

    private fun select(i: Int) {
        selected = i
        onSelectionChanged?.invoke()
        invalidate()
    }

    private fun toPx(r: RectF, out: RectF) =
        out.set(r.left * width, r.top * height, r.right * width, r.bottom * height)

    // Touch

    private enum class Mode { NONE, MOVE, RESIZE }

    private var mode = Mode.NONE
    private var dragLeft = false
    private var dragTop = false
    private var dragRight = false
    private var dragBottom = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val start = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** Which edges the selected zone's handle at ([x], [y]) moves, as left, top, right, bottom. */
    private fun handleAt(x: Float, y: Float): BooleanArray? {
        val box = boxes.getOrNull(selected) ?: return null
        toPx(box.rect, px)
        val reach = 24 * dp
        val xs = floatArrayOf(px.left, px.centerX(), px.right)
        val ys = floatArrayOf(px.top, px.centerY(), px.bottom)
        var best: BooleanArray? = null
        var bestDistance = reach
        for (i in 0..2) for (j in 0..2) {
            if (i == 1 && j == 1) continue
            val d = hypot(x - xs[i], y - ys[j])
            // Corners first: at equal distance the corner is what a finger means.
            val bias = if (i != 1 && j != 1) 0.8f else 1f
            if (d * bias < bestDistance) {
                bestDistance = d * bias
                best = booleanArrayOf(i == 0, j == 0, i == 2, j == 2)
            }
        }
        return best
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.NONE
                handleAt(x, y)?.let { edges ->
                    dragLeft = edges[0]; dragTop = edges[1]; dragRight = edges[2]; dragBottom = edges[3]
                    mode = Mode.RESIZE
                }
                if (mode == Mode.NONE) {
                    val i = boxes.indexOfLast {
                        toPx(it.rect, px)
                        px.contains(x, y)
                    }
                    if (i >= 0) {
                        boxes.add(boxes.removeAt(i))
                        select(boxes.lastIndex)
                        mode = Mode.MOVE
                    } else {
                        select(-1)
                    }
                }
                if (mode != Mode.NONE) {
                    start.set(boxes[selected].rect)
                    downX = x
                    downY = y
                    moved = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE || width == 0 || height == 0) return true
                if (!moved) {
                    if (hypot(x - downX, y - downY) < slop) return true
                    moved = true
                    onDragging?.invoke(true)
                }
                drag((x - downX) / width, (y - downY) / height)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moved) onDragging?.invoke(false)
                mode = Mode.NONE
                moved = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun drag(dx: Float, dy: Float) {
        val rect = boxes[selected].rect
        val snapX = 12 * dp / width
        val snapY = 12 * dp / height
        val xs = targets(horizontal = true)
        val ys = targets(horizontal = false)
        if (mode == Mode.MOVE) {
            val w = start.width()
            val h = start.height()
            var left = (start.left + dx).coerceIn(0f, 1f - w)
            var top = (start.top + dy).coerceIn(0f, 1f - h)
            left = (left + snapShift(left, left + w, xs, snapX)).coerceIn(0f, 1f - w)
            top = (top + snapShift(top, top + h, ys, snapY)).coerceIn(0f, 1f - h)
            rect.set(left, top, left + w, top + h)
            return
        }
        val minW = min(48 * dp / width, 1f)
        val minH = min(48 * dp / height, 1f)
        var left = start.left
        var top = start.top
        var right = start.right
        var bottom = start.bottom
        if (dragLeft) left = snap(start.left + dx, xs, snapX).coerceIn(0f, right - minW)
        if (dragRight) right = snap(start.right + dx, xs, snapX).coerceIn(left + minW, 1f)
        if (dragTop) top = snap(start.top + dy, ys, snapY).coerceIn(0f, bottom - minH)
        if (dragBottom) bottom = snap(start.bottom + dy, ys, snapY).coerceIn(top + minH, 1f)
        rect.set(left, top, right, bottom)
    }

    /** Where an edge may snap to: the screen's edges and middle, and the other zones' edges. */
    private fun targets(horizontal: Boolean): List<Float> = buildList {
        add(0f); add(0.5f); add(1f)
        boxes.forEachIndexed { i, box ->
            if (i == selected) return@forEachIndexed
            if (horizontal) { add(box.rect.left); add(box.rect.right) }
            else { add(box.rect.top); add(box.rect.bottom) }
        }
    }

    private fun snap(value: Float, targets: List<Float>, reach: Float): Float =
        targets.minByOrNull { abs(it - value) }?.takeIf { abs(it - value) < reach } ?: value

    /** How far to shift a span from [a] to [b] so its nearer edge lands on a target, or 0. */
    private fun snapShift(a: Float, b: Float, targets: List<Float>, reach: Float): Float {
        var best = 0f
        var bestDistance = reach
        for (t in targets) for (edge in floatArrayOf(a, b)) {
            val d = abs(t - edge)
            if (d < bestDistance) {
                bestDistance = d
                best = t - edge
            }
        }
        return best
    }

    // Drawing

    override fun onDraw(canvas: Canvas) {
        val radius = 12 * dp
        boxes.forEachIndexed { i, box ->
            val isSelected = i == selected
            val (colour, onColour) = when (box.zone) {
                Zone.BACK -> backColour to onBackColour
                Zone.FORWARD -> forwardColour to onForwardColour
                Zone.MENU -> menuColour to onMenuColour
            }
            toPx(box.rect, px)

            fill.color = MaterialColors.compositeARGBWithAlpha(colour, if (isSelected) 120 else 80)
            canvas.drawRoundRect(px, radius, radius, fill)
            stroke.color = colour
            stroke.strokeWidth = (if (isSelected) 3f else 1.5f) * dp
            val inset = stroke.strokeWidth / 2
            px.inset(inset, inset)
            canvas.drawRoundRect(px, radius, radius, stroke)
            px.inset(-inset, -inset)

            drawLabel(canvas, box.zone, colour, onColour)
            if (isSelected) drawHandles(canvas, colour)
        }
    }

    /** The zone's arrow and name on a pill in its middle, or the arrow alone when it's narrow. */
    private fun drawLabel(canvas: Canvas, zone: Zone, colour: Int, onColour: Int) {
        val (icon, label) = when (zone) {
            Zone.BACK -> backIcon to backLabel
            Zone.FORWARD -> forwardIcon to forwardLabel
            Zone.MENU -> menuIcon to menuLabel
        }
        val iconSize = 18 * dp
        val pad = 12 * dp
        val height = 32 * dp
        val full = pad + iconSize + 6 * dp + text.measureText(label) + pad
        val withText = full <= px.width() - 8 * dp && height <= px.height() - 8 * dp
        val width = if (withText) full else height
        pill.set(px.centerX() - width / 2, px.centerY() - height / 2,
            px.centerX() + width / 2, px.centerY() + height / 2)
        fill.color = colour
        canvas.drawRoundRect(pill, height / 2, height / 2, fill)
        val iconLeft = if (withText) pill.left + pad else pill.centerX() - iconSize / 2
        icon.setTint(onColour)
        icon.setBounds(iconLeft.toInt(), (pill.centerY() - iconSize / 2).toInt(),
            (iconLeft + iconSize).toInt(), (pill.centerY() + iconSize / 2).toInt())
        icon.draw(canvas)
        if (withText) {
            text.color = onColour
            val baseline = pill.centerY() - (text.descent() + text.ascent()) / 2
            canvas.drawText(label, iconLeft + iconSize + 6 * dp, baseline, text)
        }
    }

    private fun drawHandles(canvas: Canvas, colour: Int) {
        val r = 7 * dp
        val xs = floatArrayOf(px.left, px.centerX(), px.right)
        val ys = floatArrayOf(px.top, px.centerY(), px.bottom)
        stroke.color = colour
        stroke.strokeWidth = 2.5f * dp
        fill.color = handleFill
        for (i in 0..2) for (j in 0..2) {
            if (i == 1 && j == 1) continue
            // Kept on screen at the edges, where half a handle would be lost.
            val cx = xs[i].coerceIn(r + stroke.strokeWidth, width - r - stroke.strokeWidth)
            val cy = ys[j].coerceIn(r + stroke.strokeWidth, height - r - stroke.strokeWidth)
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, stroke)
        }
    }
}

/**
 * A stand-in manga page for the tap-zone editor: paper, panels, a figure and a speech bubble in
 * each — enough to place zones against without loading a real one. Drawn in print colours, as a
 * page would be, whatever the theme.
 */
class FakeMangaPageView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val paper = Color.rgb(246, 243, 236)
    private val ink = Color.rgb(30, 30, 30)
    private val screentone = Color.rgb(222, 218, 210)
    private val shade = Color.rgb(196, 191, 182)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ink
        strokeCap = Paint.Cap.ROUND
    }
    private val panel = RectF()
    private val shape = RectF()
    private val tail = Path()

    // Panels as fractions of the page inside its margins.
    private val portrait = listOf(
        RectF(0f, 0f, 1f, 0.3f),
        RectF(0f, 0.3f, 0.42f, 0.66f), RectF(0.42f, 0.3f, 1f, 0.66f),
        RectF(0f, 0.66f, 0.62f, 1f), RectF(0.62f, 0.66f, 1f, 1f),
    )
    private val landscape = listOf(
        RectF(0f, 0f, 0.36f, 0.5f), RectF(0.36f, 0f, 1f, 0.5f),
        RectF(0f, 0.5f, 0.58f, 1f), RectF(0.58f, 0.5f, 1f, 1f),
    )

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(paper)
        val w = width.toFloat()
        val h = height.toFloat()
        val margin = min(w, h) * 0.06f
        val gutter = min(w, h) * 0.025f
        val innerW = w - margin * 2
        val innerH = h - margin * 2
        (if (w > h) landscape else portrait).forEachIndexed { i, p ->
            panel.set(
                margin + p.left * innerW + gutter / 2, margin + p.top * innerH + gutter / 2,
                margin + p.right * innerW - gutter / 2, margin + p.bottom * innerH - gutter / 2,
            )
            drawPanel(canvas, i)
        }
    }

    private fun drawPanel(canvas: Canvas, index: Int) {
        val pw = panel.width()
        val ph = panel.height()
        canvas.save()
        canvas.clipRect(panel)
        paint.color = screentone
        canvas.drawRect(panel.left, panel.top + ph * 0.72f, panel.right, panel.bottom, paint)

        // A figure: head and shoulders, on alternating sides.
        val cx = panel.left + pw * (if (index % 2 == 0) 0.62f else 0.38f)
        val head = min(pw, ph) * 0.16f
        val headY = panel.top + ph * 0.5f
        paint.color = shade
        shape.set(cx - head * 2f, headY + head * 0.9f, cx + head * 2f, headY + head * 5f)
        canvas.drawOval(shape, paint)
        paint.color = paper
        canvas.drawCircle(cx, headY, head, paint)
        line.strokeWidth = 1.5f * dp
        canvas.drawCircle(cx, headY, head, line)
        canvas.drawOval(shape, line)

        // A speech bubble in the far top corner, with two lines of "text".
        val left = index % 2 == 0
        val bw = pw * 0.42f
        val bh = min(ph * 0.3f, bw * 0.6f)
        val bx = if (left) panel.left + pw * 0.06f else panel.right - pw * 0.06f - bw
        val by = panel.top + ph * 0.08f
        shape.set(bx, by, bx + bw, by + bh)
        tail.reset()
        tail.moveTo(shape.centerX() - bw * 0.08f, shape.bottom - bh * 0.1f)
        tail.lineTo(cx + (if (left) -head else head) * 0.5f, headY - head * 1.3f)
        tail.lineTo(shape.centerX() + bw * 0.08f, shape.bottom - bh * 0.1f)
        tail.close()
        paint.color = Color.WHITE
        canvas.drawPath(tail, paint)
        canvas.drawPath(tail, line)
        canvas.drawOval(shape, paint)
        canvas.drawOval(shape, line)
        line.strokeWidth = 2f * dp
        line.color = shade
        for (row in 0..1) {
            val ly = shape.top + bh * (0.4f + row * 0.22f)
            val inset = bw * (0.22f + row * 0.08f)
            canvas.drawLine(shape.left + inset, ly, shape.right - inset, ly, line)
        }
        line.color = ink
        canvas.restore()

        line.strokeWidth = 2.5f * dp
        canvas.drawRect(panel, line)
    }
}
