package ani.dantotsu.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemExtensionMediaBinding
import ani.dantotsu.loadImage

class BrowseMediaAdapter(
    private val onClick: (BrowseItem) -> Unit,
    private val onOpenInBrowser: (BrowseItem) -> Unit,
) : RecyclerView.Adapter<BrowseMediaAdapter.VH>() {

    private val items = mutableListOf<BrowseItem>()
    private val expandedPositions = mutableSetOf<Int>()

    companion object {
        private const val COLLAPSED_MAX_LINES = 3
    }

    fun submit(list: List<BrowseItem>) {
        items.clear()
        expandedPositions.clear()
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
        holder.binding.extensionMediaTitle.maxLines =
            if (position in expandedPositions) Int.MAX_VALUE else COLLAPSED_MAX_LINES
        holder.binding.extensionMediaCover.loadImage(item.thumbnail)

        val clickRow = { onClick(item) }
        holder.binding.extensionMediaRoot.setOnClickListener { clickRow() }
        holder.binding.extensionMediaTitle.setOnClickListener { clickRow() }
        holder.binding.extensionMediaCover.setOnClickListener { clickRow() }

        holder.binding.extensionMediaTitle.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
            if (pos in expandedPositions) expandedPositions.remove(pos)
            else expandedPositions.add(pos)
            notifyItemChanged(pos)
            true
        }
        holder.binding.extensionMediaCover.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
            onOpenInBrowser(items[pos])
            true
        }
    }

    override fun getItemCount() = items.size

    class VH(val binding: ItemExtensionMediaBinding) : RecyclerView.ViewHolder(binding.root)
}
