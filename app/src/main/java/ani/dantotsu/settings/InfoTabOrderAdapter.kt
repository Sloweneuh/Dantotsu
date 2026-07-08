package ani.dantotsu.settings

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.getThemeColor

data class InfoTabOrderItem(val id: Int, val name: String, val iconRes: Int, var visible: Boolean)

/** Vertical counterpart to [HomeLayoutAdapter] with an added per-row connection icon. */
class InfoTabOrderAdapter(private val items: MutableList<InfoTabOrderItem>) :
    RecyclerView.Adapter<InfoTabOrderAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.infoTabIcon)
        val text: TextView = view.findViewById(R.id.infoTabText)
        val checkbox: CheckBox = view.findViewById(R.id.infoTabCheckbox)
    }

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
        holder.text.text = item.name
        holder.checkbox.isChecked = item.visible
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
