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
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.setSlideIn
import ani.dantotsu.setSlideUp
import ani.dantotsu.settings.SettingsDialogFragment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefManager.asLiveBool
import ani.dantotsu.settings.saving.PrefName
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
                if (result1 != null) {
                    binding.homeUserStat1Row.visibility = View.VISIBLE
                    binding.homeUserStat1Label.text = result1.first
                    binding.homeUserStat1Value.text = result1.second
                } else {
                    binding.homeUserStat1Row.visibility = View.GONE
                }
                if (result2 != null) {
                    binding.homeUserStat2Row.visibility = View.VISIBLE
                    binding.homeUserStat2Label.text = result2.first
                    binding.homeUserStat2Value.text = result2.second
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

        // Shared state so both the unread observer and the MU-lists observer can re-render together
        var lastUnreadAniList: List<Media> = emptyList()
        var lastUnreadInfoMap: Map<Int, UnreadChapterInfo> = emptyMap()

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
            // Don't render unread cached results until home data has finished loading
            if (!model.loaded) {
                ani.dantotsu.util.Logger.log("HomeFragment: skipping unread observer until home data loaded")
                return@observe
            }
            binding.homeUnreadChaptersRecyclerView.visibility = View.GONE
            binding.homeUnreadChaptersEmpty.visibility = View.GONE
            if (unreadList != null) {
                if (unreadList.isNotEmpty()) {
                    // Fetch MalSync data using batch endpoint (much faster)
                    scope.launch {
                        val unreadInfo = mutableMapOf<Int, ani.dantotsu.connections.malsync.UnreadChapterInfo>()

                        withContext(Dispatchers.IO) {
                            // Only perform MalSync batch if preference enabled and check mode allows manga
                            val malMode3 = PrefManager.getVal<String>(PrefName.MalSyncCheckMode) ?: "both"
                            if (!PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled) || malMode3 == "anime") {
                            } else {
                                // Collect pairs of (anilistId, malId) - prefer MAL ID, fallback to AniList ID
                                val mediaIds = unreadList.map { media ->
                                    Pair(media.id, media.idMAL)
                                }
                                val batchResults = ani.dantotsu.connections.malsync.MalSyncApi.getBatchProgressByMedia(mediaIds)

                                // Map results back to media IDs
                                for (media in unreadList) {
                                    val result = batchResults[media.id]
                                    if (result != null && result.lastEp != null) {
                                        unreadInfo[media.id] = ani.dantotsu.connections.malsync.UnreadChapterInfo(
                                            mediaId = media.id,
                                            lastChapter = result.lastEp.total,
                                            source = result.source,
                                            userProgress = media.userProgress ?: 0
                                        )
                                    }
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            val muUnread = model.getMuHomeLists().value?.get("Reading")
                                ?.filter { it.latestChapter != null && it.latestChapter > (it.userChapter ?: 0) }
                                ?.sortedBy { (it.latestChapter ?: 0) - (it.userChapter ?: 0) }
                                ?: emptyList()

                            if (unreadInfo.isNotEmpty()) {
                                // Sort by unread chapters (least unread first)
                                val sortedList = unreadList.sortedBy { media ->
                                    val info = unreadInfo[media.id]
                                    if (info != null) {
                                        info.lastChapter - info.userProgress // Calculate unread count
                                    } else {
                                        Int.MAX_VALUE // Put items without info at the end
                                    }
                                }
                                lastUnreadAniList = sortedList
                                lastUnreadInfoMap = unreadInfo
                                val combined: List<Any> = sortedList + muUnread

                                // Show live MALSync results (do not filter — fresh data may include new unread chapters)
                                binding.homeUnreadChaptersRecyclerView.adapter =
                                    UnreadChaptersAdapter(combined, unreadInfo)
                                binding.homeUnreadChaptersRecyclerView.layoutManager = LinearLayoutManager(
                                    requireContext(),
                                    LinearLayoutManager.HORIZONTAL,
                                    false
                                )
                                binding.homeUnreadChaptersMore.setOnClickListener { i ->
                                    MediaListViewActivity.passedMedia = ArrayList(sortedList)
                                    MediaListViewActivity.passedUnreadInfo = unreadInfo
                                    ContextCompat.startActivity(
                                        i.context, Intent(i.context, MediaListViewActivity::class.java)
                                            .putExtra("title", getString(R.string.unread_chapters)),
                                        null
                                    )
                                }
                                binding.homeUnreadChaptersRecyclerView.visibility = View.VISIBLE
                                binding.homeUnreadChaptersRecyclerView.layoutAnimation =
                                    LayoutAnimationController(setSlideIn(), 0.25f)
                                binding.homeUnreadChaptersRecyclerView.scheduleLayoutAnimation()
                                // Persist MALSync unread info so cached UI can show source/lastEp on next load
                                try {
                                    ani.dantotsu.settings.saving.PrefManager.setCustomVal("cached_unread_info", unreadInfo)
                                } catch (e: Exception) {
                                    ani.dantotsu.util.Logger.log("Failed to cache unread info: ${e.message}")
                                }
                            } else {
                                // No fresh MALSync data. Prefer cached unreadInfo when available.
                                val merged = mergedCachedInfoFor(model.getMangaContinue().value)
                                if (merged.isNotEmpty()) {
                                        // Filter cached results using merged info
                                        val filteredCached = unreadList.filter { media ->
                                            val last = getLastChapterForMedia(media, merged)
                                            val progress = merged[media.id]?.userProgress ?: media.userProgress ?: 0
                                            last != null && last > progress
                                        }
                                        lastUnreadAniList = filteredCached
                                        lastUnreadInfoMap = merged
                                        val combined: List<Any> = filteredCached + muUnread
                                        binding.homeUnreadChaptersRecyclerView.adapter =
                                            UnreadChaptersAdapter(combined, merged)
                                        binding.homeUnreadChaptersMore.setOnClickListener { i ->
                                            MediaListViewActivity.passedMedia = ArrayList(filteredCached)
                                            MediaListViewActivity.passedUnreadInfo = merged
                                            ContextCompat.startActivity(
                                                i.context, Intent(i.context, MediaListViewActivity::class.java)
                                                    .putExtra("title", getString(R.string.unread_chapters)),
                                                null
                                            )
                                        }
                                        binding.homeUnreadChaptersRecyclerView.layoutManager = LinearLayoutManager(
                                            requireContext(),
                                            LinearLayoutManager.HORIZONTAL,
                                            false
                                        )
                                        binding.homeUnreadChaptersRecyclerView.visibility = View.VISIBLE
                                        binding.homeUnreadChaptersEmpty.visibility = View.GONE
                                        binding.homeUnreadChaptersRecyclerView.layoutAnimation =
                                            LayoutAnimationController(setSlideIn(), 0.25f)
                                        binding.homeUnreadChaptersRecyclerView.post {
                                            binding.homeUnreadChaptersRecyclerView.scheduleLayoutAnimation()
                                        }
                                } else if (muUnread.isNotEmpty()) {
                                    // No MALSync data but MU has unread — show MU-only list
                                    lastUnreadAniList = emptyList()
                                    lastUnreadInfoMap = emptyMap()
                                    binding.homeUnreadChaptersRecyclerView.adapter =
                                        UnreadChaptersAdapter(muUnread, emptyMap())
                                    binding.homeUnreadChaptersRecyclerView.layoutManager = LinearLayoutManager(
                                        requireContext(),
                                        LinearLayoutManager.HORIZONTAL,
                                        false
                                    )
                                    binding.homeUnreadChaptersMore.setOnClickListener {}
                                    binding.homeUnreadChaptersRecyclerView.visibility = View.VISIBLE
                                    binding.homeUnreadChaptersEmpty.visibility = View.GONE
                                    binding.homeUnreadChaptersRecyclerView.layoutAnimation =
                                        LayoutAnimationController(setSlideIn(), 0.25f)
                                } else {
                                    // Show a helpful message when MALSync is disabled
                                    val malModeEmpty = PrefManager.getVal<String>(PrefName.MalSyncCheckMode) ?: "both"
                                    binding.homeUnreadChaptersEmptyText.text = when {
                                        !PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled) -> getString(R.string.malsync_disabled_home)
                                        malModeEmpty == "anime" -> getString(R.string.malsync_anime_only_home)
                                        else -> getString(R.string.no_unread_chapters)
                                    }
                                    binding.homeUnreadChaptersEmpty.visibility = View.VISIBLE
                                }
                            }
                            binding.homeUnreadChaptersMore.visibility = View.VISIBLE
                            updateUnreadRefreshAlignment()
                            binding.homeUnreadChapters.visibility = View.VISIBLE
                            binding.homeUnreadChaptersMore.startAnimation(setSlideUp())
                            binding.homeUnreadChapters.startAnimation(setSlideUp())
                            binding.homeUnreadChaptersProgressBar.visibility = View.GONE
                        }
                    }
                } else {
                    // unreadList is empty — still show MU items if any have unread chapters
                    val muUnread = model.getMuHomeLists().value?.get("Reading")
                        ?.filter { it.latestChapter != null && it.latestChapter > (it.userChapter ?: 0) }
                        ?.sortedBy { (it.latestChapter ?: 0) - (it.userChapter ?: 0) }
                        ?: emptyList()
                    lastUnreadAniList = emptyList()
                    lastUnreadInfoMap = emptyMap()
                    if (muUnread.isNotEmpty()) {
                        binding.homeUnreadChaptersRecyclerView.adapter =
                            UnreadChaptersAdapter(muUnread, emptyMap())
                        binding.homeUnreadChaptersRecyclerView.layoutManager = LinearLayoutManager(
                            requireContext(), LinearLayoutManager.HORIZONTAL, false
                        )
                        binding.homeUnreadChaptersRecyclerView.visibility = View.VISIBLE
                        binding.homeUnreadChaptersRecyclerView.layoutAnimation =
                            LayoutAnimationController(setSlideIn(), 0.25f)
                    } else {
                        binding.homeUnreadChaptersEmpty.visibility = View.VISIBLE
                    }
                    binding.homeUnreadChaptersMore.visibility = View.VISIBLE
                    binding.homeUnreadChapters.visibility = View.VISIBLE
                    binding.homeUnreadChaptersMore.startAnimation(setSlideUp())
                    binding.homeUnreadChapters.startAnimation(setSlideUp())
                    binding.homeUnreadChaptersProgressBar.visibility = View.GONE
                }
            }
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
            // Update the unread chapters section with latest MU items
            val muUnread = it?.get("Reading")
                ?.filter { mu -> mu.latestChapter != null && mu.latestChapter > (mu.userChapter ?: 0) }
                ?.sortedBy { mu -> (mu.latestChapter ?: 0) - (mu.userChapter ?: 0) }
                ?: emptyList()
            val combined: List<Any> = lastUnreadAniList + muUnread
            if (combined.isNotEmpty()) {
                binding.homeUnreadChaptersRecyclerView.adapter =
                    UnreadChaptersAdapter(combined, lastUnreadInfoMap)
                if (binding.homeUnreadChaptersRecyclerView.layoutManager == null) {
                    binding.homeUnreadChaptersRecyclerView.layoutManager = LinearLayoutManager(
                        requireContext(), LinearLayoutManager.HORIZONTAL, false
                    )
                }
                binding.homeUnreadChaptersRecyclerView.visibility = View.VISIBLE
                binding.homeUnreadChaptersEmpty.visibility = View.GONE
            }
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
            val cached = model.getUnreadChapters().value
            val currentManga = model.getMangaContinue().value
            if (cached == null) return

            // Load cached unread info map (malsync results) to preserve source/lastEp
            val cachedUnreadInfo = try {
                @Suppress("UNCHECKED_CAST")
                ani.dantotsu.settings.saving.PrefManager.getNullableCustomVal(
                    "cached_unread_info",
                    null,
                    java.util.HashMap::class.java
                ) as? Map<Int, ani.dantotsu.connections.malsync.UnreadChapterInfo> ?: mapOf()
            } catch (e: Exception) {
                mapOf()
            }

            // Filter cached unread to existing currentManga entries and update userProgress
            val filtered = cached.mapNotNull { cachedMedia: ani.dantotsu.media.Media ->
                val updated = currentManga?.firstOrNull { it.id == cachedMedia.id }
                if (updated != null) {
                    // If user progress changed, use the newer progress value
                    cachedMedia.userProgress = updated.userProgress
                    cachedMedia
                } else null
            }

            if (filtered.isNotEmpty()) {
                val merged = mergedCachedInfoFor(currentManga)
                val excludeList = ani.dantotsu.settings.saving.PrefManager.getCustomVal(
                    "malSyncBatchExcludeList", setOf<Int>()
                )
                // Further filter cached results to remove items the user has already caught up to or excluded
                val filteredCached = filtered.filter { media ->
                    if (media.id in excludeList) return@filter false
                    val last = getLastChapterForMedia(media, merged)
                    val progress = merged[media.id]?.userProgress ?: media.userProgress ?: 0
                    last != null && last > progress
                }
                if (filteredCached.isNotEmpty()) {
                    val muUnread = model.getMuHomeLists().value?.get("Reading")
                        ?.filter { it.latestChapter != null && it.latestChapter > (it.userChapter ?: 0) }
                        ?.sortedBy { (it.latestChapter ?: 0) - (it.userChapter ?: 0) }
                        ?: emptyList()
                    val combined: List<Any> = filteredCached + muUnread
                    binding.homeUnreadChaptersRecyclerView.adapter = UnreadChaptersAdapter(combined, merged)
                    binding.homeUnreadChaptersRecyclerView.layoutManager = LinearLayoutManager(
                        requireContext(), LinearLayoutManager.HORIZONTAL, false
                    )
                    binding.homeUnreadChaptersMore.setOnClickListener { i ->
                        MediaListViewActivity.passedMedia = ArrayList(filteredCached)
                        MediaListViewActivity.passedUnreadInfo = merged
                        ContextCompat.startActivity(
                            i.context, Intent(i.context, MediaListViewActivity::class.java)
                                .putExtra("title", getString(R.string.unread_chapters)),
                            null
                        )
                    }
                    binding.homeUnreadChaptersRecyclerView.visibility = View.VISIBLE
                    binding.homeUnreadChaptersEmpty.visibility = View.GONE
                    binding.homeUnreadChaptersRecyclerView.layoutAnimation = LayoutAnimationController(setSlideIn(), 0.25f)
                    binding.homeUnreadChaptersRecyclerView.post {
                        binding.homeUnreadChaptersRecyclerView.scheduleLayoutAnimation()
                    }
                } else {
                    binding.homeUnreadChaptersRecyclerView.visibility = View.GONE
                    binding.homeUnreadChaptersEmpty.visibility = View.VISIBLE
                }
            } else {
                binding.homeUnreadChaptersRecyclerView.visibility = View.GONE
                binding.homeUnreadChaptersEmpty.visibility = View.VISIBLE
            }
            binding.homeUnreadChaptersProgressBar.visibility = View.GONE
            binding.homeUnreadChaptersMore.visibility = View.VISIBLE
            updateUnreadRefreshAlignment()
        } catch (e: Exception) {
            ani.dantotsu.util.Logger.log("refreshUnreadFromCache error: ${e.message}")
        }
    }
}