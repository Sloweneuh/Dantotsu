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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

class MUMediaAdapter(private val items: List<MUMedia>) :
    RecyclerView.Adapter<MUMediaAdapter.ViewHolder>() {

    // null value = fetch was attempted but no URL found; key absent = not yet tried
    private val coverCache = mutableMapOf<Long, String?>()
    private val fetchingIds = mutableSetOf<Long>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class ViewHolder(val binding: ItemMediaCompactBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun getItemCount(): Int = items.size

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope.cancel()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val b = holder.binding

        // Lazy-load cover: use cached URL if available, otherwise fetch series details once
        when {
            item.coverUrl != null -> {
                coverCache[item.id] = item.coverUrl
                b.itemCompactImage.loadImage(item.coverUrl)
            }
            coverCache.containsKey(item.id) -> {
                val url = coverCache[item.id]
                if (url != null) b.itemCompactImage.loadImage(url)
                else b.itemCompactImage.setImageResource(0)
            }
            fetchingIds.add(item.id) -> {
                b.itemCompactImage.setImageResource(0)
                scope.launch {
                    val url = withContext(Dispatchers.IO) {
                        MangaUpdates.getSeriesDetails(item.id)
                            ?.image?.url?.run { original ?: thumb }
                    }
                    coverCache[item.id] = url
                    fetchingIds.remove(item.id)
                    val pos = items.indexOfFirst { it.id == item.id }
                    if (pos != -1) notifyItemChanged(pos)
                }
            }
            else -> b.itemCompactImage.setImageResource(0)
        }
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
