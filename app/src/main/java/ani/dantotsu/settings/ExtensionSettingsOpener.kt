package ani.dantotsu.settings

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.LanguageMapper.Companion.getLanguageName
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.radiobutton.MaterialRadioButton
import android.widget.ProgressBar
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.source.ConfigurableSource

object ExtensionSettingsOpener {
    fun openConfigurableSourcePreferences(
        activity: FragmentActivity,
        configurableSources: List<Any>,
        onCloseAction: (() -> Unit)? = null,
        selectedSourceIndex: Int = -1
    ) {
        if (configurableSources.isEmpty()) {
            // show toast via activity
            if (!activity.isFinishing) {
                (activity as? androidx.fragment.app.FragmentActivity)?.let { a ->
                    ani.dantotsu.snackString(a.getString(R.string.source_not_configurable))
                }
            }
            return
        }

        fun openSingle(selected: Any) {
            when (selected) {
                is ConfigurableAnimeSource -> {
                    // If hosting activity is ExtensionsActivity, display fragment in-place
                    if (activity is ExtensionsActivity) {
                        val changeUIVisibility: (Boolean) -> Unit = { show ->
                            activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager).isVisible = show
                            activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout).isVisible = show
                            activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.searchView).isVisible = show
                            activity.findViewById<android.widget.ImageView>(R.id.languageselect).isVisible = show
                            activity.findViewById<android.widget.TextView>(R.id.extensions).text = if (show) activity.getString(R.string.extensions) else ""
                            activity.findViewById<android.widget.FrameLayout>(R.id.fragmentExtensionsContainer).isGone = show
                        }
                        val fragment = ani.dantotsu.settings.extensionprefs.AnimeSourcePreferencesFragment().getInstance(selected.id) {
                            onCloseAction?.invoke()
                            changeUIVisibility(true)
                        }
                        changeUIVisibility(false)
                        activity.supportFragmentManager.beginTransaction()
                            .setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                            .replace(R.id.fragmentExtensionsContainer, fragment)
                            .addToBackStack(null)
                            .commit()
                    } else if (activity is ExtensionBrowseActivity) {
                        val filterChips = activity.findViewById<RecyclerView>(R.id.extensionBrowseActiveFilterChips)
                        val filterChipsWasVisible = filterChips.isVisible
                        val changeUIVisibility: (Boolean) -> Unit = { show ->
                            activity.findViewById<LinearLayout>(R.id.extensionBrowseToolbar).isVisible = show
                            activity.findViewById<ChipGroup>(R.id.extensionBrowseChipGroup).isVisible = show
                            activity.findViewById<RecyclerView>(R.id.extensionBrowseRecycler).isVisible = show
                            activity.findViewById<ProgressBar>(R.id.extensionBrowseProgress).isVisible = if (show) activity.findViewById<ProgressBar>(R.id.extensionBrowseProgress).visibility == android.view.View.VISIBLE else false
                            filterChips.isVisible = show && filterChipsWasVisible
                            activity.findViewById<android.widget.FrameLayout>(R.id.fragmentExtensionsContainer).isGone = show
                        }
                        val fragment = ani.dantotsu.settings.extensionprefs.AnimeSourcePreferencesFragment().getInstance(selected.id) {
                            onCloseAction?.invoke()
                            changeUIVisibility(true)
                        }
                        changeUIVisibility(false)
                        activity.supportFragmentManager.beginTransaction()
                            .setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                            .replace(R.id.fragmentExtensionsContainer, fragment)
                            .addToBackStack(null)
                            .commit()
                    } else {
                        val intent = Intent(activity, ExtensionsActivity::class.java)
                        intent.putExtra(ExtensionsActivity.EXTRA_OPEN_SOURCE_ID, selected.id)
                        intent.putExtra(ExtensionsActivity.EXTRA_OPEN_SOURCE_TYPE, ExtensionBrowseActivity.TYPE_ANIME)
                        activity.startActivity(intent)
                    }
                }

