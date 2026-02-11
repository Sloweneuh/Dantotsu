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
import com.google.android.material.tabs.TabLayoutMediator

class MediaInfoFragment : Fragment() {
    private var _binding: FragmentMediaInfoContainerBinding? = null
    private val binding
        get() = _binding!!

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

                // Setup ViewPager - show 2 tabs for anime, 4 for manga
                val isAnime = media.anime != null
                val adapter =
                        InfoPagerAdapter(
                                childFragmentManager,
                                viewLifecycleOwner.lifecycle,
                                isAnime
                        )
                binding.mediaInfoViewPager.adapter = adapter

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
                    if (isAnime) {
                        // Anime: only AniList and MAL tabs
                        when (position) {
                            0 -> tab.setIcon(ani.dantotsu.R.drawable.ic_anilist)
                            1 -> tab.setIcon(ani.dantotsu.R.drawable.ic_myanimelist)
                        }
                    } else {
                        // Manga: all 4 tabs
                        when (position) {
                            0 -> tab.setIcon(ani.dantotsu.R.drawable.ic_anilist)
                            1 -> tab.setIcon(ani.dantotsu.R.drawable.ic_myanimelist)
                            2 -> tab.setIcon(ani.dantotsu.R.drawable.ic_round_comick_24)
                            3 -> tab.setIcon(ani.dantotsu.R.drawable.ic_round_mangaupdates_24)
                        }
                    }

                    // Set long-click listeners for opening URLs
                    tab.view.setOnLongClickListener { handleTabLongClick(position, model, media) }
                }
                .attach()

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

        for (i in 0 until binding.mediaInfoTabLayout.tabCount) {
            val tab = binding.mediaInfoTabLayout.getTabAt(i) ?: continue

            val alpha =
                    when (i) {
                        0 -> 1.0f // AniList always has data
                        1 -> if (media.idMAL != null) 1.0f else 0.4f
                        2 -> if (model.comickSlug.value != null) 1.0f else 0.4f
                        3 -> {
                            // MangaUpdates: show loading state
                            val isLoading = model.mangaUpdatesLoading.value == true
                            val hasData = model.mangaUpdatesLink.value != null
                            when {
                                hasData -> 1.0f // Has data - full brightness
                                isLoading -> 0.6f // Loading - medium brightness
                                else -> 0.4f // No data - dim
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
