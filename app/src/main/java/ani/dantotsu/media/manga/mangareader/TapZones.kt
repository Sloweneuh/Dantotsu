package ani.dantotsu.media.manga.mangareader

import ani.dantotsu.media.manga.mangareader.MangaReaderActivity.PressPos
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.util.Locale

/**
 * Custom tap zones: boxes over the reader, each going back a page, going forward a page or opening
 * the menu (the reader's overlay); a tap outside every box opens the menu too. Box edges are
 * fractions of the reader's width and height, so a layout holds in both orientations. Stored in
 * ReaderCustomTapZones as `B:left,top,right,bottom;F:…;M:…`.
 *
 * Also where a tap in the paged reader lands, for every tap-zone layout: see [sideAt].
 */
object TapZones {

    enum class Zone(val code: Char) { BACK('B'), FORWARD('F'), MENU('M') }

    data class Box(val zone: Zone, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    }

    fun parse(encoded: String): List<Box> {
        // The first custom layouts were a 6 × 6 grid string, with no ':' in it.
        if (encoded.isNotBlank() && ':' !in encoded) return EDGES
        return encoded.split(';').mapNotNull { entry ->
            val zone = Zone.entries.firstOrNull { it.code == entry.substringBefore(':').singleOrNull() }
                ?: return@mapNotNull null
            val edges = entry.substringAfter(':').split(',').mapNotNull { it.toFloatOrNull() }
            if (edges.size != 4) return@mapNotNull null
            val (l, t, r, b) = edges.map { it.coerceIn(0f, 1f) }
            if (r <= l || b <= t) null else Box(zone, l, t, r, b)
        }
    }

    fun encode(boxes: List<Box>): String = boxes.joinToString(";") { b ->
        "${b.zone.code}:" + listOf(b.left, b.top, b.right, b.bottom)
            .joinToString(",") { String.format(Locale.ROOT, "%.4f", it) }
    }

    /** The zone a tap at ([x], [y]) — fractions of the reader — falls in; the last box drawn wins. */
    fun zoneAt(boxes: List<Box>, x: Float, y: Float): Zone? = boxes.lastOrNull { it.contains(x, y) }?.zone

    private const val THIRD = 1f / 3
    private const val TWO_THIRDS = 2f / 3

    /** The original layout: a fifth each side, below a top strip that opens the menu. */
    val EDGES = listOf(
        Box(Zone.MENU, 0f, 0f, 1f, 0.1f),
        Box(Zone.BACK, 0f, 0.1f, 0.2f, 1f),
        Box(Zone.MENU, 0.2f, 0.1f, 0.8f, 1f),
        Box(Zone.FORWARD, 0.8f, 0.1f, 1f, 1f),
    )

    val THIRDS = listOf(
        Box(Zone.BACK, 0f, 0f, THIRD, 1f),
        Box(Zone.MENU, THIRD, 0f, TWO_THIRDS, 1f),
        Box(Zone.FORWARD, TWO_THIRDS, 0f, 1f, 1f),
    )

    val L_SHAPE = listOf(
        Box(Zone.BACK, 0f, 0f, 1f, THIRD),
        Box(Zone.BACK, 0f, THIRD, THIRD, TWO_THIRDS),
        Box(Zone.MENU, THIRD, THIRD, TWO_THIRDS, TWO_THIRDS),
        Box(Zone.FORWARD, TWO_THIRDS, THIRD, 1f, TWO_THIRDS),
        Box(Zone.FORWARD, 0f, TWO_THIRDS, 1f, 1f),
    )

    /**
     * Which side of the screen a tap at ([x], [y]) in a [w] × [h] reader counts as for turning pages,
     * by the chosen tap-zone layout (ReaderTapZones):
     *  - 0 Edges: the left and right fifths, below a top strip the width of a fifth (the original).
     *  - 1 Thirds: the left and right thirds; the middle third opens the menu.
     *  - 2 L-shape: a 3 × 3 grid — the top row and the middle row's left cell go back, the bottom
     *    row and the middle row's right cell go forward, the centre opens the menu.
     *  - 3 Off: every tap opens the menu.
     *  - 4 Custom: the user's own boxes (ReaderCustomTapZones, edited in TapZoneEditorActivity),
     *    as back and forward rather than sides, whatever the direction.
     * Inverted (ReaderTapZonesInverted) swaps the two sides.
     */
    fun sideAt(x: Int, y: Int, w: Int, h: Int): PressPos {
        if (w <= 0 || h <= 0) return PressPos.CENTER
        val side = when (PrefManager.getVal<Int>(PrefName.ReaderTapZones)) {
            1 -> when {
                x < w / 3 -> PressPos.LEFT
                x > w - w / 3 -> PressPos.RIGHT
                else -> PressPos.CENTER
            }
            2 -> when {
                y < h / 3 -> PressPos.LEFT
                y > h - h / 3 -> PressPos.RIGHT
                x < w / 3 -> PressPos.LEFT
                x > w - w / 3 -> PressPos.RIGHT
                else -> PressPos.CENTER
            }
            3 -> PressPos.CENTER
            4 -> when (
                zoneAt(
                    parse(PrefManager.getVal(PrefName.ReaderCustomTapZones)),
                    x.toFloat() / w, y.toFloat() / h,
                )
            ) {
                Zone.BACK -> PressPos.LEFT
                Zone.FORWARD -> PressPos.RIGHT
                Zone.MENU, null -> PressPos.CENTER
            }
            else -> when {
                y <= w / 5 -> PressPos.CENTER
                x < w / 5 -> PressPos.LEFT
                x > w - w / 5 -> PressPos.RIGHT
                else -> PressPos.CENTER
            }
        }
        if (!PrefManager.getVal<Boolean>(PrefName.ReaderTapZonesInverted)) return side
        return when (side) {
            PressPos.LEFT -> PressPos.RIGHT
            PressPos.RIGHT -> PressPos.LEFT
            PressPos.CENTER -> PressPos.CENTER
        }
    }

    /** ReaderCustomTapZones' default: [EDGES]. */
    const val DEFAULT = "M:0.0000,0.0000,1.0000,0.1000;B:0.0000,0.1000,0.2000,1.0000;" +
        "M:0.2000,0.1000,0.8000,1.0000;F:0.8000,0.1000,1.0000,1.0000"
}
