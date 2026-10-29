package ani.dantotsu.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.color.MaterialColors
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
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

/** Tiled rows: the hairline between tiles of one group, the gap between groups, and the corners
 *  at a group's ends and between its tiles. */
/**
 * A settings row's tile: its corners round where its group begins ([groupStart]) and ends
 * ([groupEnd]), tight in between. Shared with screens laid out by other adapters (extension
 * preferences) so they read as the same settings.
 */
fun settingsTile(row: View, groupStart: Boolean, groupEnd: Boolean): Drawable {
    val top = if (groupStart) TILE_OUTER_RADIUS else TILE_INNER_RADIUS
    val bottom = if (groupEnd) TILE_OUTER_RADIUS else TILE_INNER_RADIUS
    fun shape(color: Int) = GradientDrawable().apply {
        cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        setColor(color)
    }
    return RippleDrawable(
        ColorStateList.valueOf(
            MaterialColors.getColor(row, androidx.appcompat.R.attr.colorControlHighlight)
        ),
        shape(SectionCard.fill(row, false)),
        shape(Color.WHITE),
    )
}

internal val TILE_GAP get() = 2f.px
internal val TILE_GROUP_GAP get() = 16f.px
private val TILE_COLUMN_GAP get() = 12f.px
private val TILE_OUTER_RADIUS get() = 24f.px.toFloat()
private val TILE_INNER_RADIUS get() = 6f.px.toFloat()

class SettingsAdapter(
    private val settings: ArrayList<Settings>,
    /** Whether rows pop in as they bind. Off inside a section card, whose opening is the motion. */
    private val popIn: Boolean = true,
    /**
     * Each row on its own rounded tile, rows grouped by [Settings.startsGroup] into blocks with
     * round outer corners and tight inner ones. For a list standing directly on a settings screen;
     * one inside a card already has its frame.
     */
    private val tiled: Boolean = false,
    /** Set apart the first tile from what sits above it in the same RecyclerView — the section
     *  cards a [androidx.recyclerview.widget.ConcatAdapter] puts ahead of these rows. */
    private val afterOtherContent: Boolean = false,
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    /**
     * Columns the list is laid out in (a wide window's settings home). Past one, each tile stands
     * alone — round on every corner, evenly spaced — since a group can't run down a grid.
     */
    var columns = 1

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        createHolder(parent, viewType).also {
            if (!tiled) return@also
            // A header lines up with the icons on the tiles below it.
            if (viewType == 3) it.itemView.setPaddingRelative(16f.px, 0, 16f.px, 0)
            else fitToTile(it.itemView)
        }

    /**
     * Pads a row in from its tile's edges. Rows framed by their icon's 24dp margins trade some of
     * that for padding, so a description running to a third line still clears the tile's edge.
     */
    private fun fitToTile(row: View) {
        val icons = listOfNotNull<View>(
            row.findViewById(R.id.settingsIcon),
            row.findViewById(R.id.settingsIconRight),
        ).filter { (it.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin == 24f.px }
        icons.forEach {
            it.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = 16f.px
                bottomMargin = 16f.px
            }
        }
        val vertical = if (icons.isEmpty()) row.paddingTop else 8f.px
        row.setPaddingRelative(16f.px, vertical, 16f.px, maxOf(vertical, row.paddingBottom))
    }

    /** The tile behind the row at [position], its corners rounded where its group begins and ends. */
    private fun tileBackground(row: View, position: Int): Drawable {
        val next = (position + 1 until settings.size).firstOrNull { settings[it].isVisible }
        val first = groupBreakBefore(position)
        val last = next == null || settings[next].type == 3 || groupBreakBefore(next)
        return settingsTile(row, first || columns > 1, last || columns > 1)
    }

    private fun tileTopMargin(position: Int): Int {
        if (columns > 1) return if (position < columns) 0 else TILE_COLUMN_GAP
        val prev = (position - 1 downTo 0).firstOrNull { settings[it].isVisible }
        return when {
            prev == null -> if (afterOtherContent) TILE_GROUP_GAP else 0
            // A header sits just above its own group, and well clear of the one before it.
            settings[position].type == 3 -> TILE_GROUP_GAP + 8f.px
            settings[prev].type == 3 -> 8f.px
            groupBreakBefore(position) -> TILE_GROUP_GAP
            else -> TILE_GAP
        }
    }

    /**
     * Whether the visible row at [position] begins a group. A hidden row that starts one hands
     * that over to the next row shown, so a group whose first row is hidden still stands apart.
     */
    private fun groupBreakBefore(position: Int): Boolean {
        val prev = (position - 1 downTo 0).firstOrNull { settings[it].isVisible } ?: return true
        return settings[prev].type == 3 || (prev + 1..position).any { settings[it].startsGroup }
    }

    private fun createHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
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
            if (this is ViewGroup.MarginLayoutParams) {
                // The row's own top margin, as inflated, kept so a recycled holder gets it back.
                val inflated = holder.itemView.getTag(R.id.settings_row_top_margin) as? Int
                    ?: topMargin.also { holder.itemView.setTag(R.id.settings_row_top_margin, it) }
                // A hidden row takes no room at all: zero height alone left its margin behind,
                // a gap in the list wherever a row was hidden.
                topMargin = when {
                    !settings.isVisible -> 0
                    tiled -> tileTopMargin(position)
                    settings.compact -> COMPACT_TOP_MARGIN
                    else -> inflated
                }
                if (tiled && columns > 1) {
                    val column = position % columns
                    marginStart = if (column > 0) TILE_COLUMN_GAP / 2 else 0
                    marginEnd = if (column < columns - 1) TILE_COLUMN_GAP / 2 else 0
                }
            }
        }
        if (tiled && settings.type != 3 && settings.isVisible) {
            holder.itemView.background = tileBackground(holder.itemView, position)
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
                // A tile reads as tappable on its own; the chevron only crowded it.
                b.settingsIconRight.visibility =
                    if (settings.isActivity && !tiled) View.VISIBLE else View.GONE
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
                // The adapter alone decides what is lit, through the segments' alpha. Left on, the
                // group's own required single selection put the previous choice back.
                b.settingsChoices.isSelectionRequired = false
                b.settingsChoices.isSingleSelection = false
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
                        if (cfg.showLabels) {
                            text = option.label
                            iconPadding = 8f.px
                            val pad = 16f.px
                            setPaddingRelative(pad, 0, pad, 0)
                            layoutParams = layoutParams.apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
                        }
                        // The segment reads alpha as its checked state; see ChoiceToggleButton.
                        alpha = if (index == cfg.selected) 1f else 0.33f
                        isEnabled = settings.isEnabled
                        setOnClickListener {
                            if (!settings.isEnabled) return@setOnClickListener
                            // Every segment, not just this one: the group's own single-selection
                            // left the previously chosen one checked as well.
                            for (i in 0 until b.settingsChoices.childCount) {
                                val child = b.settingsChoices.getChildAt(i)
                                child.alpha = if (child === this) 1f else 0.33f
                            }
                            cfg.selected = index
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