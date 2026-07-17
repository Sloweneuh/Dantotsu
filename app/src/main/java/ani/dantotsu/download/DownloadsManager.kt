package ani.dantotsu.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.download.DownloadCompat.Companion.removeDownloadCompat
import ani.dantotsu.download.DownloadCompat.Companion.removeMediaCompat
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.anggrayudi.storage.callback.FolderCallback
import com.anggrayudi.storage.file.deleteRecursively
import com.anggrayudi.storage.file.moveFolderTo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.xdrop.fuzzywuzzy.FuzzySearch
import java.io.Serializable
import kotlin.coroutines.resume
import kotlin.math.ln
import kotlin.math.pow

class DownloadsManager(private val context: Context) {
    private val gson = Gson()
    private val downloadsList = loadDownloads().toMutableList()

    // Snapshots taken under lock: services add downloads concurrently while the manager UI
    // reads these, which would otherwise throw ConcurrentModificationException.
    val mangaDownloadedTypes: List<DownloadedType>
        get() = synchronized(downloadsList) { downloadsList.filter { it.type == MediaType.MANGA } }
    val animeDownloadedTypes: List<DownloadedType>
        get() = synchronized(downloadsList) { downloadsList.filter { it.type == MediaType.ANIME } }
    val novelDownloadedTypes: List<DownloadedType>
        get() = synchronized(downloadsList) { downloadsList.filter { it.type == MediaType.NOVEL } }

    private fun saveDownloads() {
        val jsonString = synchronized(downloadsList) { gson.toJson(downloadsList) }
        PrefManager.setVal(PrefName.DownloadsKeys, jsonString)
    }

    private fun loadDownloads(): List<DownloadedType> {
        val jsonString = PrefManager.getVal(PrefName.DownloadsKeys, null as String?)
        return if (jsonString != null) {
            val type = object : TypeToken<List<DownloadedType>>() {}.type
            gson.fromJson(jsonString, type)
        } else {
            emptyList()
        }
    }

    fun addDownload(downloadedType: DownloadedType) {
        synchronized(downloadsList) { downloadsList.add(downloadedType) }
        saveDownloads()
    }

    fun removeDownload(
        downloadedType: DownloadedType,
        toast: Boolean = true,
        onFinished: () -> Unit
    ) {
        removeDownloadCompat(context, downloadedType, toast)
        downloadsList.removeAll { it.titleName == downloadedType.titleName && it.chapterName == downloadedType.chapterName }
        CoroutineScope(Dispatchers.IO).launch {
            removeDirectory(downloadedType, toast)
            withContext(Dispatchers.Main) {
                onFinished()
            }
        }
        saveDownloads()
    }

    fun getSize(downloadedType: DownloadedType): Double {
        val index = downloadsList.indexOfFirst { it.titleName == downloadedType.titleName && it.chapterName == downloadedType.chapterName }
        if (index == -1) return 0.0
        if(downloadedType.size == null) {
            val episodeSize = bytesToDouble(
                getDirSize(
                    context,
                    MediaType.ANIME,
                    downloadedType.titleName,
                    downloadedType.chapterName
                )
            )
            downloadsList[index].size = episodeSize
            saveDownloads()
            return episodeSize
        }
        else
            return downloadedType.size ?: 0.0
    }

    fun removeMedia(title: String, type: MediaType) {
        removeMediaCompat(context, title, type)
        val baseDirectory = getBaseDirectory(context, type)
        val directory = baseDirectory?.findFolder(title)
        if (directory?.exists() == true) {
            val deleted = directory.deleteRecursively(context, false)
            if (deleted) {
                snackString("Successfully deleted")
            } else {
                snackString("Failed to delete directory")
            }
        } else {
            snackString("Directory does not exist")
            cleanDownloads()
        }
        when (type) {
            MediaType.MANGA -> {
                downloadsList.removeAll { it.titleName == title && it.type == MediaType.MANGA }
            }

            MediaType.ANIME -> {
                downloadsList.removeAll { it.titleName == title && it.type == MediaType.ANIME }
            }

            MediaType.NOVEL -> {
                downloadsList.removeAll { it.titleName == title && it.type == MediaType.NOVEL }
            }
        }
        saveDownloads()
    }

    private fun cleanDownloads() {
        cleanDownload(MediaType.MANGA)
        cleanDownload(MediaType.ANIME)
        cleanDownload(MediaType.NOVEL)
    }

