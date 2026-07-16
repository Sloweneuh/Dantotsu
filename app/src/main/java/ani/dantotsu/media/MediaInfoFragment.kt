package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
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
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.tabs.TabLayoutMediator

class MediaInfoFragment : Fragment() {
    private var _binding: FragmentMediaInfoContainerBinding? = null
    private val binding
        get() = _binding!!

    private data class TabInfo(val type: String, val fragment: Fragment, val iconRes: Int)
    private var tabs: List<TabInfo> = emptyList()

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
    }

    private var isSetup = false
    private var currentMedia: Media? = null

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

                // Build list of tabs according to the user's saved order/visibility for this
                // media context (falls back to fetch-enabled connections only)
                val tabContext = if (isAnime) InfoTabContext.ANILIST_ANIME else InfoTabContext.ANILIST_MANGA
                // Offline, the external source tabs (MAL/Comick/MangaUpdates/MangaBaka) can't fetch
                // anything, so show only the AniList tab, whose info is stored with the download.
                val offline = PrefManager.getVal<Boolean>(PrefName.OfflineMode) ||
                        !isOnline(requireContext())
                val orderedTabs = tabContext.visibleOrderedTabs().let { ordered ->
                    if (offline) ordered.filter { it == InfoTabType.ANILIST }
                        .ifEmpty { listOf(InfoTabType.ANILIST) }
                    else ordered
                }
                tabs = orderedTabs.map { type ->
                    TabInfo(type.key, createTabFragment(type), type.iconRes)
                }

                val adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(childFragmentManager, viewLifecycleOwner.lifecycle) {
                    override fun getItemCount(): Int = tabs.size
                    override fun createFragment(position: Int): Fragment = tabs[position].fragment
                }

                binding.mediaInfoViewPager.adapter = adapter

                // If only AniList is enabled (single tab), hide the TabLayout and show the
                // AniList info directly without tabs.
                if (tabs.size <= 1) {
                    binding.mediaInfoTabLayout.visibility = android.view.View.GONE
                    // Keep ViewPager visible to host the AniList fragment
                    binding.mediaInfoViewPager.isUserInputEnabled = false
                    // No need to setup tabs
                    return@observe
                } else {
                    binding.mediaInfoTabLayout.visibility = android.view.View.VISIBLE
                }

                // Only load adjacent tabs to reduce API calls and prevent rate limiting
                binding.mediaInfoViewPager.offscreenPageLimit = 1

                // Disable swipe gestures
                binding.mediaInfoViewPager.isUserInputEnabled = false

                setupTabs(model, media)
            }
        }

        // Observe data availability changes (only for manga)
        model.comickSlug.observe(viewLifecycleOwner) {
            currentMedia?.let { media ->
                if (media.anime == null) {
                    updateTabStates(model, media)
                }
            }
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
    }

    private fun setupTabs(model: MediaDetailsViewModel, media: Media) {
        val isAnime = media.anime != null

        // Setup TabLayout - all tabs long-clickable
        TabLayoutMediator(binding.mediaInfoTabLayout, binding.mediaInfoViewPager) { tab, position ->
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

        // Update initial tab states for manga
        if (!isAnime) {
            updateTabStates(model, media)
        }
    }

    private fun createTabFragment(type: InfoTabType): Fragment = when (type) {
        InfoTabType.ANILIST -> AniListInfoFragment()
        InfoTabType.MAL -> MALInfoFragment()
        InfoTabType.COMICK -> ComickInfoFragment()
        InfoTabType.MANGAUPDATES -> MangaUpdatesInfoFragment()
        InfoTabType.MANGABAKA -> MangaBakaInfoFragment()
    }

    private fun handleTabLongClick(
            position: Int,
            model: MediaDetailsViewModel,
            media: Media
    ): Boolean {
        val isAnime = media.anime != null
        val tabContext = if (isAnime) InfoTabContext.ANILIST_ANIME else InfoTabContext.ANILIST_MANGA
        val type = tabContext.visibleOrderedTabs().getOrNull(position) ?: return false

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
                    "https://comick.dev/comic/$comickSlug"
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
        }

        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return true
    }

    private fun updateTabStates(model: MediaDetailsViewModel, media: Media) {
        val isAnime = media.anime != null
        if (isAnime) return

        // Tab types in current display order to compute alpha states
        val tabTypes = InfoTabContext.ANILIST_MANGA.visibleOrderedTabs()

        for (i in 0 until binding.mediaInfoTabLayout.tabCount) {
            val tab = binding.mediaInfoTabLayout.getTabAt(i) ?: continue
            val type = tabTypes.getOrNull(i)
            val alpha = when (type) {
                InfoTabType.ANILIST -> 1.0f
                InfoTabType.MAL -> if (media.idMAL != null) 1.0f else 0.4f
                InfoTabType.COMICK -> if (model.comickSlug.value != null) 1.0f else 0.4f
                InfoTabType.MANGAUPDATES -> {
                    val isLoading = model.mangaUpdatesLoading.value == true
                    val hasData = model.mangaUpdatesLink.value != null
                    when {
                        hasData -> 1.0f
                        isLoading -> 0.6f
                        else -> 0.4f
                    }
                }
                InfoTabType.MANGABAKA -> {
                    val resolved = model.mangaBakaLoaded.value == true
                    val hasData = (model.mangaBakaId.value ?: 0L) > 0L
                    when {
                        hasData -> 1.0f
                        resolved -> 0.4f
                        else -> 0.6f
                    }
                }
                null -> 0.4f
            }
            tab.view.alpha = alpha
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
