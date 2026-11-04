package ani.dantotsu.download.manage

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemDownloadFailedBinding
import ani.dantotsu.databinding.ItemDownloadFailedHeaderBinding
import ani.dantotsu.databinding.ItemDownloadQueueBinding
import ani.dantotsu.databinding.ItemDownloadSectionBinding
import ani.dantotsu.download.DownloadItem
import ani.dantotsu.download.DownloadState
import ani.dantotsu.download.FailedDownload
import ani.dantotsu.download.FailedDownloads
import ani.dantotsu.download.OfflineMediaLoader
import ani.dantotsu.formatBytes
import ani.dantotsu.formatDownloadSpeed
import ani.dantotsu.formatEta
import ani.dantotsu.loadImage
import java.util.Collections

class DownloadQueueAdapter(
    private val onCancel: (DownloadItem) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onRetry: (FailedDownload) -> Unit,
    private val onOpen: (FailedDownload) -> Unit,
    private val onDismiss: (FailedDownload) -> Unit,
    private val onRetryAll: () -> Unit,
    private val onClearFailed: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        val id: String get() = when (this) {
            is Header -> "header:$titleRes"
            is Entry -> item.id
            is FailedHeader -> "failed-header"
            is Failed -> "failed:${item.id}"
        }

        data class Header(val titleRes: Int) : Row()
        data class Entry(val item: DownloadItem) : Row()
        data class FailedHeader(val canRetryAny: Boolean) : Row()
        data class Failed(val item: FailedDownload, val canRetry: Boolean) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val coverCache = HashMap<String, String?>()

    /** Failures first, as they need something done; then items in progress; then queued ones. */
    fun submit(list: List<DownloadItem>, failed: List<FailedDownload>) {
        val inProgress = list.filter { it.state != DownloadState.QUEUED }
        val queued = list.filter { it.state == DownloadState.QUEUED }
        val newRows = mutableListOf<Row>()
        if (failed.isNotEmpty()) {
            val rows = failed.map { Row.Failed(it, FailedDownloads.canRetry(it.id)) }
            newRows.add(Row.FailedHeader(rows.any { it.canRetry }))
            newRows.addAll(rows)
        }
        if (inProgress.isNotEmpty()) {
            newRows.add(Row.Header(R.string.download_in_progress))
            inProgress.forEach { newRows.add(Row.Entry(it)) }
        }
        if (queued.isNotEmpty()) {
            newRows.add(Row.Header(R.string.download_queued))
            queued.forEach { newRows.add(Row.Entry(it)) }
        }

        val old = ArrayList(rows)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == newRows[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newRows[n]
            // For the same entry only progress/state changes — never the cover/title.
            override fun getChangePayload(o: Int, n: Int): Any = PAYLOAD_PROGRESS
        })
        rows.clear()
        rows.addAll(newRows)
        diff.dispatchUpdatesTo(this)
    }

    /** Queued entries currently shown, in display order (for committing a drag). */
    fun queuedIds(): List<String> =
        rows.mapNotNull { (it as? Row.Entry)?.takeIf { e -> e.item.state == DownloadState.QUEUED }?.item?.id }

    fun itemAt(position: Int): DownloadItem? = (rows.getOrNull(position) as? Row.Entry)?.item

    fun isQueuedEntry(position: Int): Boolean =
        (rows.getOrNull(position) as? Row.Entry)?.item?.state == DownloadState.QUEUED

    /** Local visual move during a drag; committed to the tracker on drop. */
    fun moveItem(from: Int, to: Int) {
        Collections.swap(rows, from, to)
        notifyItemMoved(from, to)
    }

    inner class EntryHolder(val binding: ItemDownloadQueueBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class HeaderHolder(val binding: ItemDownloadSectionBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class FailedHeaderHolder(val binding: ItemDownloadFailedHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class FailedHolder(val binding: ItemDownloadFailedBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Entry -> TYPE_ENTRY
        is Row.FailedHeader -> TYPE_FAILED_HEADER
        is Row.Failed -> TYPE_FAILED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemDownloadSectionBinding.inflate(inflater, parent, false))
            TYPE_FAILED_HEADER ->
                FailedHeaderHolder(ItemDownloadFailedHeaderBinding.inflate(inflater, parent, false))

            TYPE_FAILED -> FailedHolder(ItemDownloadFailedBinding.inflate(inflater, parent, false))
            else -> EntryHolder(ItemDownloadQueueBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty() && holder is EntryHolder) {
            (rows[position] as? Row.Entry)?.let { bindProgress(holder, it.item) }
        } else onBindViewHolder(holder, position)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).binding.itemSectionTitle
                .setText(row.titleRes)

            is Row.FailedHeader -> {
                val b = (holder as FailedHeaderHolder).binding
                b.itemFailedRetryAll.isVisible = row.canRetryAny
                b.itemFailedRetryAll.setOnClickListener { onRetryAll() }
                b.itemFailedClear.setOnClickListener { onClearFailed() }
            }

            is Row.Failed -> bindFailed(holder as FailedHolder, row)

            is Row.Entry -> {
                holder as EntryHolder
                val item = row.item
                val b = holder.binding
                val ctx = b.root.context
                b.itemDownloadTitle.text = item.title
                b.itemDownloadLabel.text = item.label

                val cover = coverCache.getOrPut(item.type.toString() + item.title) {
                    OfflineMediaLoader.load(ctx, item.type, item.title).coverUri?.toString()
                        ?: item.coverUrl
                }
                b.itemDownloadCover.loadImage(cover)

                b.itemDownloadCancel.setOnClickListener { onCancel(item) }
                b.itemDownloadDrag.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
                    false
                }
                bindProgress(holder, item)
            }
        }
    }

    private fun bindFailed(holder: FailedHolder, row: Row.Failed) {
        val item = row.item
        val b = holder.binding
        val ctx = b.root.context
        b.itemFailedTitle.text = item.title
        b.itemFailedLabel.text = item.label
        b.itemFailedReason.text = item.reason ?: ctx.getString(R.string.download_failed_unknown)
        val cover = coverCache.getOrPut(item.type.toString() + item.title) {
            OfflineMediaLoader.load(ctx, item.type, item.title).coverUri?.toString() ?: item.coverUrl
        }
        b.itemFailedCover.loadImage(cover)
        b.itemFailedDismiss.setOnClickListener { onDismiss(item) }
        // From an earlier session there's no task left to retry, only its title to start again from.
        val canOpen = FailedDownloads.canOpen(item)
        b.itemFailedAction.isVisible = row.canRetry || canOpen
        b.itemFailedAction.setText(if (row.canRetry) R.string.retry else R.string.download_failed_open)
        b.itemFailedAction.setOnClickListener { if (row.canRetry) onRetry(item) else onOpen(item) }
    }

    /** Updates only the progress line, bar and drag affordance (no cover reload). */
    private fun bindProgress(holder: EntryHolder, item: DownloadItem) {
        val b = holder.binding
        val ctx = b.root.context
        if (item.state == DownloadState.QUEUED) {
            b.itemDownloadStats.text = ctx.getString(R.string.download_queued)
            b.itemDownloadProgress.isIndeterminate = false
            b.itemDownloadProgress.progress = 0
            b.itemDownloadDrag.visibility = View.VISIBLE
        } else {
            val parts = mutableListOf("${item.percent}%")
            formatDownloadSpeed(item.speedBps).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            formatEta(item.etaMs).takeIf { it.isNotEmpty() }?.let { parts.add("ETA $it") }
            parts.add(
                if (item.bytesTotal > 0)
                    "${formatBytes(item.bytesDone)}/${formatBytes(item.bytesTotal)}"
                else formatBytes(item.bytesDone)
            )
            b.itemDownloadStats.text = parts.joinToString(" · ")
            if (item.percent > 0) {
                b.itemDownloadProgress.isIndeterminate = false
                b.itemDownloadProgress.setProgressCompat(item.percent, true)
            } else {
                b.itemDownloadProgress.isIndeterminate = true
            }
            b.itemDownloadDrag.visibility = View.INVISIBLE
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1
        private const val TYPE_FAILED_HEADER = 2
        private const val TYPE_FAILED = 3
        private const val PAYLOAD_PROGRESS = "progress"
    }
}
