package ani.dantotsu.download.manage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentDownloadManagementBinding
import ani.dantotsu.download.DownloadActivity
import ani.dantotsu.download.DownloadTracker
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.OfflineMediaLoader
import ani.dantotsu.formatBytes
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.openInFileManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.anggrayudi.storage.file.getAbsolutePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Serializable

class DownloadManagementFragment : Fragment() {
    private var _binding: FragmentDownloadManagementBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: DownloadManagementAdapter
    private val downloadsManager get() = Injekt.get<DownloadsManager>()

    // Only the very first load (nothing shown on screen yet) should show the loading spinner —
    // subsequent reloads (live refresh on download completion, onResume, after a delete) refresh
    // the already-visible list in place and shouldn't spin again.
    private var hasLoadedOnce = false

    // Reloads can overlap (onResume, a finished download, a delete). Only the newest one is
    // allowed to write to the views, so a slow size pass cannot land on top of a newer list.
    private var loadToken = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = DownloadManagementAdapter(
            onDeleteMedia = { confirmDeleteMedia(it) },
            onDeleteChild = { confirmDeleteChild(it) },
            onOpenMediaFolder = { openMediaFolder(it) },
            onOpenChildFolder = { openChildFolder(it) },
            onOpenMedia = { openMedia(it) },
        )
        binding.downloadManageRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.downloadManageRecycler.adapter = adapter
        binding.downloadLocationRow.setOnClickListener { changeDownloadLocation() }
        binding.downloadLocationOpen.setOnClickListener { openDownloadLocation() }
        reload()

        // Refresh whenever a download starts or finishes (membership change, not each progress
        // tick), so newly completed downloads appear here while the screen is open.
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadTracker.items
                .map { list -> list.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect { reload() }
        }