                is ConfigurableSource -> {
                    if (activity is ExtensionsActivity) {
                        val changeUIVisibility: (Boolean) -> Unit = { show ->
                            activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager).isVisible = show
                            activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout).isVisible = show
                            activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.searchView).isVisible = show
                            activity.findViewById<android.widget.ImageView>(R.id.languageselect).isVisible = show
                            activity.findViewById<android.widget.TextView>(R.id.extensions).text = if (show) activity.getString(R.string.extensions) else ""
                            activity.findViewById<android.widget.FrameLayout>(R.id.fragmentExtensionsContainer).isGone = show
                        }
                        val fragment = ani.dantotsu.settings.extensionprefs.MangaSourcePreferencesFragment().getInstance(selected.id) {
                            onCloseAction?.invoke()
                            changeUIVisibility(true)
                        }
                        changeUIVisibility(false)
                        activity.supportFragmentManager.beginTransaction()
                            .setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                            .replace(R.id.fragmentExtensionsContainer, fragment)
                            .addToBackStack(null)
                            .commit()
                    } else if (activity is ExtensionBrowseActivity) {
                        val filterChips = activity.findViewById<RecyclerView>(R.id.extensionBrowseActiveFilterChips)
                        val filterChipsWasVisible = filterChips.isVisible
                        val changeUIVisibility: (Boolean) -> Unit = { show ->
                            activity.findViewById<LinearLayout>(R.id.extensionBrowseToolbar).isVisible = show
                            activity.findViewById<ChipGroup>(R.id.extensionBrowseChipGroup).isVisible = show
                            activity.findViewById<RecyclerView>(R.id.extensionBrowseRecycler).isVisible = show
                            activity.findViewById<ProgressBar>(R.id.extensionBrowseProgress).isVisible = if (show) activity.findViewById<ProgressBar>(R.id.extensionBrowseProgress).visibility == android.view.View.VISIBLE else false
                            filterChips.isVisible = show && filterChipsWasVisible
                            activity.findViewById<android.widget.FrameLayout>(R.id.fragmentExtensionsContainer).isGone = show
                        }
                        val fragment = ani.dantotsu.settings.extensionprefs.MangaSourcePreferencesFragment().getInstance(selected.id) {
                            onCloseAction?.invoke()
                            changeUIVisibility(true)
                        }
                        changeUIVisibility(false)
                        activity.supportFragmentManager.beginTransaction()
                            .setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                            .replace(R.id.fragmentExtensionsContainer, fragment)
                            .addToBackStack(null)
                            .commit()
                    } else {
                        val intent = Intent(activity, ExtensionsActivity::class.java)
                        intent.putExtra(ExtensionsActivity.EXTRA_OPEN_SOURCE_ID, selected.id)
                        intent.putExtra(ExtensionsActivity.EXTRA_OPEN_SOURCE_TYPE, ExtensionBrowseActivity.TYPE_MANGA)
                        activity.startActivity(intent)
                    }
                }

                else -> {
                    // unsupported
                }
            }
        }

        if (configurableSources.size == 1 || selectedSourceIndex >= 0) {
            val source = if (selectedSourceIndex >= 0)
                configurableSources.getOrElse(selectedSourceIndex) { configurableSources[0] }
            else
                configurableSources[0]
            openSingle(source)
            return
        }

        // Multiple options: prompt user to select
        val names = configurableSources.map { s ->
            when (s) {
                is ConfigurableAnimeSource -> getLanguageName(s.lang)
                is ConfigurableSource -> getLanguageName(s.lang)
                else -> ""
            }
        }

        val sheet = BottomSheetDialog(activity)
        val dp = activity.resources.displayMetrics.density
        val rootView = activity.window.decorView
        val onBgColor = MaterialColors.getColor(rootView, com.google.android.material.R.attr.colorOnBackground)

        val scrollView = NestedScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bottom_sheet_background)
            val h = (24 * dp).toInt()
            setPadding(h, (20 * dp).toInt(), h, navBarHeight + (16 * dp).toInt())
        }

        container.addView(AppCompatTextView(activity).apply {
            text = activity.getString(R.string.select_a_source)
            textSize = 18f
            typeface = ResourcesCompat.getFont(activity, R.font.poppins_bold)
            setTextColor(onBgColor)
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        container.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                it.bottomMargin = (12 * dp).toInt()
            }
            alpha = 0.12f
            setBackgroundColor(onBgColor)
        })

        val radioGroup = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        names.forEachIndexed { index, name ->
            MaterialRadioButton(activity).apply {
                id = index
                text = name
                textSize = 15f
                typeface = ResourcesCompat.getFont(activity, R.font.poppins_semi_bold)
                minHeight = (48 * dp).toInt()
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                )
                radioGroup.addView(this)
            }
        }
        radioGroup.setOnCheckedChangeListener { _, which ->
            if (which >= 0) openSingle(configurableSources[which])
            sheet.dismiss()
        }

        container.addView(radioGroup)
        scrollView.addView(container)
        sheet.setContentView(scrollView)
        sheet.show()
    }
}
