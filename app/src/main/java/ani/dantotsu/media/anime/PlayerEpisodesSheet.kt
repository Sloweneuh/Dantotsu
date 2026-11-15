package ani.dantotsu.media.anime

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.databinding.BottomSheetPlayerEpisodesBinding

/**
 * Every episode, from the episode title over the video: the upright panel's list, in a sheet, opened
 * at the one playing. Picking another plays it and closes the sheet.
 */
@UnstableApi
class PlayerEpisodesSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPlayerEpisodesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetPlayerEpisodesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val player = requireActivity() as ExoplayerView
        val host = player.episodeListHost ?: return dismiss()
        binding.playerEpisodesList.layoutManager = LinearLayoutManager(requireContext())
        binding.playerEpisodesList.adapter =
            PlayerEpisodeAdapter(requireActivity() as AppCompatActivity, host) { dismiss() }
        // Opened at the episode playing, with the one before it above for context.
        binding.playerEpisodesList.scrollToPosition((host.currentIndex - 1).coerceAtLeast(0))
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? ExoplayerView)?.onSettingsSheetClosed()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "PlayerEpisodesSheet"
    }
}
