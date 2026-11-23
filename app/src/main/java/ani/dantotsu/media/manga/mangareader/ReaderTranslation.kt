package ani.dantotsu.media.manga.mangareader

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.DialogDrawBoxesBinding
import ani.dantotsu.media.manga.MangaCache
import ani.dantotsu.media.manga.mangareader.BaseImageAdapter.Companion.loadBitmap
import ani.dantotsu.media.manga.translation.AutoTranslator
import ani.dantotsu.media.manga.translation.BlockEditorView
import ani.dantotsu.media.manga.translation.BlockScorer
import ani.dantotsu.media.manga.translation.BlockVerdict
import ani.dantotsu.media.manga.translation.DetectionThresholds
import ani.dantotsu.media.manga.translation.DrawnRegion
import ani.dantotsu.media.manga.translation.DrawnRegions
import ani.dantotsu.media.manga.translation.MtlSettings
import ani.dantotsu.media.manga.translation.PageTextDetector
import ani.dantotsu.media.manga.translation.PageTranslationPipeline
import ani.dantotsu.media.manga.translation.ScoredBlock
import ani.dantotsu.media.manga.translation.SourceScript
import ani.dantotsu.media.manga.translation.TextScript
import ani.dantotsu.media.manga.translation.TranslatedPage
import ani.dantotsu.media.manga.translation.TranslatedPages
import ani.dantotsu.media.manga.translation.TranslationOverlayView
import ani.dantotsu.others.ImageViewDialog
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.parsers.OfflineMangaParser
import ani.dantotsu.px
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.choiceBottomSheet
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import uy.kohesive.injekt.api.get

/**
 * Pages past the last visible one that automatic translation works ahead on.
 *
 * Small on purpose. Every page ahead is a request that may never be read, and the wait for the page
 * actually on screen is what the reader notices — a deep lookahead makes that wait longer, not
 * shorter, because the queue in front of it is longer.
 */
private const val AUTO_LOOKAHEAD = 2

/** Failures in a row that end an automatic run. See [MangaReaderActivity.autoFailures]. */
private const val AUTO_FAILURE_LIMIT = 3

/** Outline of a box the reader drew by hand. See [MangaReaderActivity.drawBoxes]. */
private const val DRAWN_BOX_COLOR = 0xFF2196F3.toInt()

/** Keeps box ids apart between the images of one page; see [ReaderTranslation.projected]. */
private const val PLACEMENT_ID_STRIDE = 100_000

/**
 * Machine translation in the manga reader: the long-press page menu, drawing boxes over text the
 * page pass missed, translating a page on request or automatically as pages come into view, and
 * painting the results over the pages. Reaches the pages through the [activity] it belongs to.
 */
class ReaderTranslation(private val activity: MangaReaderActivity) {

    /**
     * The long-press menu, once translation gives a page more than one thing to do to it.
     *
     * Re-translating is offered for a page already done because the first answer is not always the
     * right one, and it is the cheapest correction available before any editing UI exists.
     */
    fun showPageActions(
        pos: Int,
        adapterPosition: Int,
        img1: MangaImage,
        img2: MangaImage?,
        callback: ((ImageViewDialog) -> Unit)?,
    ) {
        // Every image the page is drawn from: a real page made of slices, or a pair side by side,
        // is translated image by image and each image's boxes moved onto the page.
        val images = pagesAtPosition(adapterPosition).ifEmpty { listOfNotNull(img1, img2) }
        val done = images.all { TranslatedPages[it.url.url] != null }
        val anyDone = images.any { TranslatedPages[it.url.url] != null }
        // Boxes are drawn over one image at a time, so a page of several offers one entry each.
        val drawEntries = if (images.size == 1) {
            listOf(activity.getString(R.string.mtl_draw_boxes) to { drawBoxes(pos, images[0]) })
        } else images.mapIndexed { i, image ->
            activity.getString(R.string.mtl_draw_boxes_part, i + 1, images.size) to { drawBoxes(pos, image) }
        }
        val actions = listOfNotNull(
            activity.getString(
                if (done) R.string.mtl_retranslate_page else R.string.mtl_translate_page,
            ) to { translatePage(pos, images) },
            (activity.getString(R.string.mtl_hide_translation) to { hideTranslation(images) }).takeIf { anyDone },
        ) + drawEntries + listOf(
            activity.getString(R.string.view_image) to { activity.showImageDialog(pos, img1, img2, callback) },
        )
        activity.choiceBottomSheet(
            activity.getString(R.string.mtl_page_actions, pos + 1),
            actions.map { it.first },
            selectedIndex = -1,
        ) { index -> actions[index].second() }
    }

