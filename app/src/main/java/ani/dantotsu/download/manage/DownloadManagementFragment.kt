package ani.dantotsu.download.manage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentDownloadManagementBinding
import ani.dantotsu.download.DownloadTracker
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.formatBytes
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadManagementFragment : Fragment() {
    private var _binding: FragmentDownloadManagementBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: DownloadManagementAdapter
    private val downloadsManager get() = Injekt.get<DownloadsManager>()

    // Only the very first load (nothing shown on screen yet) should show the loading spinner —
    // subsequent reloads (live refresh on download completion, onResume, after a delete) refresh
    // the already-visible list in place and shouldn't spin again.
    private var hasLoadedOnce = false

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
            onDeleteChild = { confirmDeleteChild(it) }
        )
        binding.downloadManageRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.downloadManageRecycler.adapter = adapter
        reload()

        // Refresh whenever a download starts or finishes (membership change, not each progress
        // tick), so newly completed downloads appear here while the screen is open.
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadTracker.items
                .map { list -> list.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect { reload() }
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
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val (groups, totals) = withContext(Dispatchers.IO) {
                try {
                    DownloadManageLoader.load(requireContext())
                } catch (e: Exception) {
                    Logger.log("Failed to load downloads: ${e.message}")
                    emptyList<DownloadMediaGroup>() to DownloadTotals(0, 0, 0)
                }
            }
            if (_binding == null) return@launch
            hasLoadedOnce = true
            binding.downloadManageProgressBar.visibility = View.GONE
            binding.downloadTotalSize.text =
                getString(R.string.download_total_size, formatBytes(totals.total))
            binding.downloadSubSize.text = getString(
                R.string.download_subsize,
                formatBytes(totals.manga),
                formatBytes(totals.anime),
                formatBytes(totals.novel)
            )
            adapter.submit(groups)
            binding.downloadManageEmpty.visibility =
                if (groups.isEmpty()) View.VISIBLE else View.GONE
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
}
