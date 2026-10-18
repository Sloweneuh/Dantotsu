package ani.dantotsu.settings

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemSubtitleStyleTileBinding
import com.google.android.material.color.MaterialColors

/** How a subtitle style tile is edited once tapped. */
enum class SubtitleTileEditor { SLIDER, CHOICE, COLOR }

/**
 * One subtitle style option, as a tile: its [label], what it's set to ([value]) and, for a colour,
 * a [swatch]. [enabled] is false when the setting can't apply right now (subtitles off, or a
 * text-renderer-only setting with the other renderer in use).
 */
data class SubtitleStyleTile(
    val id: String,
    val icon: Int,
    val label: String,
    val value: String,
    val editor: SubtitleTileEditor,
    val enabled: Boolean = true,
    val swatch: Drawable? = null,
)

/**
 * The subtitle style options laid out as a grid of tiles rather than one long column of rows: each
 * shows what it's set to, so the whole look can be read at a glance next to the preview, and the
 * one being edited is outlined while its editor is open.
 */
class SubtitleStyleTileAdapter(
    private val onTap: (SubtitleStyleTile) -> Unit,
) : RecyclerView.Adapter<SubtitleStyleTileAdapter.Holder>() {

    var tiles: List<SubtitleStyleTile> = emptyList()
        private set

    /** The tile whose editor is open, outlined; null when none is. */
    var selectedId: String? = null
        private set

    class Holder(val binding: ItemSubtitleStyleTileBinding) : RecyclerView.ViewHolder(binding.root)

    @Suppress("NotifyDataSetChanged") // Values and enabled states can all change together.
    fun submit(tiles: List<SubtitleStyleTile>) {
        this.tiles = tiles
        notifyDataSetChanged()
    }

    /** Swaps in an updated [tile] without touching the others, for a live slider reading. */
    fun update(tile: SubtitleStyleTile) {
        val index = tiles.indexOfFirst { it.id == tile.id }
        if (index < 0) return
        tiles = tiles.toMutableList().also { it[index] = tile }
        notifyItemChanged(index)
    }

    fun select(id: String?) {
        val previous = selectedId
        selectedId = id
        tiles.forEachIndexed { index, tile ->
            if (tile.id == previous || tile.id == id) notifyItemChanged(index)
        }
    }

    override fun getItemCount() = tiles.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemSubtitleStyleTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tile = tiles[position]
        val b = holder.binding
        val selected = tile.id == selectedId
        b.subtitleTileIcon.setImageResource(tile.icon)
        b.subtitleTileLabel.text = tile.label
        b.subtitleTileValue.text = tile.value
        b.subtitleTileSwatch.visibility = if (tile.swatch != null) View.VISIBLE else View.GONE
        b.subtitleTileSwatch.setImageDrawable(tile.swatch)

        val card = b.subtitleTileCard
        card.setCardBackgroundColor(SectionCard.fill(card, open = selected))
        card.strokeWidth = if (selected) (2 * card.resources.displayMetrics.density).toInt() else 0
        card.setStrokeColor(
            MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)
        )
        card.alpha = if (tile.enabled) 1f else 0.4f
        card.isEnabled = tile.enabled
        card.setOnClickListener { if (tile.enabled) onTap(tile) }
    }
}
