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
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.connections.anilist.Anilist
import android.widget.CheckBox
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
                                val intent = Intent(activity, MangaReaderActivity::class.java)
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

        fun showProgressPopupIfNecessary(
            activity: android.app.Activity,
            media: ani.dantotsu.media.Media,
            onResult: () -> Unit
        ) {
            val isContinuousMultiChapter = PrefManager.getVal<Boolean>(PrefName.ContinuousMultiChapter)
            val incognito = PrefManager.getVal<Boolean>(PrefName.Incognito)
            val isAdultAllowed = !media.isAdult || PrefManager.getVal<Boolean>(PrefName.UpdateForHReader)
            val shouldAsk = if (PrefManager.getVal<Boolean>(PrefName.AskIndividualReader))
                PrefManager.getCustomVal("${media.id}_progressDialog", true)
            else false

            if (isContinuousMultiChapter && shouldAsk && !incognito && isAdultAllowed && Anilist.userid != null) {
                val dialogView = activity.layoutInflater.inflate(R.layout.item_custom_dialog, null)
                val checkbox = dialogView.findViewById<CheckBox>(R.id.dialog_checkbox)
                checkbox.text = activity.getString(R.string.dont_ask_again, media.userPreferredName)
                
                var isCheckedNow = false
                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    isCheckedNow = isChecked
                }
                
                activity.customAlertDialog().apply {
                    setTitle(R.string.title_update_progress)
                    setCustomView(dialogView)
                    setCancelable(false)
                    setPosButton(R.string.yes) {
                        PrefManager.setCustomVal("${media.id}_progressDialog", !isCheckedNow)
                        PrefManager.setCustomVal("${media.id}_save_progress", true)
                        onResult()
                    }
                    setNegButton(R.string.no) {
                        PrefManager.setCustomVal("${media.id}_progressDialog", !isCheckedNow)
                        PrefManager.setCustomVal("${media.id}_save_progress", false)
                        onResult()
                    }
                    show()
                }
            } else {
                onResult()
            }
        }
    }
}