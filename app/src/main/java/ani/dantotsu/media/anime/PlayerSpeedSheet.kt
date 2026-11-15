package ani.dantotsu.media.anime

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.util.UnstableApi
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetPlayerSpeedBinding
import com.google.android.material.chip.Chip

/**
 * The speed button's sheet: the speed in large, a slider through every speed the player offers
 * (the cursed ones included, when switched on), and the usual ones as chips. Each change applies
 * at once, the episode playing on, and is kept for the series as the old list did.
 */
@UnstableApi
class PlayerSpeedSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPlayerSpeedBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetPlayerSpeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val player = requireActivity() as ExoplayerView
        val speeds = player.speeds
        if (speeds.isEmpty()) return dismiss()

        // Chips for the usual speeds, as far as this list has them.
        val chips = speeds.withIndex().filter { it.value in PRESETS }.map { (index, value) ->
            (layoutInflater.inflate(R.layout.item_speed_chip, binding.speedPresets, false) as Chip).apply {
                id = View.generateViewId()
                text = speedLabel(value)
                setOnClickListener { select(player, index) }
                tag = index
            }
        }
        chips.forEach { binding.speedPresets.addView(it) }

        binding.speedSlider.apply {
            valueFrom = 0f
            valueTo = (speeds.size - 1).coerceAtLeast(1).toFloat()
            stepSize = 1f
            isEnabled = speeds.size > 1
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) select(player, value.toInt())
            }
        }
        show(player.speedIndex)
    }

    private fun select(player: ExoplayerView, index: Int) {
        player.setSpeedIndex(index)
        show(index)
    }

    /** The sheet as [index] makes it: the value, the slider and the matching chip, if any. */
    private fun show(index: Int) {
        val speeds = (activity as? ExoplayerView)?.speeds ?: return
        val value = speeds.getOrNull(index) ?: return
        binding.speedValue.text = speedLabel(value)
        if (binding.speedSlider.value != index.toFloat()) binding.speedSlider.value = index.toFloat()
        for (i in 0 until binding.speedPresets.childCount) {
            val chip = binding.speedPresets.getChildAt(i) as Chip
            chip.isChecked = chip.tag == index
        }
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
        const val TAG = "PlayerSpeedSheet"

        /** The speeds worth a chip, where the list has them. */
        private val PRESETS = setOf(0.75f, 1f, 1.25f, 1.5f, 2f)

        /** 1×, 1.25×: no trailing ".0". */
        fun speedLabel(value: Float): String =
            (if (value % 1f == 0f) value.toInt().toString() else value.toString()) + "×"
    }
}
