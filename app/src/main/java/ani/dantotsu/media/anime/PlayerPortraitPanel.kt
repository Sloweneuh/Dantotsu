package ani.dantotsu.media.anime

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.databinding.ItemEpisodeListBinding
import ani.dantotsu.databinding.ItemPlayerPortraitHeaderBinding
import ani.dantotsu.databinding.ItemPlayerPortraitSequelBinding
import ani.dantotsu.media.InfoTabContext
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaListDialogSmallFragment
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.mediaShareLinks
import ani.dantotsu.media.showShareDialog
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.snackString
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.google.android.material.color.MaterialColors

/**
 * The player held upright on a phone: under the video, what is playing (series, episode, synopsis),
 * the actions that leave the video's own controls in this layout, and every episode to pick from —
 * the way a video site lists what comes next under the one playing.
 *
 * The panel only shows and asks; playing an episode, clipping, the screenshot and the hand-off stay
 * the player's, reached through [Host].
 */
class PlayerPortraitPanel(
    private val activity: AppCompatActivity,
    private val list: RecyclerView,
    private val host: Host,
) {
    interface Host {
        val media: Media
        /** Episode numbers in playing order, and the one playing. */
        val episodeNumbers: List<String>
        val currentIndex: Int
        /** The episode playing comes from a download, so there is nothing to download. */
        val playingDownload: Boolean
        /** The source the episode plays from, for subscribing to its new episodes. */
        val sourceName: String?
        fun playEpisode(index: Int)
        fun download()
        fun clip()
        fun screenshot()
        fun handoff()
        /** How tall a sheet over the panel can be: the room under the video. */
        fun sheetHeight(): Int
    }

    private val media get() = host.media
    private val header = HeaderAdapter()
    private val episodes = PlayerEpisodeAdapter(activity, host)
    private val sequel = SequelAdapter()

    init {
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = ConcatAdapter(header, episodes, sequel)
        list.itemAnimator = null
    }

    /**
     * Everything again: a new episode playing, or the list's state changed. With [reveal], the
     * playing episode is also scrolled into view — by as little as it takes, so the header stays
     * up whenever the episode fits below it.
     */
    fun refresh(reveal: Boolean = false) {
        header.notifyItemChanged(0)
        @Suppress("NotifyDataSetChanged")
        episodes.notifyDataSetChanged()
        @Suppress("NotifyDataSetChanged")
        sequel.notifyDataSetChanged()
        if (reveal) list.post {
            val index = host.currentIndex
            if (index >= 0) list.scrollToPosition(1 + index)
        }
    }

    // region Next season

    /** The series' sequel after the last episode, opening its page; nothing when it has none. */
    private inner class SequelAdapter : RecyclerView.Adapter<SequelAdapter.Holder>() {
        inner class Holder(val binding: ItemPlayerPortraitSequelBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = if (media.sequel != null) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemPlayerPortraitSequelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val next = media.sequel ?: return
            Glide.with(holder.binding.playerSequelImage).load(next.banner ?: next.cover)
                .into(holder.binding.playerSequelImage)
            holder.binding.playerSequelTitle.text = next.userPreferredName
            holder.binding.root.setOnClickListener {
                activity.startActivity(
                    Intent(activity, MediaDetailsActivity::class.java)
                        .putExtra("media", next as java.io.Serializable)
                )
            }
        }
    }

    // endregion

    /** The list's status chip again, after the list editor has been closed. */
    fun refreshListStatus() = header.notifyItemChanged(0)

    // region Header

    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.Holder>() {
        inner class Holder(val binding: ItemPlayerPortraitHeaderBinding) : RecyclerView.ViewHolder(binding.root)

        /** Kept open across rebinds of the same episode, as the user left it. */
        private var synopsisOpenFor: String? = null

        override fun getItemCount() = 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemPlayerPortraitHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val b = holder.binding
            val number = host.episodeNumbers.getOrNull(host.currentIndex)
            val episode = number?.let { media.anime?.episodes?.get(it) }
            b.playerPortraitTitle.text = media.userPreferredName
            b.playerPortraitEpisode.text = episodeLine(number, episode)

            val synopsis = episode?.desc?.takeIf { it.isNotBlank() }
            b.playerPortraitSynopsis.isVisible = synopsis != null
            b.playerPortraitSynopsis.text = synopsis
            fun showSynopsis(open: Boolean) {
                b.playerPortraitSynopsis.maxLines = if (open) Int.MAX_VALUE else 2
                b.playerPortraitSynopsis.contentDescription = activity.getString(
                    if (open) R.string.player_show_less else R.string.player_show_more
                )
            }
            showSynopsis(synopsisOpenFor == number)
            b.playerPortraitSynopsis.setOnClickListener {
                synopsisOpenFor = if (synopsisOpenFor == number) null else number
                showSynopsis(synopsisOpenFor == number)
            }

            bindActions(b, number)
        }
    }

    private fun episodeLine(number: String?, episode: Episode?): String {
        number ?: return ""
        val title = episode?.title?.let { MediaNameAdapter.removeEpisodeNumberCompletely(it).trim() }
            ?.takeIf { it.isNotBlank() && it != "null" }
        return activity.getString(R.string.episode_num, number) + (title?.let { " · $it" } ?: "")
    }

    private fun bindActions(b: ItemPlayerPortraitHeaderBinding, number: String?) {
        // Anything tied to the series' own page needs a real one: extension-only media has none.
        val linked = media.id >= 0
        val signedIn = Anilist.userid != null

        b.playerChipList.isVisible = linked && signedIn
        b.playerChipList.text = listStatusLabel()
        b.playerChipList.setOnClickListener {
            if (activity.supportFragmentManager.findFragmentByTag(TAG_LIST) == null) {
                MediaListDialogSmallFragment.newInstance(media)
                    .show(activity.supportFragmentManager, TAG_LIST)
            }
        }

        b.playerChipComments.isVisible = linked
        b.playerChipComments.setOnClickListener {
            if (activity.supportFragmentManager.findFragmentByTag(PlayerCommentsSheet.TAG) == null) {
                PlayerCommentsSheet.newInstance(
                    media.id, media.mainName(), number?.toIntOrNull(), host.sheetHeight()
                ).show(activity.supportFragmentManager, PlayerCommentsSheet.TAG)
            }
        }

        val shareLinks = mediaShareLinks(
            order = InfoTabContext.forAniList(media),
            isAnime = true,
            aniListId = media.id.takeIf { linked },
            malId = media.idMAL,
            comickSlug = null,
            mangaUpdatesUrl = null,
            mangaBakaId = null,
        )
        b.playerChipShare.isVisible = shareLinks.isNotEmpty()
        b.playerChipShare.setOnClickListener {
            activity.showShareDialog(media.userPreferredName, shareLinks)
        }

        b.playerChipDownload.isVisible = !host.playingDownload
        b.playerChipDownload.setOnClickListener { host.download() }

        val subscribed = SubscriptionHelper.getSubscriptions().containsKey(media.id)
        b.playerChipSubscribe.isVisible = linked
        b.playerChipSubscribe.text = activity.getString(
            if (subscribed) R.string.player_subscribed else R.string.player_subscribe
        )
        b.playerChipSubscribe.setChipIconResource(
            if (subscribed) R.drawable.ic_round_notifications_active_24
            else R.drawable.ic_round_notifications_outline_24
        )
        b.playerChipSubscribe.setOnClickListener {
            val now = !SubscriptionHelper.getSubscriptions().containsKey(media.id)
            val source = host.sourceName
            SubscriptionHelper.saveSubscription(media, now, source)
            snackString(
                if (now) activity.getString(R.string.subscribed_notification, source.orEmpty())
                else activity.getString(R.string.unsubscribed_notification)
            )
            header.notifyItemChanged(0)
        }

        b.playerChipClip.setOnClickListener { host.clip() }
        b.playerChipMore.setOnClickListener { showMore(it, number, linked, signedIn) }
    }

    /** "Watching · 5/12", or "Add to list" for a series not on one. */
    private fun listStatusLabel(): String {
        val status = media.userStatus ?: return activity.getString(R.string.player_add_to_list)
        val statuses = activity.resources.getStringArray(R.array.status)
        val labels = activity.resources.getStringArray(R.array.status_anime)
        val label = labels.getOrNull(statuses.indexOf(status)) ?: status
        val total = media.anime?.totalEpisodes?.toString() ?: "?"
        return "$label · ${media.userProgress ?: 0}/$total"
    }

    private fun showMore(anchor: View, number: String?, linked: Boolean, signedIn: Boolean) {
        PopupMenu(activity, anchor).apply {
            menu.add(0, MORE_SCREENSHOT, 0, R.string.screenshot)
                .setIcon(R.drawable.ic_round_screenshot_frame_2_24)
            if (linked) {
                menu.add(0, MORE_HANDOFF, 1, R.string.continue_on_another_device)
                    .setIcon(R.drawable.ic_round_devices_outline_24)
            }
            if (linked && signedIn && number != null) {
                menu.add(0, MORE_WATCHED, 2, R.string.mark_as_watched)
                    .setIcon(R.drawable.ic_round_visibility_24)
            }
            if (linked) {
                menu.add(0, MORE_SERIES, 3, R.string.player_open_series)
                    .setIcon(R.drawable.ic_round_info_24)
            }
            val tint = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurface)
            for (i in 0 until menu.size()) menu.getItem(i).icon?.mutate()?.setTint(tint)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setForceShowIcon(true)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MORE_SCREENSHOT -> host.screenshot()
                    MORE_HANDOFF -> host.handoff()
                    MORE_WATCHED -> number?.let {
                        updateProgress(media, it)
                        list.postDelayed({ refresh() }, 1500)
                    }
                    MORE_SERIES -> activity.startActivity(
                        Intent(activity, MediaDetailsActivity::class.java).putExtra("mediaId", media.id)
                    )
                }
                true
            }
            show()
        }
    }

    // endregion


    companion object {
        /** The list editor opened from the panel; the player leaves playback alone while it shows. */
        const val TAG_LIST = "player_list_status"

        private const val MORE_SCREENSHOT = 1
        private const val MORE_HANDOFF = 2
        private const val MORE_WATCHED = 3
        private const val MORE_SERIES = 4
    }
}
