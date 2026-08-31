package ani.dantotsu.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.databinding.FragmentDownloadSettingsBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.SettingsAdapter
import ani.dantotsu.themes.ThemeManager

class DownloadSettingsFragment : Fragment() {
    private var _binding: FragmentDownloadSettingsBinding? = null
    private val binding get() = _binding!!
    private var onCloseAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(requireActivity()).applyTheme()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface))
        binding.downloadSettingsRecycler.clipToPadding = false
        binding.downloadSettingsRecycler.apply {
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + navBarHeight)
        }
        binding.downloadSettingsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.downloadSettingsRecycler.adapter =
            SettingsAdapter(ArrayList(requireContext().downloadSettingsRows()))
    }


    override fun onDestroyView() {
        super.onDestroyView()
        onCloseAction?.invoke()
        _binding = null
    }

    fun getInstance(onCloseAction: (() -> Unit)? = null): DownloadSettingsFragment {
        val fragment = DownloadSettingsFragment()
        fragment.onCloseAction = onCloseAction
        return fragment
    }
}
