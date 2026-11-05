package ani.dantotsu.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.util.Locale

object LanguageHelper {

    const val SYSTEM_DEFAULT = "system"
    const val ENGLISH = "en"
    const val FRENCH = "fr"

    /**
     * What a language code runs as. The app's text comes from its strings either way, so this only
     * decides dates and times: plain "en" formats the American way, with 12-hour clocks and the
     * month first, where English here is read with 24-hour clocks and the day first.
     */
    private fun localeOf(languageCode: String): Locale =
        if (languageCode == ENGLISH) Locale.UK else Locale(languageCode)

    fun getSupportedLanguages(context: Context): List<LanguageOption> {
        return listOf(
            LanguageOption(SYSTEM_DEFAULT, getLanguageDisplayName(context, SYSTEM_DEFAULT)),
            LanguageOption(ENGLISH, "English"),
            LanguageOption(FRENCH, "Français")
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
        val tags = if (languageCode == SYSTEM_DEFAULT) "" else localeOf(languageCode).toLanguageTag()
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
            else -> localeOf(languageCode)
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
            else -> localeOf(languageCode)
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

    /**
     * The app's language when it follows the device: the first in the device's list the app has,
     * as Android itself picks, and English if it has none of them. The device's list is read rather
     * than the app's current locale, which a language picked in the app has replaced.
     */
    private fun systemLanguageCode(context: Context): String {
        val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales
        } else null
        val locales = system ?: Resources.getSystem().configuration.locales
        return (0 until locales.size()).map { locales[it].language }
            .firstOrNull { it == ENGLISH || it == FRENCH } ?: ENGLISH
    }

    fun getLanguageDisplayName(context: Context, languageCode: String): String {
        return when (languageCode) {
            SYSTEM_DEFAULT -> context.getString(
                R.string.language_system_default,
                getLanguageDisplayName(context, systemLanguageCode(context)),
            )

            ENGLISH -> "English"
            FRENCH -> "Français"
            else -> languageCode
        }
    }

    /** [name] is the language's own name, the same whichever language the app is in. */
    data class LanguageOption(val code: String, val name: String) {
        fun getDisplayName(): String = name
    }
}

