package ani.dantotsu.download.manage

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemDownloadChildBinding
import ani.dantotsu.databinding.ItemDownloadMediaBinding
import ani.dantotsu.formatBytes
import ani.dantotsu.loadImage
import ani.dantotsu.media.MediaType

class DownloadManagementAdapter(
    private val onDeleteMedia: (DownloadMediaGroup) -> Unit,
    private val onDeleteChild: (DownloadChild) -> Unit,
    private val onOpenMediaFolder: (DownloadMediaGroup) -> Unit,
    private val onOpenChildFolder: (DownloadChild) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Media(val group: DownloadMediaGroup) : Row()
        data class Child(val child: DownloadChild) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val expanded = mutableSetOf<String>() // group titleName+type keys

    private fun keyOf(g: DownloadMediaGroup) = "${g.type}:${g.titleName}"

    @SuppressLint("NotifyDataSetChanged")
    fun submit(groups: List<DownloadMediaGroup>) {
        // Drop expansion state for groups that no longer exist.
        expanded.retainAll(groups.map { keyOf(it) }.toSet())
        rebuild(groups)
        notifyDataSetChanged()
    }

    private var currentGroups: List<DownloadMediaGroup> = emptyList()

    private fun rebuild(groups: List<DownloadMediaGroup>) {
        currentGroups = groups
        rows.clear()
        for (g in groups) {
            rows.add(Row.Media(g))
            if (keyOf(g) in expanded) g.children.forEach { rows.add(Row.Child(it)) }
        }
    }

    private fun toggle(group: DownloadMediaGroup) {
        val key = keyOf(group)
        if (key in expanded) expanded.remove(key) else expanded.add(key)
        rebuild(currentGroups)
        @SuppressLint("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) =
        if (rows[position] is Row.Media) TYPE_MEDIA else TYPE_CHILD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_MEDIA)
            MediaHolder(ItemDownloadMediaBinding.inflate(inflater, parent, false))
        else ChildHolder(ItemDownloadChildBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Media -> (holder as MediaHolder).bind(row.group)
            is Row.Child -> (holder as ChildHolder).bind(row.child)
        }
    }

    inner class MediaHolder(private val b: ItemDownloadMediaBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(group: DownloadMediaGroup) {
            val ctx = b.root.context
            b.itemMediaTitle.text = group.title
            val countRes = if (group.type == MediaType.ANIME)
                R.string.download_episodes_count else R.string.download_chapters_count
            b.itemMediaSubtitle.text = ctx.getString(
                countRes,
                group.itemCount,
                formatBytes(group.sizeBytes)
            )
            b.itemMediaCover.loadImage(group.coverUri?.toString())
            b.itemMediaExpand.rotation = if (keyOf(group) in expanded) -90f else 90f
            b.root.setOnClickListener { toggle(group) }
            b.itemMediaDelete.setOnClickListener { onDeleteMedia(group) }
            b.itemMediaOpenFolder.setOnClickListener { onOpenMediaFolder(group) }
        }
    }

    inner class ChildHolder(private val b: ItemDownloadChildBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(child: DownloadChild) {
            b.itemChildLabel.text = child.chapterName
            b.itemChildSize.text = formatBytes(child.sizeBytes)
            b.itemChildDelete.setOnClickListener { onDeleteChild(child) }
            b.itemChildOpenFolder.setOnClickListener { onOpenChildFolder(child) }
        }
    }

    companion object {
        private const val TYPE_MEDIA = 0
        private const val TYPE_CHILD = 1
    }
}
