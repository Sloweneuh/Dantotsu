package ani.dantotsu.offline

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LayoutAnimationController
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.FragmentHomeBinding
import ani.dantotsu.download.OfflineMediaLoader
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaAdaptor
import ani.dantotsu.media.user.ListActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.setSlideIn
import ani.dantotsu.settings.SettingsDialogFragment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The offline home screen. Reuses the regular home layout but shows only downloaded content:
 * two sections ("Downloaded Anime" and "Downloaded Manga") in the user's preferred order, plus the
 * Anime/Manga List buttons (which open a downloaded-only list). The search button is hidden and the
 * user stats area shows "Offline Mode".
 */
class OfflineHomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Same header spacing as the online home
        binding.homeContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.homeUserBg.updateLayoutParams { height += statusBarHeight }
        binding.homeUserBgNoKen.updateLayoutParams { height += statusBarHeight }
        binding.homeTopContainer.updatePadding(top = statusBarHeight)

        // Header: no search, stats replaced with "Offline Mode"
        binding.searchImageContainer.visibility = View.GONE
        binding.homeUserDataProgressBar.visibility = View.GONE
        binding.homeUserDataContainer.visibility = View.VISIBLE
        binding.homeNotificationCount.visibility = View.GONE
        binding.homeUserName.text =
            Anilist.username?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        // Leave the avatar ImageView showing its default cog icon (set in the layout) rather than
        // trying to load the network avatar, which isn't fetchable offline anyway.
        val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
        blurImage(if (bannerAnimations) binding.homeUserBg else binding.homeUserBgNoKen, Anilist.bg)
        binding.homeUserStat1Row.visibility = View.VISIBLE
        binding.homeUserStat1Label.text = getString(R.string.offline_mode)
        binding.homeUserStat1Value.text = ""
        binding.homeUserStat2Row.visibility = View.GONE

        binding.homeUserAvatarContainer.setSafeOnClickListener {
            SettingsDialogFragment
                .newInstance(SettingsDialogFragment.Companion.PageType.OfflineHOME)
                .show((it.context as AppCompatActivity).supportFragmentManager, "dialog")
        }

        // List buttons open the downloaded-only list
        binding.homeAnimeList.visibility = View.VISIBLE
        binding.homeMangaList.visibility = View.VISIBLE
        binding.homeAnimeList.setOnClickListener { openOfflineList(true) }
        binding.homeMangaList.setOnClickListener { openOfflineList(false) }
        binding.homeListContainer.layoutAnimation = LayoutAnimationController(setSlideIn(), 0.25f)

        // Hide every section except the two downloaded ones
        listOf(
            binding.homeUserStatusContainer,
            binding.homeHiddenItemsContainer,
            binding.homeFavAnimeContainer,
            binding.homePlannedAnimeContainer,
            binding.homeUnreadChaptersContainer,
            binding.homeFavMangaContainer,
            binding.homePlannedMangaContainer,
            binding.homeRecommendedContainer,
            binding.homeDantotsuContainer,
        ).forEach { it.visibility = View.GONE }

        binding.homeContinueWatch.text = getString(R.string.downloaded_anime)
        binding.homeContinueRead.text = getString(R.string.downloaded_manga)

        applySectionOrder()

        binding.homeRefresh.setOnRefreshListener {
            loadSections()
            binding.homeRefresh.isRefreshing = false
        }
        // Content is loaded in onResume(), which fires immediately after this on first creation.
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) loadSections()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun openOfflineList(anime: Boolean) {
        ContextCompat.startActivity(
            requireActivity(),
            Intent(requireActivity(), ListActivity::class.java)
                .putExtra("anime", anime)
                .putExtra("offline", true),
            null
        )
    }

    /** Reorders the two visible sections to respect the user's home layout order preference. */
    private fun applySectionOrder() {
        val containers = arrayOf(
            binding.homeContinueWatchingContainer, // 0 AnimeContinue
            binding.homeFavAnimeContainer,         // 1
            binding.homePlannedAnimeContainer,     // 2
            binding.homeUnreadChaptersContainer,   // 3
            binding.homeContinueReadingContainer,  // 4 MangaContinue
            binding.homeFavMangaContainer,         // 5
            binding.homePlannedMangaContainer,     // 6
            binding.homeRecommendedContainer,      // 7
            binding.homeUserStatusContainer,       // 8
        )
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
            // Fail silently if the preference is malformed
        }
    }

    private fun loadSections() {
        binding.homeContinueWatchingContainer.visibility = View.VISIBLE
        binding.homeContinueReadingContainer.visibility = View.VISIBLE
        binding.homeWatchingProgressBar.visibility = View.VISIBLE
        binding.homeReadingProgressBar.visibility = View.VISIBLE
        binding.homeWatchingRecyclerView.visibility = View.GONE
        binding.homeReadingRecyclerView.visibility = View.GONE
        binding.homeWatchingEmpty.visibility = View.GONE
        binding.homeReadingEmpty.visibility = View.GONE

        lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val animeList = withContext(Dispatchers.IO) {
                OfflineMediaLoader.loadDownloadedMediaList(ctx, true)
            }
            val mangaList = withContext(Dispatchers.IO) {
                OfflineMediaLoader.loadDownloadedMediaList(ctx, false)
            }
            if (_binding == null) return@launch

            animeList.firstOrNull()?.cover?.let { binding.homeAnimeListImage.loadImage(it) }
            mangaList.firstOrNull()?.cover?.let { binding.homeMangaListImage.loadImage(it) }

            bindSection(
                animeList,
                binding.homeWatchingRecyclerView,
                binding.homeWatchingProgressBar,
                binding.homeWatchingEmpty,
                binding.homeContinueWatchMore,
                anime = true
            )
            bindSection(
                mangaList,
                binding.homeReadingRecyclerView,
                binding.homeReadingProgressBar,
                binding.homeReadingEmpty,
                binding.homeContinueReadMore,
                anime = false
            )
        }
    }

    private fun bindSection(
        list: ArrayList<Media>,
        recyclerView: androidx.recyclerview.widget.RecyclerView,
        progress: View,
        empty: View,
        more: View,
        anime: Boolean
    ) {
        progress.visibility = View.GONE
        if (list.isNotEmpty()) {
            recyclerView.adapter = MediaAdaptor(0, list, requireActivity())
            recyclerView.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            recyclerView.visibility = View.VISIBLE
            recyclerView.layoutAnimation = LayoutAnimationController(setSlideIn(), 0.25f)
            empty.visibility = View.GONE
            more.visibility = View.VISIBLE
            more.setOnClickListener { openOfflineList(anime) }
        } else {
            recyclerView.visibility = View.GONE
            empty.visibility = View.VISIBLE
            more.visibility = View.INVISIBLE
            // Browse buttons don't apply offline
            (empty as? ViewGroup)?.let {
                val browseId = if (anime) R.id.homeWatchingBrowseButton else R.id.homeReadingBrowseButton
                it.findViewById<View>(browseId)?.visibility = View.GONE
            }
        }
    }
}
