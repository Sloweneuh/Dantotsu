package ani.dantotsu.settings

import ani.dantotsu.databinding.ItemSettingsBinding
import ani.dantotsu.databinding.ItemSettingsSwitchBinding

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