    private fun hideTranslation(images: List<MangaImage>) {
        images.forEach { TranslatedPages.remove(it.url.url) }
        refreshTranslationOverlays()
    }

    /**
     * Lets the reader draw boxes over text the page pass missed, then translates the page with
     * them.
     *
     * The page pass drops whole columns often enough to need this — a caption in display type, one
     * column of a two-column bubble — and the same text nearly always reads from a crop, so a box
     * is all that is needed to get it. What the page pass *did* find is shown first, outlined in
     * the colour of its verdict, so the missing text is obvious and a bubble it split or framed
     * wrong can be redrawn: a detected box dragged or resized becomes a drawn one and supersedes
     * it. Every box drawn or edited is read again at once and the result shown, so a box is judged
     * before it costs a translation. Boxes are kept per page (see [DrawnRegions]) and shown again
     * here, so a second visit edits rather than starts over.
     */
    private fun drawBoxes(pos: Int, image: MangaImage) {
        if (!mtlSettings().ready()) {
            snackString(activity.getString(R.string.mtl_needs_key))
            return
        }
        activity.lifecycleScope.launch {
            val bitmap = activity.loadBitmap(image.url, activity.pageTransforms(image))
            if (bitmap == null) {
                snackString(activity.getString(R.string.mtl_page_unavailable))
                return@launch
            }
            val key = image.url.url
            val detector = PageTextDetector(activeScript())
            val binding = DialogDrawBoxesBinding.inflate(activity.layoutInflater)
            val editor = binding.drawBoxesPage.apply { setPage(bitmap) }

            // Detected blocks keep their ids from the page pass; drawn ones count on from there.
            // Text is what each box was read as — blank for a box that read as nothing, absent
            // while a read is still in flight.
            val detected = LinkedHashMap<Int, ScoredBlock>()
            val drawn = LinkedHashMap<Int, Rect>()
            val text = HashMap<Int, String>()
            // What the scorer makes of each drawn box, once it has been read. The page pass'
            // blocks carry their own; this is the same judgement passed on the boxes the reader
            // drew, so a box over artwork or over a sound effect says so instead of being green
            // for having read any text at all.
            val verdicts = HashMap<Int, BlockVerdict>()
            // Boxes whose verdict the reader has overruled. Kept by id rather than on the box so
            // that a drag, which re-reads and re-scores, does not quietly drop an answer they
            // already gave.
            val trusted = HashSet<Int>()
            var nextId = 1
            var open = true
            // Guards the checkbox against its own listener while it is being brought into line
            // with the selection.
            var syncing = false

            fun status(id: Int?) {
                binding.drawBoxesStatus.text = when {
                    id == null -> activity.getString(R.string.mtl_draw_boxes_hint)
                    id !in text -> activity.getString(R.string.mtl_draw_boxes_reading, id)
                    text.getValue(id).isBlank() ->
                        activity.getString(R.string.mtl_draw_boxes_read_nothing, id)

                    else -> activity.getString(R.string.mtl_draw_boxes_read, id, text.getValue(id))
                }
            }
            /**
             * Offers the override on the selected box, where there is one to offer.
             *
             * Only a drawn box that read something and then scored as untranslatable has anything
             * to overrule: a box already judged dialogue or an effect is translated regardless, one
             * still being read has no verdict yet, and one that read nothing has no words to
             * translate however much anybody insists.
             */
            fun trust() {
                val id = editor.selectedId?.takeIf { it in drawn && !text[it].isNullOrBlank() }
                val verdict = id?.let { verdicts[it] }
                val offer = verdict != null && !verdict.translatable
                syncing = true
                binding.drawBoxesTrust.isChecked = offer && id in trusted
                syncing = false
                binding.drawBoxesTrust.visibility = if (offer) View.VISIBLE else View.GONE
            }
            fun render() {
                editor.setBoxes(
                    detected.map { (id, entry) ->
                        BlockEditorView.Box(id, entry.block.box, entry.verdict.color)
                    } + drawn.map { (id, rect) ->
                        val color = when {
                            id !in text -> DRAWN_BOX_COLOR
                            text.getValue(id).isBlank() -> BlockVerdict.LOW_CONF.color
                            else -> (verdicts[id] ?: BlockVerdict.DIALOGUE).color
                        }
                        BlockEditorView.Box(id, rect, color)
                    },
                )
            }
            fun read(id: Int) {
                val rect = drawn[id] ?: return
                text.remove(id)
                verdicts.remove(id)
                status(id)
                trust()
                activity.lifecycleScope.launch {
                    val read = detector.readRegion(bitmap, rect)
                    // The box may have moved or gone while the recognizer was busy.
                    if (!open || drawn[id] !== rect) return@launch
                    // Through the same run the pipeline builds, so the box carries its lines'
                    // places on the page and is judged a line at a time exactly as it will be
                    // when the page is translated.
                    val run = detector.regionRun(read, trusted = id in trusted)
                    val block = detector.toBlock(
                        id, read.lines, rect,
                        synthetic = true, glyphScale = read.scale,
                        lineBoxes = run?.lineBoxes.orEmpty(),
                    )
                    text[id] = block.text
                    // Scored on its own, so the ruby test — which compares a block against the
                    // page's median glyph — cannot fire on the one block it is given. That is the
                    // right answer here anyway: a box drawn by hand is not ruby.
                    verdicts[id] = withContext(Dispatchers.Default) {
                        BlockScorer
                            .score(listOf(block), bitmap, DetectionThresholds.fromPrefs())
                            .first()
                            .verdict
                    }
                    if (!open || drawn[id] !== rect) return@launch
                    render()
                    trust()
                    if (editor.selectedId == id || editor.selectedId == null) status(id)
                }
            }
            fun remove() {
                val id = editor.selectedId?.takeIf { it in drawn } ?: drawn.keys.lastOrNull()
                if (id == null) return
                drawn.remove(id)
                text.remove(id)
                verdicts.remove(id)
                trusted.remove(id)
                editor.select(null)
                render()
                status(null)
                trust()
            }

            editor.onBoxAdded = { rect ->
                val id = nextId++
                drawn[id] = rect
                editor.select(id)
                render()
                read(id)
            }
            editor.onBoxChanged = { id, rect ->
                // *Moving* a detected box makes it the reader's own: from here it is read from
                // where they put it, and whatever the page pass found there gives way to it. Only
                // a real drag gets here — a tap that merely selects is not an edit, or picking a
                // box up to look at it would silently convert it.
                detected.remove(id)
                drawn[id] = rect
                // What the page pass read is what it read at the old rect, and what it made of it
                // was judged there too. Neither describes this box any more, so the box goes back
                // to pending until the re-read at the end of the drag answers for the new one.
                text.remove(id)
                verdicts.remove(id)
                render()
            }
            editor.onBoxEditEnded = { id -> read(id) }
            editor.onSelectionChanged = { id ->
                status(id)
                trust()
            }
            binding.drawBoxesTrust.setOnCheckedChangeListener { _, checked ->
                if (syncing) return@setOnCheckedChangeListener
                val id = editor.selectedId ?: return@setOnCheckedChangeListener
                if (checked) trusted.add(id) else trusted.remove(id)
            }

            binding.drawBoxesStatus.setText(R.string.mtl_draw_boxes_reading_page)
            activity.customAlertDialog().apply {
                setTitle(R.string.mtl_draw_boxes_title, pos + 1)
                setCustomView(binding.root)
                setPosButton(R.string.mtl_draw_boxes_translate) {
                    DrawnRegions.put(
                        key,
                        drawn.map { (id, rect) -> DrawnRegion(rect, id in trusted) },
                    )
                    translatePage(pos, listOf(image))
                }
                setNeutralButton(R.string.mtl_draw_boxes_remove)
                setNegButton(R.string.cancel)
                onDismiss { open = false }
                // Removing is one step of editing, not the end of it, so the button is rewired
                // once the dialog exists to keep it open — the builder dismisses on every button.
                // Only drawn boxes go: a detected one belongs to the page pass, and the way to
                // overrule it is to draw over it. What is selected goes, else the last box drawn,
                // so a slip is one tap to undo.
                var dialog: AlertDialog? = null
                attach { dialog = it }
                // The builder installs its own show listener after attach, so the rewiring has
                // to go through the builder's hook rather than the dialog's.
                setOnShowListener {
                    dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { remove() }
                }
                show()
            }

            // What the page pass finds, shown before anything is drawn — minus what the boxes
            // already drawn on this page have superseded. Drawing is armed only once this is in,
            // since ids are handed out from where the detected ones stop.
            val kept = DrawnRegions[key]
            // The neighbours the translation would read across, so the boxes shown here are the
            // ones it will actually act on. See [PageTranslationPipeline.detect].
            val (before, after) = seamNeighbours(image)
            val found = withContext(Dispatchers.Default) {
                PageTranslationPipeline.detect(
                    bitmap, activeScript(), kept, mtlSettings(), before, after,
                )
            }
            if (!open) return@launch
            found.forEach { entry ->
                detected[entry.block.id] = entry
                text[entry.block.id] = entry.block.text
            }
            nextId = (found.maxOfOrNull { it.block.id } ?: 0) + 1
            kept.forEach { region ->
                val id = nextId++
                drawn[id] = Rect(region.rect)
                if (region.trusted) trusted.add(id)
                read(id)
            }
            render()
            // Armed for as long as the dialog is open, and the hint says so in words, so the frame
            // the view draws around itself would be permanently lit and read as a box around the
            // whole page.
            editor.addModeFrame = false
            editor.addMode = true
            status(null)
            trust()
        }
    }