    private fun cleanDownload(type: MediaType) {
        // remove all folders that are not in the downloads list
        val directory = getBaseDirectory(context, type)
        val downloadsSubLists = when (type) {
            MediaType.MANGA -> mangaDownloadedTypes
            MediaType.ANIME -> animeDownloadedTypes
            else -> novelDownloadedTypes
        }
        if (directory?.exists() == true && directory.isDirectory) {
            val files = directory.listFiles()
            for (file in files) {
                if (!downloadsSubLists.any { it.titleName == file.name }) {
                    file.deleteRecursively(context, false)
                }
            }
        }
        //now remove all downloads that do not have a folder
        val iterator = downloadsList.iterator()
        while (iterator.hasNext()) {
            val download = iterator.next()
            val downloadDir = directory?.findFolder(download.titleName)
            if ((downloadDir?.exists() == false && download.type == type) || download.titleName.isBlank()) {
                iterator.remove()
            }
        }
    }

    fun moveDownloadsDir(
        context: Context,
        oldUri: Uri,
        newUri: Uri,
        finished: (Boolean, String) -> Unit
    ) {
        if (oldUri == newUri) {
            Logger.log("Source and destination are the same")
            finished(false, "Source and destination are the same")
            return
        }
        if (oldUri == Uri.EMPTY) {
            Logger.log("Old Uri is empty")
            finished(true, "Old Uri is empty")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val oldRoot =
                    DocumentFile.fromTreeUri(context, oldUri) ?: throw Exception("Old base is null")
                val newRoot =
                    DocumentFile.fromTreeUri(context, newUri) ?: throw Exception("New base is null")

                // Only the folders the app itself manages are moved, never the whole picked
                // directory — the old or new root may be a shared folder (e.g. Downloads) with
                // unrelated files that must be left untouched.
                val oldNested = PrefManager.getVal<Boolean>(PrefName.DownloadsDirNested)
                val oldContentBase = if (oldNested) oldRoot.findFolder(BASE_LOCATION) else oldRoot
                val subFolders = oldContentBase?.listFiles()
                    ?.filter { it.isDirectory && it.name in SUB_LOCATIONS }
                    .orEmpty()

                val newNested = shouldNestDownloadsFolder(newRoot)
                val newContentBase = if (newNested) {
                    newRoot.findOrCreateFolder(BASE_LOCATION, false)
                        ?: throw Exception("Could not create target folder")
                } else newRoot

                for (folder in subFolders) {
                    val (success, message) = moveFolder(context, folder, newContentBase)
                    if (!success) {
                        finished(false, message)
                        return@launch
                    }
                }
                PrefManager.setVal(PrefName.DownloadsDirNested, newNested)
                finished(true, "Successfully moved downloads")
            } catch (e: Exception) {
                snackString("Error: ${e.message}")
                Logger.log("Failed to move downloads: ${e.message}")
                Logger.log(e)
                Logger.log("oldUri: $oldUri, newUri: $newUri")
                finished(false, "Failed to move downloads: ${e.message}")
                return@launch
            }
        }
    }

    private suspend fun moveFolder(
        context: Context,
        folder: DocumentFile,
        targetParent: DocumentFile
    ): Pair<Boolean, String> = suspendCancellableCoroutine { cont ->
        folder.moveFolderTo(context, targetParent, false, folder.name, object : FolderCallback() {
            override fun onFailed(errorCode: ErrorCode) {
                Logger.log("Failed to move downloads: $errorCode")
                val message = when (errorCode) {
                    ErrorCode.CANCELED -> "Move canceled"
                    ErrorCode.CANNOT_CREATE_FILE_IN_TARGET -> "Cannot create file in target"
                    ErrorCode.INVALID_TARGET_FOLDER -> "Invalid target folder"
                    ErrorCode.NO_SPACE_LEFT_ON_TARGET_PATH -> "No space left on target path"
                    ErrorCode.UNKNOWN_IO_ERROR -> "Unknown IO error"
                    ErrorCode.SOURCE_FOLDER_NOT_FOUND -> "Source folder not found"
                    ErrorCode.STORAGE_PERMISSION_DENIED -> "Storage permission denied"
                    ErrorCode.TARGET_FOLDER_CANNOT_HAVE_SAME_PATH_WITH_SOURCE_FOLDER ->
                        "Target folder cannot have same path with source folder"

                    else -> "Failed to move downloads: $errorCode"
                }
                // INVALID_TARGET_FOLDER seems to still work despite being reported as a failure.
                val success = errorCode == ErrorCode.INVALID_TARGET_FOLDER
                if (cont.isActive) cont.resume(success to message)
                super.onFailed(errorCode)
            }

            override fun onCompleted(result: Result) {
                if (cont.isActive) cont.resume(true to "Successfully moved downloads")
                super.onCompleted(result)
            }
        })
    }

    fun queryDownload(downloadedType: DownloadedType): Boolean {
        return downloadsList.contains(downloadedType)
    }

    fun queryDownload(title: String, chapter: String, type: MediaType? = null): Boolean {
        // titleName/chapterName are sanitized (folder-name-safe) getters, so the query values
        // must be sanitized the same way or a title/chapter containing e.g. ':' or '/' would
        // never match a persisted download.
        val validTitle = title.findValidName()
        val validChapter = chapter.findValidName()
        return if (type == null) {
            downloadsList.any { it.titleName == validTitle && it.chapterName == validChapter }
        } else {
            downloadsList.any { it.titleName == validTitle && it.chapterName == validChapter && it.type == type }
        }
    }

    private fun removeDirectory(downloadedType: DownloadedType, toast: Boolean) {
        val baseDirectory = getBaseDirectory(context, downloadedType.type)
        val directory =
            baseDirectory?.findFolder(downloadedType.titleName)
                ?.findFolder(downloadedType.chapterName)
        downloadsList.removeAll { it.titleName == downloadedType.titleName && it.chapterName == downloadedType.chapterName }
        // Check if the directory exists and delete it recursively
        if (directory?.exists() == true) {
            val deleted = directory.deleteRecursively(context, false)
            if (deleted) {
                if (toast) snackString("Successfully deleted")
            } else {
                snackString("Failed to delete directory")
            }
        } else {
            snackString("Directory does not exist")
        }
    }

    fun purgeDownloads(type: MediaType) {
        val directory = getBaseDirectory(context, type)
        if (directory?.exists() == true) {
            val deleted = directory.deleteRecursively(context, false)
            if (deleted) {
                snackString("Successfully deleted")
            } else {
                snackString("Failed to delete directory")
            }
        } else {
            snackString("Directory does not exist")
        }

        downloadsList.removeAll { it.type == type }
        saveDownloads()
        _libraryChanges.tryEmit(Unit)
    }

    companion object {
        private const val BASE_LOCATION = "Dantotsu"
        private const val MANGA_SUB_LOCATION = "Manga"
        private const val ANIME_SUB_LOCATION = "Anime"
        private const val NOVEL_SUB_LOCATION = "Novel"
        private val SUB_LOCATIONS =
            setOf(MANGA_SUB_LOCATION, ANIME_SUB_LOCATION, NOVEL_SUB_LOCATION)

        /** Emits whenever downloaded content is bulk-removed (e.g. purge), so UI showing the
         *  downloaded library — not just the active queue — knows to refresh. */
        private val _libraryChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val libraryChanges: SharedFlow<Unit> = _libraryChanges

        /**
         * Whether downloads should be nested inside a "Dantotsu" subfolder of the picked root,
         * or written directly into the root. Nesting is skipped only when the picked folder is
         * already empty or is itself named "Dantotsu" — otherwise it keeps the app's files
         * isolated from whatever else lives in a shared folder the user picked (e.g. Downloads).
         */
        fun shouldNestDownloadsFolder(root: DocumentFile): Boolean {
            if (root.name?.equals(BASE_LOCATION, ignoreCase = true) == true) return false
            return root.listFiles().isNotEmpty()
        }

        /**
         * Get and create a base directory for the given type
         * @param context the context
         * @param type the type of media
         * @return the base directory
         */
        @Synchronized
        private fun getBaseDirectory(context: Context, type: MediaType): DocumentFile? {
            val base = getBaseDirectory(context) ?: return null
            return when (type) {
                MediaType.MANGA -> {
                    base.findOrCreateFolder(MANGA_SUB_LOCATION, false)
                }

                MediaType.ANIME -> {
                    base.findOrCreateFolder(ANIME_SUB_LOCATION, false)
                }

                else -> {
                    base.findOrCreateFolder(NOVEL_SUB_LOCATION, false)
                }
            }
        }

        /**
         * Get and create a subdirectory for the given type
         * @param context the context
         * @param type the type of media
         * @param title the title of the media
         * @param chapter the chapter of the media
         * @return the subdirectory
         */
        @Synchronized
        fun getSubDirectory(
            context: Context,
            type: MediaType,
            overwrite: Boolean,
            title: String,
            chapter: String? = null
        ): DocumentFile? {
            val baseDirectory = getBaseDirectory(context, type) ?: return null
            return if (chapter != null) {
                baseDirectory.findOrCreateFolder(title, false)
                    ?.findOrCreateFolder(chapter, overwrite)
            } else {
                baseDirectory.findOrCreateFolder(title, overwrite)
            }
        }

        fun getDirSize(
            context: Context,
            type: MediaType,
            title: String,
            chapter: String? = null
        ): Long {
            val directory = getSubDirectory(context, type, false, title, chapter) ?: return 0
            return folderSize(directory)
        }

        /** Recursively sums the byte length of every file under [file]. */
        fun folderSize(file: DocumentFile?): Long {
            if (file == null || !file.exists()) return 0
            if (file.isFile) return file.length()
            var size = 0L
            file.listFiles().forEach { size += folderSize(it) }
            return size
        }

        fun addNoMedia(context: Context) {
            val baseDirectory = getBaseDirectory(context) ?: return
            if (baseDirectory.findFile(".nomedia") == null) {
                baseDirectory.createFile("application/octet-stream", ".nomedia")
            }
        }

        /** The root folder ("Dantotsu") all downloads are stored under. */
        fun getDownloadsRootDirectory(context: Context): DocumentFile? = getBaseDirectory(context)

        @Synchronized
        private fun getBaseDirectory(context: Context): DocumentFile? {
            val baseDirectory = Uri.parse(PrefManager.getVal<String>(PrefName.DownloadsDir))
            if (baseDirectory == Uri.EMPTY) return null
            val root = DocumentFile.fromTreeUri(context, baseDirectory) ?: return null
            return if (PrefManager.getVal<Boolean>(PrefName.DownloadsDirNested)) {
                root.findOrCreateFolder(BASE_LOCATION, false)
            } else {
                root
            }
        }

        private val lock = Any()

        private fun DocumentFile.findOrCreateFolder(
            name: String, overwrite: Boolean
        ): DocumentFile? {
            val validName = name.findValidName()
            synchronized(lock) {
                return if (overwrite) {
                    findFolder(validName)?.delete()
                    createDirectory(validName)
                } else {
                    val folder = findFolder(validName)
                    folder ?: createDirectory(validName)
                }
            }
        }

        private fun DocumentFile.findFolder(name: String): DocumentFile? =
            listFiles().find { it.name == name && it.isDirectory }

        private const val RATIO_THRESHOLD = 95
        fun Media.compareName(name: String): Boolean {
            val mainName = mainName().findValidName().lowercase()
            val ratio = FuzzySearch.ratio(mainName, name.lowercase())
            return ratio > RATIO_THRESHOLD
        }

        fun String.compareName(name: String): Boolean {
            val mainName = findValidName().lowercase()
            val compareName = name.findValidName().lowercase()
            val ratio = FuzzySearch.ratio(mainName, compareName)
            return ratio > RATIO_THRESHOLD
        }
    }
}

private const val RESERVED_CHARS = "|\\?*<\":>+[]/'"
fun String?.findValidName(): String {
    return this?.replace("/", "_")?.filterNot { RESERVED_CHARS.contains(it) } ?: ""
}

data class DownloadedType(
    private val pTitle: String?,
    private val pChapter: String?,
    val type: MediaType,
    @Deprecated("use pTitle instead")
    private val title: String? = null,
    @Deprecated("use pChapter instead")
    private val chapter: String? = null,
    var size: Double? = null,
    val scanlator: String = "Unknown"
) : Serializable {
    val titleName: String
        get() = title ?: pTitle.findValidName()
    val chapterName: String
        get() = chapter ?: pChapter.findValidName()
    val uniqueName: String
        get() = "$chapterName-${scanlator}"
}

private fun bytesToDouble(bytes: Long): Double {
    if (bytes < 0) return 0.0
    val unit = 1000
    val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
    return bytes / unit.toDouble().pow(exp.toDouble())
}