package ani.dantotsu.home

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.mangaupdates.MUListEditorFragment
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.currActivity
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaListDialogSmallFragment
import androidx.fragment.app.FragmentActivity
import ani.dantotsu.setSafeOnClickListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

/**
 * A single horizontal-list adapter that renders both [Media] (Anilist) and [MUMedia]
 * items in one merged sorted list.  Items are expected to be pre-sorted by the caller.
 */
class MergedReadingAdapter(
    private val items: List<Any>
) : RecyclerView.Adapter<MergedReadingAdapter.VH>() {

    private val coverCache = mutableMapOf<Long, String?>()
    private val fetchingIds = mutableSetOf<Long>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope.cancel()
    }

    inner class VH(val binding: ItemMediaCompactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        when (val item = items[position]) {
            is Media -> bindMedia(holder.binding, item)
            is MUMedia -> bindMuMedia(holder.binding, item)
        }
    }

    private fun bindMedia(b: ItemMediaCompactBinding, media: Media) {
        b.itemCompactImage.loadImage(media.cover)

        val status = media.status ?: ""
        val ctx = b.root.context
        val releasingStr = ctx.getString(R.string.status_releasing)
        val hiatusStr = ctx.getString(R.string.status_hiatus)
        val isReleasing = status == releasingStr
        val isHiatus = status.equals(hiatusStr, ignoreCase = true)
        b.itemCompactOngoing.visibility = if (isReleasing || isHiatus) View.VISIBLE else View.GONE
        if (isHiatus) b.itemCompactOngoing.getChildAt(0)?.setBackgroundResource(R.drawable.item_hiatus)
        else b.itemCompactOngoing.getChildAt(0)?.setBackgroundResource(R.drawable.item_ongoing)

        b.itemCompactTitle.text = media.userPreferredName
        b.itemCompactScore.text =
            ((if (media.userScore == 0) (media.meanScore ?: 0) else media.userScore) / 10.0).toString()
        b.itemCompactScoreBG.background = ContextCompat.getDrawable(
            ctx,
            if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score
        )
        b.itemCompactUserProgress.text = (media.userProgress ?: "~").toString()
        if (media.relation != null) {
            b.itemCompactRelation.text = "${media.relation}  "
            b.itemCompactType.visibility = View.VISIBLE
        } else {
            b.itemCompactType.visibility = View.GONE
        }
        if (media.manga != null) {
            if (media.relation != null) b.itemCompactTypeImage.setImageDrawable(
                AppCompatResources.getDrawable(ctx, R.drawable.ic_round_import_contacts_24)
            )
            b.itemCompactTotal.text = " | ${media.manga.totalChapters ?: "~"}"
        }
        b.itemCompactProgressContainer.visibility = View.VISIBLE

        b.root.setSafeOnClickListener {
            ContextCompat.startActivity(
                ctx,
                Intent(ctx, MediaDetailsActivity::class.java).putExtra("media", media as Serializable),
                null
            )
        }
        b.root.setOnLongClickListener {
            val activity = currActivity() as? FragmentActivity ?: return@setOnLongClickListener false
            if (activity.supportFragmentManager.findFragmentByTag("list") == null) {
                MediaListDialogSmallFragment.newInstance(media)
                    .show(activity.supportFragmentManager, "list")
                return@setOnLongClickListener true
            }
            false
        }
    }

    private fun bindMuMedia(b: ItemMediaCompactBinding, item: MUMedia) {
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
                            ?.image?.url?.run { thumb ?: original }
                    }
                    coverCache[item.id] = url
                    fetchingIds.remove(item.id)
                    val pos = items.indexOfFirst { it is MUMedia && it.id == item.id }
                    if (pos != -1) notifyItemChanged(pos)
                }
            }
            else -> b.itemCompactImage.setImageResource(0)
        }

        b.itemCompactTitle.text = item.title ?: ""
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE

        b.itemCompactUserProgress.text = (item.userChapter ?: "~").toString()
        b.itemCompactTotal.text = " | ${item.latestChapter ?: "~"}"
        b.itemCompactProgressContainer.visibility = View.VISIBLE

        val rating = item.bayesianRating
        if (rating != null && rating > 0.0) {
            b.itemCompactScore.text = String.format("%.1f", rating)
            b.itemCompactScoreBG.visibility = View.VISIBLE
            b.itemCompactScoreBG.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#FF6B00"))
        } else {
            b.itemCompactScoreBG.visibility = View.GONE
        }

        b.root.setOnClickListener {
            val intent = Intent(it.context, MUMediaDetailsActivity::class.java)
            intent.putExtra("muMedia", item as Serializable)
            it.context.startActivity(intent)
        }
        b.root.setOnLongClickListener { v ->
            val fm = (currActivity() as? FragmentActivity)?.supportFragmentManager
            if (fm != null && fm.findFragmentByTag("muListEditor") == null) {
                MUListEditorFragment.newInstance(item).show(fm, "muListEditor")
            }
            true
        }
    }
}
