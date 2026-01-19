package ani.dantotsu.connections.malsync

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import ani.dantotsu.R

/**
 * Data class representing a language option with icon
 */
data class LanguageOption(
    val id: String,           // e.g., "en/dub", "en/sub"
    val displayName: String,  // e.g., "English (Dub)", "English (Sub)"
    val iconRes: Int,         // Drawable resource ID
    val episodeCount: Int? = null  // Episode count for this language
) {
    override fun toString(): String {
        // This is what AutoCompleteTextView uses for the text field
        return displayName
    }
}

/**
 * Custom adapter for displaying languages with icons
 */
class LanguageAdapter(
    context: Context,
    private val languages: List<LanguageOption>,
    private val isForListPopup: Boolean = false
) : ArrayAdapter<LanguageOption>(context, R.layout.item_language_dropdown, languages) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        // For ListPopupWindow (isForListPopup=true), always show episode count
        // For AutoCompleteTextView (collapsed state), don't show episode count
        return createItemView(position, convertView, parent, showEpisodeCount = isForListPopup)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        // For dropdown items in AutoCompleteTextView, show episode count
        return createItemView(position, convertView, parent, showEpisodeCount = true)
    }

    private fun createItemView(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
        showEpisodeCount: Boolean
    ): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_language_dropdown, parent, false)

        val language = languages[position]
        val icon = view.findViewById<ImageView>(R.id.languageIcon)
        val text = view.findViewById<TextView>(R.id.languageText)

        icon.setImageResource(language.iconRes)

        // Show episode count only in dropdown items
        val displayText = if (showEpisodeCount && language.episodeCount != null && language.episodeCount > 0) {
            "${language.displayName} - Ep ${language.episodeCount}"
        } else {
            language.displayName
        }

        // Debug logging
        if (showEpisodeCount) {
            android.util.Log.d("LanguageAdapter", "Dropdown item: ${language.id}, episodeCount: ${language.episodeCount}, displayText: $displayText")
        }

        text.text = displayText

        return view
    }
}

/**
 * Helper to convert MALSync language IDs to display format with icons
 */
object LanguageMapper {