    /**
     * The language code the extension is serving these chapters in, where it names one.
     *
     * The source knows what is actually printed on the pages; the media's country of origin only
     * knows what the work was written in, which is a different thing the moment anybody translates
     * it. See [SourceScript].
     */
    fun sourceLanguageCode(): String? {
        val index = activity.media.selected?.sourceIndex ?: return null
        return when (val parser = activity.model.mangaReadSources?.get(index)) {
            is DynamicMangaParser -> parser.extension.sources.getOrNull(parser.sourceLanguage)?.lang
            // A download has no extension left to ask, so it answers with what was recorded when
            // it was made. See [OfflineMangaParser.languageFor].
            is OfflineMangaParser -> parser.languageFor(activity.media.mainName())
            else -> null
        }
    }

    /** What automatic resolves to for this activity.media, which the settings rows name. */
    fun detectedScript(): TextScript =
        SourceScript.detect(sourceLanguageCode(), activity.media.countryOfOrigin)

    /** The recognizer to actually use: the user's choice where there is one, otherwise the above. */
    private fun activeScript(): TextScript =
        SourceScript.resolve(mtlSettings().script, sourceLanguageCode(), activity.media.countryOfOrigin)

    /**
     * This manga's translation choices — part of its reader settings, like its layout, and saved
     * in the same file. The preferences only seed the first copy; see [MtlSettings].
     */
    fun mtlSettings(): MtlSettings = MtlSettings.from(activity.defaultSettings)

