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
 * A number stepped with − and + beside its title, for a `type = 6` row; the number can also be typed.
 *
 * @param step what − and + change it by.
 * @param min / [max] clamp it, typed or stepped; null leaves that end open.
 * @param decimals digits shown after the point (and what a stepped value is rounded to), so 0.1
 *                 steps don't show float residue like 1.5000001.
 * @param onChange called with the new value whenever it changes.
 */
data class StepperConfig(
    val value: Float,
    val step: Float,
    val min: Float? = null,
    val max: Float? = null,
    val decimals: Int = 0,
    val onChange: (Float) -> Unit,
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
    /** Turns the icon, for options drawn as one glyph pointing several ways (reading directions). */
    val rotation: Float = 0f,
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
    /** Required by `type = 6`, ignored otherwise. */
    val stepper: StepperConfig? = null,
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
    /** In a [SettingsAdapter] with `tiled` on, begins a new group of tiles, set apart from the
     *  rows above it — the way system settings bundle related entries into one rounded block. */
    val startsGroup: Boolean = false,
)