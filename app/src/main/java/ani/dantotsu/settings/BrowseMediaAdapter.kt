package ani.dantotsu.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemExtensionMediaBinding
import ani.dantotsu.loadImage

class BrowseMediaAdapter(
    private val onClick: (BrowseItem) -> Unit
) : RecyclerView.Adapter<BrowseMediaAdapter.VH>() {

    private val items = mutableListOf<BrowseItem>()

    fun submit(list: List<BrowseItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<BrowseItem>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemExtensionMediaBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.extensionMediaTitle.text = item.title
        holder.binding.extensionMediaCover.loadImage(item.thumbnail)
        holder.binding.extensionMediaRoot.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size

    class VH(val binding: ItemExtensionMediaBinding) : RecyclerView.ViewHolder(binding.root)
}
