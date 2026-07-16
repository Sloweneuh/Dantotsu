package ani.dantotsu.download.manga

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.R
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.download.DownloadTracker
import ani.dantotsu.download.downloadActivityIntent
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.formatBytes
import ani.dantotsu.formatDownloadSpeed
import ani.dantotsu.formatEta
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.download.findValidName
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.manga.ImageData
import ani.dantotsu.media.manga.mangareader.PDF_CHAPTERS_FILE
import ani.dantotsu.media.manga.mangareader.PdfChapterEntry
import ani.dantotsu.media.manga.mangareader.PdfChapterMetadata
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_FAILED
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_FINISHED
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_PROGRESS
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_STARTED
import ani.dantotsu.media.manga.MangaReadFragment.Companion.EXTRA_CHAPTER_NUMBER
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.NumberConverter.Companion.ofLength
import com.anggrayudi.storage.file.deleteRecursively
import com.anggrayudi.storage.file.forceDelete
import com.anggrayudi.storage.file.openOutputStream
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_DOWNLOADER_PROGRESS
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URL
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

class MangaDownloaderService : Service() {

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var builder: NotificationCompat.Builder
    private val downloadsManager: DownloadsManager = Injekt.get<DownloadsManager>()

    private val downloadJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    private var isCurrentlyProcessing = false

