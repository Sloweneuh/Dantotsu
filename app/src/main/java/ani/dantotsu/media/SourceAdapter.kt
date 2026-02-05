package ani.dantotsu.media

import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemCharacterBinding
import ani.dantotsu.loadImage
import ani.dantotsu.parsers.BaseParser
import ani.dantotsu.parsers.DynamicAnimeParser
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.parsers.ShowResponse
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

abstract class SourceAdapter(
    private val sources: List<ShowResponse>,
    private val dialogFragment: SourceSearchDialogFragment,
    private val scope: CoroutineScope,
    private val hostUrl: String = "",
    private val parser: BaseParser? = null
) : RecyclerView.Adapter<SourceAdapter.SourceViewHolder>() {

    /**
     * Extracts translator/group name from title and returns a pair of (cleanedTitle, groupName)
     * Recognizes patterns like brackets, parentheses, and various bracket types
     */
    private fun extractGroupName(title: String): Pair<String, String?> {
        // Regex to match common patterns at the end of titles
        // Matches: [Group], (Group), 【Group】, 〈Group〉, {Group}
        val endPattern = Regex("""\s*[(\[【〈{]([^)\]】〉}]+)[)\]】〉}]\s*$""")
        val startPattern = Regex("""^[(\[【〈{]([^)\]】〉}]+)[)\]】〉}]\s*""")

        // Try matching at the end first
        endPattern.find(title)?.let { match ->
            val groupName = match.groupValues[1].trim()
            val cleanedTitle = title.replace(endPattern, "").trim()
            return Pair(cleanedTitle, groupName)
        }
        
        // Try matching at the start
        startPattern.find(title)?.let { match ->
            val groupName = match.groupValues[1].trim()
            val cleanedTitle = title.replace(startPattern, "").trim()
            return Pair(cleanedTitle, groupName)
        }
        
        return Pair(title, null)
    }

    // ...existing code...

    private fun isValidUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        // Check if it's a valid URL format
        return url.startsWith("http://") ||
               url.startsWith("https://") ||
               url.startsWith("//") ||
               url.startsWith("/") && hostUrl.isNotBlank() ||  // Relative URL with leading slash
               (url.contains("/") && !url.startsWith("/") && hostUrl.isNotBlank())  // Relative URL without leading slash
    }

    private fun openInBrowser(source: ShowResponse) {
        val context = dialogFragment.requireContext()
        var url = source.link

        // Validate URL again as a safety check
        if (url.isBlank()) {
            return
        }

        // Try to get the proper URL from the extension if we have parser with source object
        try {
            when (parser) {
                is DynamicMangaParser -> {
                    if (source.sManga != null) {
                        val httpSource = parser.extension.sources.getOrNull(parser.sourceLanguage) as? HttpSource
                        if (httpSource != null) {
                            val properUrl = httpSource.getMangaUrl(source.sManga)
                            if (properUrl.isNotBlank()) {
                                url = properUrl
                            }
                        }
                    }
                }
                is DynamicAnimeParser -> {
                    if (source.sAnime != null) {
                        val httpSource = parser.extension.sources.getOrNull(parser.sourceLanguage) as? eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
                        if (httpSource != null) {
                            val properUrl = httpSource.getAnimeUrl(source.sAnime)
                            if (properUrl.isNotBlank()) {
                                url = properUrl
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Silently fail and use the original URL
        }

        // Check if the link is a relative URL and needs a base URL
        val fullUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") && hostUrl.isNotBlank() -> {
                // Relative URL starting with /
                val cleanHostUrl = hostUrl.removeSuffix("/")
                "$cleanHostUrl$url"
            }
            hostUrl.isNotBlank() -> {
                // Relative URL without leading slash
                val cleanHostUrl = hostUrl.removeSuffix("/")
                "$cleanHostUrl/$url"
            }
            else -> {
                // If it's a relative path without a base URL, we can't open it
                return
            }
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, fullUrl.toUri())
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No browser app available
            Toast.makeText(
                context,
                "No browser app found to open the link",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            // Handle any other errors
            Toast.makeText(
                context,
                "Failed to open link: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val binding =
            ItemCharacterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SourceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        val binding = holder.binding
        val character = sources[position]
        binding.itemCompactImage.loadImage(character.coverUrl, 200)
        binding.itemCompactTitle.isSelected = true

        // Extract group name from title
        val (cleanedTitle, groupName) = extractGroupName(character.name)

        binding.itemCompactTitle.text = cleanedTitle

        // Display group name if it exists
        if (groupName != null) {
            binding.itemCompactRelation.visibility = View.VISIBLE
            binding.itemCompactRelation.text = groupName
        } else {
            binding.itemCompactRelation.visibility = View.GONE
        }

        // Check if link is valid and set long-click listener on image
        val hasValidLink = isValidUrl(character.link)

        if (hasValidLink) {
            binding.itemCompactImage.setOnLongClickListener {
                openInBrowser(character)
                true
            }
        } else {
            binding.itemCompactImage.setOnLongClickListener(null)
            binding.itemCompactImage.isLongClickable = false
        }

        // Ensure image click still selects the item
        binding.itemCompactImage.setOnClickListener {
            holder.itemView.performClick()
        }
    }

    override fun getItemCount(): Int = sources.size

    abstract suspend fun onItemClick(source: ShowResponse)

    inner class SourceViewHolder(val binding: ItemCharacterBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                dialogFragment.dismiss()
                scope.launch(Dispatchers.IO) { onItemClick(sources[bindingAdapterPosition]) }
            }
            var a = true
            itemView.setOnLongClickListener {
                a = !a
                binding.itemCompactTitle.isSingleLine = a
                true
            }
        }
    }
}