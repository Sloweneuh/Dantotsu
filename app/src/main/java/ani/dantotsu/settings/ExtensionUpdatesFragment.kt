package ani.dantotsu.settings

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
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
import eu.kanade.tachiyomi.data.notification.Notifications
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
                val animeUpdates = animeExtensionManager.installedExtensionsFlow.value
                    .filter { it.hasUpdate }
                    .map { UpdateItem.AnimeUpdate(it) }

                val mangaUpdates = mangaExtensionManager.installedExtensionsFlow.value
                    .filter { it.hasUpdate }
                    .map { UpdateItem.MangaUpdate(it) }

                val novelUpdates = novelExtensionManager.installedExtensionsFlow.value
                    .filter { it.hasUpdate }
                    .map { UpdateItem.NovelUpdate(it) }

                animeUpdates + mangaUpdates + novelUpdates
            }

            adapter.submitList(updates)
            binding.emptyView.isVisible = updates.isEmpty()
            binding.updatesRecyclerView.isVisible = updates.isNotEmpty()
            binding.updateAllButton.isVisible = updates.isNotEmpty()
        }
    }

    private fun updateExtension(item: UpdateItem) {
        val context = requireContext()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (item) {
            is UpdateItem.AnimeUpdate -> {
                animeExtensionManager.updateExtension(item.extension)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { showUpdateNotification(notificationManager, context, "Updating extension", "$it") },
                        { error ->
                            Logger.log(error)
                            showErrorNotification(notificationManager, context, error.message)
                            snackString("Update failed: ${error.message}")
                        },
                        {
                            showCompleteNotification(notificationManager, context)
                            snackString("Extension updated")
                            loadUpdates() // Refresh the list
                        }
                    )
            }
            is UpdateItem.MangaUpdate -> {
                mangaExtensionManager.updateExtension(item.extension)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { showUpdateNotification(notificationManager, context, "Updating extension", "$it") },
                        { error ->
                            Logger.log(error)
                            showErrorNotification(notificationManager, context, error.message)
                            snackString("Update failed: ${error.message}")
                        },
                        {
                            showCompleteNotification(notificationManager, context)
                            snackString("Extension updated")
                            loadUpdates() // Refresh the list
                        }
                    )
            }
            is UpdateItem.NovelUpdate -> {
                novelExtensionManager.updateExtension(item.extension)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { showUpdateNotification(notificationManager, context, "Updating extension", "$it") },
                        { error ->
                            Logger.log(error)
                            showErrorNotification(notificationManager, context, error.message)
                            snackString("Update failed: ${error.message}")
                        },
                        {
                            showCompleteNotification(notificationManager, context)
                            snackString("Extension updated")
                            loadUpdates() // Refresh the list
                        }
                    )
            }
        }
    }

    private fun updateAllExtensions(items: List<UpdateItem>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.update_all_extensions))
            .setMessage(getString(R.string.update_extensions_count, items.size))
            .setPositiveButton("Update") { _, _ ->
                updateExtensionsSequentially(items.toMutableList())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateExtensionsSequentially(items: MutableList<UpdateItem>) {
        if (items.isEmpty()) {
            snackString("All extensions updated")
            loadUpdates() // Final refresh
            return
        }

        val item = items.removeAt(0)
        val context = requireContext()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val subscription: Subscription = when (item) {
            is UpdateItem.AnimeUpdate -> {
                animeExtensionManager.updateExtension(item.extension)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { showUpdateNotification(notificationManager, context, "Updating extension", "$it") },
                        { error ->
                            Logger.log(error)
                            showErrorNotification(notificationManager, context, error.message)
                            snackString("Update failed: ${item.name} - ${error.message}")
                            // Continue with next extension even if one fails
                            updateExtensionsSequentially(items)
                        },
                        {
                            showCompleteNotification(notificationManager, context)
                            snackString("Updated: ${item.name}")
                            // Continue with next extension
                            updateExtensionsSequentially(items)
                        }
                    )
            }
            is UpdateItem.MangaUpdate -> {
                mangaExtensionManager.updateExtension(item.extension)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { showUpdateNotification(notificationManager, context, "Updating extension", "$it") },
                        { error ->
                            Logger.log(error)
                            showErrorNotification(notificationManager, context, error.message)
                            snackString("Update failed: ${item.name} - ${error.message}")
                            // Continue with next extension even if one fails
                            updateExtensionsSequentially(items)
                        },
                        {
                            showCompleteNotification(notificationManager, context)
                            snackString("Updated: ${item.name}")
                            // Continue with next extension
                            updateExtensionsSequentially(items)
                        }
                    )
            }
            is UpdateItem.NovelUpdate -> {
                novelExtensionManager.updateExtension(item.extension)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { showUpdateNotification(notificationManager, context, "Updating extension", "$it") },
                        { error ->
                            Logger.log(error)
                            showErrorNotification(notificationManager, context, error.message)
                            snackString("Update failed: ${item.name} - ${error.message}")
                            // Continue with next extension even if one fails
                            updateExtensionsSequentially(items)
                        },
                        {
                            showCompleteNotification(notificationManager, context)
                            snackString("Updated: ${item.name}")
                            // Continue with next extension
                            updateExtensionsSequentially(items)
                        }
                    )
            }
        }

        // Add subscription to composite to prevent it from being garbage collected
        compositeSubscription.add(subscription)
    }

    private fun showUpdateNotification(manager: NotificationManager, context: Context, title: String, text: String) {
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADER_PROGRESS)
            .setSmallIcon(R.drawable.ic_round_sync_24)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        manager.notify(1, builder.build())
    }

    private fun showErrorNotification(manager: NotificationManager, context: Context, message: String?) {
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADER_ERROR)
            .setSmallIcon(R.drawable.ic_round_info_24)
            .setContentTitle("Update failed")
            .setContentText("Error: $message")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        manager.notify(1, builder.build())
    }

    private fun showCompleteNotification(manager: NotificationManager, context: Context) {
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_DOWNLOADER_PROGRESS)
            .setSmallIcon(R.drawable.ic_circle_check)
            .setContentTitle("Update complete")
            .setContentText("The extension has been successfully updated.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
        manager.notify(1, builder.build())
    }

    override fun onDestroyView() {
        compositeSubscription.clear()
        super.onDestroyView()
        _binding = null
    }
}