        // Refresh after a bulk purge, which can happen from the download settings dialog while
        // this tab is the one currently on screen.
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadsManager.libraryChanges.collect { reload() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) reload()
    }

    private fun reload() {
        val isInitialLoad = !hasLoadedOnce
        if (isInitialLoad) {
            binding.downloadManageProgressBar.visibility = View.VISIBLE
            binding.downloadManageEmpty.visibility = View.GONE
            binding.downloadLocationRow.visibility = View.GONE
            binding.downloadTotalSize.text =
                getString(R.string.download_total_size, SIZE_PENDING)
            binding.downloadSubSize.text = getString(
                R.string.download_subsize, SIZE_PENDING, SIZE_PENDING, SIZE_PENDING
            )
        }
        val token = ++loadToken
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            val groups = withContext(Dispatchers.IO) {
                try {
                    DownloadManageLoader.load(context)
                } catch (e: Exception) {
                    Logger.log("Failed to load downloads: ${e.message}")
                    emptyList()
                }
            }
            val locationPath = withContext(Dispatchers.IO) { downloadLocationText(context) }
            if (_binding == null || token != loadToken) return@launch
            hasLoadedOnce = true
            binding.downloadManageProgressBar.visibility = View.GONE
            binding.downloadLocationRow.visibility = View.VISIBLE
            binding.downloadLocationPath.text = locationPath
            adapter.submit(groups)
            binding.downloadManageEmpty.visibility =
                if (groups.isEmpty()) View.VISIBLE else View.GONE

            // Sizes are measured separately: each one is a recursive walk of a chapter folder
            // over SAF, which is slow enough that waiting for all of them used to hold the whole
            // list back. The rows are already on screen by now and fill their sizes in here.
            val (sized, totals) = withContext(Dispatchers.IO) {
                try {
                    DownloadManageLoader.loadSizes(context, groups)
                } catch (e: Exception) {
                    Logger.log("Failed to measure downloads: ${e.message}")
                    groups to DownloadTotals(0, 0, 0)
                }
            }
            if (_binding == null || token != loadToken) return@launch
            binding.downloadTotalSize.text =
                getString(R.string.download_total_size, formatBytes(totals.total))
            binding.downloadSubSize.text = getString(
                R.string.download_subsize,
                formatBytes(totals.manga),
                formatBytes(totals.anime),
                formatBytes(totals.novel)
            )
            adapter.updateSizes(sized)
        }
    }

    private fun downloadLocationText(context: Context): String {
        val root = DownloadsManager.getDownloadsRootDirectory(context)
        val path = root?.getAbsolutePath(context)?.takeIf { it.isNotBlank() }
        return path ?: getString(R.string.dir_access_msg)
    }

    private fun changeDownloadLocation() {
        val activity = requireActivity() as DownloadActivity
        requireContext().customAlertDialog().apply {
            setTitle(R.string.change_download_location)
            setMessage(R.string.download_location_msg)
            setPosButton(R.string.ok) {
                val oldUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
                activity.launcher.registerForCallback { success ->
                    if (success) {
                        toast(getString(R.string.please_wait))
                        val newUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
                        downloadsManager.moveDownloadsDir(
                            activity,
                            Uri.parse(oldUri),
                            Uri.parse(newUri),
                        ) { finished, message ->
                            if (finished) {
                                toast(getString(R.string.success))
                                if (_binding != null) reload()
                            } else {
                                toast(message)
                            }
                        }
                    } else {
                        toast(getString(R.string.error))
                    }
                }
                activity.launcher.launch()
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private fun openDownloadLocation() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            val root = withContext(Dispatchers.IO) {
                DownloadsManager.getDownloadsRootDirectory(context)
            }
            if (root == null) {
                toast(getString(R.string.dir_access_msg))
                return@launch
            }
            openInFileManager(context, root.uri)
        }
    }

    /**
     * Opens the media itself from its cover, showing the downloaded copy: everything the details
     * screen needs is in the title's own `media.json`, so this works with no network.
     */
    private fun openMedia(group: DownloadMediaGroup) {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            val media = withContext(Dispatchers.IO) {
                OfflineMediaLoader.loadMedia(context, group.type, group.titleName)
            }
            if (media == null) {
                toast(getString(R.string.error))
                return@launch
            }
            ContextCompat.startActivity(
                requireActivity(),
                Intent(context, MediaDetailsActivity::class.java)
                    .putExtra("media", media as Serializable)
                    .putExtra("download", true),
                null
            )
        }
    }

    private fun openMediaFolder(group: DownloadMediaGroup) {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            val folder = withContext(Dispatchers.IO) {
                DownloadsManager.getSubDirectory(context, group.type, false, group.titleName)
            }
            if (folder == null) {
                toast(getString(R.string.dir_error))
                return@launch
            }
            openInFileManager(context, folder.uri)
        }
    }

    private fun openChildFolder(child: DownloadChild) {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            val folder = withContext(Dispatchers.IO) {
                DownloadsManager.getSubDirectory(
                    context, child.type, false, child.titleName, child.chapterName
                )
            }
            if (folder == null) {
                toast(getString(R.string.dir_error))
                return@launch
            }
            openInFileManager(context, folder.uri)
        }
    }

    private fun confirmDeleteMedia(group: DownloadMediaGroup) {
        requireContext().customAlertDialog().apply {
            setTitle(getString(R.string.delete_item, group.title))
            setMessage(getString(R.string.are_you_sure_delete_item, group.title))
            setPosButton(R.string.yes) {
                downloadsManager.removeMedia(group.titleName, group.type)
                reload()
            }
            setNegButton(R.string.no)
        }.show()
    }

    private fun confirmDeleteChild(child: DownloadChild) {
        requireContext().customAlertDialog().apply {
            setTitle(getString(R.string.delete))
            setMessage(getString(R.string.are_you_sure_delete_item, child.chapterName))
            setPosButton(R.string.delete) {
                downloadsManager.removeDownload(
                    DownloadedType(
                        child.titleName,
                        child.chapterName,
                        child.type,
                        scanlator = child.scanlator
                    )
                ) { reload() }
            }
            setNegButton(R.string.cancel)
        }.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Stands in for a size that has not been measured yet. */
        private const val SIZE_PENDING = "…"
    }
}
