package ani.dantotsu.settings

import ani.dantotsu.databinding.ItemSettingsBinding
import ani.dantotsu.databinding.ItemSettingsSwitchBinding

/**
 * The control behind a `type = 4` row.
 *
 * [value] and [onChange] work in the slider's own units, not the preference's — the animation-speed
 * row, for one, maps its track positions onto multipliers, and that mapping belongs to the row
 * rather than to this.
 *
 * @param format renders the live value beside the title. Null leaves it hidden.
 */
data class SliderConfig(
    val from: Float,
    val to: Float,
    val stepSize: Float,
    val value: Float,
    val onChange: (Float) -> Unit,
    val format: ((Float) -> String)? = null,
)

/**
 * One button in a `type = 5` row.
 *
 * @param flipHorizontally mirrors the icon, for a glyph that reads the wrong way round in place
 *                         (the home icon in the start-up tab picker is drawn mirrored).
 */
data class ChoiceOption(
    val icon: Int,
    val label: String,
    val visible: Boolean = true,
    val flipHorizontally: Boolean = false,
)

/**
 * The control behind a `type = 5` row: a small set of icon buttons, one of which is selected.
 *
 * For a choice short enough that showing every option beats hiding them behind a dialog — the
 * start-up tab, say, where there are three and each has an obvious icon.
 */
data class ChoiceConfig(
    val options: List<ChoiceOption>,
    val selected: Int,
    val onSelect: (Int) -> Unit,
)

data class Settings(
    val type: Int,
    val name: String,
    val desc: String,
    val icon: Int,
    val onClick: ((ItemSettingsBinding) -> Unit)? = null,
    val onLongClick: (() -> Unit)? = null,
    val switch: ((isChecked: Boolean, view: ItemSettingsSwitchBinding) -> Unit)? = null,
    val attach: ((ItemSettingsBinding) -> Unit)? = null,
    val attachToSwitch: ((ItemSettingsSwitchBinding) -> Unit)? = null,
    /** Required by `type = 4`, ignored otherwise. */
    val slider: SliderConfig? = null,
    /** Required by `type = 5`, ignored otherwise. */
    val choice: ChoiceConfig? = null,
    val isVisible: Boolean = true,
    val isActivity: Boolean = false,
    var isChecked: Boolean = false,
    val isEnabled: Boolean = true,
    /** Tighter row spacing, for rows nested inside a container that already has its own padding
     *  (the account cards) rather than sitting directly on a full settings screen. */
    val compact: Boolean = false,
    /** A stable id for [SettingsAdapter.indexOfKey] to find this row by — independent of its
     *  (possibly parameterised, possibly retitled) display name. Used to land a settings-search
     *  result on a row nested inside an account card. */
    val anchorKey: String? = null,
)