package ani.dantotsu.media.novel.novelreader

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.databinding.BottomSheetNovelBookmarksBinding
import ani.dantotsu.databinding.ItemNovelBookmarkBinding
import java.util.Date

/** The novel's bookmarks, newest first: tap one to go there, or delete it. */
class NovelBookmarksSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetNovelBookmarksBinding? = null
    private val binding get() = _binding!!

    private val reader get() = activity as? NovelReaderActivity
    private var bookmarks = mutableListOf<NovelBookmark>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetNovelBookmarksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bookmarks = reader?.bookmarks().orEmpty().toMutableList()
        binding.novelBookmarksList.adapter = adapter
        showEmpty()
    }

    private fun showEmpty() {
        binding.novelBookmarksEmpty.isVisible = bookmarks.isEmpty()
        binding.novelBookmarksList.isVisible = bookmarks.isNotEmpty()
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = bookmarks.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val item = ItemNovelBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return object : RecyclerView.ViewHolder(item.root) {}.also { it.itemView.tag = item }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = holder.itemView.tag as ItemNovelBookmarkBinding
            val bookmark = bookmarks[position]
            item.novelBookmarkChapter.text = bookmark.chapterTitle
            item.novelBookmarkChapter.isVisible = bookmark.chapterTitle.isNotBlank()
            item.novelBookmarkExcerpt.text = bookmark.excerpt
            item.novelBookmarkExcerpt.isVisible = bookmark.excerpt.isNotBlank()
            item.novelBookmarkDate.text = if (bookmark.createdAt > 0) {
                DateFormat.getMediumDateFormat(holder.itemView.context).format(Date(bookmark.createdAt))
            } else ""
            item.root.setOnClickListener {
                reader?.openBookmark(bookmark)
                dismiss()
            }
            item.novelBookmarkDelete.setOnClickListener {
                val index = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?: return@setOnClickListener
                reader?.removeBookmark(bookmarks[index])
                bookmarks.removeAt(index)
                notifyItemRemoved(index)
                showEmpty()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "NovelBookmarksSheet"
    }
}
