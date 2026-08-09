package ani.dantotsu.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemSettingsBinding
import ani.dantotsu.databinding.ItemSettingsSwitchBinding
import ani.dantotsu.setAnimation

class SettingsAdapter(private val settings: ArrayList<Settings>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    inner class SettingsViewHolder(val binding: ItemSettingsBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class SettingsSwitchViewHolder(val binding: ItemSettingsSwitchBinding) :
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
        }
        holder.itemView.alpha = if (settings.isEnabled) 1f else 0.5f
        when (settings.type) {
            1 -> {
                val b = (holder as SettingsViewHolder).binding
                setAnimation(b.root.context, b.root)

                b.settingsTitle.text = settings.name
                b.settingsDesc.text = settings.desc
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
                setAnimation(b.root.context, b.root)

                b.settingsButton.text = settings.name
                b.settingsDesc.text = settings.desc
                b.settingsIcon.setImageDrawable(
                    ContextCompat.getDrawable(
                        b.root.context, settings.icon
                    )
                )
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
}