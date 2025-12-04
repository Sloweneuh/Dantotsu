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
    private val binding get() = _binding!!

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

        // Setup ViewPager once - always show all 4 tabs
        val adapter = InfoPagerAdapter(
            childFragmentManager,
            viewLifecycleOwner.lifecycle
        )
        binding.mediaInfoViewPager.adapter = adapter

        // Load all fragments immediately so data loads in background
        binding.mediaInfoViewPager.offscreenPageLimit = 3

        // Disable swipe gestures
        binding.mediaInfoViewPager.isUserInputEnabled = false

        model.getMedia().observe(viewLifecycleOwner) { media ->
            currentMedia = media
            if (media != null && !isSetup) {
                isSetup = true
                setupTabs(model, media)
            }
            updateTabStates(model, media)
        }

        // Observe data availability changes
        model.comickSlug.observe(viewLifecycleOwner) {
            currentMedia?.let { media -> updateTabStates(model, media) }
        }

        model.mangaUpdatesLink.observe(viewLifecycleOwner) {
            currentMedia?.let { media -> updateTabStates(model, media) }
        }
    }

    private fun setupTabs(model: MediaDetailsViewModel, media: Media) {
        // Setup TabLayout - all tabs long-clickable
        TabLayoutMediator(binding.mediaInfoTabLayout, binding.mediaInfoViewPager) { tab, position ->
            when (position) {
                0 -> tab.setIcon(ani.dantotsu.R.drawable.ic_anilist)
                1 -> tab.setIcon(ani.dantotsu.R.drawable.ic_myanimelist)
                2 -> tab.setIcon(ani.dantotsu.R.drawable.ic_round_comick_24)
                3 -> tab.setIcon(ani.dantotsu.R.drawable.ic_round_mangaupdates_24)
            }
        }.attach()

        // Set long-click listeners
        for (i in 0 until binding.mediaInfoTabLayout.tabCount) {
            val tab = binding.mediaInfoTabLayout.getTabAt(i) ?: continue

            tab.view.setOnLongClickListener {
                handleTabLongClick(i, model, media)
            }
        }
    }

    private fun handleTabLongClick(position: Int, model: MediaDetailsViewModel, media: Media): Boolean {
        val url = when (position) {
            0 -> media.shareLink
            1 -> {
                if (media.idMAL != null) {
                    "https://myanimelist.net/${if (media.anime != null) "anime" else "manga"}/${media.idMAL}"
                } else {
                    val mediaType = if (media.anime != null) "anime" else "manga"
                    "https://myanimelist.net/${mediaType}.php?q=${
                        java.net.URLEncoder.encode(media.userPreferredName, "utf-8")
                    }&cat=$mediaType"
                }
            }
            2 -> {
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
                val muLink = model.mangaUpdatesLink.value
                if (muLink != null) {
                    muLink
                } else {
                    val encoded = java.net.URLEncoder.encode(media.userPreferredName, "utf-8").replace("+", "%20")
                    "https://www.mangaupdates.com/series?search=$encoded"
                }
            }
            else -> return false
        }

        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return true
    }

    private fun updateTabStates(model: MediaDetailsViewModel, media: Media?) {
        if (media == null) return

        for (i in 0 until binding.mediaInfoTabLayout.tabCount) {
            val tab = binding.mediaInfoTabLayout.getTabAt(i) ?: continue
            val hasData = when (i) {
                0 -> true // AniList always has data
                1 -> media.idMAL != null
                2 -> model.comickSlug.value != null
                3 -> model.mangaUpdatesLink.value != null
                else -> false
            }

            // Set alpha to make tabs without data darker
            tab.view.alpha = if (hasData) 1.0f else 0.4f
        }
    }

    private class InfoPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> AniListInfoFragment()
            1 -> MALInfoFragment()
            2 -> ComickInfoFragment()
            3 -> MangaUpdatesInfoFragment()
            else -> AniListInfoFragment()
        }
    }
}

