package ani.dantotsu.media.manga.mangareader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.currActivity
import ani.dantotsu.databinding.BottomSheetSelectorBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.MediaSingleton
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.others.getSerialized
import ani.dantotsu.tryWith
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.util.customAlertDialog
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Serializable

class ChapterLoaderDialog : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSelectorBinding? = null
    private val binding get() = _binding!!

    val model: MediaDetailsViewModel by activityViewModels()

    private val launch: Boolean by lazy { arguments?.getBoolean("launch", false) ?: false }
    private val chp: MangaChapter by lazy { arguments?.getSerialized("next")!! }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        var loaded = false
        binding.selectorAutoListContainer.visibility = View.VISIBLE
        binding.selectorListContainer.visibility = View.GONE

        binding.selectorTitle.text = getString(R.string.loading_chap_number, chp.number)
        binding.selectorCancel.setOnClickListener {
            dismiss()
        }

        model.getMedia().observe(viewLifecycleOwner) { m ->
            if (m != null && !loaded) {
                // If chapter title/number contains a lock emoji, show premium dialog instead of loading
                val hasLock = (chp.title?.contains("🔒") == true) || (chp.number.contains("🔒"))
                if (hasLock) {
                    val parser = model.mangaReadSources?.get(m.selected!!.sourceIndex) as? DynamicMangaParser
                    val sourceName = parser?.name
                        ?: (parser?.extension?.sources?.getOrNull(parser.sourceLanguage)?.name ?: getString(R.string.open_in_browser))

                    requireContext().customAlertDialog().apply {
                        setTitle(getString(R.string.premium_chapter_title))
                        setMessage(getString(R.string.premium_chapter_message, sourceName))
                        setPosButton(R.string.open_in_browser) {
                            // Try to open chapter in browser
                            if (parser != null) {
                                val httpSource = parser.extension.sources.getOrNull(parser.sourceLanguage) as? HttpSource
                                if (httpSource != null) {
                                    try {
                                        val url = httpSource.getChapterUrl(chp.sChapter)
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                        startActivity(intent)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                        setNegButton(R.string.cancel)
                        show()
                    }
                    dismiss()
                    return@observe
                }

                loaded = true
                binding.selectorAutoText.text = chp.title
                lifecycleScope.launch(Dispatchers.IO) {
                    if (model.loadMangaChapterImages(
                            chp,
                            m.selected!!
                        )
                    ) {
                        val activity = currActivity()
                        activity?.runOnUiThread {
                            tryWith { dismiss() }
                            if (launch) {
                                MediaSingleton.media = m
                                val intent = Intent(
                                    activity,
                                    MangaReaderActivity::class.java
                                )//.apply { putExtra("media", m) }
                                activity.startActivity(intent)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSelectorBinding.inflate(inflater, container, false)
        val window = dialog?.window
        window?.statusBarColor = Color.TRANSPARENT
        window?.navigationBarColor =
            requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
        return binding.root
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    companion object {
        fun newInstance(next: MangaChapter, launch: Boolean = false) = ChapterLoaderDialog().apply {
            arguments = bundleOf("next" to next as Serializable, "launch" to launch)
        }
    }
}