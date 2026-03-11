package ani.dantotsu.connections.mangaupdates

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentMediaInfoContainerBinding
import ani.dantotsu.media.ComickInfoFragment
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Container fragment for the Info bottom tab in [MUMediaDetailsActivity].
 * Mirrors the structure of [ani.dantotsu.media.MediaInfoFragment]:
 *  - First inner tab: MangaUpdates info ([MUMediaInfoFragment]) – always shown
 *  - Second inner tab: Comick info ([ComickInfoFragment]) – only when Comick is enabled
 * If only one tab would appear the TabLayout is hidden.
 */
class MUMediaInfoContainerFragment : Fragment() {

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
        val model: MediaDetailsViewModel by activityViewModels()
        val comickEnabled = PrefManager.getVal<Boolean>(PrefName.ComickEnabled)

        data class TabInfo(val fragment: Fragment, val iconRes: Int)

        val tabs = buildList {
            add(TabInfo(MUMediaInfoFragment(), R.drawable.ic_round_mangaupdates_24))
            if (comickEnabled) {
                add(TabInfo(ComickInfoFragment(), R.drawable.ic_round_comick_24))
            }
        }

        val adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(
            childFragmentManager, viewLifecycleOwner.lifecycle
        ) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int): Fragment = tabs[position].fragment
        }

        binding.mediaInfoViewPager.adapter = adapter
        binding.mediaInfoViewPager.isUserInputEnabled = false
        // Only load adjacent pages to reduce unnecessary API calls
        binding.mediaInfoViewPager.offscreenPageLimit = 1

        if (tabs.size <= 1) {
            binding.mediaInfoTabLayout.visibility = View.GONE
            return
        }

        binding.mediaInfoTabLayout.visibility = View.VISIBLE

        TabLayoutMediator(binding.mediaInfoTabLayout, binding.mediaInfoViewPager) { tab, position ->
            tab.setIcon(tabs[position].iconRes)
        }.attach()

        // Dim the Comick tab icon until a slug is confirmed
        model.comickSlug.observe(viewLifecycleOwner) {
            applyTabAlpha(model)
        }
        model.comickLoaded.observe(viewLifecycleOwner) {
            applyTabAlpha(model)
        }
    }

    private fun applyTabAlpha(model: MediaDetailsViewModel) {
        val comickTabIndex = 1 // always index 1 when Comick is enabled (and tab count > 1)
        val tab = binding.mediaInfoTabLayout.getTabAt(comickTabIndex) ?: return
        tab.view.alpha = when {
            model.comickSlug.value != null -> 1.0f
            model.comickLoaded.value == true -> 0.4f   // loaded but not found
            else -> 0.6f                                // still loading
        }
    }
}
