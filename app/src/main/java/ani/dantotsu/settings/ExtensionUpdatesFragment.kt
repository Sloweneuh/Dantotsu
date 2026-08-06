package ani.dantotsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentExtensionUpdatesBinding
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.subscriptions.CompositeSubscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Fragment that shows all extensions with available updates across all media types.
 */
class ExtensionUpdatesFragment : Fragment() {
    private var _binding: FragmentExtensionUpdatesBinding? = null
    private val binding get() = _binding!!

    private val skipIcons: Boolean = ani.dantotsu.settings.saving.PrefManager.getVal(ani.dantotsu.settings.saving.PrefName.SkipExtensionIcons)
    private val animeExtensionManager: AnimeExtensionManager = Injekt.get()
    private val mangaExtensionManager: MangaExtensionManager = Injekt.get()
    private val novelExtensionManager: NovelExtensionManager = Injekt.get()

    private val compositeSubscription = CompositeSubscription()

    private lateinit var adapter: UpdatesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtensionUpdatesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = UpdatesAdapter(
            onUpdateClick = { item -> updateExtension(item) },
            onUpdateAllClick = { items -> updateAllExtensions(items) },
            skipIcons = skipIcons
        )

        binding.updatesRecyclerView.adapter = adapter
        binding.updatesRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        binding.updateAllButton.setOnClickListener {
            val items = adapter.currentList
            if (items.isNotEmpty()) {
                updateAllExtensions(items)
            }
        }

        loadUpdates()
    }

    private fun loadUpdates() {
        lifecycleScope.launch {
            val updates = withContext(Dispatchers.Default) {
                // What each update would install, looked up from the repo listing by package. The
                // installed entry only knows that an update exists, not what version it is.
                val animeVersions = animeExtensionManager.availableExtensionsFlow.value
                    .associate { it.pkgName to it.versionName }
                val mangaVersions = mangaExtensionManager.availableExtensionsFlow.value
                    .associate { it.pkgName to it.versionName }
                val novelVersions = novelExtensionManager.availableExtensionsFlow.value
                    .associate { it.pkgName to it.versionName }

                val animeUpdates = animeExtensionManager.installedExtensionsFlow.value
                    .filter { it.hasUpdate }
                    .map { UpdateItem.AnimeUpdate(it, animeVersions[it.pkgName]) }

                val mangaUpdates = mangaExtensionManager.installedExtensionsFlow.value
                    .filter { it.hasUpdate }
                    .map { UpdateItem.MangaUpdate(it, mangaVersions[it.pkgName]) }

                val novelUpdates = novelExtensionManager.installedExtensionsFlow.value
                    .filter { it.hasUpdate }
                    .map { UpdateItem.NovelUpdate(it, novelVersions[it.pkgName]) }

                animeUpdates + mangaUpdates + novelUpdates
            }

            adapter.submitList(updates)
            binding.emptyView.isVisible = updates.isEmpty()
            binding.updatesRecyclerView.isVisible = updates.isNotEmpty()
            binding.updateAllButton.isVisible = updates.isNotEmpty()
        }
    }

    private fun updateObservable(item: UpdateItem) = when (item) {
        is UpdateItem.AnimeUpdate -> animeExtensionManager.updateExtension(item.extension)
        is UpdateItem.MangaUpdate -> mangaExtensionManager.updateExtension(item.extension)
        is UpdateItem.NovelUpdate -> novelExtensionManager.updateExtension(item.extension)
    }

    private fun updateExtension(item: UpdateItem) {
        var lastStep: InstallStep? = null
        adapter.setUpdating(item, true)
        updateObservable(item)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { step -> lastStep = step },
                { error ->
                    Logger.log(error)
                    adapter.setUpdating(item, false)
                    snackString(getString(R.string.update_failed, error.message))
                },
                {
                    adapter.setUpdating(item, false)
                    lastStep.updateResultMessage()?.let { snackString(getString(it)) }
                    loadUpdates() // Refresh the list
                }
            )
    }

    private fun updateAllExtensions(items: List<UpdateItem>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.update_all_extensions))
            .setMessage(getString(R.string.update_extensions_count, items.size))
            .setPositiveButton("Update") { _, _ ->
                // Each row spins itself as its turn comes; this shows the batch as a whole is running,
                // and stops in the terminal branch of updateExtensionsSequentially.
                binding.updateAllButton.setIconSpinning(true)
                updateExtensionsSequentially(items.toMutableList())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateExtensionsSequentially(items: MutableList<UpdateItem>) {
        if (items.isEmpty()) {
            // The batch is done: stop the button spinning (see updateAllExtensions).
            _binding?.updateAllButton?.setIconSpinning(false)
            snackString("All extensions updated")
            loadUpdates() // Final refresh
            (activity as? ExtensionsActivity)?.onExtensionUpdatesFinished()
            return
        }

        val item = items.removeAt(0)

        var lastStep: InstallStep? = null
        adapter.setUpdating(item, true)

        val subscription: Subscription = updateObservable(item)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { step -> lastStep = step },
                { error ->
                    Logger.log(error)
                    adapter.setUpdating(item, false)
                    snackString("${getString(R.string.update_failed_short)}: ${item.name} - ${error.message}")
                    // Continue with next extension even if one fails
                    updateExtensionsSequentially(items)
                },
                {
                    adapter.setUpdating(item, false)
                    lastStep.updateResultMessage()?.let {
                        snackString("${getString(it)}: ${item.name}")
                    }
                    loadUpdates() // Refresh list after each update
                    // Continue with next extension
                    updateExtensionsSequentially(items)
                }
            )

        // Add subscription to composite to prevent it from being garbage collected
        compositeSubscription.add(subscription)
    }

    override fun onDestroyView() {
        compositeSubscription.clear()
        super.onDestroyView()
        _binding = null
    }
}

