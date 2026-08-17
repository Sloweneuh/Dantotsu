package ani.dantotsu.media.novel

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetSelectorBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.novel.novelreader.NovelReaderActivity
import ani.dantotsu.parsers.novel.lnreader.LNReaderBook
import ani.dantotsu.parsers.novel.lnreader.LNReaderNovel
import ani.dantotsu.parsers.novel.lnreader.LNReaderParser
import ani.dantotsu.parsers.novel.lnreader.LNReaderReadState
import ani.dantotsu.parsers.novel.lnreader.LNReaderSession
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The sheet that fetches a chapter and opens the reader on it.
 *
 * The novel counterpart of [ani.dantotsu.media.manga.mangareader.ChapterLoaderDialog], and shown at
 * the same point for the same reason: a chapter only exists as HTML on someone's site until it is
 * asked for, so opening one is a network round trip. A sheet naming what is being fetched, with a
 * cancel button, is a better answer to that wait than a screen that appears to have ignored the tap.
 *
 * What it is handed cannot go in a Bundle — a parser holds a live JavaScript context and a novel
 * runs to thousands of chapters — so the request is parked in [pending] and picked up in
 * [onCreate], the same handoff [ani.dantotsu.media.screenshot.ScreenshotDialogFragment] uses.
 */
class NovelChapterLoaderDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSelectorBinding? = null
    private val binding get() = _binding!!

    private class Request(
        val parser: LNReaderParser,
        val novel: LNReaderNovel,
        val index: Int,
        val media: Media?,
    )

    private var request: Request? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = pending
        pending = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.selectorAutoListContainer.visibility = View.VISIBLE
        binding.selectorListContainer.visibility = View.GONE
        binding.selectorCancel.setOnClickListener { dismiss() }

        // Recreated without its request — the process was killed while the sheet was up. There is
        // nothing to fetch and no way to recover what was asked for, so it just goes away.
        val req = request ?: run { dismiss(); return }
        val chapter = req.novel.chapters.getOrNull(req.index) ?: run { dismiss(); return }
        binding.selectorTitle.text = getString(R.string.loading_chap_number, chapter.name)

        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                LNReaderBook.build(requireContext().applicationContext, req.parser, req.novel, req.index)
            }
            if (_binding == null) return@launch

            file.onSuccess { epub ->
                LNReaderSession.start(req.parser, req.novel, req.index, req.media)
                LNReaderReadState.markRead(
                    req.media?.id, req.parser.plugin.id, req.novel.path, chapter.path
                )
                val context = requireContext()
                // Through the app's FileProvider rather than a file:// URI: the reader is a
                // separate activity, and a raw path would trip Android's file-URI exposure check.
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.provider", epub
                )
                startActivity(
                    Intent(context, NovelReaderActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        setDataAndType(uri, "application/epub+zip")
                        putExtra(NovelReaderActivity.EXTRA_LN_SESSION, true)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                )
                dismiss()
            }.onFailure {
                Logger.log("Novel chapter open failed: ${it.message}")
                snackString(it.message ?: getString(R.string.failed_to_load))
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private var pending: Request? = null

        fun show(
            manager: FragmentManager,
            parser: LNReaderParser,
            novel: LNReaderNovel,
            index: Int,
            media: Media?,
        ) {
            pending = Request(parser, novel, index, media)
            NovelChapterLoaderDialog().show(manager, "novel_chapter_loader")
        }
    }
}
