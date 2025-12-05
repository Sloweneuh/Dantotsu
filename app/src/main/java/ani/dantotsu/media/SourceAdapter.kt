package ani.dantotsu.media

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemCharacterBinding
import ani.dantotsu.loadImage
import ani.dantotsu.parsers.ShowResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

abstract class SourceAdapter(
    private val sources: List<ShowResponse>,
    private val dialogFragment: SourceSearchDialogFragment,
    private val scope: CoroutineScope
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