    /**
     * Stores a change from the settings sheet and reopens what automatic translation had given up
     * on, since whatever failed under the old settings is worth trying again under the new ones.
     *
     * Saved without [applySettings], which rebuilds the page adapters: nothing about how the pages
     * are laid out has changed, and re-binding them for an engine switch would drop every decoded
     * bitmap on screen for no reason.
     */
    fun updateMtlSettings(settings: MtlSettings) {
        settings.applyTo(activity.defaultSettings)
        activity.saveReaderSettings("${activity.media.id}_current_settings", activity.defaultSettings)
        onMtlSettingsChanged()
    }

    /** Whether the feature is on and this manga asks for pages to translate as they appear. */
    private fun autoTranslating(): Boolean =
        PageTranslationPipeline.enabled() && mtlSettings().auto

    /**
     * Translates one page and paints it.
     *
     * The bitmap comes back through the same [loadBitmap] the adapters use, so a page on screen is
     * already in [ani.dantotsu.media.manga.MangaCache] and this costs a lookup rather than a
     * re-fetch — and the recognizer sees exactly the pixels the reader is showing, transforms and
     * all, rather than a differently-processed copy.
     */
    private fun translatePage(pos: Int, images: List<MangaImage>) {
        if (!mtlSettings().ready()) {
            snackString(activity.getString(R.string.mtl_needs_key))
            return
        }
        if (images.isEmpty()) return
        snackString(activity.getString(R.string.mtl_translating, pos + 1))
        activity.lifecycleScope.launch {
            // Each image the page is drawn from, one after another; each shows as soon as it's in.
            var found = false
            var failure: Throwable? = null
            for (image in images) {
                runTranslation(image)
                    .onSuccess { page ->
                        if (page == null) return@onSuccess
                        found = true
                        TranslatedPages.put(image.url.url, page)
                        refreshTranslationOverlays()
                    }
                    .onFailure {
                        Logger.log("MTL page translation failed: ${it.stackTraceToString()}")
                        if (failure == null) failure = it
                    }
            }
            val failed = failure
            when {
                failed != null -> snackString(failed.message ?: activity.getString(R.string.mtl_failed))
                !found -> snackString(activity.getString(R.string.mtl_nothing_found))
            }
        }
    }