    override fun onBind(intent: Intent?): IBinder? {
        // This is only required for bound services.
        return null
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        builder = NotificationCompat.Builder(this, CHANNEL_DOWNLOADER_PROGRESS).apply {
            setContentTitle("Manga Download Progress")
            setSmallIcon(R.drawable.ic_download_24)
            priority = NotificationCompat.PRIORITY_DEFAULT
            setOnlyAlertOnce(true)
            setProgress(0, 0, false)
            setContentIntent(downloadActivityIntent())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
        ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(ACTION_CANCEL_DOWNLOAD),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        MangaServiceDataSingleton.downloadQueue.clear()
        downloadJobs.clear()
        MangaServiceDataSingleton.isServiceRunning = false
        unregisterReceiver(cancelReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        snackString("Download started")
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serviceScope.launch {
            mutex.withLock {
                if (!isCurrentlyProcessing) {
                    isCurrentlyProcessing = true
                    processQueue()
                    isCurrentlyProcessing = false
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun processQueue() {
        CoroutineScope(Dispatchers.Default).launch {
            while (MangaServiceDataSingleton.downloadQueue.isNotEmpty()) {
                val task = MangaServiceDataSingleton.downloadQueue.poll()
                if (task != null) {
                    val job = launch { download(task) }
                    mutex.withLock {
                        downloadJobs[task.chapter] = job
                    }
                    job.join()
                    mutex.withLock {
                        downloadJobs.remove(task.chapter)
                    }
                    updateNotification()
                }
                if (MangaServiceDataSingleton.downloadQueue.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        stopSelf()
                    }
                }
            }
        }
    }

    fun cancelDownload(chapter: String) {
        CoroutineScope(Dispatchers.Default).launch {
            mutex.withLock {
                downloadJobs[chapter]?.cancel()
                downloadJobs.remove(chapter)
                MangaServiceDataSingleton.downloadQueue.removeAll { it.chapter == chapter }
                DownloadTracker.removeByKey(MediaType.MANGA, chapter)
                updateNotification() // Update the notification after cancellation
            }
        }
    }

    private fun updateNotification() {
        // Update the notification to reflect the current state of the queue
        val pendingDownloads = MangaServiceDataSingleton.downloadQueue.size
        val text = if (pendingDownloads > 0) {
            "Pending downloads: $pendingDownloads"
        } else {
            "All downloads completed"
        }
        builder.setContentText(text)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    suspend fun download(task: DownloadTask) {
        val itemId = DownloadTracker.idOf(MediaType.MANGA, task.title, task.chapter)
        try {
            withContext(Dispatchers.IO) {
                val notifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        this@MangaDownloaderService,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                val deferredMap = mutableMapOf<Int, Deferred<Bitmap?>>()
                builder.setContentText("Downloading ${task.title} - ${task.chapter}")
                if (notifi) {
                    withContext(Dispatchers.Main) {
                        notificationManager.notify(NOTIFICATION_ID, builder.build())
                    }
                }

                val baseOutputDir = getSubDirectory(
                    this@MangaDownloaderService,
                    MediaType.MANGA,
                    false,
                    task.title
                ) ?: throw Exception("Base output directory not found")
                val outputDir = getSubDirectory(
                    this@MangaDownloaderService,
                    MediaType.MANGA,
                    false,
                    task.title,
                    task.chapter
                ) ?: throw Exception("Output directory not found")

                outputDir.deleteRecursively(this@MangaDownloaderService, true)

                DownloadTracker.markDownloading(itemId)
                val startTime = System.currentTimeMillis()
                val totalBytes = java.util.concurrent.atomic.AtomicLong(0)
                val pagesDone = java.util.concurrent.atomic.AtomicInteger(0)

                for ((index, image) in task.imageData.withIndex()) {
                    if (deferredMap.size >= task.simultaneousDownloads) {
                        deferredMap.values.awaitAll()
                        deferredMap.clear()
                    }

                    deferredMap[index] = async(Dispatchers.IO) {
                        var bitmap: Bitmap? = null
                        var retryCount = 0

                        while (bitmap == null && retryCount < task.retries) {
                            bitmap = image.fetchAndProcessImage(
                                image.page,
                                image.source
                            )
                            if (bitmap == null) {
                                snackString("${task.chapter} - Retrying to download page ${index.ofLength(3)}, attempt ${retryCount + 1}.")
                            }
                            retryCount++
                        }

                        if (bitmap == null) {
                            outputDir.deleteRecursively(this@MangaDownloaderService, false)
                            throw Exception("${task.chapter} - Unable to download all pages after $retryCount attempts. Try again.")
                        }

                        val written = saveToDisk("${index.ofLength(3)}.jpg", outputDir, bitmap)
                        val done = totalBytes.addAndGet(written)
                        val pages = pagesDone.incrementAndGet()
                        val total = task.imageData.size
                        val percent = pages * 100 / total
                        val elapsed = System.currentTimeMillis() - startTime
                        val speed = if (elapsed > 0) done * 1000 / elapsed else 0
                        // ETA estimate: remaining pages at the current average page time.
                        val etaMs = if (pages > 0) (elapsed / pages) * (total - pages) else -1L

                        builder.setProgress(total, pages, false)
                            .setContentText(downloadProgressText(task, percent, done, 0, speed, etaMs))
                        DownloadTracker.updateProgress(itemId, percent, done, 0, speed, etaMs)
                        broadcastDownloadProgress(task.uniqueName, percent, done, 0, speed, etaMs)
                        if (notifi) {
                            withContext(Dispatchers.Main) {
                                notificationManager.notify(NOTIFICATION_ID, builder.build())
                            }
                        }
                        bitmap
                    }
                }

                deferredMap.values.awaitAll()

                // If requested, bundle the downloaded pages into a PDF and drop the
                // intermediate images. Works for both paged and long-strip manga since
                // each source image becomes its own PDF page sized to that image.
                if (task.asPdf) {
                    withContext(Dispatchers.Main) {
                        builder.setContentText("${task.title} - ${task.chapter} Creating PDF")
                        if (notifi) {
                            notificationManager.notify(NOTIFICATION_ID, builder.build())
                        }
                    }
                    createPdfFromDirectory(
                        outputDir,
                        "${task.chapter.findValidName()}.pdf",
                        task.pdfTransitions
                    )
                }

                withContext(Dispatchers.Main) {
                    builder.setContentText("${task.title} - ${task.chapter} Download complete")
                        .setProgress(0, 0, false)
                    if (notifi) {
                        notificationManager.notify(NOTIFICATION_ID, builder.build())
                    }
                }

                saveMediaInfo(task, baseOutputDir)
                downloadsManager.addDownload(
                    DownloadedType(
                        task.title,
                        task.chapter,
                        MediaType.MANGA,
                        scanlator = task.scanlator,
                    )
                )
                broadcastDownloadFinished(task.uniqueName)
                DownloadTracker.remove(itemId)
                snackString("${task.title} - ${task.chapter} Download finished")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // User cancelled the download; not an error.
            DownloadTracker.remove(itemId)
            throw e
        } catch (e: Exception) {
            Logger.log("Exception while downloading file: ${e.message}")
            snackString("Exception while downloading file: ${e.message}")
            Injekt.get<CrashlyticsInterface>().logException(e)
            broadcastDownloadFailed(task.uniqueName)
            DownloadTracker.remove(itemId)
        }
    }

    /** Builds the notification/progress text with percent, speed, ETA and size. */
    private fun downloadProgressText(
        task: DownloadTask,
        percent: Int,
        bytesDone: Long,
        bytesTotal: Long,
        speedBps: Long,
        etaMs: Long
    ): String {
        val parts = mutableListOf("$percent%")
        formatDownloadSpeed(speedBps).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        formatEta(etaMs).takeIf { it.isNotEmpty() }?.let { parts.add("ETA $it") }
        val sizeStr = if (bytesTotal > 0) "${formatBytes(bytesDone)}/${formatBytes(bytesTotal)}"
        else formatBytes(bytesDone)
        parts.add(sizeStr)
        return "${task.chapter} • ${parts.joinToString(" • ")}"
    }


    /** Saves [bitmap] as JPEG and returns the number of bytes written (0 on failure). */
    private fun saveToDisk(
        fileName: String,
        directory: DocumentFile,
        bitmap: Bitmap
    ): Long {
        try {
            directory.findFile(fileName)?.forceDelete(this)
            val file =
                directory.createFile("image/jpeg", fileName) ?: throw Exception("File not created")

            file.openOutputStream(this, false).use { outputStream ->
                if (outputStream == null) throw Exception("Output stream is null")
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }
            return file.length()
        } catch (e: Exception) {
            println("Exception while saving image: ${e.message}")
            snackString("Exception while saving image: ${e.message}")
            Injekt.get<CrashlyticsInterface>().logException(e)
            return 0
        }
    }

    /** One JPEG-backed page to embed in a PDF, either a downloaded file or an in-memory image. */
    private sealed class PdfSource {
        abstract val width: Int
        abstract val height: Int
        abstract fun jpegBytes(service: MangaDownloaderService): ByteArray

        class FileImage(val file: DocumentFile, override val width: Int, override val height: Int) :
            PdfSource() {
            override fun jpegBytes(service: MangaDownloaderService): ByteArray =
                service.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                    ?: throw Exception("Unable to read page ${file.name}")
        }

        class BytesImage(val data: ByteArray, override val width: Int, override val height: Int) :
            PdfSource() {
            override fun jpegBytes(service: MangaDownloaderService): ByteArray = data
        }
    }

    /**
     * Bundles every downloaded page image in [directory] into a single PDF and then
     * removes the intermediate images, leaving only the PDF. Each source image becomes
     * its own PDF page sized to the image's pixel dimensions, so both normal paged manga
     * and long-strip (webtoon) chapters are reproduced faithfully.
     *
     * When [transitions] are supplied (one-file downloads), a rendered "chapter divider"
     * page is inserted before the first page of each subsequent chapter, mirroring the
     * continuous reader's transitions so chapters stay separated even in paged mode.
     *
     * The already-saved JPEG bytes are embedded directly (DCTDecode) and the file is
     * streamed to disk, so memory stays bounded to a single page regardless of how many
     * chapters are combined. Returns true on success.
     */
    private fun createPdfFromDirectory(
        directory: DocumentFile,
        pdfFileName: String,
        transitions: List<DownloadTask.PdfTransition> = emptyList()
    ): Boolean {
        val pageIndexRegex = Regex("""(\d+)\.jpg$""")
        val pageFiles = directory.listFiles()
            .filter { it.isFile && it.name?.endsWith(".jpg", ignoreCase = true) == true }
            .mapNotNull { file ->
                val index = pageIndexRegex.find(file.name ?: "")?.groupValues?.get(1)?.toIntOrNull()
                if (index != null) index to file else null
            }
            .sortedBy { it.first }
        if (pageFiles.isEmpty()) return false

        val transitionsByIndex = transitions.associateBy { it.beforePageIndex }

        // Read only the JPEG headers up front to learn each page's dimensions cheaply, and
        // interleave transition pages at chapter boundaries. Also record where each content
        // page (keyed by its original image index) lands in the final PDF, so a chapter
        // sidecar can map chapters to their content pages (skipping the divider pages).
        val sources = mutableListOf<PdfSource>()
        val contentFinalPos = HashMap<Int, Int>()
        for ((index, file) in pageFiles) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(file.uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) continue
            transitionsByIndex[index]?.let { transition ->
                renderTransitionPage(opts.outWidth, transition)?.let { sources.add(it) }
            }
            contentFinalPos[index] = sources.size
            sources.add(PdfSource.FileImage(file, opts.outWidth, opts.outHeight))
        }
        if (sources.isEmpty()) return false

        var pdfFile: DocumentFile? = null
        try {
            directory.findFile(pdfFileName)?.forceDelete(this)
            pdfFile = directory.createFile("application/pdf", pdfFileName)
                ?: throw Exception("PDF file not created")
            pdfFile.openOutputStream(this, false).use { outputStream ->
                if (outputStream == null) throw Exception("Output stream is null")
                writeImagesAsPdf(sources, outputStream)
            }

            // For one-file downloads, persist the chapter layout so the offline reader can
            // present each bundled chapter as its own instance.
            if (transitions.isNotEmpty()) {
                writeChapterMetadata(directory, transitions, pageFiles.size, contentFinalPos)
            }

            // Keep only the PDF; the individual page images are no longer needed.
            pageFiles.forEach { it.second.forceDelete(this) }
            return true
        } catch (e: Exception) {
            Logger.log("Exception while creating PDF: ${e.message}")
            snackString("Exception while creating PDF: ${e.message}")
            Injekt.get<CrashlyticsInterface>().logException(e)
            // Drop the half-written PDF so the JPEG pages remain the source of truth.
            pdfFile?.forceDelete(this)
            return false
        }
    }

    /**
     * Writes the [PDF_CHAPTERS_FILE] sidecar describing which final PDF pages belong to each
     * bundled chapter (divider pages excluded). [transitions] mark the content-index of each
     * chapter boundary, [contentCount] is the number of content pages, and [contentFinalPos]
     * maps a content page's original image index to its page position in the finished PDF.
     */
    private fun writeChapterMetadata(
        directory: DocumentFile,
        transitions: List<DownloadTask.PdfTransition>,
        contentCount: Int,
        contentFinalPos: Map<Int, Int>
    ) {
        try {
            val boundaries = transitions.sortedBy { it.beforePageIndex }
            // Chapter boundaries in content-index space: [0, b0, b1, ..., contentCount].
            val starts = mutableListOf(0).apply { boundaries.forEach { add(it.beforePageIndex) } }
            val ends = boundaries.map { it.beforePageIndex }.toMutableList().apply { add(contentCount) }
            val titles = mutableListOf(boundaries.first().prevTitle)
                .apply { boundaries.forEach { add(it.nextTitle) } }
            val scanlators = mutableListOf(boundaries.first().prevScanlator)
                .apply { boundaries.forEach { add(it.nextScanlator) } }

            val entries = starts.indices.map { i ->
                val pages = (starts[i] until ends[i]).mapNotNull { contentFinalPos[it] }.sorted()
                PdfChapterEntry(titles[i], pages, scanlators[i])
            }.filter { it.pages.isNotEmpty() }

            val json = GsonBuilder().create().toJson(PdfChapterMetadata(entries))
            directory.findFile(PDF_CHAPTERS_FILE)?.forceDelete(this)
            val file = directory.createFile("application/json", PDF_CHAPTERS_FILE)
                ?: throw Exception("Chapter metadata file not created")
            file.openOutputStream(this, false).use { output ->
                if (output == null) throw Exception("Output stream is null")
                output.write(json.toByteArray())
            }
        } catch (e: Exception) {
            Logger.log("Exception while writing PDF chapter metadata: ${e.message}")
            Injekt.get<CrashlyticsInterface>().logException(e)
        }
    }

    /**
     * Renders a chapter-divider page matching the reader's transition look ("End of ..." /
     * "Next: ...") to a JPEG, sized to [pageWidth] so it sits flush with the surrounding pages.
     */
    private fun renderTransitionPage(
        pageWidth: Int,
        transition: DownloadTask.PdfTransition
    ): PdfSource.BytesImage? {
        return try {
            val width = pageWidth.coerceIn(600, 2200)
            val height = (width * 1.4f).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.parseColor("#111111"))

            val endText = getString(R.string.chapter_transition_end, transition.prevTitle)
            val nextText = getString(R.string.chapter_transition_next, transition.nextTitle)

            val endPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#B3FFFFFF")
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = width * 0.045f
            }
            val nextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = width * 0.055f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val centerX = width / 2f
            val centerY = height / 2f
            drawWrappedText(canvas, endText, endPaint, centerX, centerY - height * 0.06f, width * 0.9f)
            canvas.drawRect(
                centerX - width * 0.08f,
                centerY - 2f,
                centerX + width * 0.08f,
                centerY + 2f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#888888")
                }
            )
            drawWrappedText(canvas, nextText, nextPaint, centerX, centerY + height * 0.09f, width * 0.9f)

            // Warn about chapters missing between these two (e.g. gaps in the source), so
            // the gap is visible in the PDF itself as well as in the in-app reader.
            if (transition.missingChapters > 0) {
                val warnText = "⚠ " + getString(
                    R.string.chapters_missing,
                    transition.missingChapters
                )
                val warnPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#FFAA00")
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = width * 0.04f
                }
                drawWrappedText(
                    canvas, warnText, warnPaint, centerX, centerY + height * 0.2f, width * 0.9f
                )
            }

            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
            bitmap.recycle()
            PdfSource.BytesImage(baos.toByteArray(), width, height)
        } catch (e: Exception) {
            Logger.log("Failed to render transition page: ${e.message}")
            null
        }
    }

    /** Draws [text] centered at [cy], wrapping onto multiple lines to fit [maxWidth]. */
    private fun drawWrappedText(
        canvas: android.graphics.Canvas,
        text: String,
        paint: android.graphics.Paint,
        cx: Float,
        cy: Float,
        maxWidth: Float
    ) {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                lines.add(current.toString())
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())

        val lineHeight = paint.textSize * 1.3f
        var y = cy - (lines.size - 1) * lineHeight / 2f - (paint.ascent() + paint.descent()) / 2f
        for (line in lines) {
            canvas.drawText(line, cx, y, paint)
            y += lineHeight
        }
    }

    private fun writeImagesAsPdf(pages: List<PdfSource>, out: java.io.OutputStream) {
        val charset = Charsets.ISO_8859_1
        var offset = 0L
        val xref = HashMap<Int, Long>()

        fun writeStr(s: String) {
            val b = s.toByteArray(charset)
            out.write(b)
            offset += b.size
        }

        fun writeRaw(b: ByteArray) {
            out.write(b)
            offset += b.size
        }

        fun startObj(id: Int) {
            xref[id] = offset
            writeStr("$id 0 obj\n")
        }

        val n = pages.size
        val catalogId = 1
        val pagesId = 2
        fun pageId(i: Int) = 3 + i * 3
        fun contentId(i: Int) = 3 + i * 3 + 1
        fun imageId(i: Int) = 3 + i * 3 + 2
        val totalObjs = 2 + n * 3

        // Header (with a binary marker so tools treat the file as binary).
        writeStr("%PDF-1.7\n")
        writeStr("%âãÏÓ\n")

        startObj(catalogId)
        writeStr("<< /Type /Catalog /Pages $pagesId 0 R >>\nendobj\n")

        startObj(pagesId)
        val kids = (0 until n).joinToString(" ") { "${pageId(it)} 0 R" }
        writeStr("<< /Type /Pages /Count $n /Kids [$kids] >>\nendobj\n")

        for (i in 0 until n) {
            val source = pages[i]
            val jpegBytes = source.jpegBytes(this)

            // Clamp the displayed page box to the PDF spec limit while embedding the
            // image at full resolution (viewers scale the image to the page box).
            val maxDim = 14400f
            val scale = minOf(1f, maxDim / source.width, maxDim / source.height)
            // Locale.US so the decimal separator is always '.', as PDF syntax requires.
            val pageW = String.format(java.util.Locale.US, "%.2f", source.width * scale)
            val pageH = String.format(java.util.Locale.US, "%.2f", source.height * scale)

            val content = "q\n$pageW 0 0 $pageH 0 0 cm\n/Im0 Do\nQ\n"
            val contentBytes = content.toByteArray(charset)

            startObj(pageId(i))
            writeStr(
                "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 $pageW $pageH] " +
                        "/Resources << /XObject << /Im0 ${imageId(i)} 0 R >> >> " +
                        "/Contents ${contentId(i)} 0 R >>\nendobj\n"
            )

            startObj(contentId(i))
            writeStr("<< /Length ${contentBytes.size} >>\nstream\n")
            writeRaw(contentBytes)
            writeStr("endstream\nendobj\n")

            startObj(imageId(i))
            writeStr(
                "<< /Type /XObject /Subtype /Image /Width ${source.width} /Height ${source.height} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                        "/Length ${jpegBytes.size} >>\nstream\n"
            )
            writeRaw(jpegBytes)
            writeStr("\nendstream\nendobj\n")
        }

        val xrefOffset = offset
        writeStr("xref\n")
        writeStr("0 ${totalObjs + 1}\n")
        writeStr("0000000000 65535 f \n")
        for (id in 1..totalObjs) {
            writeStr(String.format(java.util.Locale.US, "%010d 00000 n \n", xref[id] ?: 0L))
        }
        writeStr("trailer\n<< /Size ${totalObjs + 1} /Root $catalogId 0 R >>\n")
        writeStr("startxref\n$xrefOffset\n%%EOF\n")
        out.flush()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveMediaInfo(task: DownloadTask, directory: DocumentFile) {
        launchIO {
            directory.findFile("media.json")?.forceDelete(this@MangaDownloaderService)
            val file = directory.createFile("application/json", "media.json")
                ?: throw Exception("File not created")
            val gson = GsonBuilder()
                .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> {
                    SChapterImpl() // Provide an instance of SChapterImpl
                })
                .create()
            val mediaJson = gson.toJson(task.sourceMedia)
            val media = gson.fromJson(mediaJson, Media::class.java)
            if (media != null) {
                media.cover = media.cover?.let { downloadImage(it, directory, "cover.jpg") }
                media.banner = media.banner?.let { downloadImage(it, directory, "banner.jpg") }

                val jsonString = gson.toJson(media)
                withContext(Dispatchers.Main) {
                    try {
                        file.openOutputStream(this@MangaDownloaderService, false).use { output ->
                            if (output == null) throw Exception("Output stream is null")
                            output.write(jsonString.toByteArray())
                        }
                    } catch (e: android.system.ErrnoException) {
                        e.printStackTrace()
                        Toast.makeText(
                            this@MangaDownloaderService,
                            "Error while saving: ${e.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }


    private suspend fun downloadImage(url: String, directory: DocumentFile, name: String): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            println("Downloading url $url")
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
                }
                directory.findFile(name)?.forceDelete(this@MangaDownloaderService)
                val file =
                    directory.createFile("image/jpeg", name) ?: throw Exception("File not created")
                file.openOutputStream(this@MangaDownloaderService, false).use { output ->
                    if (output == null) throw Exception("Output stream is null")
                    connection.inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                return@withContext file.uri.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MangaDownloaderService,
                        "Exception while saving ${name}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun broadcastDownloadStarted(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_STARTED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFinished(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_FINISHED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFailed(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_FAILED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadProgress(
        chapterNumber: String,
        progress: Int,
        bytesDone: Long = 0,
        bytesTotal: Long = 0,
        speed: Long = 0,
        eta: Long = -1
    ) {
        val intent = Intent(ACTION_DOWNLOAD_PROGRESS).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
            putExtra("progress", progress)
            putExtra("bytesDone", bytesDone)
            putExtra("bytesTotal", bytesTotal)
            putExtra("speed", speed)
            putExtra("eta", eta)
        }
        sendBroadcast(intent)
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CANCEL_DOWNLOAD) {
                val chapter = intent.getStringExtra(EXTRA_CHAPTER)
                chapter?.let {
                    cancelDownload(it)
                }
            }
        }
    }


    data class DownloadTask(
        val title: String,
        val chapter: String,
        val scanlator: String,
        val imageData: List<ImageData>,
        val sourceMedia: Media? = null,
        val retries: Int = 2,
        val simultaneousDownloads: Int = 2,
        val asPdf: Boolean = false,
        val pdfTransitions: List<PdfTransition> = emptyList(),
    ) {
        val uniqueName: String
            get() = "$chapter-$scanlator"

        /**
         * Marks a chapter boundary inside a combined ("one file") PDF: a divider page is
         * inserted before the image at [beforePageIndex] (an index into [imageData]).
         */
        data class PdfTransition(
            val beforePageIndex: Int,
            val prevTitle: String,
            val nextTitle: String,
            val missingChapters: Int = 0,
            val prevScanlator: String = "Unknown",
            val nextScanlator: String = "Unknown",
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 1103
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"
        const val EXTRA_CHAPTER = "extra_chapter"
    }
}

object MangaServiceDataSingleton {
    var imageData: List<ImageData> = listOf()
    var sourceMedia: Media? = null
    var downloadQueue: Queue<MangaDownloaderService.DownloadTask> = ConcurrentLinkedQueue()

    @Volatile
    var isServiceRunning: Boolean = false

    /** Reorders the pending queue so its tasks follow [orderedKeys] (by chapter). */
    @Synchronized
    fun reorderQueue(orderedKeys: List<String>) {
        val ordered = downloadQueue.toList().sortedBy {
            val i = orderedKeys.indexOf(it.chapter); if (i == -1) Int.MAX_VALUE else i
        }
        downloadQueue.clear()
        downloadQueue.addAll(ordered)
    }
}