sealed class UpdateItem {
    abstract val name: String

    /** The version installed right now. */
    abstract val versionName: String

    /**
     * The version this update would install, or null when the repo entry can't be matched.
     *
     * Nullable rather than defaulted because "we don't know yet" and "it's the same version" are
     * different things, and only the first should make the row fall back to showing one version.
     * The available list is fetched separately from the installed one, so a refresh that hasn't
     * landed — or an extension whose repo was removed — genuinely has no answer here.
     */
    abstract val newVersionName: String?

    abstract val type: String
    abstract val icon: android.graphics.drawable.Drawable?

    /** Where to look the new version up. */
    abstract val pkgName: String

    data class AnimeUpdate(
        val extension: AnimeExtension.Installed,
        override val newVersionName: String? = null,
    ) : UpdateItem() {
        override val name: String get() = extension.name
        override val versionName: String get() = extension.versionName
        override val type: String get() = "Anime"
        override val icon: android.graphics.drawable.Drawable? get() = extension.icon
        override val pkgName: String get() = extension.pkgName
    }

    data class MangaUpdate(
        val extension: MangaExtension.Installed,
        override val newVersionName: String? = null,
    ) : UpdateItem() {
        override val name: String get() = extension.name
        override val versionName: String get() = extension.versionName
        override val type: String get() = "Manga"
        override val icon: android.graphics.drawable.Drawable? get() = extension.icon
        override val pkgName: String get() = extension.pkgName
    }

    data class NovelUpdate(
        val extension: NovelExtension.Installed,
        override val newVersionName: String? = null,
    ) : UpdateItem() {
        override val name: String get() = extension.name
        override val versionName: String get() = extension.versionName
        override val type: String get() = "Novel"
        override val icon: android.graphics.drawable.Drawable? get() = extension.icon
        override val pkgName: String get() = extension.pkgName
    }
}

class UpdatesAdapter(
    private val onUpdateClick: (UpdateItem) -> Unit,
    private val onUpdateAllClick: (List<UpdateItem>) -> Unit,
    private val skipIcons: Boolean = false
) : ListAdapter<UpdateItem, UpdatesAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val updatingKeys = mutableSetOf<String>()

    private fun UpdateItem.key() = "$type::$name"

    fun setUpdating(item: UpdateItem, updating: Boolean) {
        if (updating) updatingKeys.add(item.key()) else updatingKeys.remove(item.key())
        val pos = currentList.indexOfFirst { it.key() == item.key() }
        if (pos != -1) notifyItemChanged(pos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_extension, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, onUpdateClick, skipIcons, updatingKeys.contains(item.key()))
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameTextView: TextView = view.findViewById(R.id.extensionNameTextView)
        private val versionTextView: TextView = view.findViewById(R.id.extensionVersionTextView)
        private val iconImageView: ImageView = view.findViewById(R.id.extensionIconImageView)
        private val updateButton: ImageView = view.findViewById(R.id.updateTextView)
        private val deleteButton: ImageView = view.findViewById(R.id.deleteTextView)
        private val settingsButton: ImageView = view.findViewById(R.id.settingsImageView)

        fun bind(item: UpdateItem, onUpdateClick: (UpdateItem) -> Unit, skipIcons: Boolean, isUpdating: Boolean) {
            nameTextView.text = item.name
            versionTextView.text = buildString {
                append(item.type)
                append(" • ")
                append(item.versionName)
                // Only when it's actually different: a repo that re-published the same version
                // number would otherwise render "1.4.5 → 1.4.5", which reads as a display bug.
                item.newVersionName?.takeIf { it != item.versionName }?.let {
                    append(" → ")
                    append(it)
                }
            }

            // Set extension icon if available and not skipped
            if (!skipIcons && item.icon != null) {
                iconImageView.setImageDrawable(item.icon)
                iconImageView.isVisible = true
            } else {
                iconImageView.isVisible = false
            }

            updateButton.isVisible = true
            updateButton.bindUpdateButton(isUpdating) { onUpdateClick(item) }

            deleteButton.isVisible = false
            settingsButton.isVisible = false
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<UpdateItem>() {
            override fun areItemsTheSame(oldItem: UpdateItem, newItem: UpdateItem): Boolean {
                return oldItem.name == newItem.name && oldItem.type == newItem.type
            }

            override fun areContentsTheSame(oldItem: UpdateItem, newItem: UpdateItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
