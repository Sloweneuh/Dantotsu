package ani.dantotsu.media

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val model: MediaDetailsViewModel by activityViewModels()

        model.getMedia().observe(viewLifecycleOwner) { media ->
            if (media != null) {
                val hasMAL = media.idMAL != null

                // Setup ViewPager
                val adapter = InfoPagerAdapter(
                    childFragmentManager,
                    viewLifecycleOwner.lifecycle,
                    hasMAL
                )
                binding.mediaInfoViewPager.adapter = adapter

                // Setup TabLayout
                TabLayoutMediator(binding.mediaInfoTabLayout, binding.mediaInfoViewPager) { tab, position ->
                    when (position) {
                        0 -> tab.setIcon(ani.dantotsu.R.drawable.ic_anilist)
                        1 -> tab.setIcon(ani.dantotsu.R.drawable.ic_myanimelist)
                    }
                }.attach()

                // Hide TabLayout if only AniList is available
                if (!hasMAL) {
                    binding.mediaInfoTabLayout.visibility = View.GONE
                }
            }
        }
    }

    private class InfoPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val hasMAL: Boolean
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int = if (hasMAL) 2 else 1

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> AniListInfoFragment()
            1 -> MALInfoFragment()
            else -> AniListInfoFragment()
        }
    }
}

