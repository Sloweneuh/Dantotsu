package ani.dantotsu.connections.mangaupdates

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.databinding.ItemMediaLargeBinding
import ani.dantotsu.loadImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Serializable

class MUMediaAdapter(
    private val items: List<MUMedia>,
    private val matchParent: Boolean = false,
    var type: Int = 0
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class CompactViewHolder(val binding: ItemMediaCompactBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class LargeViewHolder(val binding: ItemMediaLargeBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            1 -> LargeViewHolder(
                ItemMediaLargeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            else -> {
                val vh = CompactViewHolder(
                    ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                )
                if (matchParent) {
                    try {
                        val lp = vh.itemView.layoutParams
                            ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                        vh.itemView.layoutParams = lp
                    } catch (e: Exception) { }
                }
                vh
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        // Bulk prefetch for items already present when adapter is attached
        if (items.isNotEmpty()) prefetchAll()
    }

    private fun prefetchAll() {
        val ids = items.map { it.id }
        MUDetailsCache.prefetch(scope, ids) { id ->
            val pos = items.indexOfFirst { it.id == id }
            if (pos != -1) notifyItemChanged(pos)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope.cancel()
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (MUDetailsCache.get(item.id) == null) {
            MUDetailsCache.prefetch(scope, listOf(item.id)) { id ->
                val pos = items.indexOfFirst { it.id == id }
                if (pos != -1) notifyItemChanged(pos)
            }
        }
        when (holder) {
            is LargeViewHolder -> bindLarge(holder.binding, item)
            is CompactViewHolder -> bindCompact(holder.binding, item)
        }
    }

    private fun bindCompact(b: ItemMediaCompactBinding, item: MUMedia) {
        val coverUrl = item.coverUrl ?: MUDetailsCache.get(item.id)?.coverUrl
        if (coverUrl != null) b.itemCompactImage.loadImage(coverUrl)
        else b.itemCompactImage.setImageResource(0)
        b.itemCompactTitle.text = item.title ?: ""
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE

        val userChapter = item.userChapter
        b.itemCompactUserProgress.text = userChapter?.toString() ?: "~"
        val latest = item.latestChapter
        val showLatest = latest != null && latest > 0 && (userChapter == null || latest > userChapter)
        b.itemCompactTotal.text = if (showLatest) " | $latest | ~" else " | ~"
        b.itemCompactProgressContainer.visibility = View.VISIBLE

        val rating = item.bayesianRating
        if (rating != null && rating > 0.0) {
            b.itemCompactScore.text = String.format("%.1f", rating)
            b.itemCompactScoreBG.visibility = View.VISIBLE
            b.itemCompactScoreBG.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        } else {
            b.itemCompactScoreBG.visibility = View.GONE
        }

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

    private fun bindLarge(b: ItemMediaLargeBinding, item: MUMedia) {
        val detail = MUDetailsCache.get(item.id)
        val coverUrl = item.coverUrl ?: detail?.coverUrl
        if (coverUrl != null) {
            b.itemCompactImage.loadImage(coverUrl)
            b.itemCompactBanner.loadImage(coverUrl)
        } else {
            b.itemCompactImage.setImageResource(0)
        }

        b.itemCompactTitle.text = item.title ?: ""
        b.itemCompactTitle.maxLines = 3

        b.itemCompactStatus.visibility = View.GONE
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE
        b.itemInfoButton.visibility = View.GONE
        b.itemCompactScoreBG.visibility = View.GONE
        b.itemCompactSourceBadge.visibility = View.GONE

        val rawDesc = detail?.description ?: ""
        b.itemCompactSynopsis.text = if (rawDesc.isBlank()) {
            b.root.context.getString(R.string.no_description_available)
        } else {
            HtmlCompat.fromHtml(rawDesc, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
        b.itemCompactSynopsis.movementMethod = LinkMovementMethod.getInstance()
        b.itemCompactSynopsis.scrollTo(0, 0)
        b.itemCompactSynopsis.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        val rating = item.bayesianRating
        if (rating != null && rating > 0.0) {
            b.itemCompactScore.text = String.format("%.1f", rating)
            b.itemCompactScoreBG.visibility = View.VISIBLE
            b.itemCompactScoreBG.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        }

        val userChapter = item.userChapter
        b.itemUserProgressLarge.text = userChapter?.toString() ?: "~"
        val latest = item.latestChapter
        val showLatest = latest != null && latest > 0 && (userChapter == null || latest > userChapter)
        b.itemCompactTotal.text = if (showLatest) latest.toString() else "~"
        b.itemTotal.text = " ${b.root.context.getString(R.string.chapter_plural)}"

        b.itemContainer.setOnClickListener {
            val intent = Intent(it.context, MUMediaDetailsActivity::class.java)
            intent.putExtra("muMedia", item as Serializable)
            it.context.startActivity(intent)
        }

        b.itemContainer.setOnLongClickListener { v ->
            val ctx = v.context
            val fm = (ctx as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager
            if (fm != null && fm.findFragmentByTag("muListEditor") == null) {
                MUListEditorFragment.newInstance(item).show(fm, "muListEditor")
            }
            true
        }
    }
}
