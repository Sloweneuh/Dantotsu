package ani.dantotsu.connections.mangaupdates

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.loadImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Serializable

class MUMediaAdapter(private val items: List<MUMedia>, private val matchParent: Boolean = false) :
    RecyclerView.Adapter<MUMediaAdapter.ViewHolder>() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class ViewHolder(val binding: ItemMediaCompactBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val vh = ViewHolder(
            ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
        if (matchParent) {
            try {
                val lp = vh.itemView.layoutParams
                    ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                vh.itemView.layoutParams = lp
                // compensate negative root margin in item_media_compact.xml by adding
                // a 16dp left padding so visual left gutter matches other lists
                // padding handled by parent RecyclerView to avoid double-padding
            } catch (e: Exception) { }
        }
        return vh
    }

    override fun getItemCount(): Int = items.size

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val idsToFetch = items.filter { it.coverUrl == null }.map { it.id }
        MUDetailsCache.prefetch(scope, idsToFetch) { id ->
            val pos = items.indexOfFirst { it.id == id }
            if (pos != -1) notifyItemChanged(pos)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope.cancel()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val b = holder.binding

        // Cover: use inline URL if present, else read from cache (populated by prefetch)
        val coverUrl = item.coverUrl ?: MUDetailsCache.get(item.id)?.coverUrl
        if (coverUrl != null) b.itemCompactImage.loadImage(coverUrl)
        else b.itemCompactImage.setImageResource(0)
        b.itemCompactTitle.text = item.title ?: ""
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE

        // Chapter progress
        val userChapter = item.userChapter
        b.itemCompactUserProgress.text = userChapter?.toString() ?: "~"
        val latest = item.latestChapter
        val showLatest = latest != null && latest > 0 && (userChapter == null || latest > userChapter)
        b.itemCompactTotal.text = if (showLatest) " | $latest | ~" else " | ~"
        b.itemCompactProgressContainer.visibility = View.VISIBLE

        // Rating in score area
        val rating = item.bayesianRating
        if (rating != null && rating > 0.0) {
            b.itemCompactScore.text = String.format("%.1f", rating)
            b.itemCompactScoreBG.visibility = View.VISIBLE
            b.itemCompactScoreBG.backgroundTintList =
                ColorStateList.valueOf(Color.WHITE)
        } else {
            b.itemCompactScoreBG.visibility = View.GONE
        }

        // MangaUpdates source badge
        b.itemCompactSourceBadge.visibility = View.VISIBLE

        b.root.setOnClickListener {
            val intent = Intent(it.context, MUMediaDetailsActivity::class.java)
            intent.putExtra("muMedia", item as Serializable)
            it.context.startActivity(intent)
        }

        b.root.setOnLongClickListener { v ->
            val ctx = v.context
            val fm = (ctx as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager
            if (fm != null && fm.findFragmentByTag("muListEditor") == null) {
                MUListEditorFragment.newInstance(item).show(fm, "muListEditor")
            }
            true
        }
    }
}
