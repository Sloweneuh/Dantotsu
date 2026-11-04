package ani.dantotsu.media

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import ani.dantotsu.R
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.databinding.BottomSheetChipSearchBinding
import ani.dantotsu.databinding.ItemInfoPickerRowBinding
import ani.dantotsu.navBarHeight
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.SectionCard
import ani.dantotsu.settings.capSheetHeight
import ani.dantotsu.getThemeColor
import com.google.android.material.color.MaterialColors

/**
 * Which source to search a tag or genre on, for one that several sources of a merged section list.
 * The searches themselves stay with [CustomInfoTabFragment], which opens this.
 */
class ChipSearchSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetChipSearchBinding? = null
    private val binding get() = _binding!!

    override val opensExpanded = true

    override fun onStart() {
        super.onStart()
        capSheetHeight()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetChipSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = requireArguments()
        val kind = args.getString(ARG_KIND).orEmpty()
        val name = args.getString(ARG_NAME).orEmpty()
        binding.chipSearchTitle.text = name
        binding.chipSearchRows.setPadding(0, 0, 0, navBarHeight)
        val leavesApp = args.getStringArrayList(ARG_WEBSITES).orEmpty()
        args.getStringArrayList(ARG_SOURCES).orEmpty().forEach { key ->
            val source = InfoTabType.forKey(key) ?: return@forEach
            val row = ItemInfoPickerRowBinding.inflate(layoutInflater, binding.chipSearchRows, false)
            row.root.setCardBackgroundColor(SectionCard.fill(row.root, false))
            row.infoPickerTile.isVisible = true
            row.infoPickerTile.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.compositeARGBWithAlpha(
                    requireContext().getThemeColor(androidx.appcompat.R.attr.colorPrimary), 36
                )
            )
            row.infoPickerLogo.setImageResource(source.iconRes)
            row.infoPickerTitle.setText(source.labelRes)
            // Choosing here is deliberate enough, so it goes straight there: this says where.
            if (key in leavesApp) {
                row.infoPickerDetail.isVisible = true
                row.infoPickerDetail.setText(R.string.chip_search_opens_website)
                row.infoPickerTrailing.isVisible = true
                row.infoPickerTrailing.setImageResource(R.drawable.ic_round_open_in_browser_24)
            }
            row.root.setSafeOnClickListener {
                (parentFragment as? CustomInfoTabFragment)?.runChipSearch(kind, name, key)
                dismiss()
            }
            binding.chipSearchRows.addView(row.root)
        }
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_NAME = "name"
        private const val ARG_SOURCES = "sources"
        private const val ARG_WEBSITES = "websites"

        fun newInstance(
            kind: InfoSectionKind, name: String, sources: List<InfoTabType>, websites: List<InfoTabType>,
        ) =
            ChipSearchSheet().apply {
                arguments = bundleOf(
                    ARG_KIND to kind.key,
                    ARG_NAME to name,
                    ARG_SOURCES to ArrayList(sources.map { it.key }),
                    ARG_WEBSITES to ArrayList(websites.map { it.key }),
                )
            }
    }
}
