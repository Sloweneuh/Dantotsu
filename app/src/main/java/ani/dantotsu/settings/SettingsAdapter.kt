package ani.dantotsu.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemSettingsBinding
import ani.dantotsu.databinding.ItemSettingsChoiceBinding
import ani.dantotsu.databinding.ItemSettingsStepperBinding
import ani.dantotsu.databinding.ItemSettingsChoiceButtonBinding
import ani.dantotsu.databinding.ItemSettingsHeaderBinding
import ani.dantotsu.databinding.ItemSettingsSliderBinding
import ani.dantotsu.databinding.ItemSettingsSwitchBinding
import ani.dantotsu.px
import ani.dantotsu.setAnimation
import kotlin.math.pow
import kotlin.math.roundToInt

/** Row spacing used by [Settings.compact] rows — tighter than the 24dp a full settings screen uses,
 *  since a card already frames its own body. */
private val COMPACT_TOP_MARGIN get() = 10f.px

class SettingsAdapter(
    private val settings: ArrayList<Settings>,
    /** Whether rows pop in as they bind. Off inside a section card, whose opening is the motion. */
    private val popIn: Boolean = true,
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    inner class SettingsViewHolder(val binding: ItemSettingsBinding) :
        RecyclerView.ViewHolder(binding.root) {
        // Captured once at inflation, before any row can override it (the account cards' Discord
        // status row shows the icon's own baked-in colour instead) — restored on every bind so a
        // recycled holder doesn't carry a previous row's override.
        val defaultIconTint = binding.settingsIcon.imageTintList
    }

    inner class SettingsSwitchViewHolder(val binding: ItemSettingsSwitchBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class SettingsHeaderViewHolder(val binding: ItemSettingsHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class SettingsSliderViewHolder(val binding: ItemSettingsSliderBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class SettingsStepperViewHolder(val binding: ItemSettingsStepperBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class SettingsChoiceViewHolder(val binding: ItemSettingsChoiceBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            1 -> SettingsViewHolder(
                ItemSettingsBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

            2 -> SettingsSwitchViewHolder(
                ItemSettingsSwitchBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

            // A plain section sub-header, used inside the account cards.
            3 -> SettingsHeaderViewHolder(
                ItemSettingsHeaderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

            4 -> SettingsSliderViewHolder(
                ItemSettingsSliderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

            5 -> SettingsChoiceViewHolder(
                ItemSettingsChoiceBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

            6 -> SettingsStepperViewHolder(
                ItemSettingsStepperBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

            else -> SettingsViewHolder(
                ItemSettingsBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val settings = settings[position]
        holder.itemView.visibility = if (settings.isVisible) View.VISIBLE else View.GONE
        holder.itemView.layoutParams = holder.itemView.layoutParams?.apply {
            height = if (settings.isVisible) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            if (settings.compact && this is ViewGroup.MarginLayoutParams) {
                topMargin = COMPACT_TOP_MARGIN
            }
        }
        holder.itemView.alpha = if (settings.isEnabled) 1f else 0.5f
        when (settings.type) {
            1 -> {
                val h = holder as SettingsViewHolder
                val b = h.binding
                if (popIn) setAnimation(b.root.context, b.root)

                b.settingsTitle.text = settings.name
                b.settingsDesc.text = settings.desc
                // Reset any per-row colour override a recycled holder may carry; `attach` re-applies
                // one where a row wants it (the account cards' Discord status row).
                b.settingsIcon.clearColorFilter()
                b.settingsIcon.imageTintList = h.defaultIconTint
                b.settingsIcon.setImageDrawable(
                    ContextCompat.getDrawable(
                        b.root.context, settings.icon
                    )
                )
                b.settingsLayout.setOnClickListener {
                    if (settings.isEnabled) settings.onClick?.invoke(b)
                }
                b.settingsLayout.setOnLongClickListener {
                    settings.onLongClick?.invoke()
                    true
                }
                b.settingsIconRight.visibility =
                    if (settings.isActivity) View.VISIBLE else View.GONE
                b.attachView.visibility = if (settings.attach != null) View.VISIBLE else View.GONE
                // Cleared before the row gets its say. This icon is opt-in per row and nothing else
                // resets it, so a recycled holder handed to a row that doesn't want one kept the
                // previous row's — visible, and still wired to what that row did with it.
                b.settingsExtraIcon.visibility = View.GONE
                b.settingsExtraIcon.setOnClickListener(null)
                b.settingsExtraIcon.setOnLongClickListener(null)
                settings.attach?.invoke(b)
            }

            2 -> {
                val b = (holder as SettingsSwitchViewHolder).binding
                if (popIn) setAnimation(b.root.context, b.root)

                b.settingsButton.text = settings.name
                b.settingsDesc.text = settings.desc
                // A switch whose title says it all has no second line, rather than an empty one.
                b.settingsDesc.visibility = if (settings.desc.isEmpty()) View.GONE else View.VISIBLE
                b.settingsIcon.setImageDrawable(
                    ContextCompat.getDrawable(
                        b.root.context, settings.icon
                    )
                )
                // Cleared before the state is applied: a recycled holder still carries the previous
                // row's listener, and setting isChecked would fire it, writing that row's preference
                // (several of which restart the screen).
                b.settingsButton.setOnCheckedChangeListener(null)
                b.settingsButton.isChecked = settings.isChecked
                b.settingsButton.isEnabled = settings.isEnabled
                b.settingsButton.setOnCheckedChangeListener { _, isChecked ->
                    if (settings.isEnabled) settings.switch?.invoke(isChecked, b)
                }
                b.settingsLayout.setOnLongClickListener {
                    settings.onLongClick?.invoke()
                    true
                }
                settings.attachToSwitch?.invoke(b)
            }

            3 -> {
                (holder as SettingsHeaderViewHolder).binding.settingsHeader.text = settings.name
            }

            4 -> {
                val b = (holder as SettingsSliderViewHolder).binding
                val cfg = settings.slider ?: return
                if (popIn) setAnimation(b.root.context, b.root)

                b.settingsTitle.text = settings.name
                b.settingsDesc.text = settings.desc
                // A slider whose reading says it all (a size, a percentage) has no description;
                // left showing, the empty line pushed the slider away from its title.
                b.settingsDesc.visibility = if (settings.desc.isEmpty()) View.GONE else View.VISIBLE
                b.settingsIcon.setImageDrawable(
                    ContextCompat.getDrawable(b.root.context, settings.icon)
                )

                fun renderValue(v: Float) {
                    val text = cfg.format?.invoke(v)
                    b.settingsValue.text = text
                    b.settingsValue.visibility = if (text != null) View.VISIBLE else View.GONE
                }

                // Cleared before the range is applied: a recycled holder still carries the previous
                // row's listener, and setting valueFrom/valueTo/value would fire it — writing this
                // row's bounds into that row's preference.
                b.settingsSlider.clearOnChangeListeners()
                b.settingsSlider.valueFrom = cfg.from
                b.settingsSlider.valueTo = cfg.to
                b.settingsSlider.stepSize = cfg.stepSize
                b.settingsSlider.value = cfg.value.coerceIn(cfg.from, cfg.to)
                b.settingsSlider.isEnabled = settings.isEnabled
                renderValue(b.settingsSlider.value)
                b.settingsSlider.addOnChangeListener { _, value, fromUser ->
                    renderValue(value)
                    // Only a drag commits. A programmatic value change during bind is not a choice
                    // the user made, and several of these rows restart the app when written.
                    if (fromUser && settings.isEnabled) cfg.onChange(value)
                }
            }

            6 -> {
                val b = (holder as SettingsStepperViewHolder).binding
                val cfg = settings.stepper ?: return
                if (popIn) setAnimation(b.root.context, b.root)

                b.settingsTitle.text = settings.name
                b.settingsDesc.text = settings.desc
                b.settingsDesc.visibility = if (settings.desc.isEmpty()) View.GONE else View.VISIBLE
                b.settingsIcon.setImageDrawable(
                    ContextCompat.getDrawable(b.root.context, settings.icon)
                )

                // Held here rather than read back from the field: a recycled holder's field may still
                // show the previous row's number when its old listeners fire.
                var value = cfg.value
                fun format(v: Float) =
                    if (cfg.decimals == 0) v.roundToInt().toString() else "%.${cfg.decimals}f".format(v)
                fun clamp(v: Float): Float {
                    var r = v
                    cfg.min?.let { r = maxOf(r, it) }
                    cfg.max?.let { r = minOf(r, it) }
                    val scale = 10f.pow(cfg.decimals)
                    return (r * scale).roundToInt() / scale
                }
                fun commit(v: Float) {
                    val next = clamp(v)
                    b.settingsValue.setText(format(next))
                    if (next != value) {
                        value = next
                        cfg.onChange(next)
                    }
                }

                b.settingsValue.onFocusChangeListener = null
                b.settingsValue.setOnEditorActionListener(null)
                b.settingsValue.setText(format(value))
                b.settingsDecrement.setOnClickListener { commit(value - cfg.step) }
                b.settingsIncrement.setOnClickListener { commit(value + cfg.step) }
                // A typed number is taken when the field is left or Done is pressed; one that doesn't
                // parse puts the last good value back.
                b.settingsValue.setOnFocusChangeListener { _, focused ->
                    if (!focused) commit(b.settingsValue.text.toString().toFloatOrNull() ?: value)
                }
                b.settingsValue.setOnEditorActionListener { v, _, _ ->
                    commit(b.settingsValue.text.toString().toFloatOrNull() ?: value)
                    v.clearFocus()
                    false
                }
                listOf(b.settingsDecrement, b.settingsIncrement, b.settingsValue)
                    .forEach { it.isEnabled = settings.isEnabled }
            }

            5 -> {
                val b = (holder as SettingsChoiceViewHolder).binding
                val cfg = settings.choice ?: return
                if (popIn) setAnimation(b.root.context, b.root)

                b.settingsTitle.text = settings.name
                b.settingsDesc.text = settings.desc
                b.settingsDesc.visibility = if (settings.desc.isEmpty()) View.GONE else View.VISIBLE
                b.settingsIcon.setImageDrawable(
                    ContextCompat.getDrawable(b.root.context, settings.icon)
                )

                // Rebuilt rather than rebound: the option count is per-row (the start-up tab picker
                // drops a button for each hidden tab), so a recycled holder's children are the
                // wrong shape as often as not.
                b.settingsChoices.removeAllViews()
                val inflater = LayoutInflater.from(b.root.context)
                cfg.options.forEachIndexed { index, option ->
                    if (!option.visible) return@forEachIndexed
                    val segment = ItemSettingsChoiceButtonBinding.inflate(
                        inflater, b.settingsChoices, false
                    ).choiceButton
                    segment.apply {
                        icon = ContextCompat.getDrawable(b.root.context, option.icon)
                        turnIcon(option.flipHorizontally, option.rotation)
                        contentDescription = option.label
                        // The segment reads alpha as its checked state; see ChoiceToggleButton.
                        alpha = if (index == cfg.selected) 1f else 0.33f
                        isEnabled = settings.isEnabled
                        setOnClickListener {
                            if (!settings.isEnabled) return@setOnClickListener
                            cfg.onSelect(index)
                        }
                    }
                    b.settingsChoices.addView(segment)
                }
            }
        }
    }

    override fun getItemCount(): Int = settings.size

    override fun getItemViewType(position: Int): Int {
        return settings[position].type
    }

    /** Position of the first visible row whose title matches [title], or -1 if none. */
    /**
     * Where the row titled [title] sits, for the settings search to scroll to and flash.
     *
     * A prefix match backs up the exact one because some rows render their current setting into
     * their own title — "Unread chapter check interval : 6 hours" — which no fixed string can ever
     * equal. Exact wins where both apply, so a label that happens to prefix a neighbouring row
     * can't take that row's place.
     */
    fun indexOfTitle(title: String): Int =
        settings.indexOfFirst { it.isVisible && it.name == title }
            .takeIf { it >= 0 }
            ?: settings.indexOfFirst { it.isVisible && it.name.startsWith(title) }

    /** Where the row tagged [key] sits (see [Settings.anchorKey]), or -1 if there is none. */
    fun indexOfKey(key: String): Int =
        settings.indexOfFirst { it.isVisible && it.anchorKey == key }
}