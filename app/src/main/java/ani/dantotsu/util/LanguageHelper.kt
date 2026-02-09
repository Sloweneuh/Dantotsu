package ani.dantotsu.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
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

    fun setLanguage(context: Context, languageCode: String) {
        PrefManager.setVal(PrefName.AppLanguage, languageCode)
        applyLanguage(context, languageCode)
    }

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

