package ani.dantotsu.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R

data class HomeLayoutItem(val id: Int, val name: String, var visible: Boolean)

class HomeLayoutAdapter(private val items: MutableList<HomeLayoutItem>) : RecyclerView.Adapter<HomeLayoutAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.homeItemText)
        val checkbox: CheckBox = view.findViewById(R.id.homeItemCheckbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_home_layout_choice, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
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

    fun getItems(): List<HomeLayoutItem> = items
}