    /** One page through the pipeline, saying nothing about it — shared by the menu and by auto. */
    private suspend fun runTranslation(image: MangaImage): Result<TranslatedPage?> = runCatching {
        val bitmap = activity.loadBitmap(image.url, activity.pageTransforms(image))
            ?: error(activity.getString(R.string.mtl_page_unavailable))
        val (before, after) = seamNeighbours(image)
        withContext(Dispatchers.Default) {
            PageTranslationPipeline.run(
                bitmap, activeScript(), sourceLanguageCode(), mtlSettings(), before, after,
                regions = DrawnRegions[image.url.url],
                pageKey = image.url.url,
            )
        }
    }

    /**
     * The images either side of this one, for reading across the seam between them.
     *
     * Only in a continuous layout, because only there are two images two slices of one drawing. In
     * a paged layout they are two pages: no bubble crosses between them, and handing the recognizer
     * the neighbour's edge would only give the merger a chance to join text that has nothing to do
     * with itself. For the same reason a seam smart spacing found to be a real page edge isn't
     * read across either.
     *
     * The neighbours are the chapter's own images either side, not what the reader shows next to
     * it: a page drawn from slices, or a transition between chapters, may sit between those.
     */
    private suspend fun seamNeighbours(image: MangaImage): Pair<Bitmap?, Bitmap?> {
        if (!mtlSettings().stitch) return null to null
        if (activity.defaultSettings.layout != CurrentReaderSettings.Layouts.CONTINUOUS) return null to null
        val (images, map) = chapterImagesOf(image.url.url) ?: return null to null
        val index = images.indexOfFirst { it.url.url == image.url.url }
        if (index < 0) return null to null
        suspend fun at(position: Int): Bitmap? = images.getOrNull(position)
            ?.let { activity.loadBitmap(it.url, activity.pageTransforms(it)) }
        val before = if (index > 0 && !realPageEdge(images, map, index - 1)) at(index - 1) else null
        val after = if (index + 1 < images.size && !realPageEdge(images, map, index)) at(index + 1) else null
        return before to after
    }

