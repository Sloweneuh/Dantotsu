package ani.dantotsu.settings

import android.content.Intent
import androidx.fragment.app.FragmentActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import android.widget.ProgressBar
import ani.dantotsu.R
import ani.dantotsu.util.customAlertDialog
import ani.dantotsu.others.LanguageMapper.Companion.getLanguageName
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.source.ConfigurableSource

object ExtensionSettingsOpener {
    fun openConfigurableSourcePreferences(
        activity: FragmentActivity,
        configurableSources: List<Any>,
        onCloseAction: (() -> Unit)? = null
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
                        val changeUIVisibility: (Boolean) -> Unit = { show ->
                            activity.findViewById<LinearLayout>(R.id.extensionBrowseToolbar).isVisible = show
                            activity.findViewById<ChipGroup>(R.id.extensionBrowseChipGroup).isVisible = show
                            activity.findViewById<RecyclerView>(R.id.extensionBrowseRecycler).isVisible = show
                            activity.findViewById<ProgressBar>(R.id.extensionBrowseProgress).isVisible = if (show) activity.findViewById<ProgressBar>(R.id.extensionBrowseProgress).visibility == android.view.View.VISIBLE else false
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
                        val changeUIVisibility: (Boolean) -> Unit = { show ->
                            activity.findViewById<LinearLayout>(R.id.extensionBrowseToolbar).isVisible = show
                            activity.findViewById<ChipGroup>(R.id.extensionBrowseChipGroup).isVisible = show
                            activity.findViewById<RecyclerView>(R.id.extensionBrowseRecycler).isVisible = show
                            activity.findViewById<ProgressBar>(R.id.extensionBrowseProgress).isVisible = if (show) activity.findViewById<ProgressBar>(R.id.extensionBrowseProgress).visibility == android.view.View.VISIBLE else false
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

        if (configurableSources.size == 1) {
            openSingle(configurableSources[0])
            return
        }

        // Multiple options: prompt user to select
        val names = configurableSources.map { s ->
            when (s) {
                is ConfigurableAnimeSource -> getLanguageName(s.lang)
                is ConfigurableSource -> getLanguageName(s.lang)
                else -> ""
            }
        }.toTypedArray()

        var selectedIndex = 0
        activity.customAlertDialog().apply {
            setTitle(activity.getString(R.string.select_a_source))
            singleChoiceItems(names, selectedIndex) { which ->
                openSingle(configurableSources[which])
            }
            show()
        }
    }
}