    /**
     * Map language ID to full name and icon
     */
    fun mapLanguage(languageId: String, episodeCount: Int? = null): LanguageOption {
        return when (languageId.lowercase()) {
            "en/dub" -> LanguageOption(languageId, "English", R.drawable.ic_anime_dub_24, episodeCount)
            "en/sub" -> LanguageOption(languageId, "English", R.drawable.ic_anime_sub_24, episodeCount)
            "en" -> LanguageOption(languageId, "English", R.drawable.ic_anime_sub_24, episodeCount)
            "ja/dub" -> LanguageOption(languageId, "Japanese", R.drawable.ic_anime_dub_24, episodeCount)
            "ja/sub" -> LanguageOption(languageId, "Japanese", R.drawable.ic_anime_sub_24, episodeCount)
            "ja" -> LanguageOption(languageId, "Japanese", R.drawable.ic_anime_sub_24, episodeCount)
            "jp/dub" -> LanguageOption(languageId, "Japanese", R.drawable.ic_anime_dub_24, episodeCount)
            "jp/sub" -> LanguageOption(languageId, "Japanese", R.drawable.ic_anime_sub_24, episodeCount)
            "jp" -> LanguageOption(languageId, "Japanese", R.drawable.ic_anime_sub_24, episodeCount)
            "de/dub" -> LanguageOption(languageId, "German", R.drawable.ic_anime_dub_24, episodeCount)
            "de/sub" -> LanguageOption(languageId, "German", R.drawable.ic_anime_sub_24, episodeCount)
            "de" -> LanguageOption(languageId, "German", R.drawable.ic_anime_sub_24, episodeCount)
            "fr/dub" -> LanguageOption(languageId, "French", R.drawable.ic_anime_dub_24, episodeCount)
            "fr/sub" -> LanguageOption(languageId, "French", R.drawable.ic_anime_sub_24, episodeCount)
            "fr" -> LanguageOption(languageId, "French", R.drawable.ic_anime_sub_24, episodeCount)
            "es/dub" -> LanguageOption(languageId, "Spanish", R.drawable.ic_anime_dub_24, episodeCount)
            "es/sub" -> LanguageOption(languageId, "Spanish", R.drawable.ic_anime_sub_24, episodeCount)
            "es" -> LanguageOption(languageId, "Spanish", R.drawable.ic_anime_sub_24, episodeCount)
            "pt/dub" -> LanguageOption(languageId, "Portuguese", R.drawable.ic_anime_dub_24, episodeCount)
            "pt/sub" -> LanguageOption(languageId, "Portuguese", R.drawable.ic_anime_sub_24, episodeCount)
            "pt" -> LanguageOption(languageId, "Portuguese", R.drawable.ic_anime_sub_24, episodeCount)
            "it/dub" -> LanguageOption(languageId, "Italian", R.drawable.ic_anime_dub_24, episodeCount)
            "it/sub" -> LanguageOption(languageId, "Italian", R.drawable.ic_anime_sub_24, episodeCount)
            "it" -> LanguageOption(languageId, "Italian", R.drawable.ic_anime_sub_24, episodeCount)
            "ru/dub" -> LanguageOption(languageId, "Russian", R.drawable.ic_anime_dub_24, episodeCount)
            "ru/sub" -> LanguageOption(languageId, "Russian", R.drawable.ic_anime_sub_24, episodeCount)
            "ru" -> LanguageOption(languageId, "Russian", R.drawable.ic_anime_sub_24, episodeCount)
            "ar/dub" -> LanguageOption(languageId, "Arabic", R.drawable.ic_anime_dub_24, episodeCount)
            "ar/sub" -> LanguageOption(languageId, "Arabic", R.drawable.ic_anime_sub_24, episodeCount)
            "ar" -> LanguageOption(languageId, "Arabic", R.drawable.ic_anime_sub_24, episodeCount)
            "zh/dub" -> LanguageOption(languageId, "Chinese", R.drawable.ic_anime_dub_24, episodeCount)
            "zh/sub" -> LanguageOption(languageId, "Chinese", R.drawable.ic_anime_sub_24, episodeCount)
            "zh" -> LanguageOption(languageId, "Chinese", R.drawable.ic_anime_sub_24, episodeCount)
            "ko/dub" -> LanguageOption(languageId, "Korean", R.drawable.ic_anime_dub_24, episodeCount)
            "ko/sub" -> LanguageOption(languageId, "Korean", R.drawable.ic_anime_sub_24, episodeCount)
            "ko" -> LanguageOption(languageId, "Korean", R.drawable.ic_anime_sub_24, episodeCount)
            "id/dub" -> LanguageOption(languageId, "Indonesian", R.drawable.ic_anime_dub_24, episodeCount)
            "id/sub" -> LanguageOption(languageId, "Indonesian", R.drawable.ic_anime_sub_24, episodeCount)
            "id" -> LanguageOption(languageId, "Indonesian", R.drawable.ic_anime_sub_24, episodeCount)
            "ms/dub" -> LanguageOption(languageId, "Malay", R.drawable.ic_anime_dub_24, episodeCount)
            "ms/sub" -> LanguageOption(languageId, "Malay", R.drawable.ic_anime_sub_24, episodeCount)
            "ms" -> LanguageOption(languageId, "Malay", R.drawable.ic_anime_sub_24, episodeCount)
            "th/dub" -> LanguageOption(languageId, "Thai", R.drawable.ic_anime_dub_24, episodeCount)
            "th/sub" -> LanguageOption(languageId, "Thai", R.drawable.ic_anime_sub_24, episodeCount)
            "th" -> LanguageOption(languageId, "Thai", R.drawable.ic_anime_sub_24, episodeCount)
            "vi/dub" -> LanguageOption(languageId, "Vietnamese", R.drawable.ic_anime_dub_24, episodeCount)
            "vi/sub" -> LanguageOption(languageId, "Vietnamese", R.drawable.ic_anime_sub_24, episodeCount)
            "vi" -> LanguageOption(languageId, "Vietnamese", R.drawable.ic_anime_sub_24, episodeCount)
            else -> {
                // Default: check if it ends with /dub or /sub
                val icon = if (languageId.endsWith("/dub")) {
                    R.drawable.ic_anime_dub_24
                } else {
                    R.drawable.ic_anime_sub_24
                }
                val displayName = languageId.split("/").let { parts ->
                    parts[0].uppercase()
                }
                LanguageOption(languageId, displayName, icon, episodeCount)
            }
        }
    }

    /**
     * Convert list of language IDs to LanguageOptions with episode counts
     */
    fun mapLanguagesWithEpisodes(malSyncResponses: List<MalSyncResponse>): List<LanguageOption> {
        return malSyncResponses.map { response ->
            mapLanguage(response.id, response.lastEp?.total)
        }
    }

    /**
     * Convert list of language IDs to LanguageOptions (without episode counts)
     */
    fun mapLanguages(languageIds: List<String>): List<LanguageOption> {
        return languageIds.map { mapLanguage(it) }
    }
}