    /** The images of the chapter image [url] is in, and its real pages when it is read by them. */
    private fun chapterImagesOf(url: String): Pair<List<MangaImage>, ChapterPageMap?>? =
        when (val adapter = activity.readerAdapter()) {
            is ContinuousChapterAdapter -> adapter.chapterOf(url)?.let { it.images() to adapter.pageMapOf(it) }
            is BaseImageAdapter -> adapter.chapterImages to adapter.pageMap
            else -> null
        }

    /**
     * Whether smart spacing found the seam between images [first] and [first] + 1 to be a real page
     * edge — what puts a gap there. The real pages say for certain once known; until then, the same
     * judgement the gaps are made by. A seam nothing has judged is read across, as before.
     */
    private fun realPageEdge(images: List<MangaImage>, map: ChapterPageMap?, first: Int): Boolean {
        if (map != null) {
            val end = map.pageOf(first, 0.9999f)
            val start = map.pageOf(first + 1, 0f)
            return end != start && map.pages.getOrNull(start)?.joinedToPrevious != true
        }
        if (!activity.smartSpacing || activity.knownLongStrip) return false
        val next = images[first + 1].url.url
        if (PageEdges.breaksFor(next)?.atStart == true) return true
        val before = PageEdges.get(images[first].url.url) ?: return false
        val after = PageEdges.get(next) ?: return false
        return PageEdges.shouldSpace(before, after, activity.defaultSettings.direction, activity.preferJoinedPages)
    }

    /** Every image one adapter position is drawn from. */
    private fun pagesAtPosition(position: Int): List<MangaImage> =
        when (val adapter = activity.readerAdapter()) {
            is BaseImageAdapter -> adapter.translatableAt(position)
            is ContinuousChapterAdapter -> {
                val item = adapter.items.getOrNull(position) as? ContinuousChapterAdapter.ReaderItem.Image
                when {
                    item == null -> emptyList()
                    item.composed -> item.page!!.slices.mapNotNull { item.imageOf(it) }.distinct()
                    else -> listOf(item.image)
                }
            }

            else -> emptyList()
        }

    /** The image at [position] when it is shown whole and alone, its boxes needing no moving. */
    private fun plainImageAt(position: Int): MangaImage? =
        when (val adapter = activity.readerAdapter()) {
            is BaseImageAdapter -> adapter.plainImageAt(position)
            is ContinuousChapterAdapter ->
                (adapter.items.getOrNull(position) as? ContinuousChapterAdapter.ReaderItem.Image)
                    ?.takeIf { !it.composed }?.image

            else -> null
        }

    /**
     * How many failures in a row end an automatic run.
     *
     * A dead key or a retired model fails identically on every page, and without a limit that is
     * one doomed request per page for the rest of the chapter. One transient network error is not
     * that, which is why it is a run of them rather than the first.
     */
    private var autoFailures = 0

    /**
     * Translates pages as they come into view, when that is switched on.
     *
     * The queue is rebuilt from what is on screen on every scroll rather than added to, so the page
     * being looked at is always next — see [AutoTranslator].
     */
    private val autoTranslator by lazy {
        AutoTranslator(activity.lifecycleScope, active = { autoTranslating() && mtlSettings().ready() }) { image ->
            if (!mtlSettings().ready()) {
                snackString(activity.getString(R.string.mtl_needs_key))
                return@AutoTranslator false
            }
            runTranslation(image)
                .onSuccess { page ->
                    autoFailures = 0
                    if (page != null) {
                        TranslatedPages.put(image.url.url, page)
                        refreshTranslationOverlays()
                    }
                }
                .onFailure {
                    autoFailures++
                    Logger.log("MTL auto translation failed: ${it.stackTraceToString()}")
                    // Said out loud, because automatic translation has no other voice. Nobody
                    // pressed anything, so a page that fails silently is indistinguishable from
                    // the feature not being switched on — which is how a dead key, an unloaded
                    // page and an out-of-memory composite all looked the same from the outside.
                    // Once per run: the first message is the diagnosis and the rest are noise.
                    if (autoFailures == 1) {
                        snackString(it.message ?: activity.getString(R.string.mtl_failed))
                    }
                }
            val keepGoing = autoFailures < AUTO_FAILURE_LIMIT
            if (!keepGoing) snackString(activity.getString(R.string.mtl_auto_stopped))
            keepGoing
        }
    }

