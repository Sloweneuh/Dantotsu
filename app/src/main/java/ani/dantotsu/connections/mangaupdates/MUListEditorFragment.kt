package ani.dantotsu.connections.mangaupdates

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.databinding.BottomSheetMediaListBinding
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.getSerialized
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom-sheet list editor for MangaUpdates series.
 * Mirrors [ani.dantotsu.media.MediaListDialogFragment] but only handles status + chapter progress.
 */
class MUListEditorFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMediaListBinding? = null
    private val binding get() = _binding!!
    private val model: MediaDetailsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMediaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.mediaListContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }

        val muMedia: MUMedia = arguments?.getSerialized("muMedia") ?: run {
            dismissAllowingStateLoss()
            return
        }

        val scope = viewLifecycleOwner.lifecycleScope

        // Hide Anilist-only fields
        binding.mediaListScoreLayout.visibility = View.GONE
        binding.mediaListStartLayout.visibility = View.GONE
        binding.mediaListEndLayout.visibility = View.GONE
        binding.mediaListNotes.visibility = View.GONE
        binding.mediaListPrivate.visibility = View.GONE
        binding.mediaListShow.visibility = View.GONE
        binding.mediaListRewatch.visibility = View.GONE
        binding.mediaListMalSyncExclude.visibility = View.GONE
        binding.mediaListAddCustomList?.visibility = View.GONE
        binding.mediaListExpandable?.visibility = View.GONE

        binding.mediaListProgressBar.visibility = View.GONE
        binding.mediaListLayout.visibility = View.VISIBLE

        // Status dropdown using MU list names
        val statusNames =
            listOf(
                getString(R.string.mu_status_reading),
                getString(R.string.mu_status_planning),
                getString(R.string.mu_status_completed),
                getString(R.string.mu_status_dropped),
                getString(R.string.mu_status_paused),
            )

        // Append any non-excluded custom lists so the user can move a series into them
        val customListExtras: List<Pair<Int, String>> = run {
            val mappingJson = PrefManager.getVal<String>(PrefName.MuCustomListMapping)
            val titlesJson = PrefManager.getVal<String>(PrefName.MuCustomListTitles)
            if (mappingJson.isBlank() || titlesJson.isBlank()) return@run emptyList()
            try {
                val mapping = ani.dantotsu.Mapper.json.decodeFromString<Map<String, String>>(mappingJson)
                val titles = ani.dantotsu.Mapper.json.decodeFromString<Map<String, String>>(titlesJson)
                mapping.keys.mapNotNull { listIdStr ->
                    val listId = listIdStr.toIntOrNull() ?: return@mapNotNull null
                    val title = titles[listIdStr] ?: return@mapNotNull null
                    listId to title
                }
            } catch (_: Exception) { emptyList() }
        }
        val allStatusNames = statusNames + customListExtras.map { it.second }

        val initialStatusIndex = if (muMedia.listId in 0..4) {
            muMedia.listId
        } else {
            val customIdx = customListExtras.indexOfFirst { it.first == muMedia.listId }
            if (customIdx >= 0) statusNames.size + customIdx else 0
        }
        binding.mediaListStatus.setText(allStatusNames[initialStatusIndex])
        binding.mediaListStatus.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_dropdown, allStatusNames)
        )

        // Chapter progress
        val latestChapter = muMedia.latestChapter
        binding.mediaListProgress.setText(muMedia.userChapter?.toString() ?: "")
        binding.mediaListProgressLayout.suffixText = if (latestChapter != null && latestChapter > 0) " / $latestChapter / ??" else " / ??"
        binding.mediaListProgressLayout.suffixTextView.updateLayoutParams {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        binding.mediaListProgressLayout.suffixTextView.gravity = Gravity.CENTER

        // +1 button
        binding.mediaListIncrement.setOnClickListener {
            val current = binding.mediaListProgress.text.toString().toIntOrNull() ?: 0
            binding.mediaListProgress.setText("${current + 1}")
        }

        // Save
        val initialListId = muMedia.listId
        val initialChapter = muMedia.userChapter
        binding.mediaListSave.setOnClickListener {
            val selectedName = binding.mediaListStatus.text.toString()
            val selectedIndex = allStatusNames.indexOf(selectedName).takeIf { it >= 0 } ?: initialStatusIndex
            val newListId = if (selectedIndex < statusNames.size) {
                selectedIndex
            } else {
                customListExtras[selectedIndex - statusNames.size].first
            }
            val newChapter = binding.mediaListProgress.text.toString().toIntOrNull()
            if (newListId == initialListId && newChapter == initialChapter) {
                dismissAllowingStateLoss()
                return@setOnClickListener
            }
            scope.launch {
                withContext(Dispatchers.IO) {
                    MangaUpdates.updateProgress(
                        seriesId = muMedia.id,
                        seriesTitle = muMedia.title,
                        listId = newListId,
                        chapter = newChapter,
                        volume = null
                    )
                    PrefManager.setCustomVal(
                        "$PREF_MU_LAST_READ_PREFIX${muMedia.id}",
                        System.currentTimeMillis()
                    )
                }
                // Update the shared Media so MangaReadFragment re-renders with the new progress
                model.getMedia().value?.let { media ->
                    media.userProgress = newChapter
                    media.muListId = newListId
                    model.setMedia(media)
                }
                Refresh.all()
                snackString(getString(R.string.list_updated))
                dismissAllowingStateLoss()
            }
        }

        // Delete (remove from all lists)
        binding.mediaListDelete.setOnClickListener {
            scope.launch {
                withContext(Dispatchers.IO) {
                    MangaUpdates.removeFromList(muMedia.id)
                }
                // Clear progress in the shared Media so MangaReadFragment reflects removal
                model.getMedia().value?.let { media ->
                    media.userProgress = null
                    media.muListId = null
                    model.setMedia(media)
                }
                Refresh.all()
                snackString(getString(R.string.deleted_from_list))
                dismissAllowingStateLoss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(muMedia: MUMedia): MUListEditorFragment =
            MUListEditorFragment().apply {
                arguments = Bundle().apply { putSerializable("muMedia", muMedia) }
            }
    }
}
