package ani.dantotsu.media

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Shows at most [limit] of another adapter's items, until [expand]. Works around any adapter, since
 * the ones behind info sections are the sources' own.
 */
class LimitAdapter(inner: RecyclerView.Adapter<*>, private var limit: Int?) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    @Suppress("UNCHECKED_CAST")
    private val inner = inner as RecyclerView.Adapter<RecyclerView.ViewHolder>

    /** Told when [hiddenCount] may have changed. */
    var onChanged: (() -> Unit)? = null

    val hiddenCount: Int get() = limit?.let { (inner.itemCount - it).coerceAtLeast(0) } ?: 0

    init {
        setHasStableIds(this.inner.hasStableIds())
        this.inner.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = refresh()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = refresh()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refresh()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refresh()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = refresh()
        })
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        notifyDataSetChanged()
        onChanged?.invoke()
    }

    fun expand() {
        limit = null
        refresh()
    }

    override fun getItemCount() = limit?.coerceAtMost(inner.itemCount) ?: inner.itemCount
    override fun getItemViewType(position: Int) = inner.getItemViewType(position)
    override fun getItemId(position: Int) = inner.getItemId(position)

    // The public wrappers, not the on...() methods: as with ConcatAdapter, these are what tell a
    // holder which adapter it belongs to, which its own clicks ask through bindingAdapterPosition.
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = inner.createViewHolder(parent, viewType)
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) =
        inner.bindViewHolder(holder, position)

    override fun findRelativeAdapterPositionIn(
        adapter: RecyclerView.Adapter<out RecyclerView.ViewHolder>,
        viewHolder: RecyclerView.ViewHolder,
        localPosition: Int,
    ): Int = if (adapter === this) localPosition
    else inner.findRelativeAdapterPositionIn(adapter, viewHolder, localPosition)

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) = inner.onViewRecycled(holder)
    override fun onFailedToRecycleView(holder: RecyclerView.ViewHolder) = inner.onFailedToRecycleView(holder)
    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) = inner.onViewAttachedToWindow(holder)
    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) = inner.onViewDetachedFromWindow(holder)
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) = inner.onAttachedToRecyclerView(recyclerView)
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) = inner.onDetachedFromRecyclerView(recyclerView)
}