    private var autoQueuePosted = false

    /**
     * Asks for the visible pages to be queued, once, after the current layout pass.
     *
     * Called from the scroll listener, the pager's page change and every overlay binding, all of
     * which fire far more often than the queue can change; posting once and reading the positions
     * afterwards is what keeps that from being a cost on every scrolled pixel.
     */
    fun scheduleAutoTranslation() {
        if (!autoTranslating() || autoQueuePosted) return
        autoQueuePosted = true
        activity.pageHost.post {
            autoQueuePosted = false
            queueAutoTranslation()
        }
    }

    /**
     * Which way through the chapter the reader is moving, as a step in adapter positions.
     *
     * Taken from the positions rather than from the scroll's sign, which would have to be read
     * differently for each of the four reading directions — right-to-left and bottom-to-top both
     * make a *negative* delta mean forwards. Positions only ever count one way.
     */
    private var autoDirection = 1
    private var autoAnchor = RecyclerView.NO_POSITION

    /**
     * Forgets where the reader was, keeping the direction: for a jump in positions that isn't the
     * reader moving — a whole chapter inserted above, say — and would read as a fast scroll.
     */
    fun resetAnchor() {
        autoAnchor = RecyclerView.NO_POSITION
    }

    private fun queueAutoTranslation() {
        if (!autoTranslating()) return
        val (first, last) = visiblePositions() ?: return

        if (autoAnchor != RecyclerView.NO_POSITION && first != autoAnchor) {
            autoDirection = if (first > autoAnchor) 1 else -1
        }
        autoAnchor = first

        // The lookahead follows the reader instead of always pointing down the chapter. Fixed
        // forwards, scrolling back up queued nothing that was not already on screen: a page only
        // began translating once it was fully in view and took several seconds to arrive, so going
        // up looked like the feature was off while going down looked like it worked. Which way
        // "ahead" is depends on where the reader is going, and that is the only thing that decides
        // which page is worth spending a request on before it is needed.
        val ahead = if (autoDirection >= 0) {
            ((last + 1)..(last + AUTO_LOOKAHEAD)).toList()
        } else {
            ((first - 1) downTo (first - AUTO_LOOKAHEAD)).toList()
        }
        autoTranslator.onVisible(
            // What is on screen first, then what is about to be. Out-of-range positions resolve to
            // no pages, so the ends of the chapter need no special case.
            ((first..last).toList() + ahead).flatMap { pagesAtPosition(it) },
        )
    }

    /**
     * The first and last adapter positions on screen, from whichever view is showing them.
     *
     * The pager shows exactly one item, so its current page is both ends of the range — the
     * lookahead is what gets the next one translated before it is swiped to.
     */
    private fun visiblePositions(): Pair<Int, Int>? {
        if (activity.usingPager) {
            val current = activity.binding.mangaReaderPager.currentItem
            return current to current
        }
        val manager = activity.binding.mangaReaderRecycler.layoutManager as? LinearLayoutManager ?: return null
        val first = manager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return null
        return first to manager.findLastVisibleItemPosition().coerceAtLeast(first)
    }

    /**
     * Called when the engine, model, script or target changes.
     *
     * A page already translated keeps its words — redoing it would spend quota to say the same
     * thing differently — but pages that failed under the old settings are worth another go, which
     * is the case the reader is usually in when they change any of this.
     */
    fun onMtlSettingsChanged() {
        autoFailures = 0
        autoTranslator.reset()
        scheduleAutoTranslation()
    }

