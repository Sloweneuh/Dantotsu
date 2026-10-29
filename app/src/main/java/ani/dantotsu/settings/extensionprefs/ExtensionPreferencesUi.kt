package ani.dantotsu.settings.extensionprefs

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.forEach
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentExtensionPreferencesBinding
import ani.dantotsu.px
import ani.dantotsu.settings.TILE_GAP
import ani.dantotsu.settings.TILE_GROUP_GAP
import ani.dantotsu.settings.settingsTile
import ani.dantotsu.util.capContentWidth

/**
 * Dresses an extension's preference screen (a stock [PreferenceFragmentCompat], filled by the
 * extension) as one of the app's settings pages: the same header, rows on grouped tiles, Material
 * switches, and the wide-window width cap.
 */
object ExtensionPreferencesUi {

    /** The page around the fragment's own [list]: back button, [title] and the extension's [icon]. */
    fun wrap(
        fragment: PreferenceFragmentCompat,
        inflater: LayoutInflater,
        container: ViewGroup?,
        list: View,
        title: CharSequence,
        icon: Drawable?,
    ): View {
        val binding = FragmentExtensionPreferencesBinding.inflate(inflater, container, false)
        binding.extensionPrefsTitle.text = title
        binding.extensionPrefsIcon.setImageDrawable(icon)
        binding.extensionPrefsIcon.isVisible = icon != null
        binding.extensionPrefsBack.setOnClickListener {
            fragment.requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        binding.extensionPrefsList.addView(list)
        binding.root.capContentWidth()
        return binding.root
    }

    /** Material switches throughout, nested groups included, and no empty icon column. */
    fun style(group: PreferenceGroup) {
        group.forEach { pref ->
            pref.isIconSpaceReserved = false
            if (pref is SwitchPreferenceCompat) pref.widgetLayoutResource = R.layout.pref_widget_material_switch
            if (pref is PreferenceGroup) style(pref)
        }
    }

    /**
     * Rows on the settings pages' tiles: grouped between category headers, round where a group
     * begins and ends, with the same gaps.
     */
    fun tile(list: RecyclerView) {
        fun item(position: Int): Preference? =
            (list.adapter as? PreferenceGroupAdapter)?.takeIf { position in 0 until it.itemCount }
                ?.getItem(position)

        fun isHeader(position: Int) = item(position) is PreferenceCategory

        fun dress(row: View) {
            val position = list.getChildAdapterPosition(row)
            if (position == RecyclerView.NO_POSITION || isHeader(position)) return
            val start = position == 0 || isHeader(position - 1)
            val end = item(position + 1) == null || isHeader(position + 1)
            row.background = settingsTile(row, start, end)
            // The settings rows' type: a bold title over a lighter summary.
            row.findViewById<TextView>(android.R.id.title)?.apply {
                typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
                textSize = 16f
            }
            row.findViewById<TextView>(android.R.id.summary)?.apply {
                typeface = ResourcesCompat.getFont(context, R.font.poppins_semi_bold)
                alpha = 0.66f
            }
        }

        list.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                if (position == RecyclerView.NO_POSITION) return
                outRect.left = 24f.px
                outRect.right = 24f.px
                outRect.top = when {
                    position == 0 -> 0
                    // A header sits just above its own group, well clear of the one before it.
                    isHeader(position) -> TILE_GROUP_GAP + 8f.px
                    isHeader(position - 1) -> 8f.px
                    else -> TILE_GAP
                }
            }
        })
        list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = dress(view)
            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
        // A row shown or hidden (a dependency) moves its neighbours' group edges.
        list.adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = redressAll()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = redressAll()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = redressAll()
            fun redressAll() {
                list.post { for (i in 0 until list.childCount) dress(list.getChildAt(i)) }
            }
        })
    }
}
