package ani.dantotsu.settings

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.addons.AddonDownloader
import ani.dantotsu.addons.torrent.TorrentAddonManager
import ani.dantotsu.addons.torrent.TorrentServerService
import ani.dantotsu.databinding.FragmentExtensionsBinding
import ani.dantotsu.settings.paging.AnimeExtensionAdapter
import ani.dantotsu.settings.paging.AnimeExtensionsViewModel
import ani.dantotsu.settings.paging.AnimeExtensionsViewModelFactory
import ani.dantotsu.settings.paging.OnAnimeInstallClickListener
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.customAlertDialog
import ani.dantotsu.util.hideEmptyState
import ani.dantotsu.util.showError
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rx.android.schedulers.AndroidSchedulers
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeExtensionsFragment : Fragment(),
    SearchQueryHandler, OnAnimeInstallClickListener {
    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AnimeExtensionsViewModel by viewModels {
        AnimeExtensionsViewModelFactory(animeExtensionManager)
    }

    private val adapter by lazy {
        AnimeExtensionAdapter(this)
    }

    private val animeExtensionManager: AnimeExtensionManager = Injekt.get()

    private var searchQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)

        binding.allExtensionsRecyclerView.isNestedScrollingEnabled = false
        binding.allExtensionsRecyclerView.adapter = adapter
        binding.allExtensionsRecyclerView.layoutManager = LinearLayoutManager(context)
        (binding.allExtensionsRecyclerView.layoutManager as LinearLayoutManager).isItemPrefetchEnabled =
            true

        lifecycleScope.launch {
            viewModel.pagerFlow.collectLatest {
                adapter.submitData(it)
            }
        }

        lifecycleScope.launch {
            adapter.loadStateFlow.collectLatest { loadStates ->
                val refresh = loadStates.refresh
                when {
                    refresh is LoadState.Error -> binding.extensionsEmptyState.showError(refresh.error)
                    refresh is LoadState.NotLoading && adapter.itemCount == 0 ->
                        binding.extensionsEmptyState.showExtensionsEmpty(
                            filtered = searchQuery.isNotEmpty() || isLanguageFiltered(),
                            installed = false,
                            repoPref = PrefName.AnimeExtensionRepos,
                        )
                    else -> binding.extensionsEmptyState.hideEmptyState()
                }
            }
        }

        return binding.root
    }

    override fun updateContentBasedOnQuery(query: String?) {
        // Kept so the empty state can tell "nothing matches what you typed" from "there is nothing".
        searchQuery = query.orEmpty()
        viewModel.setSearchQuery(searchQuery)
    }

    override fun notifyDataChanged() {
        viewModel.invalidatePager()
    }

    /**
     * A torrent extension's streams only play through the Torrent add-on, and without it nothing
     * says so until an episode is picked. So before installing one, it offers to set the add-on up
     * too: download it, or switch it on when it's installed but off.
     */
    override fun onInstallClick(pkg: AnimeExtension.Available, onStart: () -> Unit) {
        if (!isAdded) return
        val torrent = Injekt.get<TorrentAddonManager>()
        if (!pkg.isTorrent || torrent.isAvailable()) {
            install(pkg)
            onStart()
            return
        }
        val addonInstalled = torrent.isAvailable(andEnabled = false)
        requireContext().customAlertDialog().apply {
            setTitle(getString(R.string.torrent_extension_needs_addon_title))
            setMessage(
                if (addonInstalled) R.string.torrent_extension_addon_disabled
                else R.string.torrent_extension_addon_missing,
                pkg.name,
            )
            setPosButton(
                getString(
                    if (addonInstalled) R.string.torrent_extension_enable_and_install
                    else R.string.torrent_extension_install_both
                )
            ) {
                // Enabled up front either way: a downloaded add-on starts its server on install.
                PrefManager.setVal(PrefName.TorrentEnabled, true)
                if (addonInstalled) {
                    launchIO { if (!TorrentServerService.isRunning()) TorrentServerService.start() }
                } else {
                    val activity = requireActivity()
                    snackString(getString(R.string.downloading))
                    lifecycleScope.launchIO {
                        AddonDownloader.update(
                            activity = activity,
                            torrent,
                            repo = TorrentAddonManager.REPO,
                            currentVersion = torrent.getVersion() ?: "",
                        )
                    }
                }
                install(pkg)
                onStart()
            }
            setNeutralButton(getString(R.string.torrent_extension_install_anyway)) {
                install(pkg)
                onStart()
            }
            setNegButton(getString(R.string.cancel))
        }.show()
    }

    private fun install(pkg: AnimeExtension.Available) {
        val context = requireContext()
        if (isAdded) {
            val notificationManager =
                requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val installerSteps = InstallerSteps(notificationManager, context)
            // Start the installation process
            animeExtensionManager.installExtension(pkg)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { installStep -> installerSteps.onInstallStep(installStep) {} },
                    { error -> installerSteps.onError(error) {} },
                    { installerSteps.onComplete { viewModel.invalidatePager() } }
                )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView();_binding = null
    }


}