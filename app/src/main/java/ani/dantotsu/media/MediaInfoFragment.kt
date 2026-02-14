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

                // Build list of tabs dynamically according to user connection settings
                val tabsMutable = mutableListOf<TabInfo>()
                // AniList always present
                tabsMutable.add(TabInfo("anilist", AniListInfoFragment(), ani.dantotsu.R.drawable.ic_anilist))
                // MAL tab is optional based on settings
                if (PrefManager.getVal<Boolean>(PrefName.MalEnabled)) {
                    tabsMutable.add(TabInfo("mal", MALInfoFragment(), ani.dantotsu.R.drawable.ic_myanimelist))
                }

                if (!isAnime) {
                    if (PrefManager.getVal<Boolean>(PrefName.ComickEnabled)) {
                        tabsMutable.add(TabInfo("comick", ComickInfoFragment(), ani.dantotsu.R.drawable.ic_round_comick_24))
                    }
                    if (PrefManager.getVal<Boolean>(PrefName.MangaUpdatesEnabled)) {
                        tabsMutable.add(TabInfo("mangaupdates", MangaUpdatesInfoFragment(), ani.dantotsu.R.drawable.ic_round_mangaupdates_24))
                    }
                }

                // Promote to class-level list for other methods to use
                tabs = tabsMutable.toList()

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

    private fun handleTabLongClick(
            position: Int,
            model: MediaDetailsViewModel,
            media: Media
    ): Boolean {
        val isAnime = media.anime != null

        val url =
                when (position) {
                    0 -> {
                        // AniList
                        "https://anilist.co/${if (isAnime) "anime" else "manga"}/${media.id}"
                    }
                    1 -> {
                        // MAL
                        val mediaType = if (isAnime) "anime" else "manga"
                        if (media.idMAL != null) {
                            "https://myanimelist.net/$mediaType/${media.idMAL}"
                        } else {
                            "https://myanimelist.net/$mediaType.php?q=${
                        java.net.URLEncoder.encode(media.userPreferredName, "utf-8")
                    }&cat=$mediaType"
                        }
                    }
                    2 -> {
                        // Comick tab (manga only)
                        if (isAnime) return false
                        val comickSlug = model.comickSlug.value
                        if (comickSlug != null) {
                            "https://comick.dev/comic/$comickSlug"
                        } else {
                            "https://comick.dev/search?q=${
                        java.net.URLEncoder.encode(media.userPreferredName, "utf-8").replace("+", "%20")
                    }"
                        }
                    }
                    3 -> {
                        // MangaUpdates tab (manga only)
                        if (isAnime) return false
                        val muLink = model.mangaUpdatesLink.value
                        if (muLink != null) {
                            muLink
                        } else {
                            val encoded =
                                    java.net.URLEncoder.encode(media.userPreferredName, "utf-8")
                                            .replace("+", "%20")
                            "https://www.mangaupdates.com/series?search=$encoded"
                        }
                    }
                    else -> return false
                }

        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return true
    }

    private fun updateTabStates(model: MediaDetailsViewModel, media: Media) {
        val isAnime = media.anime != null
        if (isAnime) return

        // Build a list of tab types in current order to compute alpha states
        val tabTypes = mutableListOf<String>()
        tabTypes.add("anilist")
        if (PrefManager.getVal<Boolean>(PrefName.MalEnabled)) tabTypes.add("mal")
        if (PrefManager.getVal<Boolean>(PrefName.ComickEnabled)) tabTypes.add("comick")
        if (PrefManager.getVal<Boolean>(PrefName.MangaUpdatesEnabled)) tabTypes.add("mangaupdates")

        for (i in 0 until binding.mediaInfoTabLayout.tabCount) {
            val tab = binding.mediaInfoTabLayout.getTabAt(i) ?: continue
            val type = tabTypes.getOrNull(i) ?: ""
            val alpha = when (type) {
                "anilist" -> 1.0f
                "mal" -> if (media.idMAL != null) 1.0f else 0.4f
                "comick" -> if (model.comickSlug.value != null) 1.0f else 0.4f
                "mangaupdates" -> {
                    val isLoading = model.mangaUpdatesLoading.value == true
                    val hasData = model.mangaUpdatesLink.value != null
                    when {
                        hasData -> 1.0f
                        isLoading -> 0.6f
                        else -> 0.4f
                    }
                }
                else -> 0.4f
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
