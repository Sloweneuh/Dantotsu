package ani.dantotsu.media

import ani.dantotsu.util.useSharedAxisTransitions
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.databinding.FragmentMediaInfoContainerBinding
import ani.dantotsu.isOnline
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.PagerTabsMediator

class MediaInfoFragment : Fragment() {
    private var _binding: FragmentMediaInfoContainerBinding? = null
    private val binding
        get() = _binding!!

    private data class TabInfo(val entry: InfoTabEntry) {
        val iconRes get() = entry.iconRes
        val type get() = (entry as? InfoTabEntry.Builtin)?.type
    }
    private var tabs: List<TabInfo> = emptyList()

    /** [tabs] with the custom tabs that turned out to have nothing for this title too. */
    private var allTabs: List<TabInfo> = emptyList()

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaInfoContainerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        pagerAdapter = null
    }

    private var isSetup = false
    private var currentMedia: Media? = null
    private var pagerAdapter: InfoTabsPagerAdapter? = null
    private var builtFrom: String? = null

    /** The tabs as the settings have them now, offline leaving only what can still show. */
    private fun buildTabs(media: Media) {
        // Build list of tabs according to the user's saved order/visibility for this
        // media context (falls back to fetch-enabled connections only)
        val tabContext = InfoTabContext.forAniList(media)
        // Offline, the external source tabs (MAL/Comick/MangaUpdates/MangaBaka) can't fetch
        // anything, so show only the AniList tab, whose info is stored with the download.
        val offline = PrefManager.getVal<Boolean>(PrefName.OfflineMode) ||
                !isOnline(requireContext())
        fun isAniList(entry: InfoTabEntry) =
            (entry as? InfoTabEntry.Builtin)?.type == InfoTabType.ANILIST
        val orderedTabs = tabContext.visibleEntries().let { ordered ->
            if (offline) ordered.filter { isAniList(it) }
                .ifEmpty { tabContext.orderedEntries().filter { isAniList(it) } }
            else ordered
        }
        allTabs = orderedTabs.map { TabInfo(it) }
        tabs = allTabs
        builtFrom = tabContext.layoutSignature()
        if (InfoTabStore.preview == null) InfoTabPreview.remember(tabContext, media.id.toLong())
    }

    /** If only one tab is left, the TabLayout goes and that tab's info shows directly. */
    private fun showTabBar() {
        binding.mediaInfoTabLayout.visibility = if (tabs.size <= 1) View.GONE else View.VISIBLE
    }

    /** Catches up with tabs edited in the settings, opened from this very page or anywhere else. */
    override fun onResume() {
        super.onResume()
        val media = currentMedia ?: return
        val adapter = pagerAdapter ?: return
        if (InfoTabContext.forAniList(media).layoutSignature() == builtFrom) return
        val model: MediaDetailsViewModel by activityViewModels()
        val shown = tabs.getOrNull(binding.mediaInfoViewPager.currentItem)?.entry?.key
        buildTabs(media)
        adapter.rebuild()
        showTabBar()
        binding.mediaInfoViewPager.setCurrentItem(
            tabs.indexOfFirst { it.entry.key == shown }.coerceAtLeast(0), false
        )
        updateTabStates(model, media)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val model: MediaDetailsViewModel by activityViewModels()

        model.getMedia().observe(viewLifecycleOwner) { media ->
            currentMedia = media
            if (media != null && !isSetup) {
                isSetup = true

                // Preload Comick and MangaUpdates data in the background for manga
                // This allows tab states to update immediately even with offscreenPageLimit = 1
                model.preloadExternalData(media)

                // Setup ViewPager - show tabs depending on media type and enabled connections
                val isAnime = media.anime != null

                buildTabs(media)
                childFragmentManager.applyInfoTabRules(InfoTabContext.forAniList(media))

                val tabContext = InfoTabContext.forAniList(media)
                val adapter = InfoTabsPagerAdapter(
                    this, { tabs.size }, { tabs[it].entry.key },
                    { tabContext.createTabFragment(tabs[it].entry) },
                )
                pagerAdapter = adapter
                binding.mediaInfoViewPager.adapter = adapter
                showTabBar()

                // Only load adjacent tabs to reduce API calls and prevent rate limiting
                binding.mediaInfoViewPager.offscreenPageLimit = 1

                // Disable swipe gestures
                binding.mediaInfoViewPager.isUserInputEnabled = false

                // Before the tabs are set up, which already leaves out those with nothing to show.
                if (savedInstanceState == null) {
                    val start = tabs.indexOfFirst { it.entry.key == tabContext.initialTabKey(this) }
                    if (start > 0) binding.mediaInfoViewPager.setCurrentItem(start, false)
                }

                setupTabs(model, media)
            }
        }

        // Observe data availability changes. Comick now backs an anime tab too, so unlike the
        // MangaUpdates/MangaBaka observers below this one must not skip anime.
        model.comickSlug.observe(viewLifecycleOwner) {
            currentMedia?.let { media -> updateTabStates(model, media) }
        }
        model.comickLoaded.observe(viewLifecycleOwner) {
            currentMedia?.let { media -> updateTabStates(model, media) }
        }

        model.mangaUpdatesLink.observe(viewLifecycleOwner) {
            currentMedia?.let { media ->
                if (media.anime == null) {
                    updateTabStates(model, media)
                }
            }
        }

        model.mangaUpdatesLoading.observe(viewLifecycleOwner) {
            currentMedia?.let { media ->
                if (media.anime == null) {
                    updateTabStates(model, media)
                }
            }
        }

        model.mangaUpdatesLoaded.observe(viewLifecycleOwner) {
            currentMedia?.let { media ->
                if (media.anime == null) updateTabStates(model, media)
            }
        }

        model.mangaBakaId.observe(viewLifecycleOwner) {
            currentMedia?.let { media ->
                if (media.anime == null) updateTabStates(model, media)
            }
        }

        model.mangaBakaLoaded.observe(viewLifecycleOwner) {
            currentMedia?.let { media ->
                if (media.anime == null) updateTabStates(model, media)
            }
        }

        model.kitsuLoaded.observe(viewLifecycleOwner) {
            currentMedia?.let { media -> updateTabStates(model, media) }
        }
        model.kitsuId.observe(viewLifecycleOwner) {
            currentMedia?.let { media -> updateTabStates(model, media) }
        }
        model.simklLoaded.observe(viewLifecycleOwner) {
            currentMedia?.let { media -> updateTabStates(model, media) }
        }
        model.simklId.observe(viewLifecycleOwner) {
            currentMedia?.let { media -> updateTabStates(model, media) }
        }
    }

    private fun setupTabs(model: MediaDetailsViewModel, media: Media) {
        // Setup TabLayout - all tabs long-clickable
        binding.mediaInfoViewPager.useSharedAxisTransitions()
        PagerTabsMediator(binding.mediaInfoTabLayout, binding.mediaInfoViewPager) { tab, position ->
            val iconRes = (binding.mediaInfoViewPager.adapter as? androidx.viewpager2.adapter.FragmentStateAdapter)?.let {
                // tabs list captured above through closure
                // Use tabs built earlier; safe to cast adapter as our anonymous adapter
                (tabs.getOrNull(position)?.iconRes) ?: ani.dantotsu.R.drawable.ic_anilist
            } ?: ani.dantotsu.R.drawable.ic_anilist

            tab.setIcon(iconRes)
            // Set long-click listeners for opening URLs
            tab.view.setOnLongClickListener { handleTabLongClick(position, model, media) }
        }.attach()

        // Re-apply tab states when selection changes to prevent default behavior overriding custom
        // alpha
        binding.mediaInfoTabLayout.addOnTabSelectedListener(
                object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(
                            tab: com.google.android.material.tabs.TabLayout.Tab?
                    ) {
                        updateTabStates(model, media)
                    }
                    override fun onTabUnselected(
                            tab: com.google.android.material.tabs.TabLayout.Tab?
                    ) {
                        updateTabStates(model, media)
                    }
                    override fun onTabReselected(
                            tab: com.google.android.material.tabs.TabLayout.Tab?
                    ) {
                        updateTabStates(model, media)
                    }
                }
        )

        updateTabStates(model, media)
    }

    private fun handleTabLongClick(
            position: Int,
            model: MediaDetailsViewModel,
            media: Media
    ): Boolean {
        val isAnime = media.anime != null
        val info = tabs.getOrNull(position) ?: return false
        val custom = (info.entry as? InfoTabEntry.Custom)?.tab
        if (custom != null && custom.longPressSource == null) {
            return runCustomTabLongPress(InfoTabContext.forAniList(media), custom)
        }
        val type = custom?.longPressSource ?: info.type ?: return false

        val url = when (type) {
            InfoTabType.ANILIST -> "https://anilist.co/${if (isAnime) "anime" else "manga"}/${media.id}"
            InfoTabType.MAL -> {
                val mediaType = if (isAnime) "anime" else "manga"
                if (media.idMAL != null) {
                    "https://myanimelist.net/$mediaType/${media.idMAL}"
                } else {
                    "https://myanimelist.net/$mediaType.php?q=${java.net.URLEncoder.encode(media.userPreferredName, "utf-8")}&cat=$mediaType"
                }
            }
            InfoTabType.COMICK -> {
                val comickSlug = model.comickSlug.value
                if (comickSlug != null) {
                    // Anime entries live under /anime/, not /comic/ — the latter 404s for them.
                    ani.dantotsu.connections.comick.ComickApi.webUrl(
                        comickSlug,
                        if (isAnime) ani.dantotsu.connections.comick.ComickApi.MEDIA_TYPE_ANIME
                        else ani.dantotsu.connections.comick.ComickApi.MEDIA_TYPE_MANGA
                    )
                } else {
                    "https://comick.dev/search?q=${java.net.URLEncoder.encode(media.userPreferredName, "utf-8").replace("+", "%20") }"
                }
            }
            InfoTabType.MANGAUPDATES -> {
                val muLink = model.mangaUpdatesLink.value
                if (muLink != null) muLink
                else {
                    val encoded = java.net.URLEncoder.encode(media.userPreferredName, "utf-8").replace("+", "%20")
                    "https://www.mangaupdates.com/series?search=$encoded"
                }
            }
            InfoTabType.MANGABAKA -> {
                val id = model.mangaBakaId.value
                if (id != null && id > 0) "https://mangabaka.org/$id"
                else {
                    val encoded = java.net.URLEncoder.encode(media.userPreferredName, "utf-8").replace("+", "%20")
                    "https://mangabaka.org/search?q=$encoded"
                }
            }
            InfoTabType.KITSU -> {
                val id = model.kitsuId.value
                val kind = if (isAnime) "anime" else "manga"
                if (!id.isNullOrBlank()) "https://kitsu.io/$kind/$id"
                else "https://kitsu.io/search?query=${java.net.URLEncoder.encode(media.userPreferredName, "utf-8")}"
            }
            InfoTabType.SIMKL -> {
                val id = model.simklId.value
                if (id != null && id > 0) "https://simkl.com/anime/$id"
                else "https://simkl.com/search/?type=anime&q=${java.net.URLEncoder.encode(media.userPreferredName, "utf-8")}"
            }
        }

        // The browser, explicitly: a bare ACTION_VIEW would be caught by the app's own deep-link
        // filters and land right back on the page the user is already on.
        openLinkInBrowser(url)
        return true
    }

    /** How far [type] got with this title. The MangaUpdates/MangaBaka branches never apply to anime, which has no such tabs. */
    private fun sourceAlpha(type: InfoTabType, model: MediaDetailsViewModel, media: Media): Float = when (type) {
        InfoTabType.ANILIST -> InfoTabAlpha.READY
        InfoTabType.MAL -> if (media.idMAL != null) InfoTabAlpha.READY else InfoTabAlpha.EMPTY
        InfoTabType.COMICK -> when {
            model.comickSlug.value != null -> InfoTabAlpha.READY
            model.comickLoaded.value == true -> InfoTabAlpha.EMPTY
            else -> InfoTabAlpha.LOADING
        }
        InfoTabType.MANGAUPDATES -> when {
            model.mangaUpdatesLink.value != null -> InfoTabAlpha.READY
            // Not just "not loading": the lookup only starts after Comick's search has been set off.
            model.mangaUpdatesLoaded.value == true && model.mangaUpdatesLoading.value != true -> InfoTabAlpha.EMPTY
            else -> InfoTabAlpha.LOADING
        }
        InfoTabType.MANGABAKA -> when {
            (model.mangaBakaId.value ?: 0L) > 0L -> InfoTabAlpha.READY
            model.mangaBakaLoaded.value == true -> InfoTabAlpha.EMPTY
            else -> InfoTabAlpha.LOADING
        }
        InfoTabType.KITSU -> when {
            !model.kitsuId.value.isNullOrBlank() -> InfoTabAlpha.READY
            model.kitsuLoaded.value == true -> InfoTabAlpha.EMPTY
            else -> InfoTabAlpha.LOADING
        }
        InfoTabType.SIMKL -> when {
            (model.simklId.value ?: 0L) > 0L -> InfoTabAlpha.READY
            model.simklLoaded.value == true -> InfoTabAlpha.EMPTY
            else -> InfoTabAlpha.LOADING
        }
    }

    /**
     * Takes out custom tabs all of whose sources came up empty, and puts them back if one of them
     * comes through after all. Never the tab being looked at, which would pull it from under the
     * user: it goes once they've moved on. The others keep their pages.
     */
    private fun hideEmptyTabs(model: MediaDetailsViewModel, media: Media) {
        val adapter = pagerAdapter ?: return
        val context = InfoTabContext.forAniList(media)
        val shown = tabs.getOrNull(binding.mediaInfoViewPager.currentItem)?.entry?.key
        val kept = allTabs.filter { info ->
            val custom = (info.entry as? InfoTabEntry.Custom)?.tab ?: return@filter true
            info.entry.key == shown || !context.customTabIsEmpty(custom) { sourceAlpha(it, model, media) }
        }
        if (kept.map { it.entry.key } == tabs.map { it.entry.key }) return
        tabs = kept
        adapter.removed()
        showTabBar()
        binding.mediaInfoViewPager.setCurrentItem(
            tabs.indexOfFirst { it.entry.key == shown }.coerceAtLeast(0), false
        )
    }

    private fun updateTabStates(model: MediaDetailsViewModel, media: Media) {
        hideEmptyTabs(model, media)
        val context = InfoTabContext.forAniList(media)
        // Alpha states follow the display order.
        for (i in 0 until binding.mediaInfoTabLayout.tabCount) {
            val tab = binding.mediaInfoTabLayout.getTabAt(i) ?: continue
            val entry = tabs.getOrNull(i)?.entry
            tab.view.alpha = when (entry) {
                is InfoTabEntry.Custom -> context.customTabAlpha(entry.tab) { sourceAlpha(it, model, media) }
                is InfoTabEntry.Builtin -> sourceAlpha(entry.type, model, media)
                null -> InfoTabAlpha.EMPTY
            }
        }
    }

    private class InfoPagerAdapter(
            fragmentManager: FragmentManager,
            lifecycle: Lifecycle,
            private val isAnime: Boolean
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int = if (isAnime) 2 else 4

        override fun createFragment(position: Int): Fragment =
                when (position) {
                    0 -> AniListInfoFragment()
                    1 -> MALInfoFragment()
                    2 -> ComickInfoFragment()
                    3 -> MangaUpdatesInfoFragment()
                    else -> AniListInfoFragment()
                }
    }
}
