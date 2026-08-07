package ani.dantotsu.home

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.drawable.Animatable
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LayoutAnimationController
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.blurImage
import ani.dantotsu.bottomBar
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.AnilistHomeViewModel
import ani.dantotsu.connections.anilist.getUserId
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.currContext
import ani.dantotsu.databinding.FragmentHomeBinding
import ani.dantotsu.home.status.UserStatusAdapter
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.connections.malsync.UnreadChapterInfo
import ani.dantotsu.media.MediaAdaptor
import ani.dantotsu.media.MediaListViewActivity
import ani.dantotsu.media.user.ListActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.profile.ChartBuilder
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.util.customAlertDialog
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.setSlideIn
import ani.dantotsu.setSlideUp
import ani.dantotsu.settings.SettingsDialogFragment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefManager.asLiveBool
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.containsMediaId
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout


class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val unreadCacheReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                refreshUnreadFromCache()
            } catch (e: Exception) {
                ani.dantotsu.util.Logger.log("unreadCacheReceiver error: ${e.message}")
            }
        }
    }
    /**
     * The AniList half of the unread row, and the MALSync info describing it.
     *
     * Three separate things draw this row — the unread observer, the MU-lists observer, and
     * [refreshUnreadFromCache] off a background broadcast — and each composes AniList media with
     * MangaUpdates media. Holding the AniList half in one place is what stops them contradicting
     * each other: the MU observer used to rebuild the adapter from a copy only the unread observer
     * ever wrote, so a row drawn from the cache lost its AniList entries the moment MU data next
     * arrived, with nothing having been refreshed.
     *
     * Fields rather than locals of `onViewCreated` for the same reason. LiveData replays its last
     * value to a newly registered observer, so a recreated view would otherwise have the MU
     * observer fire against an AniList half that had been reset to empty.
     */
    private var unreadAniList: List<Media> = emptyList()
    private var unreadInfoMap: Map<Int, UnreadChapterInfo> = emptyMap()

    /**
     * Whether the AniList half has produced an answer yet. Until it has, an empty row means "still
     * loading", not "nothing to read" — the MU observer fires long before MALSync has been asked
     * anything, and letting it declare the row empty replaces the spinner with "no unread chapters"
     * while the answer is still on its way.
     */
    private var unreadAniListSettled = false

    /**
     * An unread list that arrived before home data had loaded. The observer can't act on one yet
     * (see where this is set), and LiveData does not redeliver, so dropping it lost the list for
     * the rest of the session.
     */
    private var pendingUnread: ArrayList<Media>? = null

    /** MangaUpdates entries with chapters the user hasn't reached. Ordered by [sortUnread]. */
    private fun muUnread(): List<ani.dantotsu.connections.mangaupdates.MUMedia> =
        model.getMuHomeLists().value?.get("Reading")
            ?.filter { it.latestChapter != null && it.latestChapter > (it.userChapter ?: 0) }
            ?: emptyList()

    /** How far behind an entry is. Unknown counts sort last, as they always have. */
    private fun unreadCountOf(item: Any): Int = when (item) {
        is Media -> unreadInfoMap[item.id]?.let { it.lastChapter - it.userProgress } ?: Int.MAX_VALUE
        is ani.dantotsu.connections.mangaupdates.MUMedia ->
            (item.latestChapter ?: 0) - (item.userChapter ?: 0)

        else -> Int.MAX_VALUE
    }

    /** When an entry's newest chapter landed, epoch ms. Unknown dates sort last. */
    private fun latestChapterAtOf(item: Any): Long = when (item) {
        is Media -> unreadInfoMap[item.id]?.latestChapterAt ?: Long.MIN_VALUE
        is ani.dantotsu.connections.mangaupdates.MUMedia -> item.latestChapterAt ?: Long.MIN_VALUE
        else -> Long.MIN_VALUE
    }

    /**
     * Orders the row per [PrefName.UnreadChaptersSort], over AniList and MangaUpdates entries
     * together.
     *
     * Both halves used to be sorted separately and concatenated, which pinned every MangaUpdates
     * series below every AniList one however far behind it was. Sorting the combined list is what
     * puts them where they belong — [unreadCountOf] and [latestChapterAtOf] each read the
     * equivalent field from whichever source the entry came from.
     */
    private fun sortUnread(items: List<Any>): List<Any> =
        if (PrefManager.getVal<String>(PrefName.UnreadChaptersSort) == "recent")
            items.sortedByDescending { latestChapterAtOf(it) }
        else items.sortedBy { unreadCountOf(it) }

    /**
     * Draws the unread row from [unreadAniList] plus whatever MangaUpdates currently has unread.
     * Every path that changes either half ends here, so the two can't disagree about the other.
     */
    private fun renderUnreadRow(animate: Boolean = true) {
        if (_binding == null) return
        val info = unreadInfoMap
        val combined: List<Any> = sortUnread(unreadAniList + muUnread())
        // "More" opens the AniList half only, in the order the row is showing it.
        val aniItems = combined.filterIsInstance<Media>()
        // Nothing to show and no answer yet: leave the section as it is, still loading.
        if (combined.isEmpty() && !unreadAniListSettled) return

        val rv = binding.homeUnreadChaptersRecyclerView
        rv.visibility = View.GONE
        binding.homeUnreadChaptersEmpty.visibility = View.GONE
        if (combined.isNotEmpty()) {
            rv.adapter = UnreadChaptersAdapter(combined, info)
            rv.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            rv.visibility = View.VISIBLE
            if (animate) {
                rv.layoutAnimation = LayoutAnimationController(setSlideIn(), 0.25f)
                rv.post { if (_binding != null) rv.scheduleLayoutAnimation() }
            }
            // "More" opens the AniList half; with none of it there is nothing for it to list.
            if (aniItems.isEmpty()) binding.homeUnreadChaptersMore.setOnClickListener {}
            else binding.homeUnreadChaptersMore.setOnClickListener { i ->
                MediaListViewActivity.passedMedia = ArrayList(aniItems)
                MediaListViewActivity.passedUnreadInfo = info
                ContextCompat.startActivity(
                    i.context, Intent(i.context, MediaListViewActivity::class.java)
                        .putExtra("title", getString(R.string.unread_chapters)),
                    null
                )
            }
        } else {
            binding.homeUnreadChaptersEmpty.visibility = View.VISIBLE
        }
        binding.homeUnreadChaptersMore.visibility = View.VISIBLE
        binding.homeUnreadChapters.visibility = View.VISIBLE
        binding.homeUnreadChaptersProgressBar.visibility = View.GONE
        updateUnreadRefreshAlignment()
    }

    /**
     * Turns a list of unread AniList manga into the row's AniList half: asks MALSync how many
     * chapters each actually has, then hands off to [renderUnreadRow].
     */
    private fun applyUnreadList(unreadList: List<Media>) {
        if (unreadList.isEmpty()) {
            unreadAniList = emptyList()
            unreadInfoMap = emptyMap()
            unreadAniListSettled = true
            renderUnreadRow()
            return
        }
        lifecycleScope.launch {
            val unreadInfo = mutableMapOf<Int, UnreadChapterInfo>()

            withContext(Dispatchers.IO) {
                // Only perform MalSync batch if preference enabled and check mode allows manga
                val malMode3 = PrefManager.getVal<String>(PrefName.MalSyncCheckMode) ?: "both"
                if (PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled) && malMode3 != "anime") {
                    // Collect pairs of (anilistId, malId) - prefer MAL ID, fallback to AniList ID
                    val mediaIds = unreadList.map { media -> Pair(media.id, media.idMAL) }
                    val batchResults =
                        ani.dantotsu.connections.malsync.MalSyncApi.getBatchProgressByMedia(mediaIds)

                    // Map results back to media IDs
                    for (media in unreadList) {
                        val result = batchResults[media.id]
                        if (result != null && result.lastEp != null) {
                            unreadInfo[media.id] = UnreadChapterInfo(
                                mediaId = media.id,
                                lastChapter = result.lastEp.total,
                                source = result.source,
                                userProgress = media.userProgress ?: 0,
                                latestChapterAt = result.lastEp.timestampMillis()
                            )
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                if (unreadInfo.isNotEmpty()) {
                    // Show live MALSync results (do not filter — fresh data may include new
                    // unread chapters). Ordering is [renderUnreadRow]'s job, since it is the only
                    // place that has the MangaUpdates entries to order these against.
                    unreadAniList = unreadList
                    unreadInfoMap = unreadInfo
                    // Persist MALSync unread info so cached UI can show source/lastEp on next load
                    try {
                        ani.dantotsu.settings.saving.PrefManager.setCustomVal("cached_unread_info", unreadInfo)
                    } catch (e: Exception) {
                        ani.dantotsu.util.Logger.log("Failed to cache unread info: ${e.message}")
                    }
                } else {
                    // No fresh MALSync data. Prefer cached unreadInfo when available.
                    val merged = mergedCachedInfoFor(model.getMangaContinue().value)
                    unreadAniList = if (merged.isEmpty()) emptyList() else unreadList.filter { media ->
                        val last = getLastChapterForMedia(media, merged)
                        val progress = merged[media.id]?.userProgress ?: media.userProgress ?: 0
                        last != null && last > progress
                    }
                    unreadInfoMap = merged
                }
                unreadAniListSettled = true
                renderUnreadRow()
                binding.homeUnreadChaptersMore.startAnimation(setSlideUp())
                binding.homeUnreadChapters.startAnimation(setSlideUp())
            }
        }
    }

    // Helper: merge cached UnreadChapterInfo (from prefs) with current list's progress
    private fun mergedCachedInfoFor(list: List<Media>?): Map<Int, UnreadChapterInfo> {
        // Load cached unread info map (malsync results)
        val cachedUnreadInfo: Map<Int, UnreadChapterInfo> = try {
            @Suppress("UNCHECKED_CAST")
            ani.dantotsu.settings.saving.PrefManager.getNullableCustomVal(
                "cached_unread_info",
                null,
                java.util.HashMap::class.java
            ) as? Map<Int, UnreadChapterInfo> ?: mapOf()
        } catch (e: Exception) {
            mapOf()
        }
        if (list.isNullOrEmpty()) return cachedUnreadInfo
        val currentById = list.associateBy { it.id }
        return cachedUnreadInfo.mapValues { (id, info) ->
            val updatedProgress = currentById[id]?.userProgress ?: info.userProgress
            info.copy(userProgress = updatedProgress)
        }
    }

    // Helper: determine last chapter number for a media, preferring MALSync info, then local chapters, then totalChapters
    private fun isMalSyncDisabledForManga(): Boolean {
        val enabled = PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled)
        val mode = PrefManager.getVal<String>(PrefName.MalSyncCheckMode) ?: "both"
        return !enabled || mode == "anime"
    }

    private fun updateUnreadRefreshButtonState() {
        if (_binding == null) return
        val disabled = isMalSyncDisabledForManga()
        binding.homeUnreadChaptersRefresh.isEnabled = !disabled
        binding.homeUnreadChaptersRefresh.alpha = if (disabled) 0.38f else 1f
    }

    private fun getLastChapterForMedia(media: Media, infoMap: Map<Int, UnreadChapterInfo>?): Int? {
        val info = infoMap?.get(media.id)
        if (info?.lastChapter != null) return info.lastChapter
        val manga = media.manga
        if (manga != null) {
            val nums = manga.chapters?.values
                ?.mapNotNull { ani.dantotsu.media.MediaNameAdapter.findChapterNumber(it.number)?.toInt() }
            if (!nums.isNullOrEmpty()) return nums.maxOrNull()
            if (manga.totalChapters != null) return manga.totalChapters
        }
        return null
    }

    // Helper to update refresh alignment (used by unread UI)
    private fun updateUnreadRefreshAlignment() {
        try {
            val moreVisible = binding.homeUnreadChaptersMore.visibility == View.VISIBLE
            val refreshContainer = binding.homeUnreadChaptersRefresh.parent as? FrameLayout
            // adjust the FrameLayout (container) width so the refresh button can align to end
            val parentLp = refreshContainer?.layoutParams as? LinearLayout.LayoutParams
            if (parentLp != null) {
                parentLp.width = if (moreVisible) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT
                refreshContainer.layoutParams = parentLp
            }

            val refreshLp = binding.homeUnreadChaptersRefresh.layoutParams as? FrameLayout.LayoutParams
            if (refreshLp != null) {
                refreshLp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                binding.homeUnreadChaptersRefresh.layoutParams = refreshLp
            }
        } catch (e: Exception) {
            ani.dantotsu.util.Logger.log("updateUnreadRefreshAlignment error: ${e.message}")
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showHomeStatsPopup() {
        val statOptions = arrayOf(
            getString(R.string.none),
            getString(R.string.episodes_watched),
            getString(R.string.chapters_read),
            getString(R.string.anime_count),
            getString(R.string.days_watched),
            getString(R.string.manga_count),
            getString(R.string.volumes_read),
            getString(R.string.anime_mean_score),
            getString(R.string.manga_mean_score),
        )
        val dialogView = layoutInflater.inflate(R.layout.dialog_home_stats, null)
        val dropdown1 = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.homeStat1Dropdown)
        val dropdown2 = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.homeStat2Dropdown)
        val adapter = android.widget.ArrayAdapter(requireContext(), R.layout.item_dropdown, statOptions)
        dropdown1.setAdapter(adapter)
        dropdown2.setAdapter(adapter)
        dropdown1.setText(statOptions[PrefManager.getVal<Int>(PrefName.HomeStat1)], false)
        dropdown2.setText(statOptions[PrefManager.getVal<Int>(PrefName.HomeStat2)], false)
        requireContext().customAlertDialog().apply {
            setTitle(getString(R.string.home_stats_select))
            setCustomView(dialogView)
            setPosButton(R.string.ok) {
                val sel1 = statOptions.indexOf(dropdown1.text.toString())
                val sel2 = statOptions.indexOf(dropdown2.text.toString())
                if (sel1 >= 0) PrefManager.setVal(PrefName.HomeStat1, sel1)
                if (sel2 >= 0) PrefManager.setVal(PrefName.HomeStat2, sel2)
                Refresh.activity[1]?.postValue(true)
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    val model: AnilistHomeViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val scope = lifecycleScope
        Logger.log("HomeFragment")

        // class-level helpers `updateUnreadRefreshAlignment`, `mergedCachedInfoFor`, and `getLastChapterForMedia` are defined at class scope
        fun load() {
            Logger.log("Loading HomeFragment")
            if (activity != null && _binding != null) lifecycleScope.launch(Dispatchers.Main) {
                binding.homeUserName.text = Anilist.username
                // Populate configurable stats
                fun getStatLabelAndValue(statIndex: Int): Pair<String, String>? {
                    return when (statIndex) {
                        1 -> getString(R.string.episodes_watched) to (Anilist.episodesWatched?.toString() ?: "0")
                        2 -> getString(R.string.chapters_read) to (Anilist.chapterRead?.toString() ?: "0")
                        3 -> getString(R.string.anime_count) to (Anilist.animeCount?.toString() ?: "0")
                        4 -> getString(R.string.days_watched) to (((Anilist.minutesWatched ?: 0) / 1440.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else String.format("%.1f", it) })
                        5 -> getString(R.string.manga_count) to (Anilist.mangaCount?.toString() ?: "0")
                        6 -> getString(R.string.volumes_read) to (Anilist.volumesRead?.toString() ?: "0")
                        7 -> getString(R.string.anime_mean_score) to (Anilist.animeMeanScore?.let { String.format("%.1f", it) } ?: "0")
                        8 -> getString(R.string.manga_mean_score) to (Anilist.mangaMeanScore?.let { String.format("%.1f", it) } ?: "0")
                        else -> null
                    }
                }
                val stat1 = PrefManager.getVal<Int>(PrefName.HomeStat1)
                val stat2 = PrefManager.getVal<Int>(PrefName.HomeStat2)
                val result1 = getStatLabelAndValue(stat1)
                val result2 = getStatLabelAndValue(stat2)
                fun statIndexToProfileArgs(statIndex: Int): Pair<ChartBuilder.Companion.MediaType, ChartBuilder.Companion.StatType>? {
                    return when (statIndex) {
                        1 -> ChartBuilder.Companion.MediaType.ANIME to ChartBuilder.Companion.StatType.TIME
                        2 -> ChartBuilder.Companion.MediaType.MANGA to ChartBuilder.Companion.StatType.TIME
                        3 -> ChartBuilder.Companion.MediaType.ANIME to ChartBuilder.Companion.StatType.COUNT
                        4 -> ChartBuilder.Companion.MediaType.ANIME to ChartBuilder.Companion.StatType.TIME
                        5 -> ChartBuilder.Companion.MediaType.MANGA to ChartBuilder.Companion.StatType.COUNT
                        6 -> ChartBuilder.Companion.MediaType.MANGA to ChartBuilder.Companion.StatType.COUNT
                        7 -> ChartBuilder.Companion.MediaType.ANIME to ChartBuilder.Companion.StatType.AVG_SCORE
                        8 -> ChartBuilder.Companion.MediaType.MANGA to ChartBuilder.Companion.StatType.AVG_SCORE
                        else -> null
                    }
                }
                if (result1 != null) {
                    binding.homeUserStat1Row.visibility = View.VISIBLE
                    binding.homeUserStat1Label.text = result1.first
                    binding.homeUserStat1Value.text = result1.second
                    val args1 = statIndexToProfileArgs(stat1)
                    binding.homeUserStat1Value.setOnClickListener {
                        if (args1 != null) {
                            ContextCompat.startActivity(
                                requireContext(),
                                Intent(requireContext(), ProfileActivity::class.java)
                                    .putExtra("userId", Anilist.userid)
                                    .putExtra("selectedTab", 2)
                                    .putExtra("statsMediaType", args1.first.name)
                                    .putExtra("statsStatType", args1.second.name),
                                null
                            )
                        }
                    }
                    binding.homeUserStat1Value.setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showHomeStatsPopup()
                        true
                    }
                } else {
                    binding.homeUserStat1Row.visibility = View.GONE
                }
                if (result2 != null) {
                    binding.homeUserStat2Row.visibility = View.VISIBLE
                    binding.homeUserStat2Label.text = result2.first
                    binding.homeUserStat2Value.text = result2.second
                    val args2 = statIndexToProfileArgs(stat2)
                    binding.homeUserStat2Value.setOnClickListener {
                        if (args2 != null) {
                            ContextCompat.startActivity(
                                requireContext(),
                                Intent(requireContext(), ProfileActivity::class.java)
                                    .putExtra("userId", Anilist.userid)
                                    .putExtra("selectedTab", 2)
                                    .putExtra("statsMediaType", args2.first.name)
                                    .putExtra("statsStatType", args2.second.name),
                                null
                            )
                        }
                    }
                    binding.homeUserStat2Value.setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showHomeStatsPopup()
                        true
                    }
                } else {
                    binding.homeUserStat2Row.visibility = View.GONE
                }
                binding.homeUserAvatar.loadImage(Anilist.avatar)
                val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
                blurImage(
                    if (bannerAnimations) binding.homeUserBg else binding.homeUserBgNoKen,
                    Anilist.bg
                )
                binding.homeUserDataProgressBar.visibility = View.GONE
                binding.homeNotificationCount.isVisible = Anilist.unreadNotificationCount > 0
                        && PrefManager.getVal<Boolean>(PrefName.ShowNotificationRedDot) == true
                binding.homeNotificationCount.text = Anilist.unreadNotificationCount.toString()

                binding.homeAnimeList.setOnClickListener {
                    ContextCompat.startActivity(
                        requireActivity(), Intent(requireActivity(), ListActivity::class.java)
                            .putExtra("anime", true)
                            .putExtra("userId", Anilist.userid)
                            .putExtra("username", Anilist.username), null
                    )
                }
                binding.homeMangaList.setOnClickListener {
                    ContextCompat.startActivity(
                        requireActivity(), Intent(requireActivity(), ListActivity::class.java)
                            .putExtra("anime", false)
                            .putExtra("userId", Anilist.userid)
                            .putExtra("username", Anilist.username), null
                    )
                }

                binding.homeUserAvatarContainer.startAnimation(setSlideUp())
                binding.homeUserDataContainer.visibility = View.VISIBLE
                binding.homeUserDataContainer.layoutAnimation =
                    LayoutAnimationController(setSlideUp(), 0.25f)
                binding.homeAnimeList.visibility = View.VISIBLE
                binding.homeMangaList.visibility = View.VISIBLE
                binding.homeListContainer.layoutAnimation =
                    LayoutAnimationController(setSlideIn(), 0.25f)
            }
            else {
                snackString(currContext()?.getString(R.string.please_reload))
            }
        }

        // Manual refresh button for unread chapters
        var refreshAnimator: android.animation.ObjectAnimator? = null
        binding.homeUnreadChaptersRefresh.setOnClickListener {
            binding.homeUnreadChaptersRefresh.isEnabled = false
            scope.launch {
                withContext(Dispatchers.IO) {
                    model.initUnreadChapters()
                }
            }
        }
        updateUnreadRefreshButtonState()

        // Observe loading state to rotate refresh icon while a check runs
        model.getUnreadChaptersLoading().observe(viewLifecycleOwner) { loading ->
            binding.homeUnreadChaptersRefresh.isEnabled = !loading && !isMalSyncDisabledForManga()
            binding.homeUnreadChaptersRefresh.alpha = if (!loading && isMalSyncDisabledForManga()) 0.38f else 1f
            if (loading) {
                if (refreshAnimator == null) {
                    refreshAnimator = android.animation.ObjectAnimator.ofFloat(
                        binding.homeUnreadChaptersRefresh,
                        "rotation",
                        0f,
                        360f
                    ).apply {
                        duration = 1000
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        interpolator = android.view.animation.LinearInterpolator()
                    }
                }
                refreshAnimator?.start()
            } else {
                refreshAnimator?.cancel()
                binding.homeUnreadChaptersRefresh.rotation = 0f
            }
            // Keep the main progress bar visible when no cached results
            if (model.getUnreadChapters().value.isNullOrEmpty()) {
                binding.homeUnreadChaptersProgressBar.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }
        binding.homeUserAvatarContainer.setSafeOnClickListener {
            val dialogFragment =
                SettingsDialogFragment.newInstance(SettingsDialogFragment.Companion.PageType.HOME)
            dialogFragment.show(
                (it.context as androidx.appcompat.app.AppCompatActivity).supportFragmentManager,
                "dialog"
            )
        }
        binding.searchImageContainer.setSafeOnClickListener {
            SearchBottomSheet.newInstance().show(
                (it.context as androidx.appcompat.app.AppCompatActivity).supportFragmentManager,
                "search"
            )
        }
        binding.homeUserAvatarContainer.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            ContextCompat.startActivity(
                requireContext(), Intent(requireContext(), ProfileActivity::class.java)
                    .putExtra("userId", Anilist.userid), null
            )
            false
        }

        binding.homeContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.homeUserBg.updateLayoutParams { height += statusBarHeight }
        binding.homeUserBgNoKen.updateLayoutParams { height += statusBarHeight }
        binding.homeTopContainer.updatePadding(top = statusBarHeight)

        var reached = false
        val duration = ((PrefManager.getVal(PrefName.AnimationSpeed) as Float) * 200).toLong()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            binding.homeScroll.setOnScrollChangeListener { _, _, _, _, _ ->
                if (!binding.homeScroll.canScrollVertically(1)) {
                    reached = true
                    bottomBar.animate().translationZ(0f).setDuration(duration).start()
                    ObjectAnimator.ofFloat(bottomBar, "elevation", 4f, 0f).setDuration(duration)
                        .start()
                } else {
                    if (reached) {
                        bottomBar.animate().translationZ(12f).setDuration(duration).start()
                        ObjectAnimator.ofFloat(bottomBar, "elevation", 0f, 4f).setDuration(duration)
                            .start()
                    }
                }
            }
        }
        var height = statusBarHeight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val displayCutout = activity?.window?.decorView?.rootWindowInsets?.displayCutout
            if (displayCutout != null) {
                if (displayCutout.boundingRects.size > 0) {
                    height =
                        max(
                            statusBarHeight,
                            min(
                                displayCutout.boundingRects[0].width(),
                                displayCutout.boundingRects[0].height()
                            )
                        )
                }
            }
        }
        binding.homeRefresh.setSlingshotDistance(height + 128)
        binding.homeRefresh.setProgressViewEndTarget(false, height + 128)
        binding.homeRefresh.setOnRefreshListener {
            Refresh.activity[1]!!.postValue(true)
        }

        //UserData
        binding.homeUserDataProgressBar.visibility = View.VISIBLE
        binding.homeUserDataContainer.visibility = View.GONE
        if (model.loaded) {
            load()
        }
        //List Images
        model.getListImages().observe(viewLifecycleOwner) {
            if (it.isNotEmpty()) {
                binding.homeAnimeListImage.loadImage(it[0] ?: "https://bit.ly/31bsIHq")
                binding.homeMangaListImage.loadImage(it[1] ?: "https://bit.ly/2ZGfcuG")
            }
        }

        //Function For Recycler Views
        fun initRecyclerView(
            mode: LiveData<ArrayList<Media>>,
            container: View,
            recyclerView: RecyclerView,
            progress: View,
            empty: View,
            title: View,
            more: View,
            string: String
        ) {
            container.visibility = View.VISIBLE
            progress.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            empty.visibility = View.GONE
            title.visibility = View.VISIBLE
            more.visibility = View.INVISIBLE

            mode.observe(viewLifecycleOwner) {
                recyclerView.visibility = View.GONE
                empty.visibility = View.GONE
                if (it != null) {
                    if (it.isNotEmpty()) {
                        recyclerView.adapter = MediaAdaptor(0, it, requireActivity())
                        recyclerView.layoutManager = LinearLayoutManager(
                            requireContext(),
                            LinearLayoutManager.HORIZONTAL,
                            false
                        )
                        more.setOnClickListener { i ->
                            MediaListViewActivity.passedMedia = it
                            ContextCompat.startActivity(
                                i.context, Intent(i.context, MediaListViewActivity::class.java)
                                    .putExtra("title", string),
                                null
                            )
                        }
                        recyclerView.visibility = View.VISIBLE
                        recyclerView.layoutAnimation =
                            LayoutAnimationController(setSlideIn(), 0.25f)

                    } else {
                        empty.visibility = View.VISIBLE
                    }
                    more.visibility = View.VISIBLE
                    title.visibility = View.VISIBLE
                    more.startAnimation(setSlideUp())
                    title.startAnimation(setSlideUp())
                    progress.visibility = View.GONE
                }
            }

        }

        // Recycler Views
        // Continue Watching with MALSync data
        binding.homeContinueWatchingContainer.visibility = View.VISIBLE
        binding.homeWatchingProgressBar.visibility = View.VISIBLE
        binding.homeWatchingRecyclerView.visibility = View.GONE
        binding.homeWatchingEmpty.visibility = View.GONE
        binding.homeContinueWatch.visibility = View.VISIBLE
        binding.homeContinueWatchMore.visibility = View.INVISIBLE

        model.getAnimeContinue().observe(viewLifecycleOwner) { continueWatchingList ->
            binding.homeWatchingRecyclerView.visibility = View.GONE
            binding.homeWatchingEmpty.visibility = View.GONE
            if (continueWatchingList != null) {
                if (continueWatchingList.isNotEmpty()) {
                    // Fetch MALSync data using batch endpoint
                    scope.launch {
                        val unreleasedInfo = mutableMapOf<Int, ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo>()

                        val malMode = PrefManager.getVal<String>(PrefName.MalSyncCheckMode) ?: "both"
                        if (PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled) && malMode != "manga") {
                            withContext(Dispatchers.IO) {
                                // Collect pairs of (anilistId, malId)
                                val animeIds = continueWatchingList.map { anime ->
                                    Pair(anime.id, anime.idMAL)
                                }
                                val batchResults = ani.dantotsu.connections.malsync.MalSyncApi.getBatchAnimeEpisodes(animeIds)

                                // Map results back to anime IDs - always include language info
                                for (anime in continueWatchingList) {
                                    val result = batchResults[anime.id]
                                    if (result != null && result.lastEp != null) {
                                        val malSyncEpisode = result.lastEp.total
                                        val userProgress = anime.userProgress ?: 0
                                        val languageOption = ani.dantotsu.connections.malsync.LanguageMapper.mapLanguage(result.id)

                                        // Always add language info for display
                                        unreleasedInfo[anime.id] = ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo(
                                            mediaId = anime.id,
                                            lastEpisode = malSyncEpisode,
                                            languageId = result.id,
                                            languageDisplay = languageOption.displayName,
                                            userProgress = userProgress
                                        )
                                    }
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (unreleasedInfo.isNotEmpty()) {
                                // Use the list as-is or sort by last watched
                                val sortedList = continueWatchingList

                                binding.homeWatchingRecyclerView.adapter =
                                    UnreleasedEpisodesAdapter(sortedList, unreleasedInfo)
                                binding.homeWatchingRecyclerView.layoutManager = LinearLayoutManager(
                                    requireContext(),
                                    LinearLayoutManager.HORIZONTAL,
                                    false
                                )
                                binding.homeContinueWatchMore.setOnClickListener { i ->
                                    MediaListViewActivity.passedMedia = ArrayList(sortedList)
                                    MediaListViewActivity.passedUnreleasedInfo = unreleasedInfo
                                    ContextCompat.startActivity(
                                        i.context, Intent(i.context, MediaListViewActivity::class.java)
                                            .putExtra("title", getString(R.string.continue_watching)),
                                        null
                                    )
                                }
                                binding.homeWatchingRecyclerView.visibility = View.VISIBLE
                                binding.homeWatchingRecyclerView.layoutAnimation =
                                    LayoutAnimationController(setSlideIn(), 0.25f)
                                } else {
                                // No MALSync data available or MALSync disabled, show standard adapter
                                binding.homeWatchingRecyclerView.adapter = MediaAdaptor(0, continueWatchingList, requireActivity())
                                binding.homeWatchingRecyclerView.layoutManager = LinearLayoutManager(
                                    requireContext(),
                                    LinearLayoutManager.HORIZONTAL,
                                    false
                                )
                                binding.homeContinueWatchMore.setOnClickListener { i ->
                                    MediaListViewActivity.passedMedia = continueWatchingList
                                    ContextCompat.startActivity(
                                        i.context, Intent(i.context, MediaListViewActivity::class.java)
                                            .putExtra("title", getString(R.string.continue_watching)),
                                        null
                                    )
                                }
                                binding.homeWatchingRecyclerView.visibility = View.VISIBLE
                                binding.homeWatchingRecyclerView.layoutAnimation =
                                    LayoutAnimationController(setSlideIn(), 0.25f)
                            }
                            binding.homeContinueWatchMore.visibility = View.VISIBLE
                            binding.homeContinueWatch.visibility = View.VISIBLE
                            binding.homeContinueWatchMore.startAnimation(setSlideUp())
                            binding.homeContinueWatch.startAnimation(setSlideUp())
                            binding.homeWatchingProgressBar.visibility = View.GONE
                        }
                    }
                } else {
                    binding.homeWatchingEmpty.visibility = View.VISIBLE
                    binding.homeContinueWatchMore.visibility = View.VISIBLE
                    binding.homeContinueWatch.visibility = View.VISIBLE
                    binding.homeContinueWatchMore.startAnimation(setSlideUp())
                    binding.homeContinueWatch.startAnimation(setSlideUp())
                    binding.homeWatchingProgressBar.visibility = View.GONE
                }
            }
        }

        binding.homeWatchingBrowseButton.setOnClickListener {
            bottomBar.selectTabAt(0)
        }

        initRecyclerView(
            model.getAnimeFav(),
            binding.homeFavAnimeContainer,
            binding.homeFavAnimeRecyclerView,
            binding.homeFavAnimeProgressBar,
            binding.homeFavAnimeEmpty,
            binding.homeFavAnime,
            binding.homeFavAnimeMore,
            getString(R.string.fav_anime)
        )

        // Planned Anime with MALSync data
        binding.homePlannedAnimeContainer.visibility = View.VISIBLE
        binding.homePlannedAnimeProgressBar.visibility = View.VISIBLE
        binding.homePlannedAnimeRecyclerView.visibility = View.GONE
        binding.homePlannedAnimeEmpty.visibility = View.GONE
        binding.homePlannedAnime.visibility = View.VISIBLE
        binding.homePlannedAnimeMore.visibility = View.INVISIBLE

        model.getAnimePlanned().observe(viewLifecycleOwner) { plannedList ->
            binding.homePlannedAnimeRecyclerView.visibility = View.GONE
            binding.homePlannedAnimeEmpty.visibility = View.GONE
            if (plannedList != null) {
                if (plannedList.isNotEmpty()) {
                    // Fetch MALSync data using batch endpoint (skipped if MALSync disabled)
                    scope.launch {
                        val plannedInfo = mutableMapOf<Int, ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo>()

                        val malMode2 = PrefManager.getVal<String>(PrefName.MalSyncCheckMode) ?: "both"
                        if (PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled) && malMode2 != "manga") {
                            withContext(Dispatchers.IO) {
                                // Collect pairs of (anilistId, malId)
                                val animeIds = plannedList.map { anime ->
                                    Pair(anime.id, anime.idMAL)
                                }
                                val batchResults = ani.dantotsu.connections.malsync.MalSyncApi.getBatchAnimeEpisodes(animeIds)

                                // Map results back to anime IDs - always include language info
                                for (anime in plannedList) {
                                    val result = batchResults[anime.id]
                                    if (result != null && result.lastEp != null) {
                                        val malSyncEpisode = result.lastEp.total
                                        val userProgress = anime.userProgress ?: 0
                                        val languageOption = ani.dantotsu.connections.malsync.LanguageMapper.mapLanguage(result.id)

                                        // Always add language info for display
                                        plannedInfo[anime.id] = ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo(
                                            mediaId = anime.id,
                                            lastEpisode = malSyncEpisode,
                                            languageId = result.id,
                                            languageDisplay = languageOption.displayName,
                                            userProgress = userProgress
                                        )
                                    }
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (plannedInfo.isNotEmpty()) {
                                // Use the list as-is
                                val sortedList = plannedList

                                binding.homePlannedAnimeRecyclerView.adapter =
                                    UnreleasedEpisodesAdapter(sortedList, plannedInfo)
                                binding.homePlannedAnimeRecyclerView.layoutManager = LinearLayoutManager(
                                    requireContext(),
                                    LinearLayoutManager.HORIZONTAL,
                                    false
                                )
                                binding.homePlannedAnimeMore.setOnClickListener { i ->
                                    MediaListViewActivity.passedMedia = ArrayList(sortedList)
                                    MediaListViewActivity.passedUnreleasedInfo = plannedInfo
                                    ContextCompat.startActivity(
                                        i.context, Intent(i.context, MediaListViewActivity::class.java)
                                            .putExtra("title", getString(R.string.planned_anime)),
                                        null
                                    )
                                }
                                binding.homePlannedAnimeRecyclerView.visibility = View.VISIBLE
                                binding.homePlannedAnimeRecyclerView.layoutAnimation =
                                    LayoutAnimationController(setSlideIn(), 0.25f)
                            } else {
                                // No MALSync data available, show standard adapter
                                binding.homePlannedAnimeRecyclerView.adapter = MediaAdaptor(0, plannedList, requireActivity())
                                binding.homePlannedAnimeRecyclerView.layoutManager = LinearLayoutManager(
                                    requireContext(),
                                    LinearLayoutManager.HORIZONTAL,
                                    false
                                )
                                binding.homePlannedAnimeMore.setOnClickListener { i ->
                                    MediaListViewActivity.passedMedia = plannedList
                                    ContextCompat.startActivity(
                                        i.context, Intent(i.context, MediaListViewActivity::class.java)
                                            .putExtra("title", getString(R.string.planned_anime)),
                                        null
                                    )
                                }
                                binding.homePlannedAnimeRecyclerView.visibility = View.VISIBLE
                                binding.homePlannedAnimeRecyclerView.layoutAnimation =
                                    LayoutAnimationController(setSlideIn(), 0.25f)
                            }
                            binding.homePlannedAnimeMore.visibility = View.VISIBLE
                            binding.homePlannedAnime.visibility = View.VISIBLE
                            binding.homePlannedAnimeMore.startAnimation(setSlideUp())
                            binding.homePlannedAnime.startAnimation(setSlideUp())
                            binding.homePlannedAnimeProgressBar.visibility = View.GONE
                        }
                    }
                } else {
                    binding.homePlannedAnimeEmpty.visibility = View.VISIBLE
                    binding.homePlannedAnimeMore.visibility = View.VISIBLE
                    binding.homePlannedAnime.visibility = View.VISIBLE
                    binding.homePlannedAnimeMore.startAnimation(setSlideUp())
                    binding.homePlannedAnime.startAnimation(setSlideUp())
                    binding.homePlannedAnimeProgressBar.visibility = View.GONE
                }
            }
        }

        binding.homePlannedAnimeBrowseButton.setOnClickListener {
            bottomBar.selectTabAt(0)
        }

        // Unread Chapters Section
        // Start in loading state; cached unread will be displayed after AniList init finishes
        binding.homeUnreadChaptersContainer.visibility = View.VISIBLE
        binding.homeUnreadChaptersProgressBar.visibility = View.VISIBLE
        binding.homeUnreadChaptersRecyclerView.visibility = View.GONE
        binding.homeUnreadChaptersEmpty.visibility = View.GONE
        binding.homeUnreadChapters.visibility = View.VISIBLE
        binding.homeUnreadChaptersMore.visibility = View.GONE
        updateUnreadRefreshAlignment()

        // Observe error state to show appropriate message
        model.getUnreadChaptersError().observe(viewLifecycleOwner) { hasError ->
            if (hasError) {
                binding.homeUnreadChaptersEmptyText.text = getString(R.string.error_fetching_unread_chapters)
            } else {
                val malMode = PrefManager.getVal<String>(PrefName.MalSyncCheckMode) ?: "both"
                binding.homeUnreadChaptersEmptyText.text = when {
                    !PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled) -> getString(R.string.malsync_disabled_home)
                    malMode == "anime" -> getString(R.string.malsync_anime_only_home)
                    else -> getString(R.string.no_unread_chapters)
                }
            }
        }

        model.getUnreadChapters().observe(viewLifecycleOwner) { unreadList ->
            if (unreadList == null) return@observe
            // Home data decides what counts as still being read, so the row can't be composed
            // before it lands. Held rather than dropped: LiveData does not redeliver, so letting
            // this one go meant the AniList half never appeared at all for the rest of the session.
            if (!model.loaded) {
                pendingUnread = unreadList
                return@observe
            }
            applyUnreadList(unreadList)
        }

        // Combined Continue Reading (Anilist + MU Reading)
        binding.homeContinueReadingContainer.visibility = View.VISIBLE
        binding.homeReadingProgressBar.visibility = View.VISIBLE
        binding.homeReadingRecyclerView.visibility = View.GONE
        binding.homeReadingEmpty.visibility = View.GONE
        binding.homeContinueRead.visibility = View.VISIBLE
        binding.homeContinueReadMore.visibility = View.INVISIBLE

        var mangaContinueData: ArrayList<Media>? = null
        var muHomeListsData: Map<String, List<ani.dantotsu.connections.mangaupdates.MUMedia>>? = null

        fun renderContinueReading() {
            // Return only if neither source has loaded yet
            if (mangaContinueData == null && muHomeListsData == null) return
            val aniItems: List<Media> = mangaContinueData ?: emptyList()
            val muItems = muHomeListsData?.get("Reading") ?: emptyList()
            binding.homeReadingRecyclerView.visibility = View.GONE
            binding.homeReadingEmpty.visibility = View.GONE
            if (aniItems.isNotEmpty() || muItems.isNotEmpty()) {
                val combined: List<Any> =
                    (aniItems.map { it to (it.userUpdatedAt ?: 0L) } +
                     muItems.map { it to (it.updatedAt ?: 0L) })
                        .sortedByDescending { (_, ts) -> ts }
                        .map { (item, _) -> item }
                binding.homeReadingRecyclerView.adapter = MergedReadingAdapter(combined)
                binding.homeReadingRecyclerView.layoutManager = LinearLayoutManager(
                    requireContext(), LinearLayoutManager.HORIZONTAL, false
                )
                binding.homeContinueReadMore.setOnClickListener { i ->
                    MediaListViewActivity.passedMedia = ArrayList(aniItems)
                    MediaListViewActivity.passedMuMedia = ArrayList(muItems)
                    ContextCompat.startActivity(
                        i.context, Intent(i.context, MediaListViewActivity::class.java)
                            .putExtra("title", getString(R.string.continue_reading)), null
                    )
                }
                binding.homeReadingRecyclerView.visibility = View.VISIBLE
                binding.homeReadingRecyclerView.layoutAnimation =
                    LayoutAnimationController(setSlideIn(), 0.25f)
            } else {
                binding.homeReadingEmpty.visibility = View.VISIBLE
            }
            binding.homeContinueReadMore.visibility = View.VISIBLE
            binding.homeContinueRead.visibility = View.VISIBLE
            binding.homeContinueReadMore.startAnimation(setSlideUp())
            binding.homeContinueRead.startAnimation(setSlideUp())
            binding.homeReadingProgressBar.visibility = View.GONE
        }

        model.getMangaContinue().observe(viewLifecycleOwner) {
            mangaContinueData = it
            renderContinueReading()
        }
        binding.homeReadingBrowseButton.setOnClickListener {
            bottomBar.selectTabAt(2)
        }

        initRecyclerView(
            model.getMangaFav(),
            binding.homeFavMangaContainer,
            binding.homeFavMangaRecyclerView,
            binding.homeFavMangaProgressBar,
            binding.homeFavMangaEmpty,
            binding.homeFavManga,
            binding.homeFavMangaMore,
            getString(R.string.fav_manga)
        )

        // Combined Planned Manga (Anilist + MU Planning)
        binding.homePlannedMangaContainer.visibility = View.VISIBLE
        binding.homePlannedMangaProgressBar.visibility = View.VISIBLE
        binding.homePlannedMangaRecyclerView.visibility = View.GONE
        binding.homePlannedMangaEmpty.visibility = View.GONE
        binding.homePlannedManga.visibility = View.VISIBLE
        binding.homePlannedMangaMore.visibility = View.INVISIBLE

        var mangaPlannedData: ArrayList<Media>? = null

        fun renderPlannedManga() {
            // Return only if neither source has loaded yet
            if (mangaPlannedData == null && muHomeListsData == null) return
            val aniItems: List<Media> = mangaPlannedData ?: emptyList()
            val muItems = muHomeListsData?.get("Planning") ?: emptyList()
            binding.homePlannedMangaRecyclerView.visibility = View.GONE
            binding.homePlannedMangaEmpty.visibility = View.GONE
            if (aniItems.isNotEmpty() || muItems.isNotEmpty()) {
                val combined: List<Any> =
                    (aniItems.map { it to (it.userUpdatedAt ?: 0L) } +
                     muItems.map { it to (it.updatedAt ?: 0L) })
                        .sortedByDescending { (_, ts) -> ts }
                        .map { (item, _) -> item }
                binding.homePlannedMangaRecyclerView.adapter = MergedReadingAdapter(combined)
                binding.homePlannedMangaRecyclerView.layoutManager = LinearLayoutManager(
                    requireContext(), LinearLayoutManager.HORIZONTAL, false
                )
                binding.homePlannedMangaMore.setOnClickListener { i ->
                    MediaListViewActivity.passedMedia = ArrayList(aniItems)
                    MediaListViewActivity.passedMuMedia = ArrayList(muItems)
                    ContextCompat.startActivity(
                        i.context, Intent(i.context, MediaListViewActivity::class.java)
                            .putExtra("title", getString(R.string.planned_manga)), null
                    )
                }
                binding.homePlannedMangaRecyclerView.visibility = View.VISIBLE
                binding.homePlannedMangaRecyclerView.layoutAnimation =
                    LayoutAnimationController(setSlideIn(), 0.25f)
            } else {
                binding.homePlannedMangaEmpty.visibility = View.VISIBLE
            }
            binding.homePlannedMangaMore.visibility = View.VISIBLE
            binding.homePlannedManga.visibility = View.VISIBLE
            binding.homePlannedMangaMore.startAnimation(setSlideUp())
            binding.homePlannedManga.startAnimation(setSlideUp())
            binding.homePlannedMangaProgressBar.visibility = View.GONE
        }

        model.getMangaPlanned().observe(viewLifecycleOwner) {
            mangaPlannedData = it
            renderPlannedManga()
        }
        model.getMuHomeLists().observe(viewLifecycleOwner) {
            muHomeListsData = it
            renderContinueReading()
            renderPlannedManga()
            // Redraw the unread row with the latest MU items. It reads the AniList half from the
            // fragment rather than carrying its own, which is what stops this call wiping it.
            renderUnreadRow(animate = false)
        }
        binding.homePlannedMangaBrowseButton.setOnClickListener {
            bottomBar.selectTabAt(2)
        }

        initRecyclerView(
            model.getRecommendation(),
            binding.homeRecommendedContainer,
            binding.homeRecommendedRecyclerView,
            binding.homeRecommendedProgressBar,
            binding.homeRecommendedEmpty,
            binding.homeRecommended,
            binding.homeRecommendedMore,
            getString(R.string.recommended)
        )
        binding.homeUserStatusContainer.visibility = View.VISIBLE
        binding.homeUserStatusProgressBar.visibility = View.VISIBLE
        binding.homeUserStatusRecyclerView.visibility = View.GONE
        model.getUserStatus().observe(viewLifecycleOwner) {
            binding.homeUserStatusRecyclerView.visibility = View.GONE
            if (it != null) {
                if (it.isNotEmpty()) {
                    PrefManager.getLiveVal(PrefName.RefreshStatus, false).apply {
                        asLiveBool()
                        observe(viewLifecycleOwner) { _ ->
                            binding.homeUserStatusRecyclerView.adapter = UserStatusAdapter(it)
                        }
                    }
                    binding.homeUserStatusRecyclerView.layoutManager = LinearLayoutManager(
                        requireContext(),
                        LinearLayoutManager.HORIZONTAL,
                        false
                    )
                    binding.homeUserStatusRecyclerView.visibility = View.VISIBLE
                    binding.homeUserStatusRecyclerView.layoutAnimation =
                        LayoutAnimationController(setSlideIn(), 0.25f)

                } else {
                    binding.homeUserStatusContainer.visibility = View.GONE
                }
                binding.homeUserStatusProgressBar.visibility = View.GONE
            }

        }
        binding.homeHiddenItemsContainer.visibility = View.GONE
        model.getHidden().observe(viewLifecycleOwner) {
            if (it != null) {
                if (it.isNotEmpty()) {
                    binding.homeHiddenItemsRecyclerView.adapter =
                        MediaAdaptor(0, it, requireActivity())
                    binding.homeHiddenItemsRecyclerView.layoutManager = LinearLayoutManager(
                        requireContext(),
                        LinearLayoutManager.HORIZONTAL,
                        false
                    )
                    binding.homeContinueWatch.setOnLongClickListener {
                        binding.homeHiddenItemsContainer.visibility = View.VISIBLE
                        binding.homeHiddenItemsRecyclerView.layoutAnimation =
                            LayoutAnimationController(setSlideIn(), 0.25f)
                        true
                    }
                    binding.homeHiddenItemsMore.setSafeOnClickListener { _ ->
                        MediaListViewActivity.passedMedia = it
                        ContextCompat.startActivity(
                            requireActivity(),
                            Intent(requireActivity(), MediaListViewActivity::class.java)
                                .putExtra("title", getString(R.string.hidden)),
                            null
                        )
                    }
                    binding.homeHiddenItemsTitle.setOnLongClickListener {
                        binding.homeHiddenItemsContainer.visibility = View.GONE
                        true
                    }
                } else {
                    binding.homeContinueWatch.setOnLongClickListener {
                        snackString(getString(R.string.no_hidden_items))
                        true
                    }
                }
            } else {
                binding.homeContinueWatch.setOnLongClickListener {
                    snackString(getString(R.string.no_hidden_items))
                    true
                }
            }
        }

        binding.homeUserAvatarContainer.startAnimation(setSlideUp())

        model.empty.observe(viewLifecycleOwner)
        {
            binding.homeDantotsuContainer.visibility = if (it == true) View.VISIBLE else View.GONE
            (binding.homeDantotsuIcon.drawable as Animatable).start()
            binding.homeDantotsuContainer.startAnimation(setSlideUp())
            binding.homeDantotsuIcon.setSafeOnClickListener {
                (binding.homeDantotsuIcon.drawable as Animatable).start()
            }
        }


        val array = arrayOf(
            "AnimeContinue",
            "AnimeFav",
            "AnimePlanned",
            "UnreadChapters",
            "MangaContinue",
            "MangaFav",
            "MangaPlanned",
            "Recommendation",
            "UserStatus",
        )

        val containers = arrayOf(
            binding.homeContinueWatchingContainer,
            binding.homeFavAnimeContainer,
            binding.homePlannedAnimeContainer,
            binding.homeUnreadChaptersContainer,
            binding.homeContinueReadingContainer,
            binding.homeFavMangaContainer,
            binding.homePlannedMangaContainer,
            binding.homeRecommendedContainer,
            binding.homeUserStatusContainer,
        )

        // Apply saved section visibility immediately so hidden sections stay hidden
        // even when this view is recreated (e.g. on rotation) without a fresh network
        // refresh triggering the same logic further below.
        try {
            val homeLayoutShow: List<Boolean> = PrefManager.getVal(PrefName.HomeLayout)
            homeLayoutShow.indices.forEach { i ->
                if (!homeLayoutShow.elementAt(i)) {
                    containers[i].visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            // Fail silently if pref malformed
        }

        // Reorder container views according to saved HomeLayoutOrder preference
        try {
            val savedOrder = PrefManager.getVal<List<Int>>(PrefName.HomeLayoutOrder)
            if (!savedOrder.isNullOrEmpty() && savedOrder.size == containers.size) {
                val parent = binding.homeContainer as ViewGroup
                val firstIndex = parent.indexOfChild(containers[0]).let { if (it >= 0) it else 0 }
                var insertIndex = firstIndex
                for (idx in savedOrder) {
                    val v = containers[idx]
                    parent.removeView(v)
                    parent.addView(v, insertIndex)
                    insertIndex++
                }
            }
        } catch (e: Exception) {
            // Fail silently if pref malformed or views not attached yet
        }

        // Refresh unread UI from cached unread list without performing MALSync network check
        // This is implemented as a class-level method below so it can be triggered by broadcasts.

        var running = false
        val live = Refresh.activity.getOrPut(1) { MutableLiveData(true) }
        live.observe(viewLifecycleOwner) { shouldRefresh ->
            if (!running && shouldRefresh) {
                running = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        // Get user data first
                        Anilist.userid =
                            PrefManager.getNullableVal<String>(PrefName.AnilistUserId, null)
                                ?.toIntOrNull()
                        if (Anilist.userid == null) {
                            withContext(Dispatchers.Main) {
                                getUserId(requireContext()) {
                                    load()
                                }
                            }
                        } else {
                            getUserId(requireContext()) {
                                load()
                            }
                        }
                        model.loaded = true
                        model.setListImages()
                    }

                    var empty = true
                    val homeLayoutShow: List<Boolean> = PrefManager.getVal(PrefName.HomeLayout)


    
                    withContext(Dispatchers.Main) {
                        homeLayoutShow.indices.forEach { i ->
                            if (homeLayoutShow.elementAt(i)) {
                                empty = false
                            } else {
                                containers[i].visibility = View.GONE
                            }
                        }
                    }

                    val initHomePage = async(Dispatchers.IO) { model.initHomePage() }
                    val initUserStatus = async(Dispatchers.IO) { model.initUserStatus() }
                    val initMuHomeLists = async(Dispatchers.IO) { model.initMuHomeLists() }
                    awaitAll(initHomePage, initUserStatus, initMuHomeLists)

                    // After home data is refreshed, update the unread display using cached results
                    withContext(Dispatchers.Main) {
                        refreshUnreadFromCache()
                        // An unread list that arrived while this was still loading was held back
                        // rather than dropped; now that the row can be composed, act on it.
                        pendingUnread?.let {
                            pendingUnread = null
                            applyUnreadList(it)
                        }
                    }

                    // Do not auto-run unread chapters check here; user can trigger manually

                    withContext(Dispatchers.Main) {
                        model.empty.postValue(empty)
                        binding.homeHiddenItemsContainer.visibility = View.GONE
                    }

                    live.postValue(false)
                    _binding?.homeRefresh?.isRefreshing = false
                    running = false
                }
            }
        }

        
    }

    override fun onResume() {
        if (!model.loaded) Refresh.activity[1]!!.postValue(true)
        if (_binding != null) {
            binding.homeNotificationCount.isVisible = Anilist.unreadNotificationCount > 0
                    && PrefManager.getVal<Boolean>(PrefName.ShowNotificationRedDot) == true
            binding.homeNotificationCount.text = Anilist.unreadNotificationCount.toString()
            updateUnreadRefreshButtonState()
        }
        super.onResume()
    }

    override fun onStart() {
        super.onStart()
        try {
            requireContext().registerReceiver(unreadCacheReceiver, IntentFilter(ani.dantotsu.notifications.unread.UnreadCache.ACTION_CACHE_UPDATED))
        } catch (e: Exception) {
            ani.dantotsu.util.Logger.log("HomeFragment.onStart registerReceiver error: ${e.message}")
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            requireContext().unregisterReceiver(unreadCacheReceiver)
        } catch (e: Exception) {
            ani.dantotsu.util.Logger.log("HomeFragment.onStop unregisterReceiver error: ${e.message}")
        }
    }

    // Class-level implementation of cached refresh so external triggers can call it
    private fun refreshUnreadFromCache() {
        try {
            // Read the persisted list directly rather than model.getUnreadChapters().value: the
            // background UnreadChapterNotificationTask writes fresh results straight to
            // PrefManager and broadcasts ACTION_CACHE_UPDATED without touching the ViewModel's
            // in-memory LiveData, so the LiveData value can be stale (e.g. still whatever was
            // loaded at process start) and would silently drop newly-found unread manga here.
            @Suppress("UNCHECKED_CAST")
            val cached = try {
                ani.dantotsu.settings.saving.PrefManager.getNullableCustomVal(
                    "cached_unread_chapters",
                    null,
                    ArrayList::class.java
                ) as? ArrayList<Media>
            } catch (e: Exception) {
                null
            }
            val currentManga = model.getMangaContinue().value
            if (cached == null) return

            // Refresh each cached entry's progress from the live "continue reading" list; one
            // that has dropped off it has been finished or removed, so it goes too.
            //
            // Only once that list exists, though. It starts null and is filled by initHomePage,
            // while this runs on a broadcast from the background unread check that can land at any
            // time — and treating "not in a list that hasn't loaded" as "caught up" discarded every
            // AniList entry, leaving a row that showed the MangaUpdates ones and nothing else.
            val filtered = if (currentManga == null) cached else cached.mapNotNull { cachedMedia ->
                currentManga.firstOrNull { it.id == cachedMedia.id }?.let { updated ->
                    // If user progress changed, use the newer progress value
                    cachedMedia.apply { userProgress = updated.userProgress }
                }
            }

            val merged = mergedCachedInfoFor(currentManga)
            val excludeList = ani.dantotsu.settings.saving.PrefManager.getVal<Set<String>>(
                ani.dantotsu.settings.saving.PrefName.MalSyncExcludeList
            )
            // Further filter cached results to remove items the user has already caught up to or excluded
            unreadAniList = filtered.filter { media ->
                if (excludeList.containsMediaId(media.id.toString())) return@filter false
                val last = getLastChapterForMedia(media, merged)
                val progress = merged[media.id]?.userProgress ?: media.userProgress ?: 0
                last != null && last > progress
            }
            unreadInfoMap = merged
            unreadAniListSettled = true
            renderUnreadRow()
        } catch (e: Exception) {
            ani.dantotsu.util.Logger.log("refreshUnreadFromCache error: ${e.message}")
        }
    }
}