    /**
     * Re-applies overlays to whatever is on screen.
     *
     * Cheaper and less disruptive than telling the adapter a page changed: rebinding would drop the
     * decoded bitmap and re-run the load for a change that only concerns a sibling view.
     */
    fun refreshTranslationOverlays() {
        // The paged layout's pages live in the pager's own RecyclerView, not the scrolling one.
        val recycler = if (activity.usingPager) {
            activity.binding.mangaReaderPager.getChildAt(0) as? RecyclerView ?: return
        } else activity.binding.mangaReaderRecycler
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i) ?: continue
            val holder = recycler.getChildViewHolder(child) ?: continue
            applyTranslationOverlay(child, holder.bindingAdapterPosition)
        }
    }

    /**
     * [blocks] moved down past whatever page-break gaps [PageEdges.withGaps] drew above them, when
     * the bitmap on [itemView] was actually drawn with them ([PageLayout.gapped] — not re-derived
     * from the settings, which an out-of-memory fallback to the gapless bitmap would contradict);
     * [blocks] as they are otherwise. A block a break runs through grows by the gaps inside it.
     */
    private fun stripGapShifted(
        url: String,
        blocks: List<ani.dantotsu.media.manga.translation.PaintedBlock>,
        pageWidth: Int,
        itemView: View,
    ): List<ani.dantotsu.media.manga.translation.PaintedBlock> {
        if (!PageLayout.gapped(itemView)) return blocks
        val viewportWidth = BaseImageAdapter.pageViewport(itemView).first.takeIf { it > 0 }
            ?: return blocks
        val gap = (16f.px * pageWidth / viewportWidth.toFloat()).toInt()
        return blocks.map { block ->
            val top = PageEdges.gapShift(url, block.rect.top, pageWidth, gap)
            val bottom = PageEdges.gapShift(url, block.rect.bottom, pageWidth, gap)
            if (top == 0 && bottom == 0) block
            else block.copy(
                rect = Rect(block.rect.left, block.rect.top + top, block.rect.right, block.rect.bottom + bottom)
            )
        }
    }

    /**
     * The translations of every image [layout] places, moved to where it drew them. A box goes with
     * the slice its centre falls in. Ids are made unique per placement: the painter caches each
     * block's set text by id.
     */
    private fun projected(layout: PageLayout): List<ani.dantotsu.media.manga.translation.PaintedBlock> =
        layout.placements.flatMapIndexed { i, placement ->
            val page = TranslatedPages[placement.url] ?: return@flatMapIndexed emptyList()
            val top = placement.from * page.pageHeight
            val bottom = placement.to * page.pageHeight
            if (bottom <= top || page.pageWidth <= 0) return@flatMapIndexed emptyList()
            val dest = placement.dest
            val sx = dest.width() / page.pageWidth
            val sy = dest.height() / (bottom - top)
            page.blocks
                .filter { it.rect.exactCenterY().let { y -> y >= top && y < bottom } }
                .map { block ->
                    block.copy(
                        id = i * PLACEMENT_ID_STRIDE + block.id,
                        rect = Rect(
                            (dest.left + block.rect.left * sx).roundToInt(),
                            (dest.top + (block.rect.top - top) * sy).roundToInt(),
                            (dest.left + block.rect.right * sx).roundToInt(),
                            (dest.top + (block.rect.bottom - top) * sy).roundToInt(),
                        ),
                    )
                }
        }

    /** Puts the stored translation for whatever page [itemView] is showing onto its overlay. */
    fun applyTranslationOverlay(itemView: View, position: Int) {
        val overlay = itemView.findViewById<TranslationOverlayView>(R.id.imgProgTranslation)
            ?: return
        val layout = PageLayout.on(itemView)
        if (layout != null) {
            val blocks = projected(layout)
            if (blocks.isEmpty()) overlay.clear() else overlay.setBlocks(blocks, layout.width)
        } else {
            // A page drawn from several images has nothing to show until its layout is in.
            val image = plainImageAt(position)
            val page = image?.let { TranslatedPages[it.url.url] }
            if (page == null) overlay.clear() else overlay.setBlocks(
                stripGapShifted(image.url.url, page.blocks, page.pageWidth, itemView), page.pageWidth
            )
        }
        // A page arriving on screen is the event automatic translation waits for, and this runs for
        // every one of them whether or not it has a translation yet.
        scheduleAutoTranslation()
    }
}
