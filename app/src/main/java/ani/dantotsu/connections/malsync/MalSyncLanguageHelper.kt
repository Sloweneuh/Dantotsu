package ani.dantotsu.connections.malsync

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * Helper object to manage per-media language preferences for MALSync anime
 */
object MalSyncLanguageHelper {

    /**
     * Get the preferred language for a specific media
     * @param mediaId AniList media ID
     * @return Preferred language (e.g., "en/dub") or default "en/dub"
     */
    fun getPreferredLanguage(mediaId: Int): String {
        val preferences = PrefManager.getVal<Set<String>>(PrefName.MalSyncLanguagePreferences)
        val entry = preferences.firstOrNull { it.startsWith("$mediaId:") }
        return entry?.substringAfter(":")  ?: "en/dub" // Default to en/dub
    }

    /**
     * Set the preferred language for a specific media
     * @param mediaId AniList media ID
     * @param language Language ID (e.g., "en/dub", "en/sub")
     */
    fun setPreferredLanguage(mediaId: Int, language: String) {
        val preferences = PrefManager.getVal<Set<String>>(PrefName.MalSyncLanguagePreferences)
        val updated = preferences.filterNot { it.startsWith("$mediaId:") }.toMutableSet()
        updated.add("$mediaId:$language")
        PrefManager.setVal(PrefName.MalSyncLanguagePreferences, updated)
    }

    /**
     * Remove language preference for a specific media
     * @param mediaId AniList media ID
     */
    fun removePreferredLanguage(mediaId: Int) {
        val preferences = PrefManager.getVal<Set<String>>(PrefName.MalSyncLanguagePreferences)
        val updated = preferences.filterNot { it.startsWith("$mediaId:") }.toSet()
        PrefManager.setVal(PrefName.MalSyncLanguagePreferences, updated)
    }
}
