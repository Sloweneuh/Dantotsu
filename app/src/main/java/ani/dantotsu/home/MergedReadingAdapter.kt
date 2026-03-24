package ani.dantotsu.home

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.mangaupdates.MUListEditorFragment
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
import ani.dantotsu.connections.mangaupdates.MUDetailsCache
import ani.dantotsu.currActivity
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.databinding.ItemMediaLargeBinding
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaListDialogSmallFragment
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import androidx.fragment.app.FragmentActivity
import ani.dantotsu.setSafeOnClickListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Serializable

/**
 * A single adapter that renders both [Media] (Anilist) and [MUMedia] items in one merged
 * sorted list.  Items are expected to be pre-sorted by the caller.
 *
 * @param type 0 = compact grid, 1 = large list
 */
class MergedReadingAdapter(
    private val items: List<Any>,
    private val type: Int = 0
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val muIds = items.filterIsInstance<MUMedia>().filter { it.coverUrl == null }.map { it.id }
        MUDetailsCache.prefetch(scope, muIds) { id ->
            val pos = items.indexOfFirst { it is MUMedia && it.id == id }
            if (pos != -1) notifyItemChanged(pos)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope.cancel()
    }

    inner class VH(val binding: ItemMediaCompactBinding) : RecyclerView.ViewHolder(binding.root)
    inner class VHLarge(val binding: ItemMediaLargeBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == 1)
            VHLarge(ItemMediaLargeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        else
            VH(ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is VHLarge) {
            when (item) {
                is Media -> bindMediaLarge(holder.binding, item)
                is MUMedia -> bindMuMediaLarge(holder.binding, item)
            }
        } else {
            val b = (holder as VH).binding
            when (item) {
                is Media -> bindMedia(b, item)
                is MUMedia -> bindMuMedia(b, item)
            }
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
        b.itemCompactScoreBG.visibility = View.VISIBLE
        try {
            val isUser = media.userScore != 0
            b.itemCompactSourceBadge.setBackgroundResource(
                if (isUser) R.drawable.item_language_badge_user else R.drawable.item_language_badge
            )
            b.itemCompactSourceBadge.backgroundTintList = b.itemCompactScoreBG.backgroundTintList
        } catch (e: Exception) {
        }
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
        b.itemCompactSourceBadge.visibility = View.GONE

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
            b.itemCompactScoreBG.backgroundTintList =
                ColorStateList.valueOf(Color.WHITE)
            try {
                b.itemCompactSourceBadge.backgroundTintList = b.itemCompactScoreBG.backgroundTintList
            } catch (e: Exception) {
            }
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
            val fm = (currActivity() as? FragmentActivity)?.supportFragmentManager
            if (fm != null && fm.findFragmentByTag("muListEditor") == null) {
                MUListEditorFragment.newInstance(item).show(fm, "muListEditor")
            }
            true
        }
    }

    private fun bindMediaLarge(b: ItemMediaLargeBinding, media: Media) {
        b.itemCompactImage.loadImage(media.cover)
        blurImage(b.itemCompactBanner, media.banner ?: media.cover)

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
        b.itemCompactScoreBG.visibility = View.VISIBLE
        try {
            val isUser = media.userScore != 0
            b.itemCompactLanguageBG.setBackgroundResource(
                if (isUser) R.drawable.item_language_badge_user else R.drawable.item_language_badge
            )
            b.itemCompactLanguageBG.backgroundTintList = b.itemCompactScoreBG.backgroundTintList
            b.itemCompactSourceBadge.setBackgroundResource(
                if (isUser) R.drawable.item_language_badge_user else R.drawable.item_language_badge
            )
            b.itemCompactSourceBadge.backgroundTintList = b.itemCompactScoreBG.backgroundTintList
        } catch (e: Exception) {
        }
        b.itemCompactStatus.text = media.status ?: ""
        b.itemCompactStatus.visibility = if (!b.itemCompactStatus.text.isNullOrBlank()) View.VISIBLE else View.GONE

        try {
            val rawDesc = media.description ?: ""
            val parsed = HtmlCompat.fromHtml(rawDesc, HtmlCompat.FROM_HTML_MODE_LEGACY)
            b.itemCompactSynopsis.text = if (parsed.isBlank() || parsed.toString() == "null")
                ctx.getString(R.string.no_description_available) else parsed
        } catch (e: Exception) {
            b.itemCompactSynopsis.text = ctx.getString(R.string.no_description_available)
        }
        Linkify.addLinks(b.itemCompactSynopsis, Linkify.WEB_URLS)
        b.itemCompactSynopsis.movementMethod = LinkMovementMethod.getInstance()
        b.itemCompactSynopsis.scrollTo(0, 0)
        b.itemCompactSynopsis.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        b.itemUserProgressLarge.text = (media.userProgress ?: "~").toString()
        if (media.manga != null) {
            b.itemCompactTotal.text = (media.manga.totalChapters ?: "??").toString()
            b.itemTotal.text = " " + ctx.getString(
                if ((media.manga.totalChapters ?: 0) != 1) R.string.chapter_plural else R.string.chapter_singular
            )
        } else if (media.anime != null) {
            b.itemCompactTotal.text = (media.anime.totalEpisodes ?: "??").toString()
            b.itemTotal.text = " " + ctx.getString(
                if ((media.anime.totalEpisodes ?: 0) != 1) R.string.episode_plural else R.string.episode_singular
            )
        }
        b.itemCompactType.visibility = View.GONE
        b.itemCompactLanguageBG.visibility = View.GONE
        b.itemCompactSourceBadge.visibility = View.GONE

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

    private fun bindMuMediaLarge(b: ItemMediaLargeBinding, item: MUMedia) {
        val ctx = b.root.context
        val cached = MUDetailsCache.get(item.id)
        val coverUrl = item.coverUrl ?: cached?.coverUrl
        if (coverUrl != null) {
            b.itemCompactImage.loadImage(coverUrl)
            blurImage(b.itemCompactBanner, coverUrl)
        } else {
            b.itemCompactImage.setImageResource(0)
        }

        b.itemCompactTitle.text = item.title ?: ""
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE
        b.itemCompactStatus.text = ""
        b.itemCompactStatus.visibility = View.GONE

        val desc = cached?.description
        b.itemCompactSynopsis.text = if (desc != null)
            HtmlCompat.fromHtml(desc, HtmlCompat.FROM_HTML_MODE_LEGACY)
        else ""
        Linkify.addLinks(b.itemCompactSynopsis, Linkify.WEB_URLS)
        b.itemCompactSynopsis.movementMethod = LinkMovementMethod.getInstance()
        b.itemCompactSynopsis.scrollTo(0, 0)
        b.itemCompactSynopsis.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        b.itemUserProgressLarge.text = (item.userChapter ?: "~").toString()
        b.itemCompactTotal.text = (item.latestChapter ?: "??").toString()
        b.itemTotal.text = " " + ctx.getString(R.string.chapter_plural)

        val rating = item.bayesianRating
        if (rating != null && rating > 0.0) {
            b.itemCompactScore.text = String.format("%.1f", rating)
            b.itemCompactScoreBG.visibility = View.VISIBLE
            b.itemCompactScoreBG.backgroundTintList =
                ColorStateList.valueOf(Color.WHITE)
            try {
                val _bg = b.itemCompactScoreBG.background
                if (_bg != null) {
                    val _copy = _bg.constantState?.newDrawable()?.mutate()
                    b.itemCompactLanguageBG.background = _copy
                    b.itemCompactLanguageBG.backgroundTintList = b.itemCompactScoreBG.backgroundTintList
                    val _copy2 = _bg.constantState?.newDrawable()?.mutate()
                    b.itemCompactSourceBadge.background = _copy2
                    b.itemCompactSourceBadge.backgroundTintList = b.itemCompactScoreBG.backgroundTintList
                }
            } catch (e: Exception) {
            }
        } else {
            b.itemCompactScoreBG.visibility = View.GONE
        }

        b.itemCompactLanguageBG.visibility = View.GONE
        b.itemCompactSourceBadge.visibility = View.VISIBLE

        b.root.setSafeOnClickListener {
            val intent = Intent(ctx, MUMediaDetailsActivity::class.java)
            intent.putExtra("muMedia", item as Serializable)
            ctx.startActivity(intent)
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
