package ani.dantotsu.settings

import android.content.res.ColorStateList
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.buildSpannedString
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.getThemeColor

/**
 * @param fetching whether this connection is switched on at all. A tab whose connection never
 *   fetches can't appear however it's ticked here, so the row is shown but inert — see the note in
 *   [InfoTabOrderAdapter.onBindViewHolder] for why it's shown rather than omitted.
 */
data class InfoTabOrderItem(
    val id: Int,
    val name: String,
    val iconRes: Int,
    var visible: Boolean,
    val fetching: Boolean = true,
)

/** Vertical counterpart to [HomeLayoutAdapter] with an added per-row connection icon. */
class InfoTabOrderAdapter(private val items: MutableList<InfoTabOrderItem>) :
    RecyclerView.Adapter<InfoTabOrderAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val handle: ImageView = view.findViewById(R.id.infoTabHandle)
        val icon: ImageView = view.findViewById(R.id.infoTabIcon)
        val text: TextView = view.findViewById(R.id.infoTabText)
        val checkbox: CheckBox = view.findViewById(R.id.infoTabCheckbox)
    }

    /** Whether the row at [position] can be ticked or dragged. */
    fun isActionable(position: Int): Boolean = items.getOrNull(position)?.fetching ?: false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_info_tab_choice, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.icon.setImageDrawable(ContextCompat.getDrawable(context, item.iconRes))
        // The base drawables carry their own colors; force the same tint the real TabLayout
        // icons get (app:tabIconTint="?attr/colorOnBackground") so they read consistently here.
        ImageViewCompat.setImageTintList(
            holder.icon,
            ColorStateList.valueOf(context.getThemeColor(com.google.android.material.R.attr.colorOnBackground))
        )
        // A connection that's switched off can't produce a tab, so ticking it here would do nothing.
        // These rows used to be left out of the list altogether, which raised the more confusing
        // question of why a tab the user knows exists isn't listed — and silently reset its saved
        // visibility to "shown" on the next OK, because it wasn't in the list to be read back. Shown
        // inert with the reason attached answers both.
        holder.text.text = if (item.fetching) item.name else buildSpannedString {
            append(item.name)
            val note = "  ${context.getString(R.string.info_tab_fetch_disabled)}"
            val start = length
            append(note)
            setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(context.getThemeColor(com.google.android.material.R.attr.colorError)),
                start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        holder.itemView.alpha = if (item.fetching) 1f else 0.5f
        holder.handle.visibility = if (item.fetching) View.VISIBLE else View.INVISIBLE
        // Cleared before setting the state: the holder is recycled, and a listener left over from a
        // live row would write this row's checkbox change into the wrong item.
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = item.visible
        holder.checkbox.isEnabled = item.fetching
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            item.visible = isChecked
        }
    }

    override fun getItemCount(): Int = items.size

    fun onItemMove(from: Int, to: Int) {
        if (from == to) return
        val t = items.removeAt(from)
        items.add(to, t)
        notifyItemMoved(from, to)
    }

    fun getItems(): List<InfoTabOrderItem> = items
}