sealed class UpdateItem {
    abstract val name: String
    abstract val versionName: String
    abstract val type: String
    abstract val icon: android.graphics.drawable.Drawable?

    data class AnimeUpdate(val extension: AnimeExtension.Installed) : UpdateItem() {
        override val name: String get() = extension.name
        override val versionName: String get() = extension.versionName
        override val type: String get() = "Anime"
        override val icon: android.graphics.drawable.Drawable? get() = extension.icon
    }

    data class MangaUpdate(val extension: MangaExtension.Installed) : UpdateItem() {
        override val name: String get() = extension.name
        override val versionName: String get() = extension.versionName
        override val type: String get() = "Manga"
        override val icon: android.graphics.drawable.Drawable? get() = extension.icon
    }

    data class NovelUpdate(val extension: NovelExtension.Installed) : UpdateItem() {
        override val name: String get() = extension.name
        override val versionName: String get() = extension.versionName
        override val type: String get() = "Novel"
        override val icon: android.graphics.drawable.Drawable? get() = extension.icon
    }
}

class UpdatesAdapter(
    private val onUpdateClick: (UpdateItem) -> Unit,
    private val onUpdateAllClick: (List<UpdateItem>) -> Unit,
    private val skipIcons: Boolean = false
) : ListAdapter<UpdateItem, UpdatesAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_extension, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, onUpdateClick, skipIcons)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameTextView: TextView = view.findViewById(R.id.extensionNameTextView)
        private val versionTextView: TextView = view.findViewById(R.id.extensionVersionTextView)
        private val iconImageView: ImageView = view.findViewById(R.id.extensionIconImageView)
        private val updateButton: ImageView = view.findViewById(R.id.updateTextView)
        private val deleteButton: ImageView = view.findViewById(R.id.deleteTextView)
        private val settingsButton: ImageView = view.findViewById(R.id.settingsImageView)

        fun bind(item: UpdateItem, onUpdateClick: (UpdateItem) -> Unit, skipIcons: Boolean) {
            nameTextView.text = item.name
            versionTextView.text = buildString {
                append(item.type)
                append(" • ")
                append(item.versionName)
            }

            // Set extension icon if available and not skipped
            if (!skipIcons && item.icon != null) {
                iconImageView.setImageDrawable(item.icon)
                iconImageView.isVisible = true
            } else {
                iconImageView.isVisible = false
            }

            updateButton.isVisible = true
            updateButton.setOnClickListener { onUpdateClick(item) }

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
