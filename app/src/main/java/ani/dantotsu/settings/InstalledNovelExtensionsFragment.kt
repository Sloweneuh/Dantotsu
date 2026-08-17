package ani.dantotsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.databinding.FragmentNovelExtensionsBinding
import ani.dantotsu.loadImage
import ani.dantotsu.parsers.NovelSources
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.parsers.novel.lnreader.LNReaderPluginManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import ani.dantotsu.util.hideEmptyState
import eu.kanade.tachiyomi.extension.InstallStep
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * Installed novel sources: extension APKs and LNReader plugins in one list.
 *
 * They are shown together because a reader picking a source does not care which kind it is, and
 * both are ordered by the same [PrefName.NovelSourcesOrder] preference. Everything that acts on a
 * row — update, uninstall — branches, since the two have nothing in common underneath.
 */
class InstalledNovelExtensionsFragment : Fragment(), SearchQueryHandler {
    private var _binding: FragmentNovelExtensionsBinding? = null
    private val binding get() = _binding!!
    private lateinit var extensionsRecyclerView: RecyclerView
    private val skipIcons: Boolean = PrefManager.getVal(PrefName.SkipExtensionIcons)
    private val novelExtensionManager: NovelExtensionManager = Injekt.get()
    private val pluginManager: LNReaderPluginManager = Injekt.get()

    private var searchQuery = ""
    private val uninstallConfirmation = UninstallConfirmation {
        snackString(getString(R.string.extension_uninstalled))
    }

    private val extensionsAdapter: NovelSourcesAdapter = NovelSourcesAdapter(
        onItemClicked = { item ->
            // Only plugins can be browsed: the browse screen's novel path runs a plugin's popular
            // and search listings, which an extension package has no equivalent of.
            if (item is NovelSourceItem.Plugin) {
                startActivity(
                    android.content.Intent(requireContext(), ExtensionBrowseActivity::class.java)
                        .putExtra(ExtensionBrowseActivity.EXTRA_PKG, item.plugin.id)
                        .putExtra(
                            ExtensionBrowseActivity.EXTRA_TYPE,
                            ExtensionBrowseActivity.TYPE_NOVEL
                        )
                )
            }
        },
        onSettingsClicked = {
            Toast.makeText(requireContext(), "Source is not configurable", Toast.LENGTH_SHORT)
                .show()
        },
        onUninstallClicked = { item ->
            if (!isAdded) return@NovelSourcesAdapter
            when (item) {
                is NovelSourceItem.Extension -> {
                    uninstallConfirmation.onUninstallRequested(item.extension.pkgName)
                    novelExtensionManager.uninstallExtension(item.extension.pkgName)
                }
                // A plugin is a downloaded file, so removal is immediate and needs its own
                // confirmation rather than the package manager's.
                is NovelSourceItem.Plugin -> requireContext().customAlertDialog().apply {
                    setTitle(getString(R.string.delete_item, item.name))
                    setMessage(getString(R.string.are_you_sure_delete_item, item.name))
                    setPosButton(R.string.yes) {
                        pluginManager.uninstall(item.plugin.id)
                        snackString(getString(R.string.extension_uninstalled))
                    }
                    setNegButton(R.string.no)
                    show()
                }
            }
        },
        onUpdateClicked = { item ->
            if (!isAdded) return@NovelSourcesAdapter
            if (!item.hasUpdate) {
                snackString(getString(R.string.no_update_available))
                return@NovelSourcesAdapter
            }
            when (item) {
                is NovelSourceItem.Extension -> {
                    var lastStep: InstallStep? = null
                    extensionsAdapter.setUpdating(item.key, true)
                    novelExtensionManager.updateExtension(item.extension)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                            { step -> lastStep = step },
                            { error ->
                                Injekt.get<CrashlyticsInterface>().logException(error)
                                Logger.log(error)
                                extensionsAdapter.setUpdating(item.key, false)
                                snackString(getString(R.string.update_failed, error.message))
                            },
                            {
                                extensionsAdapter.setUpdating(item.key, false)
                                lastStep.updateResultMessage()?.let { snackString(getString(it)) }
                            }
                        )
                }

                is NovelSourceItem.Plugin -> lifecycleScope.launch {
                    extensionsAdapter.setUpdating(item.key, true)
                    pluginManager.update(item.plugin)
                        .onSuccess { snackString(getString(R.string.extension_installed)) }
                        .onFailure {
                            snackString(getString(R.string.update_failed, it.message.orEmpty()))
                        }
                    extensionsAdapter.setUpdating(item.key, false)
                }
            }
        },
        skipIcons = skipIcons,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNovelExtensionsBinding.inflate(inflater, container, false)

