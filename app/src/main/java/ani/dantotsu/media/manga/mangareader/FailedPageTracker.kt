package ani.dantotsu.media.manga.mangareader

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import com.google.android.material.button.MaterialButton
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Keeps track of the pages whose image failed to load, so that every failed page can be
 * retried at once from any error placeholder.
 *
 * Pages are tracked by identity, not by adapter position: in the multi-chapter reader every
 * position shifts as soon as a previous chapter or a boundary item is prepended, which would
 * quietly leave the tracked positions pointing at different pages.
 *
 * Retrying never touches the item list: the failed pages are reloaded in place, which
 * leaves both the scroll position and the page order untouched. Failed pages that are no
 * longer attached to the RecyclerView are left as they are — they get reloaded anyway when
 * they are bound again on the way back to them.
 *
 * @param pageAt the page an adapter position currently holds, or null when it holds no image.
 */
class FailedPageTracker(private val pageAt: (Int) -> Any?) {

    private val failedPages: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

    val count: Int get() = failedPages.size

    /**
     * Marks [page] as failed, shows its error placeholder and wires both retry buttons.
     * [retry] is expected to (re)start the load of the given position into the given item view.
     */
    fun showError(parent: View, page: Any, retry: (Int, View) -> Unit) {
        failedPages.add(page)

        parent.findViewById<View>(R.id.imgProgProgress)?.visibility = View.GONE
        parent.findViewById<View>(R.id.imgProgError)?.visibility = View.VISIBLE
        parent.findViewById<View>(R.id.imgProgRetry)?.setOnClickListener {
            // Resolved on click instead of captured: the page may have moved by then.
            val position = positionOf(parent)
            if (position != RecyclerView.NO_POSITION) retry(position, parent)
        }
        parent.findViewById<View>(R.id.imgProgRetryAll)?.setOnClickListener {
            retryAll(parent, retry)
        }

        updateRetryAllButtons(parent)
    }

    /** Drops the failed state of [page] once it finally loaded. */
    fun clearError(parent: View, page: Any) {
        if (failedPages.remove(page)) updateRetryAllButtons(parent)
    }

    /** Reloads every failed page that is currently attached, in place. */
    fun retryAll(anyItemView: View, retry: (Int, View) -> Unit) {
        val recycler = recyclerOf(anyItemView) ?: return
        // Snapshot first: each retry mutates the set as soon as a page loads.
        val targets = ArrayList<Pair<Int, View>>(failedPages.size)
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            val position = recycler.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val page = pageAt(position) ?: continue
            if (failedPages.contains(page)) targets.add(position to child)
        }
        targets.forEach { (position, child) -> retry(position, child) }
    }

    fun clear() = failedPages.clear()

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

    private fun recyclerOf(itemView: View): RecyclerView? = itemView.parent as? RecyclerView

    private fun positionOf(itemView: View): Int =
        recyclerOf(itemView)?.getChildAdapterPosition(itemView) ?: RecyclerView.NO_POSITION
}
