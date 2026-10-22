package ani.dantotsu.media.manga.mangareader

import android.widget.CheckBox
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog

/**
 * Progress from the manga reader to the user's lists: asking whether to update it at the end of a
 * chapter, and updating it as chapters go by in the continuous multi-chapter mode.
 */
class ReaderProgress(private val activity: MangaReaderActivity) {

    /**
     * Chapter indices whose progress has already been pushed during this reader session.
     * Resting on a transition leaves the tracked chapter oscillating across the divider — the
     * item below is only borderline visible, so it drops in and out of the visible range as the
     * images under it settle from their placeholder height into their real one. Each forward flip
     * looks like a fresh chapter boundary, so without this the same update (and its toast) fires
     * over and over while the reader sits still.
     */
    private val progressedChapterIndices = mutableSetOf<Int>()

    /**
     * Whether progress may be written for this media without a prompt.
     *
     * `AskIndividualReader` off means the setting itself has opted into always auto-updating.
     * Otherwise the user must have actually answered the "update progress?" question at some point
     * — both Yes and No write `_save_progress`, so the key existing is the record of a choice.
     * When asking is on and that key is absent the prompt was skipped rather than answered (an
     * adult title with H-updates off, a stale "don't ask again" flag, an entry path that bypassed
     * [ChapterLoaderDialog.showProgressPopupIfNecessary]); silently tracking there is exactly the
     * "never asked but tracked anyway" case, so don't.
     */
    fun maySaveSilently(): Boolean {
        if (!PrefManager.getVal<Boolean>(PrefName.AskIndividualReader)) return true
        return PrefManager.customValExists("${activity.media.id}_save_progress")
    }

    /**
     * At the end of a chapter, before [runnable] moves on: asks whether to update the list, or
     * updates it as already decided, when the reader is on the chapter's last page.
     */
    fun beforeLeaving(runnable: Runnable) {
        if (activity.media.id < 0) { runnable.run(); return }
        if (activity.maxChapterPage - activity.currentChapterPage <= 1 && Anilist.userid != null) {
            activity.showProgressDialog =
                if (PrefManager.getVal(PrefName.AskIndividualReader)) PrefManager.getCustomVal(
                    "${activity.media.id}_progressDialog",
                    true
                )
                else false
            val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
            val isContinuousMultiChapter = PrefManager.getVal<Boolean>(PrefName.ContinuousMultiChapter)
            if (activity.showProgressDialog && !incognito && !isContinuousMultiChapter) {

                val dialogView = activity.layoutInflater.inflate(R.layout.item_custom_dialog, null)
                val checkbox = dialogView.findViewById<CheckBox>(R.id.dialog_checkbox)
                checkbox.text = activity.getString(R.string.dont_ask_again, activity.media.userPreferredName)
                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    PrefManager.setCustomVal("${activity.media.id}_progressDialog", !isChecked)
                    activity.showProgressDialog = !isChecked
                }
                activity.customAlertDialog().apply {
                    setTitle(R.string.title_update_progress)
                    setCustomView(dialogView)
                    // Not dismissable, deliberately: the two buttons are the only answers and one
                    // of them is needed. The checkbox beside them writes "don't ask again" the
                    // moment it's ticked, so a dismissal would suppress the dialog for good while
                    // leaving the answer it suppresses unset — which then falls back to the
                    // default, silently auto-updating progress from then on. That is the opposite
                    // of what walking away from the question implies.
                    setCancelable(false)
                    setPosButton(R.string.yes) {
                        PrefManager.setCustomVal("${activity.media.id}_save_progress", true)
                        updateProgress(
                            activity.media,
                            MediaNameAdapter.findChapterNumber(activity.media.manga!!.selectedChapter!!.number)
                                .toString(),
                            MediaNameAdapter.findVolumeNumber(activity.media.manga!!.selectedChapter!!.number)
                        )
                        runnable.run()
                    }
                    setNegButton(R.string.no) {
                        PrefManager.setCustomVal("${activity.media.id}_save_progress", false)
                        runnable.run()
                    }
                    show()

                }
            } else {
                if (!incognito && maySaveSilently() && PrefManager.getCustomVal(
                        "${activity.media.id}_save_progress",
                        true
                    ) && if (activity.media.isAdult) PrefManager.getVal(PrefName.UpdateForHReader) else true
                )
                    updateProgress(
                        activity.media,
                        MediaNameAdapter.findChapterNumber(activity.media.manga!!.selectedChapter!!.number)
                            .toString(),
                        MediaNameAdapter.findVolumeNumber(activity.media.manga!!.selectedChapter!!.number)
                    )
                runnable.run()
            }
        } else {
            runnable.run()
        }
    }

    /**
     * Silently updates progress when crossing a chapter boundary in continuous multi-chapter
     * mode. The user's choice is collected once up front by
     * [ChapterLoaderDialog.showProgressPopupIfNecessary] when a chapter is tapped in the list,
     * so each transition only consults the stored `_save_progress` decision — and only when a
     * decision is actually on record ([maySaveSilently]), so a prompt that was skipped
     * rather than answered doesn't turn into silent tracking. Fires at most once per chapter per
     * session — see [progressedChapterIndices].
     */
    fun chapterFinished(completedChapter: MangaChapter, chapterIndex: Int) {
        if (activity.media.id < 0) return
        if (Anilist.userid == null) return
        if (!progressedChapterIndices.add(chapterIndex)) return
        val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
        if (incognito) return
        if (activity.media.isAdult && !PrefManager.getVal<Boolean>(PrefName.UpdateForHReader)) return

        val chapterNum = MediaNameAdapter.findChapterNumber(completedChapter.number)?.toString() ?: return

        if (maySaveSilently() && PrefManager.getCustomVal("${activity.media.id}_save_progress", true)) {
            updateProgress(activity.media, chapterNum, MediaNameAdapter.findVolumeNumber(completedChapter.number))
        }
    }
}
