package ani.dantotsu.settings

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.paging.LoadState
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.loadImage
import ani.dantotsu.parsers.novel.lnreader.LNReaderPlugin
import ani.dantotsu.parsers.novel.lnreader.LNReaderPluginManager
import kotlinx.coroutines.flow.combine
import java.util.Locale
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.databinding.FragmentNovelExtensionsBinding
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.settings.paging.NovelExtensionAdapter
import ani.dantotsu.settings.paging.NovelExtensionsViewModel
import ani.dantotsu.settings.paging.NovelExtensionsViewModelFactory
import ani.dantotsu.settings.paging.OnNovelInstallClickListener
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.hideEmptyState
import ani.dantotsu.util.showError
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.InstallStep
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelExtensionsFragment : Fragment(),
    SearchQueryHandler, OnNovelInstallClickListener {
    private var _binding: FragmentNovelExtensionsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NovelExtensionsViewModel by viewModels {
        NovelExtensionsViewModelFactory(novelExtensionManager)
    }

    private val adapter by lazy {
        NovelExtensionAdapter(this)
    }

    private val novelExtensionManager: NovelExtensionManager = Injekt.get()
    private val pluginManager: LNReaderPluginManager = Injekt.get()

    /**
     * LNReader plugins are concatenated ahead of the extension list rather than merged into it.
     *
     * The extensions arrive through a Paging source; a repository index is one JSON file of a few
     * hundred entries already in memory. Forcing the second into the first would mean a paging
     * source that pages nothing, so the two adapters simply sit end to end.
     */
    private val pluginAdapter by lazy { AvailablePluginAdapter(::installPlugin) }

    private var searchQuery = ""

    /** Latest paging refresh state, kept so the empty state can be recomputed on plugin changes. */
    private var lastRefreshState: LoadState = LoadState.Loading

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNovelExtensionsBinding.inflate(inflater, container, false)

        binding.allNovelExtensionsRecyclerView.isNestedScrollingEnabled = false
        binding.allNovelExtensionsRecyclerView.adapter = ConcatAdapter(pluginAdapter, adapter)
        binding.allNovelExtensionsRecyclerView.layoutManager = LinearLayoutManager(context)
        (binding.allNovelExtensionsRecyclerView.layoutManager as LinearLayoutManager).isItemPrefetchEnabled =
            true

        lifecycleScope.launch {
            viewModel.pagerFlow.collectLatest { pagingData ->
                adapter.submitData(pagingData)
            }
        }

        lifecycleScope.launch {
            combine(
                pluginManager.availablePluginsFlow,
                pluginManager.installedPluginsFlow,
            ) { available, _ -> available }.collect { renderPlugins() }
        }

        if (pluginManager.availablePluginsFlow.value.isEmpty()) {
            lifecycleScope.launch { pluginManager.findAvailablePlugins() }
        }

        lifecycleScope.launch {
            adapter.loadStateFlow.collectLatest { loadStates ->
                lastRefreshState = loadStates.refresh
                updateEmptyState()
            }
        }
        return binding.root
    }

    override fun updateContentBasedOnQuery(query: String?) {
        // Kept so the empty state can tell "nothing matches what you typed" from "there is nothing".
        searchQuery = query.orEmpty()
        viewModel.setSearchQuery(searchQuery)
        renderPlugins()
    }

    private fun renderPlugins() {
        if (_binding == null) return
        val query = searchQuery.lowercase(Locale.ROOT)
        // The picker stores an ISO code, and a plugin names its language in full ("English"), so
        // the two only meet through the mapper. Compared directly, every plugin failed the test
        // and the filter looked like it did nothing at all.
        val lang: String = PrefManager.getVal(PrefName.LangSort)
        // Installed plugins drop off this list entirely, the way the extension pager already
        // filters out what is installed — leaving them here with the install action hidden reads
        // as a row that does nothing.
        pluginAdapter.submit(
            pluginManager.availablePluginsFlow.value
                .filterNot { pluginManager.isInstalled(it.id) }
                .filter { query.isEmpty() || it.name.lowercase(Locale.ROOT).contains(query) }
                .filter { lang == "all" || LanguageMapper.getLanguageCode(it.lang) == lang }
        )
        updateEmptyState()
    }

    /**
     * The placeholder covers the whole list, so it has to account for both halves of it.
     *
     * The paged extension list reports its own load state, but plugins come from a separate
     * adapter it knows nothing about — checking only the paged count put "nothing here" over a
     * screen full of plugins, which is the common case now that no novel extensions exist.
     */
    private fun updateEmptyState() {
        val b = _binding ?: return
        val refresh = lastRefreshState
        val total = adapter.itemCount + pluginAdapter.itemCount
        when {
            refresh is LoadState.Error && total == 0 -> b.extensionsEmptyState.showError(refresh.error)
            refresh is LoadState.NotLoading && total == 0 ->
                b.extensionsEmptyState.showExtensionsEmpty(
                    filtered = searchQuery.isNotEmpty() || isLanguageFiltered(),
                    installed = false,
                    repoPref = PrefName.NovelExtensionRepos,
                )
            else -> b.extensionsEmptyState.hideEmptyState()
        }
    }

    private fun installPlugin(plugin: LNReaderPlugin) {
        snackString(getString(R.string.installing_extension_text))
        lifecycleScope.launch {
            pluginManager.install(plugin)
                .onSuccess { snackString(getString(R.string.extension_installed)) }
                .onFailure {
                    snackString(getString(R.string.installation_failed, it.message.orEmpty()))
                }
        }
    }

    /** Rows for LNReader plugins, shown above the paged extension list. */
    private class AvailablePluginAdapter(
        private val onInstall: (LNReaderPlugin) -> Unit,
    ) : RecyclerView.Adapter<AvailablePluginAdapter.Holder>() {

        private val items = mutableListOf<LNReaderPlugin>()

        fun submit(new: List<LNReaderPlugin>) {
            items.clear()
            items.addAll(new)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_extension_all, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val plugin = items[position]
            holder.name.text = plugin.name
            holder.version.text = "${plugin.lang} ${plugin.version}"
            plugin.iconUrl?.let { holder.icon.loadImage(it) }

            holder.action.isVisible = true
            holder.action.setImageResource(R.drawable.ic_download_24)
            holder.action.setOnClickListener { onInstall(plugin) }
            holder.itemView.setOnClickListener { onInstall(plugin) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.extensionNameTextView)
            val version: TextView = view.findViewById(R.id.extensionVersionTextView)
            val icon: ImageView = view.findViewById(R.id.extensionIconImageView)
            val action: ImageView = view.findViewById(R.id.closeTextView)
        }
    }

    override fun notifyDataChanged() {
        // Both halves of the list, not just the paged one: the language picker calls this and the
        // plugins live in their own adapter, which is why changing the language appeared to do
        // nothing to them.
        viewModel.invalidatePager()
        renderPlugins()
    }

    override fun onInstallClick(pkg: NovelExtension.Available) {
        if (isAdded) {  // Check if the fragment is currently added to its activity
            val context = requireContext()
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Completion alone doesn't mean success: the stream also ends on cancellation (Idle)
            // and failure (Error), so remember the terminal step.
            var lastStep: InstallStep? = null

            // Start the installation process
            novelExtensionManager.installExtension(pkg)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { installStep ->
                        lastStep = installStep
                        val builder = NotificationCompat.Builder(
                            context,
                            Notifications.CHANNEL_DOWNLOADER_PROGRESS
                        )
                            .setSmallIcon(R.drawable.ic_round_sync_24)
                            .setContentTitle(getString(R.string.installing_extension_text))
                            .setContentText(getString(R.string.install_step, installStep))
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                        notificationManager.notify(1, builder.build())
                    },
                    { error ->
                        Injekt.get<CrashlyticsInterface>().logException(error)
                        val builder = NotificationCompat.Builder(
                            context,
                            Notifications.CHANNEL_DOWNLOADER_ERROR
                        )
                            .setSmallIcon(R.drawable.ic_round_info_24)
                            .setContentTitle(getString(R.string.installation_failed, error.message))
                            .setContentText(getString(R.string.error_message, error.message))
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                        notificationManager.notify(1, builder.build())
                        snackString(getString(R.string.installation_failed, error.message))
                    },
                    {
                        notificationManager.cancel(1)
                        viewModel.invalidatePager()
                        when (lastStep) {
                            InstallStep.Installed ->
                                snackString(getString(R.string.extension_installed))

                            InstallStep.Idle ->
                                snackString(getString(R.string.installation_cancelled))

                            InstallStep.Error ->
                                snackString(getString(R.string.installation_failed_short))

                            else -> {}
                        }
                    }
                )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView();_binding = null
    }


}