        extensionsRecyclerView = binding.allNovelExtensionsRecyclerView
        extensionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        extensionsRecyclerView.adapter = extensionsAdapter
        extensionsAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateEmptyState()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = updateEmptyState()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = updateEmptyState()
        })

        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.absoluteAdapterPosition
                val toPosition = target.absoluteAdapterPosition
                val newList = extensionsAdapter.currentList.toMutableList().apply {
                    add(toPosition, removeAt(fromPosition))
                }
                extensionsAdapter.submitList(newList)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.elevation = 8f
                    viewHolder?.itemView?.translationZ = 8f
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                extensionsAdapter.updatePref()
                viewHolder.itemView.elevation = 0f
                viewHolder.itemView.translationZ = 0f
            }
        }
        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(extensionsRecyclerView)

        lifecycleScope.launch {
            combine(
                novelExtensionManager.installedExtensionsFlow,
                pluginManager.installedPluginsFlow,
            ) { extensions, plugins -> extensions to plugins }
                .collect { (extensions, plugins) ->
                    uninstallConfirmation.onInstalledPackagesChanged(extensions.map { it.pkgName })
                    if (isResumed) uninstallConfirmation.flush()
                    extensionsAdapter.updateData(sortToNovelSourcesList(combined(extensions, plugins)))
                }
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // A confirmed uninstall usually lands while the system dialog is still in front.
        uninstallConfirmation.flush()
    }

    private fun combined(
        extensions: List<ani.dantotsu.parsers.novel.NovelExtension.Installed>,
        plugins: List<ani.dantotsu.parsers.novel.lnreader.InstalledLNReaderPlugin>,
    ): List<NovelSourceItem> =
        extensions.map { NovelSourceItem.Extension(it) } +
            plugins.map { NovelSourceItem.Plugin(it) }

    private fun updateEmptyState() {
        val b = _binding ?: return
        if (extensionsAdapter.itemCount == 0) {
            // Installed sources are never filtered by language, only by the search box.
            b.extensionsEmptyState.showExtensionsEmpty(
                filtered = searchQuery.isNotEmpty(),
                installed = true,
            )
        } else b.extensionsEmptyState.hideEmptyState()
    }

    private fun sortToNovelSourcesList(input: List<NovelSourceItem>): List<NovelSourceItem> {
        val sourcesMap = input.associateBy { it.name }
        val orderedSources = NovelSources.pinnedNovelSources.mapNotNull { sourcesMap[it] }
        return orderedSources + input.filter { !NovelSources.pinnedNovelSources.contains(it.name) }
    }

    override fun onDestroyView() {
        super.onDestroyView();_binding = null
    }

    override fun updateContentBasedOnQuery(query: String?) {
        // Kept so the empty state can tell "nothing matches what you typed" from "there is nothing".
        searchQuery = query.orEmpty()
        extensionsAdapter.filter(
            searchQuery,
            sortToNovelSourcesList(
                combined(
                    novelExtensionManager.installedExtensionsFlow.value,
                    pluginManager.installedPluginsFlow.value,
                )
            )
        )
    }

    override fun notifyDataChanged() { // Do nothing
    }

    private class NovelSourcesAdapter(
        private val onItemClicked: (NovelSourceItem) -> Unit,
        private val onSettingsClicked: (NovelSourceItem) -> Unit,
        private val onUninstallClicked: (NovelSourceItem) -> Unit,
        private val onUpdateClicked: (NovelSourceItem) -> Unit,
        val skipIcons: Boolean
    ) : ListAdapter<NovelSourceItem, NovelSourcesAdapter.ViewHolder>(DIFF_CALLBACK_INSTALLED) {

        private val updatingKeys = mutableSetOf<String>()

        fun updateData(newItems: List<NovelSourceItem>) {
            submitList(newItems)
        }

        /** Spins the row's update button for as long as the update is in flight. */
        fun setUpdating(key: String, updating: Boolean) {
            if (updating) updatingKeys += key else updatingKeys -= key
            val pos = currentList.indexOfFirst { it.key == key }
            if (pos != -1) notifyItemChanged(pos)
        }

        fun updatePref() {
            val map = currentList.map { it.name }
            PrefManager.setVal(PrefName.NovelSourcesOrder, map)
            NovelSources.pinnedNovelSources = map
            NovelSources.performReorderNovelSources()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_extension, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            holder.extensionNameTextView.text = item.name
            holder.extensionVersionTextView.text = item.versionLabel
            if (!skipIcons) {
                when (item) {
                    is NovelSourceItem.Extension ->
                        holder.extensionIconImageView.setImageDrawable(item.icon)
                    is NovelSourceItem.Plugin ->
                        item.iconUrl?.let { holder.extensionIconImageView.loadImage(it) }
                }
            }
            holder.updateView.isVisible = item.hasUpdate
            holder.deleteView.setOnClickListener { onUninstallClicked(item) }
            holder.updateView.bindUpdateButton(item.key in updatingKeys) { onUpdateClicked(item) }
            holder.settingsImageView.setOnClickListener { onSettingsClicked(item) }
            holder.itemView.setOnClickListener { onItemClicked(item) }
        }

        fun filter(query: String, currentList: List<NovelSourceItem>) {
            val filtered = currentList.filter {
                it.name.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT))
            }
            if (filtered != currentList) submitList(filtered)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val extensionNameTextView: TextView = view.findViewById(R.id.extensionNameTextView)
            val extensionVersionTextView: TextView =
                view.findViewById(R.id.extensionVersionTextView)
            val settingsImageView: ImageView = view.findViewById(R.id.settingsImageView)
            val extensionIconImageView: ImageView = view.findViewById(R.id.extensionIconImageView)
            val deleteView: ImageView = view.findViewById(R.id.deleteTextView)
            val updateView: ImageView = view.findViewById(R.id.updateTextView)
        }

        companion object {
            val DIFF_CALLBACK_INSTALLED = object : DiffUtil.ItemCallback<NovelSourceItem>() {
                override fun areItemsTheSame(a: NovelSourceItem, b: NovelSourceItem) = a.key == b.key
                override fun areContentsTheSame(a: NovelSourceItem, b: NovelSourceItem) = a == b
            }
        }
    }
}
