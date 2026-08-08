package ani.dantotsu.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentDownloadSettingsBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.Settings
import ani.dantotsu.settings.SettingsAdapter
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadSettingsFragment : Fragment() {
    private var _binding: FragmentDownloadSettingsBinding? = null
    private val binding get() = _binding!!
    private var onCloseAction: (() -> Unit)? = null
    private val downloadsManager get() = Injekt.get<DownloadsManager>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(requireActivity()).applyTheme()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface))
        binding.downloadSettingsRecycler.clipToPadding = false
        binding.downloadSettingsRecycler.apply {
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + navBarHeight)
        }
        binding.downloadSettingsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.downloadSettingsRecycler.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 1,
                    name = getString(R.string.download_manager_select),
                    desc = getString(R.string.download_manager_select_desc),
                    icon = R.drawable.ic_round_download_manager_24,
                    onClick = {
                        val managers = arrayOf("Default", "1DM", "ADM")
                        requireContext().customAlertDialog().apply {
                            setTitle(getString(R.string.download_manager))
                            singleChoiceItems(
                                managers,
                                PrefManager.getVal(PrefName.DownloadManager),
                            ) { count ->
                                PrefManager.setVal(PrefName.DownloadManager, count)
                            }
                            show()
                        }
                    },
                ),
                Settings(
                    type = 2,
                    name = getString(R.string.allow_metered_downloads),
                    desc = getString(R.string.allow_metered_downloads_desc),
                    icon = R.drawable.ic_round_download_metered_24,
                    isChecked = PrefManager.getVal(PrefName.AllowMeteredDownloads),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.AllowMeteredDownloads, isChecked)
                    },
                ),
                purgeSetting(
                    MediaType.ANIME,
                    R.string.purge_anime_downloads,
                    R.string.purge_anime_downloads_desc,
                    R.string.anime,
                    R.drawable.ic_round_purge_anime_24,
                ),
                purgeSetting(
                    MediaType.MANGA,
                    R.string.purge_manga_downloads,
                    R.string.purge_manga_downloads_desc,
                    R.string.manga,
                    R.drawable.ic_round_purge_manga_24,
                ),
                purgeSetting(
                    MediaType.NOVEL,
                    R.string.purge_novel_downloads,
                    R.string.purge_novel_downloads_desc,
                    R.string.novels,
                    R.drawable.ic_round_purge_novel_24,
                ),
            )
        )
    }

    /**
     * @param iconRes the media type badged with a bin. All three rows are the same irreversible
     *   action and used to share one bin between them, which left the only thing that differs —
     *   which library it empties — carried by the label alone.
     */
    private fun purgeSetting(
        type: MediaType,
        titleRes: Int,
        descRes: Int,
        mediaNameRes: Int,
        iconRes: Int,
    ) = Settings(
        type = 1,
        name = getString(titleRes),
        desc = getString(descRes),
        icon = iconRes,
        onClick = {
            requireContext().customAlertDialog().apply {
                setTitle(titleRes)
                setMessage(R.string.purge_confirm, getString(mediaNameRes))
                setPosButton(R.string.yes) {
                    downloadsManager.purgeDownloads(type)
                }
                setNegButton(R.string.no)
                show()
            }
        },
    )

    override fun onDestroyView() {
        super.onDestroyView()
        onCloseAction?.invoke()
        _binding = null
    }

    fun getInstance(onCloseAction: (() -> Unit)? = null): DownloadSettingsFragment {
        val fragment = DownloadSettingsFragment()
        fragment.onCloseAction = onCloseAction
        return fragment
    }
}
