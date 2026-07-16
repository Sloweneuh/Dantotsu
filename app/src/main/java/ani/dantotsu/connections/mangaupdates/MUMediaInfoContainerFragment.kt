package ani.dantotsu.connections.mangaupdates

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.databinding.FragmentMediaInfoContainerBinding
import ani.dantotsu.media.ComickInfoFragment
import ani.dantotsu.media.InfoTabContext
import ani.dantotsu.media.InfoTabType
import ani.dantotsu.isOnline
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private data class TabInfo(val type: String, val fragment: Fragment, val iconRes: Int)
    private var tabs: List<TabInfo> = emptyList()

    private fun createTabFragment(type: InfoTabType): Fragment = when (type) {
        InfoTabType.MANGAUPDATES -> MUMediaInfoFragment()
        InfoTabType.COMICK -> ComickInfoFragment()
        InfoTabType.MANGABAKA -> ani.dantotsu.media.MangaBakaInfoFragment()
        InfoTabType.ANILIST, InfoTabType.MAL -> error("$type is not a MangaUpdates info tab")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val model: MediaDetailsViewModel by activityViewModels()

        // Comick and MangaBaka tabs are pure network lookups, so offline show only the
        // MangaUpdates tab (which renders the downloaded basics).
        val offline = PrefManager.getVal<Boolean>(PrefName.OfflineMode) ||
                !isOnline(requireContext())
        tabs = InfoTabContext.MANGAUPDATES_MANGA.visibleOrderedTabs()
            .filter { !offline || it == InfoTabType.MANGAUPDATES }
            .ifEmpty { listOf(InfoTabType.MANGAUPDATES) }
            .map { type -> TabInfo(type.key, createTabFragment(type), type.iconRes) }

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

        // preloadExternalData isn't run in the MangaUpdates activity, so fetch the MangaBaka series
        // here (once, as soon as the media is available). The source route embeds the full object,
        // so this both drives tab dimming *before the tab is opened* and is reused by the info
        // fragment — observing (rather than reading .value once) avoids missing a media set later.
        // Gated only on the connection being enabled, not on whether the MangaBaka tab is
        // actually visible (checked further down, before the early return below): an AniList
        // equivalent must still be found and offered even when the user has hidden the tab.
        if (InfoTabType.MANGABAKA.fetchEnabled && !offline) {
            var mbStarted = false
            model.getMedia().observe(viewLifecycleOwner) { media ->
                if (media != null && !mbStarted && model.mangaBakaLoaded.value != true) {
                    mbStarted = true
                    viewLifecycleOwner.lifecycleScope.launch {
                        val series = withContext(Dispatchers.IO) {
                            MangaBakaApi.getSeriesForMedia(media.muSeriesId, media.id, media.idMAL)
                        }
                        model.mangaBakaSeries.postValue(series)
                        model.mangaBakaId.postValue(series?.id)
                        model.mangaBakaLoaded.postValue(true)

                        val anilistId = series?.source?.anilist?.id
                        val mangaBakaTabVisible = tabs.any { it.type == InfoTabType.MANGABAKA.key }
                        if (anilistId != null && anilistId > 0 && !mangaBakaTabVisible &&
                            !model.mangaBakaEquivalentPromptShown
                        ) {
                            model.mangaBakaEquivalentPromptShown = true
                            showAnilistEquivalentDialog(anilistId)
                        }
                    }
                }
            }
        }

        if (tabs.size <= 1) {
            binding.mediaInfoTabLayout.visibility = View.GONE
            return
        }

        binding.mediaInfoTabLayout.visibility = View.VISIBLE

        TabLayoutMediator(binding.mediaInfoTabLayout, binding.mediaInfoViewPager) { tab, position ->
            tab.setIcon(tabs[position].iconRes)

            // Long-press the tab icon to open the corresponding external page in browser
            tab.view.setOnLongClickListener {
                val media = model.getMedia().value
                val encoded = java.net.URLEncoder.encode(media?.userPreferredName ?: "", "utf-8").replace("+", "%20")
                val url = when (tabs.getOrNull(position)?.type) {
                    "mangaupdates" -> {
                        val muLink = model.mangaUpdatesLink.value ?: run {
                            media?.externalLinks?.firstOrNull { entry ->
                                entry.getOrNull(1)?.contains("mangaupdates", ignoreCase = true) == true ||
                                        entry.getOrNull(0)?.contains("mangaupdates", ignoreCase = true) == true
                            }?.getOrNull(1)
                        }
                        if (muLink != null && muLink.contains("mangaupdates", ignoreCase = true)) muLink
                        else "https://www.mangaupdates.com/series?search=$encoded"
                    }
                    "comick" -> {
                        val comickSlug = model.comickSlug.value
                        if (!comickSlug.isNullOrBlank()) "https://comick.dev/comic/$comickSlug"
                        else "https://comick.dev/search?q=$encoded"
                    }
                    "mangabaka" -> {
                        val id = model.mangaBakaId.value
                        if (id != null && id > 0) "https://mangabaka.org/$id"
                        else "https://mangabaka.org/search?q=$encoded"
                    }
                    else -> return@setOnLongClickListener false
                }
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                true
            }
        }.attach()

        // Dim the Comick / MangaBaka tab icons until their data is confirmed
        model.comickSlug.observe(viewLifecycleOwner) { applyTabAlpha(model) }
        model.comickLoaded.observe(viewLifecycleOwner) { applyTabAlpha(model) }
        model.mangaBakaId.observe(viewLifecycleOwner) { applyTabAlpha(model) }
        model.mangaBakaLoaded.observe(viewLifecycleOwner) { applyTabAlpha(model) }
    }

    private fun showAnilistEquivalentDialog(anilistId: Int) {
        val ctx = context ?: return
        ctx.customAlertDialog().apply {
            setTitle(getString(R.string.anilist_equivalent_found_title))
            setMessage(getString(R.string.anilist_equivalent_found_desc))
            setPosButton(R.string.view_on_anilist) {
                val intent = android.content.Intent(ctx, ani.dantotsu.media.MediaDetailsActivity::class.java)
                intent.putExtra("mediaId", anilistId)
                startActivity(intent)
            }
            setNegButton(R.string.cancel, null)
            show()
        }
    }

    private fun applyTabAlpha(model: MediaDetailsViewModel) {
        tabs.forEachIndexed { index, info ->
            val tab = binding.mediaInfoTabLayout.getTabAt(index) ?: return@forEachIndexed
            tab.view.alpha = when (info.type) {
                "comick" -> when {
                    model.comickSlug.value != null -> 1.0f
                    model.comickLoaded.value == true -> 0.4f
                    else -> 0.6f
                }
                "mangabaka" -> when {
                    (model.mangaBakaId.value ?: 0L) > 0L -> 1.0f
                    model.mangaBakaLoaded.value == true -> 0.4f
                    else -> 0.6f
                }
                else -> 1.0f
            }
        }
    }
}
