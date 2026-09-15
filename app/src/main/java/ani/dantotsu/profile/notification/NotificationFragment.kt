package ani.dantotsu.profile.notification

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.Notification
import ani.dantotsu.databinding.FragmentNotificationsBinding
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.notifications.NotificationReadState
import ani.dantotsu.notifications.comment.CommentStore
import ani.dantotsu.notifications.subscription.SubscriptionStore
import ani.dantotsu.notifications.unread.UnreadChapterStore
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.profile.activity.FeedActivity
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.COMMENT
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.MEDIA
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.ONE
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.SUBSCRIPTION
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.UNREAD_CHAPTER
import ani.dantotsu.profile.notification.NotificationFragment.Companion.NotificationType.USER
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.xwray.groupie.GroupieAdapter
import eu.kanade.tachiyomi.util.system.getSerializableCompat
import kotlinx.coroutines.launch


class NotificationFragment : Fragment() {
    private lateinit var type: NotificationType
    private var getID: Int = -1
    private lateinit var binding: FragmentNotificationsBinding
    private var adapter: GroupieAdapter = GroupieAdapter()
    private var currentPage = 1
    private var hasNextPage = false

    /** Smallest id on the last AniList page fetched, before the tab's own filter. */
    private var lastPageMinId: Int? = null
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.let {
            getID = it.getInt("id")
            type = it.getSerializableCompat<NotificationType>("type") as NotificationType
        }
        binding.notificationRecyclerView.adapter = adapter
        binding.notificationRecyclerView.layoutManager = LinearLayoutManager(context)
        // Fires with the current value on subscribe, so this is the initial load as well as the
        // reload when "show all" is toggled.
        (requireActivity() as NotificationActivity).showAll.observe(viewLifecycleOwner) {
            lifecycleScope.launch {
                binding.notificationProgressBar.isVisible = true
                reload()
                binding.notificationProgressBar.isVisible = false
            }
        }
        binding.notificationSwipeRefresh.setOnRefreshListener {
            lifecycleScope.launch {
                reload()
                binding.notificationSwipeRefresh.isRefreshing = false
            }
        }
        binding.notificationRecyclerView.addOnScrollListener(object :
            RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (shouldLoadMore()) {
                    lifecycleScope.launch {
                        binding.notificationRefresh.isVisible = true
                        getList()
                        binding.notificationRefresh.isVisible = false
                    }
                }
            }
        })

    }

    /** Unread-only unless the screen's "show all" is on; a single deep-linked one always shows. */
    private fun unreadOnly(): Boolean =
        type != ONE && (activity as? NotificationActivity)?.showAll?.value != true

    /**
     * Marks this tab's notifications read — every stored one, not just the pages loaded. An
     * unread-only list empties; a "show all" list restyles its cards in place.
     */
    fun markAllRead() {
        val prefix = when (type) {
            USER -> NotificationReadState.PREFIX_ANILIST_USER
            MEDIA -> NotificationReadState.PREFIX_ANILIST_MEDIA
            SUBSCRIPTION -> NotificationReadState.PREFIX_SUBSCRIPTION
            UNREAD_CHAPTER -> NotificationReadState.PREFIX_CHAPTER
            COMMENT -> NotificationReadState.PREFIX_COMMENT
            ONE -> return
        }
        NotificationReadState.markAllRead(prefix)
        if (unreadOnly()) {
            adapter.clear()
            updateEmptyView()
        } else {
            for (i in 0 until adapter.itemCount) {
                (adapter.getItem(i) as? NotificationItem)?.setRead()
            }
        }
    }

    /**
     * Drops cards read since the list was last in front. A tapped card is only restyled at the
     * time — pulling it out mid-transition would break the shared-element animation into the
     * page it opens — so an unread-only list sheds it here, once the user is back.
     */
    private fun pruneRead() {
        if (!unreadOnly()) return
        val read = (0 until adapter.itemCount)
            .mapNotNull { adapter.getItem(it) as? NotificationItem }
            .filterNot { it.isUnread }
        if (read.isEmpty()) return
        read.forEach { adapter.remove(it) }
        updateEmptyView()
    }

    private fun updateEmptyView() {
        binding.emptyTextView.text = getString(
            if (unreadOnly()) R.string.no_unread_notifications else R.string.nothing_here
        )
        binding.emptyTextView.isVisible = adapter.itemCount == 0
    }

    private suspend fun reload() {
        adapter.clear()
        currentPage = 1
        hasNextPage = false
        binding.emptyTextView.isVisible = false
        getList()
    }

    private suspend fun getList() {
        val unreadOnly = unreadOnly()
        val isAnilist = type == USER || type == MEDIA
        // Unread AniList notifications are the newest, so in unread-only mode pages are pulled
        // until the oldest unread id has been passed — the list may otherwise be too short to
        // scroll, and the scroll listener is what fetches the next page.
        val oldestUnread = NotificationReadState.oldestUnreadAnilistId()
        var pagesLoaded = 0
        do {
            val list = when (type) {
                ONE -> getNotificationsFiltered(false) { it.id == getID }
                // Only the User tab resets AniList's own unread count: it fetches the unfiltered
                // page, which is the one that count lines up with.
                MEDIA -> getNotificationsFiltered(reset = false, type = true) { it.media != null }
                USER -> getNotificationsFiltered { it.media == null }
                SUBSCRIPTION -> getSubscriptions()
                UNREAD_CHAPTER -> getUnreadChapters()
                COMMENT -> getComments()
            }
            val items = list.mapNotNull { notification ->
                val key = NotificationReadState.keyOf(notification)
                val unread = NotificationReadState.isUnread(key)
                if (unreadOnly && !unread) null
                else NotificationItem(notification, type, adapter, ::onClick, key, unread)
            }
            adapter.addAll(items)
            pagesLoaded++

            if (unreadOnly && isAnilist) {
                val pageMin = lastPageMinId
                val pastOldestUnread =
                    oldestUnread == null || pageMin == null || pageMin <= oldestUnread
                if (pastOldestUnread) hasNextPage = false
            }
        } while (unreadOnly && isAnilist && hasNextPage && pagesLoaded < MAX_AUTO_PAGES)

        updateEmptyView()
    }

    private suspend fun getNotificationsFiltered(
        reset: Boolean = true,
        type: Boolean? = null,
        filter: (Notification) -> Boolean
    ): List<Notification> {
        val userId =
            Anilist.userid ?: PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull() ?: 0
        val res = Anilist.query.getNotifications(userId, currentPage, reset, type)?.data?.page
        currentPage = res?.pageInfo?.currentPage?.plus(1) ?: 1
        hasNextPage = res?.pageInfo?.hasNextPage ?: false
        lastPageMinId = res?.notifications?.minOfOrNull { it.id }
        return res?.notifications?.filter(filter) ?: listOf()
    }

    private fun getSubscriptions(): List<Notification> {
        val list = PrefManager.getNullableVal<List<SubscriptionStore>>(
            PrefName.SubscriptionNotificationStore,
            null
        ) ?: listOf()

        return list
            .sortedByDescending { (it.time / 1000L).toInt() }
            .filter { it.image != null } // to remove old data
            .map {
                Notification(
                    it.type,
                    System.currentTimeMillis().toInt(),
                    commentId = it.mediaId,
                    mediaId = it.mediaId,
                    notificationType = it.type,
                    context = it.title + ": " + it.content,
                    createdAt = (it.time / 1000L).toInt(),
                    image = it.image,
                    banner = it.banner ?: it.image,
                    readKey = NotificationReadState.keyOf(it)
                )
            }
    }

    private fun getComments(): List<Notification> {
        val list = PrefManager.getNullableVal<List<CommentStore>>(
            PrefName.CommentNotificationStore,
            null
        ) ?: listOf()
        return list
            .sortedByDescending { (it.time / 1000L).toInt() }
            .map {
                Notification(
                    it.type.toString(),
                    System.currentTimeMillis().toInt(),
                    commentId = it.commentId,
                    notificationType = it.type.toString(),
                    mediaId = it.mediaId,
                    context = it.title + "\n" + it.content,
                    createdAt = (it.time / 1000L).toInt(),
                    readKey = NotificationReadState.keyOf(it)
                )
            }
    }

    private fun getUnreadChapters(): List<Notification> {
        val list = PrefManager.getNullableVal<List<UnreadChapterStore>>(
            PrefName.UnreadChapterNotificationStore,
            null
        ) ?: listOf()
        return list
            .sortedByDescending { (it.time / 1000L).toInt() }
            .filter { it.image != null } // Remove old/invalid data
            .map {
                // Format with HTML for better styling - each on separate line
                val title = "<b>${it.mediaName}</b>"
                val unit = if (it.type == "UnreadEpisode") "Episode" else "Chapter"
                val pending = if (it.type == "UnreadEpisode") "unwatched" else "unread"
                val chapter = if (it.unreadCount == 1) {
                    "$unit ${it.lastChapter}"
                } else {
                    "$unit ${it.lastChapter} <i>(${it.unreadCount} $pending)</i>"
                }
                val source = if (it.type == "UnreadEpisode" && !it.language.isNullOrBlank()) {
                    "<small>${ani.dantotsu.connections.malsync.LanguageMapper.displayWithType(it.language)}</small>"
                } else if (it.source.isNotBlank()) {
                    "<small>Source: ${it.source}</small>"
                } else ""

                // Use <br/> for HTML line breaks to ensure separation
                val content = if (source.isNotEmpty()) {
                    "$title<br/>$chapter<br/>$source"
                } else {
                    "$title<br/>$chapter"
                }

                Notification(
                    it.type,
                    System.currentTimeMillis().toInt(),
                    commentId = it.mediaId,
                    mediaId = it.mediaId,
                    notificationType = it.type,
                    context = content,
                    createdAt = (it.time / 1000L).toInt(),
                    image = it.image,
                    banner = it.banner ?: it.image,
                    readKey = NotificationReadState.keyOf(it)
                )
            }
    }

    private fun shouldLoadMore(): Boolean {
        val layoutManager =
            (binding.notificationRecyclerView.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
        val adapter = binding.notificationRecyclerView.adapter

        return hasNextPage && !binding.notificationRefresh.isVisible && adapter?.itemCount != 0 &&
                layoutManager == (adapter!!.itemCount - 1) &&
                !binding.notificationRecyclerView.canScrollVertically(1)
    }

    fun onClick(id: Int, optional: Int?, type: NotificationClickType, sharedView: View?) {
        val intent = when (type) {
            NotificationClickType.USER -> Intent(
                requireContext(),
                ProfileActivity::class.java
            ).apply {
                putExtra("userId", id)
            }

            NotificationClickType.MEDIA -> Intent(
                requireContext(),
                MediaDetailsActivity::class.java
            ).apply {
                putExtra("mediaId", id)
            }

            NotificationClickType.ACTIVITY -> Intent(
                requireContext(),
                FeedActivity::class.java
            ).apply {
                putExtra("activityId", id)
            }

            NotificationClickType.COMMENT -> Intent(
                requireContext(),
                MediaDetailsActivity::class.java
            ).apply {
                putExtra("FRAGMENT_TO_LOAD", "COMMENTS")
                putExtra("mediaId", id)
                putExtra("commentId", optional ?: -1)
            }

            NotificationClickType.UNDEFINED -> null
        }

        intent?.let {
            val options = if (sharedView != null) {
                ActivityOptionsCompat.makeSceneTransitionAnimation(
                    requireActivity(),
                    sharedView,
                    ViewCompat.getTransitionName(sharedView)!!
                ).toBundle()
            } else null
            ContextCompat.startActivity(requireContext(), it, options)
        }
    }


    override fun onResume() {
        super.onResume()
        if (this::binding.isInitialized) {
            pruneRead()
            binding.root.requestLayout()
        }
    }

    companion object {
        /** Bound on the pages fetched back-to-back to fill an unread-only AniList tab. */
        private const val MAX_AUTO_PAGES = 4

        enum class NotificationClickType { USER, MEDIA, ACTIVITY, COMMENT, UNDEFINED }
        enum class NotificationType { MEDIA, USER, SUBSCRIPTION, UNREAD_CHAPTER, COMMENT, ONE }

        fun newInstance(type: NotificationType, id: Int = -1): NotificationFragment {
            return NotificationFragment().apply {
                arguments = Bundle().apply {
                    putSerializable("type", type)
                    putInt("id", id)
                }
            }
        }
    }

}