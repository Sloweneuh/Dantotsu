package ani.dantotsu.media

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.databinding.FragmentMediaInfoContainerBinding
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val model: MediaDetailsViewModel by activityViewModels()

        model.getMedia().observe(viewLifecycleOwner) { media ->
            if (media != null) {
                val hasMAL = media.idMAL != null

                // Check for Comick availability
                lifecycleScope.launch {
                    val hasComick = withContext(Dispatchers.IO) {
                        try {
                            val mediaType = if (media.anime != null) "anime" else "manga"
                            val quicklinks = ani.dantotsu.connections.malsync.MalSyncApi.getQuicklinks(
                                media.id,
                                media.idMAL,
                                mediaType
                            )
                            // Check MalSync first
                            var comickSlug = quicklinks?.Sites?.entries?.firstOrNull {
                                it.key.equals("Comick", true) || it.key.contains("comick", true)
                            }?.value?.values?.firstOrNull()?.identifier

                            // If not found in MalSync, try search API
                            if (comickSlug == null && mediaType == "manga") {
                                // Build a list of titles to try, prioritizing English titles
                                val titlesToTry = mutableListOf<String>()

                                // Add main English title first
                                media.name?.let { titlesToTry.add(it) }

                                // Add English synonyms (filter out non-Latin titles)
                                val englishSynonyms = media.synonyms.filter { synonym ->
                                    if (synonym.isBlank()) return@filter false

                                    // Check if the title contains CJK characters
                                    val hasCJK = synonym.any { char ->
                                        char.code in 0x3040..0x309F || // Hiragana
                                        char.code in 0x30A0..0x30FF || // Katakana
                                        char.code in 0x4E00..0x9FFF || // CJK Ideographs
                                        char.code in 0xAC00..0xD7AF || // Hangul Syllables
                                        char.code in 0x1100..0x11FF    // Hangul Jamo
                                    }
                                    !hasCJK
                                }
                                englishSynonyms.forEach { synonym ->
                                    if (!titlesToTry.contains(synonym)) {
                                        titlesToTry.add(synonym)
                                    }
                                }

                                // Add romaji title as fallback
                                if (!titlesToTry.contains(media.nameRomaji)) {
                                    titlesToTry.add(media.nameRomaji)
                                }

                                if (titlesToTry.isNotEmpty()) {
                                    comickSlug = ani.dantotsu.connections.comick.ComickApi.searchAndMatchComic(
                                        titlesToTry,
                                        media.id,
                                        media.idMAL
                                    )
                                }
                            }

                            comickSlug != null
                        } catch (_: Exception) {
                            false
                        }
                    }

                    // Setup ViewPager
                    val adapter = InfoPagerAdapter(
                        childFragmentManager,
                        viewLifecycleOwner.lifecycle,
                        hasMAL,
                        hasComick
                    )
                    binding.mediaInfoViewPager.adapter = adapter

                    // Disable swipe gestures to allow horizontal scrolling inside fragments
                    binding.mediaInfoViewPager.isUserInputEnabled = false

                    // Setup TabLayout
                    TabLayoutMediator(binding.mediaInfoTabLayout, binding.mediaInfoViewPager) { tab, position ->
                        when (position) {
                            0 -> tab.setIcon(ani.dantotsu.R.drawable.ic_anilist)
                            1 -> if (hasMAL && hasComick) {
                                tab.setIcon(ani.dantotsu.R.drawable.ic_myanimelist)
                            } else if (hasMAL) {
                                tab.setIcon(ani.dantotsu.R.drawable.ic_myanimelist)
                            } else if (hasComick) {
                                tab.setIcon(ani.dantotsu.R.drawable.ic_round_comick_24)
                            }
                            2 -> tab.setIcon(ani.dantotsu.R.drawable.ic_round_comick_24)
                        }
                    }.attach()

                    // Hide TabLayout if only AniList is available
                    if (!hasMAL && !hasComick) {
                        binding.mediaInfoTabLayout.visibility = View.GONE
                    }
                }
            }
        }
    }

    private class InfoPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val hasMAL: Boolean,
        private val hasComick: Boolean
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int {
            return when {
                hasMAL && hasComick -> 3
                hasMAL || hasComick -> 2
                else -> 1
            }
        }

        override fun createFragment(position: Int): Fragment = when {
            position == 0 -> AniListInfoFragment()
            position == 1 && hasMAL && hasComick -> MALInfoFragment()
            position == 1 && hasMAL -> MALInfoFragment()
            position == 1 && hasComick -> ComickInfoFragment()
            position == 2 && hasComick -> ComickInfoFragment()
            else -> AniListInfoFragment()
        }
    }
}

