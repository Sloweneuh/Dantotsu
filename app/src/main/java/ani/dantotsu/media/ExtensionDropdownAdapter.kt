package ani.dantotsu.media

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.widget.doAfterTextChanged
import ani.dantotsu.R
import com.google.android.material.textfield.TextInputLayout

/**
 * Custom adapter for extension dropdown that displays icons alongside names
 */
class ExtensionDropdownAdapter(
    context: Context,
    private val extensionNames: List<String>,
    private val extensionIcons: List<Drawable?>
) : ArrayAdapter<String>(context, R.layout.item_dropdown_with_icon, extensionNames) {

    /** The icon listed for [name], or null when it has none or isn't listed. */
    fun iconFor(name: String): Drawable? =
        extensionIcons.getOrNull(extensionNames.indexOf(name))

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView, parent, false)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView, parent, true)
    }

    private fun createView(position: Int, convertView: View?, parent: ViewGroup, isDropdown: Boolean): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(
            R.layout.item_dropdown_with_icon,
            parent,
            false
        )

        val icon = view.findViewById<ImageView>(R.id.dropdownIcon)
        val text = view.findViewById<TextView>(R.id.dropdownText)

        text.text = extensionNames[position]

        // Set icon if available
        val extensionIcon = extensionIcons.getOrNull(position)
        if (extensionIcon != null) {
            icon.setImageDrawable(extensionIcon)
            icon.visibility = View.VISIBLE
        } else {
            icon.visibility = View.GONE
        }

        return view
    }
}

/** The source the dropdown lists for downloads, whose icon is a themed glyph like the folder. */
private const val DOWNLOADED_SOURCE = "Downloaded"

/**
 * Shows the selected source's icon in place of the generic folder: the extension's own icon, or
 * the download glyph for downloads. Follows the field's text, so every way a source gets picked
 * (a tap, the fallback to the next source, a rebind) updates it; call again after replacing the
 * dropdown's adapter, since that changes the icons without changing the text.
 */
fun TextInputLayout.followSourceIcon() {
    val field = editText as? AutoCompleteTextView ?: return
    if (getTag(R.id.source_icon_follow) == null) {
        setTag(R.id.source_icon_follow, true)
        field.doAfterTextChanged { showSourceIcon(field) }
    }
    showSourceIcon(field)
}

private fun TextInputLayout.showSourceIcon(field: AutoCompleteTextView) {
    val name = field.text.toString()
    val icon = (field.adapter as? ExtensionDropdownAdapter)?.iconFor(name)
    val themedTint = AppCompatResources.getColorStateList(
        context, com.google.android.material.R.color.mtrl_outlined_icon_tint
    )
    when {
        icon == null -> {
            setStartIconDrawable(R.drawable.ic_round_source_24)
            setStartIconTintList(themedTint)
        }
        name == DOWNLOADED_SOURCE -> {
            startIconDrawable = icon.constantState?.newDrawable(resources)?.mutate() ?: icon
            setStartIconTintList(themedTint)
        }
        else -> {
            // Launcher icons come at launcher size; pin them to a glyph's 24dp so the field
            // keeps its height, and leave them in their own colours.
            val size = (24 * resources.displayMetrics.density).toInt()
            startIconDrawable = icon.toBitmap(size, size).toDrawable(resources)
            setStartIconTintList(null)
        }
    }
}
