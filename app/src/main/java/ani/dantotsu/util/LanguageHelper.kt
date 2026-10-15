package ani.dantotsu.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.util.Locale

object LanguageHelper {

    const val SYSTEM_DEFAULT = "system"
    const val ENGLISH = "en"
    const val FRENCH = "fr"

    fun getSupportedLanguages(): List<LanguageOption> {
        return listOf(
            LanguageOption(SYSTEM_DEFAULT, "System Default", "Langue du système"),
            LanguageOption(ENGLISH, "English", "Anglais"),
            LanguageOption(FRENCH, "Français", "French")
        )
    }

    fun getCurrentLanguageCode(): String {
        return PrefManager.getVal(PrefName.AppLanguage, SYSTEM_DEFAULT)
    }

    /**
     * Makes the chosen language the app's own, so every activity is created in it before its
     * layout is inflated. The manual paths below only reach the activities that call them, and
     * [applyLanguage] from `initActivity` runs after most of them have already inflated in the
     * system language — which is how whole settings screens stayed untranslated.
     *
     * On Android 13+ this is the system's per-app language, set through [LocaleManager] directly:
     * AppCompat's own route there looks the service up through an open activity and silently does
     * nothing when there isn't one yet, as at startup. Below 13 AppCompat keeps it in memory and
     * applies it to each activity itself, which is why this runs on every start.
     *
     * Called at startup rather than when the language is picked: applying it then would recreate
     * the open activity under the "restart to apply" dialog.
     */
    fun applyAppLocales(context: Context, languageCode: String = getCurrentLanguageCode()) {
        val tags = if (languageCode == SYSTEM_DEFAULT) "" else languageCode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            val locales = LocaleList.forLanguageTags(tags)
            if (manager.applicationLocales != locales) manager.applicationLocales = locales
        } else {
            val locales = LocaleListCompat.forLanguageTags(tags)
            if (AppCompatDelegate.getApplicationLocales() != locales) {
                AppCompatDelegate.setApplicationLocales(locales)
            }
        }
    }

    fun setLanguage(context: Context, languageCode: String) {
        PrefManager.setVal(PrefName.AppLanguage, languageCode)
        applyLanguage(context, languageCode)
    }

    /**
     * Returns a new context with the language applied.
     * Use this in attachBaseContext() to properly apply language before activity creation.
     */
    fun applyLanguageToContext(context: Context, languageCode: String = getCurrentLanguageCode()): Context {
        val locale = when (languageCode) {
            SYSTEM_DEFAULT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    LocaleList.getDefault()[0]
                } else {
                    Locale.getDefault()
                }
            }
            else -> Locale(languageCode)
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }

        return context.createConfigurationContext(config)
    }

    /**
     * Apply language to an existing context (for compatibility).
     * Note: This updates the existing context but may not work properly for all cases.
     * Prefer using applyLanguageToContext() in attachBaseContext() instead.
     */
    fun applyLanguage(context: Context, languageCode: String = getCurrentLanguageCode()) {
        val locale = when (languageCode) {
            SYSTEM_DEFAULT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    LocaleList.getDefault()[0]
                } else {
                    Locale.getDefault()
                }
            }
            else -> Locale(languageCode)
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }

        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    fun getLanguageDisplayName(languageCode: String): String {
        return when (languageCode) {
            SYSTEM_DEFAULT -> "System Default"
            ENGLISH -> "English"
            FRENCH -> "Français"
            else -> languageCode
        }
    }

    data class LanguageOption(
        val code: String,
        val englishName: String,
        val nativeName: String
    ) {
        fun getDisplayName(): String {
            return if (code == SYSTEM_DEFAULT) englishName else "$nativeName ($englishName)"
        }
    }
}

