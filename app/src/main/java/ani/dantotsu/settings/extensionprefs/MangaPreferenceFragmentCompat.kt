package ani.dantotsu.settings.extensionprefs

import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import ani.dantotsu.px
import android.view.ViewGroup
import android.view.LayoutInflater
import android.graphics.drawable.Drawable
import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.forEach
import androidx.preference.getOnBindEditTextListener
import ani.dantotsu.navBarHeight
import eu.kanade.tachiyomi.PreferenceScreen
import eu.kanade.tachiyomi.data.preference.SharedPreferencesDataStore
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.manga.getPreferenceKey
import eu.kanade.tachiyomi.widget.TachiyomiTextInputEditText.Companion.setIncognito
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaSourcePreferencesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = populateMangaPreferenceScreen()
    }

    private var onCloseAction: (() -> Unit)? = null

    /** The page's title and icon: the extension's, found while populating the screen. */
    private var pageTitle: CharSequence = ""
    private var pageIcon: Drawable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val list = super.onCreateView(inflater, container, savedInstanceState)
        return ExtensionPreferencesUi.wrap(this, inflater, container, list, pageTitle, pageIcon)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.clipToPadding = false
        listView.setPadding(0, 8f.px, 0, navBarHeight + 16f.px)
        ExtensionPreferencesUi.tile(listView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        onCloseAction?.invoke()

    }

    fun populateMangaPreferenceScreen(): PreferenceScreen {
        val sourceId = requireArguments().getLong(SOURCE_ID)
        val source = Injekt.get<MangaSourceManager>().get(sourceId)!!
        check(source is ConfigurableSource)
        val sharedPreferences =
            requireContext().getSharedPreferences(source.getPreferenceKey(), Context.MODE_PRIVATE)
        val dataStore = SharedPreferencesDataStore(sharedPreferences)
        preferenceManager.preferenceDataStore = dataStore
        val sourceScreen = preferenceManager.createPreferenceScreen(requireContext())
        source.setupPreferenceScreen(sourceScreen)
        pageTitle = source.name
        pageIcon = Injekt.get<MangaExtensionManager>().installedExtensionsFlow.value
            .find { ext -> ext.sources.any { it.id == sourceId } }?.icon
        ExtensionPreferencesUi.style(sourceScreen)
        sourceScreen.forEach { pref ->
            if (pref is DialogPreference) {
                pref.dialogTitle = pref.title
            }

            // Apply incognito IME for EditTextPreference
            if (pref is EditTextPreference) {
                val setListener = pref.getOnBindEditTextListener()
                pref.setOnBindEditTextListener {
                    setListener?.onBindEditText(it)
                    it.setIncognito(lifecycleScope)
                }
            }
        }

        return sourceScreen
    }

    fun getInstance(
        sourceId: Long,
        onCloseAction: (() -> Unit)? = null
    ): MangaSourcePreferencesFragment {
        val fragment = MangaSourcePreferencesFragment()
        fragment.arguments = bundleOf(SOURCE_ID to sourceId)
        fragment.onCloseAction = onCloseAction
        return fragment
    }

    companion object {
        private const val SOURCE_ID = "source_id"
    }
}