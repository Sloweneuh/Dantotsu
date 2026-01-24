package ani.dantotsu.media

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import ani.dantotsu.R

/**
 * Custom adapter for extension dropdown that displays icons alongside names
 */
class ExtensionDropdownAdapter(
    context: Context,
    private val extensionNames: List<String>,
    private val extensionIcons: List<Drawable?>
) : ArrayAdapter<String>(context, R.layout.item_dropdown_with_icon, extensionNames) {

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

