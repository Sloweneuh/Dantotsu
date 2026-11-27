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
import ani.dantotsu.media.Media
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

        binding.selectorTitle.text = getString(
            R.string.loading_chap_number, ani.dantotsu.media.MediaNameAdapter.withoutChapterWord(chp.number)
        )
        binding.selectorCancel.setOnClickListener {
            dismiss()
        }

        fun start(m: Media) {
            run {
                // A premium chapter can't be read here; offer the source's page instead.
                if (isLocked(chp)) {
                    showLocked(requireContext(), model, m, chp)
                    dismiss()
                    return
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

        // The read tab can be running on the media the page was opened with (previewMedia) while
        // AniList's full details are still queued behind its rate limit. Its source and chapters
        // are already set on that object, so the pages can load now instead of waiting on AniList.
        val preview = model.previewMedia
        if (model.getMedia().value == null && preview?.selected != null) start(preview)

        model.getMedia().observe(viewLifecycleOwner) { m ->
            if (m != null && !loaded) start(m)
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

        /** A chapter the source marks as premium: a lock in its title or number. */
        fun isLocked(chp: MangaChapter) =
            chp.title?.contains("🔒") == true || chp.number.contains("🔒")

        /** Says [chp] is premium, and offers to open it on the source's site. */
        fun showLocked(
            context: android.content.Context,
            model: MediaDetailsViewModel,
            media: ani.dantotsu.media.Media,
            chp: MangaChapter,
        ) {
            val parser = model.mangaReadSources?.get(media.selected!!.sourceIndex) as? DynamicMangaParser
            val sourceName = parser?.name
                ?: (parser?.extension?.sources?.getOrNull(parser.sourceLanguage)?.name
                    ?: context.getString(R.string.open_in_browser))
            context.customAlertDialog().apply {
                setTitle(context.getString(R.string.premium_chapter_title))
                setMessage(context.getString(R.string.premium_chapter_message, sourceName))
                setPosButton(R.string.open_in_browser) {
                    val httpSource = parser?.extension?.sources?.getOrNull(parser.sourceLanguage) as? HttpSource
                    if (httpSource != null) {
                        try {
                            val url = httpSource.getChapterUrl(chp.sChapter)
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            )
                        } catch (_: Exception) {
                        }
                    }
                }
                setNegButton(R.string.cancel)
                show()
            }
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
                    // Tapping outside (or backing out) means the same as Cancel: neither answers
                    // the question, so neither records one and neither continues. Cancel already
                    // does nothing but dismiss, so there is nothing to hook it up to.
                    setCancelable(true)
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
                    setNeutralButton(R.string.cancel)
                    show()
                }
            } else {
                onResult()
            }
        }
    }
}