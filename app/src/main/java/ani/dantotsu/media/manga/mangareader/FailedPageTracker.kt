package ani.dantotsu.media.manga.mangareader

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import com.google.android.material.button.MaterialButton

/**
 * Keeps track of the pages whose image failed to load, so that every failed page can be
 * retried at once from any error placeholder.
 *
 * Retrying never touches the item list: the failed pages are reloaded in place, which
 * leaves both the scroll position and the page order untouched. Failed pages that are no
 * longer attached to the RecyclerView are left as they are — they get reloaded anyway when
 * they are bound again on the way back to them.
 */
class FailedPageTracker {

    private val failedPositions = sortedSetOf<Int>()

    val count: Int get() = failedPositions.size

    /**
     * Marks [position] as failed, shows its error placeholder and wires both retry buttons.
     * [retry] is expected to (re)start the load of the given position into the given item view.
     */
    fun showError(parent: View, position: Int, retry: (Int, View) -> Unit) {
        if (position != RecyclerView.NO_POSITION) failedPositions.add(position)

        parent.findViewById<View>(R.id.imgProgProgress)?.visibility = View.GONE
        parent.findViewById<View>(R.id.imgProgError)?.visibility = View.VISIBLE
        parent.findViewById<View>(R.id.imgProgRetry)?.setOnClickListener {
            retry(position, parent)
        }
        parent.findViewById<View>(R.id.imgProgRetryAll)?.setOnClickListener {
            retryAll(parent, retry)
        }

        updateRetryAllButtons(parent)
    }

    /** Drops the failed state of [position] once it finally loaded. */
    fun clearError(parent: View, position: Int) {
        if (failedPositions.remove(position)) updateRetryAllButtons(parent)
    }

    /** Reloads every failed page that is currently attached, in place. */
    fun retryAll(anyItemView: View, retry: (Int, View) -> Unit) {
        val recycler = recyclerOf(anyItemView) ?: return
        // Snapshot first: each retry mutates the set as soon as a page loads.
        val targets = ArrayList<Pair<Int, View>>(failedPositions.size)
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            val position = recycler.getChildAdapterPosition(child)
            if (position != RecyclerView.NO_POSITION && failedPositions.contains(position))
                targets.add(position to child)
        }
        targets.forEach { (position, child) -> retry(position, child) }
    }

    /**
     * Remaps the tracked positions after [delta] items were inserted at [fromInclusive],
     * so the failed pages keep pointing at the same images.
     */
    fun shiftPositions(fromInclusive: Int, delta: Int) {
        if (delta == 0 || failedPositions.isEmpty()) return
        val shifted = failedPositions.map { if (it >= fromInclusive) it + delta else it }
        failedPositions.clear()
        failedPositions.addAll(shifted)
    }

    fun clear() = failedPositions.clear()

    private fun updateRetryAllButtons(anyItemView: View) {
        val recycler = recyclerOf(anyItemView)
        if (recycler == null) {
            applyRetryAllState(anyItemView)
            return
        }
        for (i in 0 until recycler.childCount) applyRetryAllState(recycler.getChildAt(i))
    }

    private fun applyRetryAllState(itemView: View) {
        val button = itemView.findViewById<MaterialButton>(R.id.imgProgRetryAll) ?: return
        if (count > 1) {
            button.text = itemView.context.getString(R.string.retry_all_failed, count)
            button.visibility = View.VISIBLE
        } else {
            button.visibility = View.GONE
        }
    }

    companion object {
        private fun recyclerOf(itemView: View): RecyclerView? = itemView.parent as? RecyclerView

        /**
         * True while [parent] is still the view bound to [position]. A load started before the
         * holder got recycled must not be applied afterwards, otherwise a slow page would be
         * drawn over whichever page the holder shows now — which is exactly what happens when
         * several pages are retried at once.
         */
        fun isStillBoundTo(parent: View, position: Int): Boolean {
            val recycler = recyclerOf(parent) ?: return true
            val current = recycler.getChildAdapterPosition(parent)
            return current == RecyclerView.NO_POSITION || current == position
        }
    }
}
