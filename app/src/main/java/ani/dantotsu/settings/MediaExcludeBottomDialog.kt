package ani.dantotsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.databinding.BottomSheetRecyclerBinding
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.xwray.groupie.GroupieAdapter

class MediaExcludeBottomDialog : BottomSheetDialogFragment() {
    private var _binding: BottomSheetRecyclerBinding? = null
    private val binding get() = _binding!!
    private val adapter: GroupieAdapter = GroupieAdapter()
    private var prefName: PrefName = PrefName.MalSyncExcludeList
    private var title: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = BottomSheetRecyclerBinding.inflate(inflater, container, false)
        return _binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.repliesRecyclerView.adapter = adapter
        binding.repliesRecyclerView.layoutManager = LinearLayoutManager(
            context,
            LinearLayoutManager.VERTICAL,
            false
        )
        binding.title.text = title
        binding.replyButton.visibility = View.GONE

        PrefManager.getVal<Set<String>>(prefName).forEach { entry ->
            adapter.add(MediaExcludeItem(entry, adapter) { removed ->
                val current = PrefManager.getVal<Set<String>>(prefName)
                PrefManager.setVal(prefName, current - removed)
            })
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(prefName: PrefName, title: String): MediaExcludeBottomDialog {
            val dialog = MediaExcludeBottomDialog()
            dialog.prefName = prefName
            dialog.title = title
            return dialog
        }
    }
}
