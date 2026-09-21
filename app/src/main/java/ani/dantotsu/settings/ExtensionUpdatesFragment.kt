package ani.dantotsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentExtensionUpdatesBinding
import ani.dantotsu.notifications.extension.RefusedExtensionUpdates
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment that shows all extensions with available updates across all media types.
 */
class ExtensionUpdatesFragment : Fragment() {
    private var _binding: FragmentExtensionUpdatesBinding? = null
    private val binding get() = _binding!!

    private val skipIcons: Boolean = ani.dantotsu.settings.saving.PrefManager.getVal(ani.dantotsu.settings.saving.PrefName.SkipExtensionIcons)


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
        viewLifecycleOwner.lifecycleScope.launch {
            val updates = withContext(Dispatchers.Default) {
                ExtensionUpdateRunner.pendingUpdates()
            }

            adapter.submitList(updates)
            binding.emptyView.isVisible = updates.isEmpty()
            binding.updatesRecyclerView.isVisible = updates.isNotEmpty()
            binding.updateAllButton.isVisible = updates.isNotEmpty()
        }
    }

    /** A single row's update button. */
    private fun updateExtension(item: UpdateItem) {
        runUpdates(listOf(item), announceBatch = false)
    }

    private fun updateAllExtensions(items: List<UpdateItem>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.update_all_extensions))
            .setMessage(getString(R.string.update_extensions_count, items.size))
            .setPositiveButton("Update") { _, _ ->
                // Each row spins itself as its turn comes; this shows the batch as a whole is
                // running, and stops when the flow ends.
                binding.updateAllButton.setIconSpinning(true)
                runUpdates(items, announceBatch = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Runs [items] through the shared runner, spinning each row as its turn comes.
     *
     * Attended: the user is on this screen, so an extension the system will not replace silently
     * gets its confirmation dialog here rather than being reported and skipped.
     *
     * Scoped to the view lifecycle, so leaving the screen stops the sequence — the installs already
     * handed to the service still finish, since that is a foreground service of its own.
     */
    private fun runUpdates(items: List<UpdateItem>, announceBatch: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            ExtensionUpdateRunner.run(items, unattended = false).collect { progress ->
                when (progress) {
                    is ExtensionUpdateRunner.Progress.Started ->
                        adapter.setUpdating(progress.item, true)

                    is ExtensionUpdateRunner.Progress.Finished -> {
                        adapter.setUpdating(progress.item, false)
                        progress.step.updateResultMessage()?.let {
                            snackString("${getString(it)}: ${progress.item.name}")
                        }
                        loadUpdates()
                    }

                    is ExtensionUpdateRunner.Progress.Failed -> {
                        Logger.log(progress.error)
                        adapter.setUpdating(progress.item, false)
                        snackString(
                            "${getString(R.string.update_failed_short)}: " +
                                "${progress.item.name} - ${progress.error.message}"
                        )
                    }
                }
            }

            if (announceBatch) {
                _binding?.updateAllButton?.setIconSpinning(false)
                snackString(getString(R.string.all_extensions_updated))
                (activity as? ExtensionsActivity)?.onExtensionUpdatesFinished()
            }
            loadUpdates()
        }
    }

    override fun onDestroyView() {
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
        private val defaultVersionTextColor: Int = versionTextView.currentTextColor
        private val iconImageView: ImageView = view.findViewById(R.id.extensionIconImageView)
        private val updateButton: ImageView = view.findViewById(R.id.updateTextView)
        private val deleteButton: ImageView = view.findViewById(R.id.deleteTextView)
        private val settingsButton: ImageView = view.findViewById(R.id.settingsImageView)

        fun bind(item: UpdateItem, onUpdateClick: (UpdateItem) -> Unit, skipIcons: Boolean, isUpdating: Boolean) {
            nameTextView.text = item.name
            val versionText = buildString {
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
            // A scheduled run already tried this exact version unattended and the system refused
            // it — the system will not silently replace a package this build is not the installer
            // of record for. Marked here so that stays visible after the one-off notification
            // about it is gone; tapping Update below is attended and works regardless.
            if (RefusedExtensionUpdates.isRefused(item)) {
                val needsConfirmationLabel = itemView.context.getString(R.string.update_needs_confirmation)
                versionTextView.text = "$versionText • $needsConfirmationLabel"
                versionTextView.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.warning)
                )
            } else {
                versionTextView.text = versionText
                versionTextView.setTextColor(defaultVersionTextColor)
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
