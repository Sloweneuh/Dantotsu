package ani.dantotsu.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.addons.AddonDownloader
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.addons.torrent.TorrentAddonManager
import ani.dantotsu.addons.torrent.TorrentServerService
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivitySettingsSourcesBinding
import ani.dantotsu.databinding.DialogUserAgentBinding
import ani.dantotsu.databinding.ItemRepositoryBinding
import ani.dantotsu.databinding.ItemSettingsBinding
import ani.dantotsu.download.downloadSettingsRows
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.parsers.ParserTestActivity
import ani.dantotsu.restartApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import eu.kanade.domain.base.BasePreferences
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Opens the sources settings with [section] expanded, when one is named.
 *
 * Extensions and Add-ons were each their own destination, so anything sending a user "to the add-on
 * settings" started that Activity and landed them on the settings themselves. There is one screen
 * now and the group takes the place of the destination, so it opens rather than leaving a collapsed
 * card the user still has to find and tap.
 */
fun sourcesSettingsIntent(context: Context, section: String? = null): Intent =
    Intent(context, SettingsSourcesActivity::class.java).apply {
        if (section != null) {
            putExtra(SettingsRouter.EXTRA_ANCHOR_SECTION, section)
            putExtra(SettingsRouter.EXTRA_ANCHOR_SECTION_EXPANDED, true)
        }
    }

/**
 * Where content comes from and where it is kept.
 *
 * Merges three places that a user had no way to tell apart. **Extensions** and **Add-ons** were
 * adjacent top-level entries both meaning "extra components you install" — the only way to know
 * which held what was to have opened both. **Downloads** had no path from Settings at all: its
 * settings lived in a tab of the downloads screen, reachable only by searching. And the four
 * network settings were split three ways, with the proxy here, DNS under Common, and the timestamp
 * proxy inside the player.
 */
class SettingsSourcesActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsSourcesBinding
    private lateinit var sectionAdapter: SettingsSectionAdapter
    private val extensionInstaller = Injekt.get<BasePreferences>().extensionInstaller()
    private val downloadAddonManager: DownloadAddonManager = Injekt.get()
    private val torrentAddonManager: TorrentAddonManager = Injekt.get()

    /** Section keys, also the search anchors — see [SettingsSection.key]. */
    object Section {
        const val REPOSITORIES = "sources_repositories"
        const val EXTENSIONS = "sources_extensions"
        const val ADDONS = "sources_addons"
        const val DOWNLOADS = "sources_downloads"
        const val NETWORK = "sources_network"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsSourcesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsSourcesLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.sourcesSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        sectionAdapter = SettingsSectionAdapter(
            listOf(
                SettingsSection(
                    key = Section.REPOSITORIES,
                    title = getString(R.string.repositories),
                    icon = R.drawable.ic_github,
                    summary = { getString(R.string.repositories_desc) },
                    rows = { repositoryRows() },
                ),
                SettingsSection(
                    key = Section.EXTENSIONS,
                    title = getString(R.string.extensions),
                    icon = R.drawable.ic_extension,
                    summary = { getString(R.string.extension_behaviour_desc) },
                    rows = { extensionRows() },
                ),
                SettingsSection(
                    key = Section.ADDONS,
                    title = getString(R.string.addons),
                    icon = R.drawable.ic_round_widgets_24,
                    summary = { getString(R.string.addons_desc) },
                    rows = { addonRows() },
                ),
                SettingsSection(
                    key = Section.DOWNLOADS,
                    title = getString(R.string.downloads),
                    icon = R.drawable.ic_download_24,
                    summary = { getString(R.string.downloads_settings_desc) },
                    rows = { downloadSettingsRows(compact = true) },
                ),
                SettingsSection(
                    key = Section.NETWORK,
                    title = getString(R.string.network),
                    icon = R.drawable.ic_globe_24,
                    summary = { getString(R.string.network_desc) },
                    rows = { networkRows() },
                ),
            ),
            stateKey = SettingsSectionAdapter.STATE_SOURCES,
            keepExpanded = SettingsRouter.hasAnchor(this),
        )

        binding.settingsRecyclerView.apply {
            adapter = sectionAdapter
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        }

        SettingsRouter.handleHighlight(this, binding.settingsRecyclerView)
        SettingsRouter.handleSectionAnchor(this, sectionAdapter, binding.settingsRecyclerView)
    }

    override fun onDestroy() {
        super.onDestroy()
        torrentAddonManager.removeListenerAction()
        downloadAddonManager.removeListenerAction()
    }

    // -----------------------------------------------------------------------------------------
    // Repositories
    // -----------------------------------------------------------------------------------------

    /** Renders a repo list into a row's attached view. */
    private fun setExtensionOutput(repoInventory: ViewGroup, type: MediaType) {
        repoInventory.removeAllViews()
        val prefName = when (type) {
            MediaType.ANIME -> PrefName.AnimeExtensionRepos
            MediaType.MANGA -> PrefName.MangaExtensionRepos
            MediaType.NOVEL -> PrefName.NovelExtensionRepos
        }
        PrefManager.getVal<Set<String>>(prefName).forEach { item ->
            val view = ItemRepositoryBinding.inflate(
                LayoutInflater.from(repoInventory.context), repoInventory, true
            )
            view.repositoryItem.text = item.removePrefix("https://raw.githubusercontent.com/")
            view.repositoryItem.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                copyToClipboard(item, true)
                true
            }
        }
        repoInventory.isVisible = repoInventory.childCount > 0
    }

    private fun repoRow(
        type: MediaType,
        titleRes: Int,
        descRes: Int,
        iconRes: Int,
        prefName: PrefName,
        key: String,
    ) = Settings(
        type = 1,
        name = getString(titleRes),
        desc = getString(descRes),
        icon = iconRes,
        compact = true,
        anchorKey = key,
        onClick = {
            AddRepositoryBottomSheet.newInstance(
                type,
                PrefManager.getVal<Set<String>>(prefName).toList(),
                onRepositoryAdded = { input, mediaType ->
                    AddRepositoryBottomSheet.addRepo(input, mediaType)
                    setExtensionOutput(it.attachView, mediaType)
                },
                onRepositoryRemoved = { item, mediaType ->
                    AddRepositoryBottomSheet.removeRepo(item, mediaType)
                    setExtensionOutput(it.attachView, mediaType)
                }
            ).show(supportFragmentManager, "add_repo")
        },
        attach = { setExtensionOutput(it.attachView, type) }
    )

    private fun repositoryRows(): List<Settings> = listOf(
        repoRow(
            MediaType.ANIME, R.string.anime_add_repository, R.string.anime_add_repository_desc,
            R.drawable.ic_round_github_anime_24, PrefName.AnimeExtensionRepos, "anime_repo",
        ),
        repoRow(
            MediaType.MANGA, R.string.manga_add_repository, R.string.manga_add_repository_desc,
            R.drawable.ic_round_github_manga_24, PrefName.MangaExtensionRepos, "manga_repo",
        ),
        repoRow(
            MediaType.NOVEL, R.string.novel_add_repository, R.string.novel_add_repository_desc,
            R.drawable.ic_round_github_novel_24, PrefName.NovelExtensionRepos, "novel_repo",
        ),
        Settings(
            type = 1,
            name = getString(R.string.extension_test),
            desc = getString(R.string.extension_test_desc),
            icon = R.drawable.ic_round_science_24,
            compact = true,
            anchorKey = "extension_test",
            isActivity = true,
            onClick = {
                ContextCompat.startActivity(
                    this, Intent(this, ParserTestActivity::class.java), null
                )
            }
        ),
    )

    // -----------------------------------------------------------------------------------------
    // Extension behaviour
    // -----------------------------------------------------------------------------------------

    private fun browseSortOptions() = arrayOf(getString(R.string.popular), getString(R.string.latest))

    /** The current default sort, spelled out on the row so it reads without opening the dialog. */
    private fun browseSortDesc(): String {
        val index = PrefManager.getVal<Int>(PrefName.DefaultBrowseSort).coerceIn(0, 1)
        return getString(R.string.default_browse_sort_desc, browseSortOptions()[index])
    }

    private fun extensionRows(): List<Settings> = listOf(
        Settings(
            type = 1,
            name = getString(R.string.default_browse_sort),
            desc = browseSortDesc(),
            icon = R.drawable.ic_round_sort_24,
            compact = true,
            anchorKey = "default_browse_sort",
            attach = {
                it.settingsDesc.text = browseSortDesc()
                it.attachView.isVisible = false
            },
            onClick = { b ->
                customAlertDialog().apply {
                    setTitle(getString(R.string.default_browse_sort))
                    singleChoiceItems(
                        browseSortOptions(), PrefManager.getVal(PrefName.DefaultBrowseSort)
                    ) { i ->
                        PrefManager.setVal(PrefName.DefaultBrowseSort, i)
                        b.settingsDesc.text = browseSortDesc()
                    }
                    show()
                }
            }
        ),
        Settings(
            type = 2,
            name = getString(R.string.force_legacy_installer),
            desc = getString(R.string.force_legacy_installer_desc),
            icon = R.drawable.ic_round_history_24,
            compact = true,
            anchorKey = "force_legacy_installer",
            isChecked = extensionInstaller.get() == BasePreferences.ExtensionInstaller.LEGACY,
            switch = { isChecked, _ ->
                extensionInstaller.set(
                    if (isChecked) BasePreferences.ExtensionInstaller.LEGACY
                    else BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
                )
            }
        ),
        Settings(
            type = 2,
            name = getString(R.string.skip_loading_extension_icons),
            desc = getString(R.string.skip_loading_extension_icons_desc),
            icon = R.drawable.ic_round_no_icon_24,
            compact = true,
            anchorKey = "skip_extension_icons",
            isChecked = PrefManager.getVal(PrefName.SkipExtensionIcons),
            switch = { isChecked, _ -> PrefManager.setVal(PrefName.SkipExtensionIcons, isChecked) }
        ),
        Settings(
            type = 2,
            name = getString(R.string.NSFWExtention),
            desc = getString(R.string.NSFWExtention_desc),
            icon = R.drawable.ic_round_nsfw_24,
            compact = true,
            anchorKey = "nsfw_extensions",
            isChecked = PrefManager.getVal(PrefName.NSFWExtension),
            switch = { isChecked, _ -> PrefManager.setVal(PrefName.NSFWExtension, isChecked) }
        ),
    )

    // -----------------------------------------------------------------------------------------
    // Add-ons
    // -----------------------------------------------------------------------------------------

    private fun addonRows(): List<Settings> {
        val context = this
        return listOf(
        Settings(
            type = 1,
            // Add-on badged with what it adds: the bare download glyph is the app's general one,
            // on episode rows, notifications and the downloads screen.
            name = getString(R.string.anime_downloader_addon),
            desc = getString(R.string.not_installed),
            icon = R.drawable.ic_round_addon_download_24,
            compact = true,
            anchorKey = "downloader_addon",
            isActivity = true,
            attach = {
                setStatus(
                    it, context, downloadAddonManager.hadError(context),
                    downloadAddonManager.hasUpdate
                )
                downloadAddonManager.addListenerAction { _ ->
                    setStatus(it, context, downloadAddonManager.hadError(context), false)
                }
                it.settingsIconRight.setOnClickListener { _ ->
                    if (it.settingsDesc.text == getString(R.string.installed)) {
                        downloadAddonManager.uninstall()
                    } else {
                        it.settingsIconRight.setImageResource(R.drawable.ic_sync)
                        it.settingsIconRight.setSpinning(true)
                        snackString(getString(R.string.downloading))
                        lifecycleScope.launchIO {
                            AddonDownloader.update(
                                activity = context,
                                downloadAddonManager,
                                repo = DownloadAddonManager.REPO,
                                currentVersion = downloadAddonManager.getVersion() ?: ""
                            )
                        }
                    }
                }
            },
        ),
        Settings(
            type = 1,
            name = getString(R.string.torrent_addon),
            desc = getString(R.string.not_installed),
            icon = R.drawable.ic_round_magnet_24,
            compact = true,
            anchorKey = "torrent_addon",
            isActivity = true,
            attach = {
                setStatus(
                    it, context, torrentAddonManager.hadError(context),
                    torrentAddonManager.hasUpdate
                )
                torrentAddonManager.addListenerAction { _ ->
                    setStatus(it, context, torrentAddonManager.hadError(context), false)
                }
                it.settingsIconRight.setOnClickListener { _ ->
                    if (it.settingsDesc.text == getString(R.string.installed)) {
                        TorrentServerService.stop()
                        torrentAddonManager.uninstall()
                    } else {
                        it.settingsIconRight.setImageResource(R.drawable.ic_sync)
                        it.settingsIconRight.setSpinning(true)
                        snackString(getString(R.string.downloading))
                        lifecycleScope.launchIO {
                            AddonDownloader.update(
                                activity = context,
                                torrentAddonManager,
                                repo = TorrentAddonManager.REPO,
                                currentVersion = torrentAddonManager.getVersion() ?: "",
                            )
                        }
                    }
                }
            },
        ),
        Settings(
            type = 2,
            name = getString(R.string.enable_torrent),
            desc = getString(R.string.enable_torrent_desc),
            icon = R.drawable.ic_round_dns_24,
            compact = true,
            anchorKey = "enable_torrent",
            isChecked = PrefManager.getVal(PrefName.TorrentEnabled),
            switch = { isChecked, view ->
                if (isChecked && !torrentAddonManager.isAvailable(false)) {
                    snackString(getString(R.string.install_torrent_addon))
                    view.settingsButton.isChecked = false
                    PrefManager.setVal(PrefName.TorrentEnabled, false)
                    return@Settings
                }
                PrefManager.setVal(PrefName.TorrentEnabled, isChecked)
                Injekt.get<TorrentAddonManager>().extension?.let {
                    lifecycleScope.launchIO {
                        if (isChecked) {
                            if (!TorrentServerService.isRunning()) TorrentServerService.start()
                        } else {
                            if (TorrentServerService.isRunning()) TorrentServerService.stop()
                        }
                    }
                }
            },
            isVisible = torrentAddonManager.isAvailable(false)
        ),
    )
    }

    private fun setStatus(
        view: ItemSettingsBinding,
        context: Context,
        status: String?,
        hasUpdate: Boolean,
    ) {
        try {
            // Reaching a status means the install/update is over, whatever the outcome — so this is
            // also where the spinner an install started gets stopped.
            view.settingsIconRight.setSpinning(false)
            when (status) {
                context.getString(R.string.loaded_successfully) -> {
                    view.settingsIconRight.setImageResource(R.drawable.ic_round_delete_24)
                    view.settingsDesc.text = context.getString(R.string.installed)
                }

                null -> {
                    view.settingsIconRight.setImageResource(R.drawable.ic_download_24)
                    view.settingsDesc.text = context.getString(R.string.not_installed)
                }

                else -> {
                    view.settingsIconRight.setImageResource(R.drawable.ic_round_new_releases_24)
                    view.settingsDesc.text = context.getString(R.string.error_msg, status)
                }
            }
            if (hasUpdate) {
                view.settingsIconRight.setImageResource(R.drawable.ic_round_sync_24)
                view.settingsDesc.text = context.getString(R.string.update_addon)
            }
        } catch (e: Exception) {
            Logger.log(e)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Network
    // -----------------------------------------------------------------------------------------

    /** Matches the order of [PrefName.DohProvider], which stores an index into this list. */
    private val dnsProviders = listOf(
        "None", "Cloudflare", "Google", "AdGuard", "Quad9", "AliDNS", "DNSPod",
        "360", "Quad101", "Mullvad", "Controld", "Njalla", "Shecan", "Libre",
    )

    private fun networkRows(): List<Settings> {
        val context = this
        return listOf(
            Settings(
                type = 1,
                name = getString(R.string.user_agent),
                desc = getString(R.string.user_agent_desc),
                icon = R.drawable.ic_globe_24,
                compact = true,
                anchorKey = "user_agent",
                onClick = {
                    val dialogView = DialogUserAgentBinding.inflate(layoutInflater)
                    val editText = dialogView.userAgentTextBox
                    editText.setText(PrefManager.getVal<String>(PrefName.DefaultUserAgent))
                    context.customAlertDialog().apply {
                        setTitle(R.string.user_agent)
                        setCustomView(dialogView.root)
                        setPosButton(R.string.ok) {
                            PrefManager.setVal(PrefName.DefaultUserAgent, editText.text.toString())
                        }
                        setNeutralButton(R.string.reset) {
                            PrefManager.removeVal(PrefName.DefaultUserAgent)
                            editText.setText("")
                        }
                        setNegButton(R.string.cancel)
                    }.show()
                }
            ),
            // Was a dropdown in Common's XML header, three sections away from the proxy it belongs
            // beside. A dialog rather than a dropdown so it can live in a card, and because that is
            // how every other multi-choice setting in the app already asks.
            Settings(
                type = 1,
                name = getString(R.string.selected_dns_s, dnsProviders[PrefManager.getVal(PrefName.DohProvider)]),
                desc = getString(R.string.selected_dns_desc),
                icon = R.drawable.ic_round_dns_24,
                compact = true,
                anchorKey = "selected_dns",
                onClick = { row ->
                    context.customAlertDialog().apply {
                        setTitle(R.string.selected_dns)
                        singleChoiceItems(
                            dnsProviders.toTypedArray(),
                            PrefManager.getVal(PrefName.DohProvider)
                        ) { i ->
                            PrefManager.setVal(PrefName.DohProvider, i)
                            row.settingsTitle.text =
                                getString(R.string.selected_dns_s, dnsProviders[i])
                            restartKeepingSections()
                        }
                        show()
                    }
                }
            ),
            Settings(
                type = 2,
                name = getString(R.string.proxy),
                desc = getString(R.string.proxy_desc),
                icon = R.drawable.vpn_key_24,
                compact = true,
                anchorKey = "proxy",
                isChecked = PrefManager.getVal(PrefName.EnableSocks5Proxy),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.EnableSocks5Proxy, isChecked)
                    restartKeepingSections()
                }
            ),
            Settings(
                type = 1,
                name = getString(R.string.proxy_setup),
                desc = getString(R.string.proxy_setup_desc),
                icon = R.drawable.lan_24,
                compact = true,
                anchorKey = "proxy_setup",
                onClick = { ProxyDialogFragment().show(supportFragmentManager, "dialog") }
            ),
        )
    }

    /** [restartApp], keeping the groups the user has open — see [SettingsSectionAdapter.markRelaunch]. */
    private fun restartKeepingSections() {
        if (::sectionAdapter.isInitialized) sectionAdapter.markRelaunch()
        restartApp()
    }
}
