package ani.dantotsu.media.history

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.R
import ani.dantotsu.media.MediaType
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.AppScope
import ani.dantotsu.util.Logger
import kotlinx.coroutines.launch

/**
 * What was watched and read on this device, newest first: one row per media and type, holding the
 * episode or chapter last opened and when. Local only — not synced, not in backups — and nothing
 * is recorded in incognito.
 *
 * Only media with a page an id can bring back are kept: AniList media, and MangaUpdates series
 * (by their MU id — their `Media.id` is only that id truncated, and would open an unrelated
 * AniList entry). An extension-only entry (negative id) has neither.
 */
object WatchHistory {

    data class Entry(
        val mediaId: Int,
        val type: MediaType,
        val title: String,
        val cover: String?,
        /** The episode or chapter number, as the source names it. */
        val number: String,
        val itemTitle: String?,
        val at: Long,
        /** Set for a MangaUpdates series, which reopens on its MangaUpdates page. */
        val muSeriesId: Long? = null,
    ) {
        /**
         * "Chapter 41 · Title". A source names its chapters however it likes ("Ch. 41",
         * "Chapter 41", "41"), so the number is parsed out of that rather than prefixed to it, and
         * the title loses the same name when it starts with it. A name with no number in it is
         * shown as it is.
         */
        fun itemLine(context: Context): String {
            val anime = type == MediaType.ANIME
            val parsed = if (anime) MediaNameAdapter.findEpisodeNumber(number)
            else MediaNameAdapter.findChapterNumber(number)
            val numberText = parsed
                ?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
                ?.let { context.getString(if (anime) R.string.episode_num else R.string.chapter_num, it) }
                ?: number
            val title = itemTitle?.trim()
                ?.removePrefix(number)
                ?.trimStart(' ', '-', ':', '·', '|', '.')
                ?.takeIf { it.isNotBlank() && it != number }
            return if (title != null) "$numberText · $title" else numberText
        }

        /** The page this entry came from: its MangaUpdates page, or its AniList one. */
        fun openIntent(context: Context): Intent =
            if (muSeriesId != null) {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.mangaupdates.com/series/${muSeriesId.toString(36)}")
                ).setClass(context, MUMediaDetailsActivity::class.java)
            } else {
                Intent(context, MediaDetailsActivity::class.java).putExtra("mediaId", mediaId)
            }
    }

    /** Oldest rows past this are dropped on each write. */
    private const val MAX_ENTRIES = 1000

    private var helper: Helper? = null

    fun init(context: Context) {
        if (helper == null) helper = Helper(context.applicationContext)
    }

    /** Off the main thread; a failure is logged and otherwise ignored. */
    fun record(media: Media, type: MediaType, number: String, itemTitle: String?) {
        if (media.id < 0 || number.isBlank()) return
        if (PrefManager.getVal<Boolean>(PrefName.Incognito)) return
        val db = helper ?: return
        val title = media.userPreferredName
        val cover = media.cover
        val muSeriesId = media.muSeriesId
        val at = System.currentTimeMillis()
        AppScope.launch {
            db.writableDatabase.run {
                insertWithOnConflict(
                    TABLE, null,
                    ContentValues().apply {
                        put(COL_ID, media.id)
                        put(COL_TYPE, type.name)
                        put(COL_TITLE, title)
                        put(COL_COVER, cover)
                        put(COL_NUMBER, number)
                        put(COL_ITEM_TITLE, itemTitle?.takeIf { it.isNotBlank() })
                        put(COL_AT, at)
                        put(COL_MU_ID, muSeriesId)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                execSQL(
                    "DELETE FROM $TABLE WHERE rowid NOT IN " +
                        "(SELECT rowid FROM $TABLE ORDER BY $COL_AT DESC LIMIT $MAX_ENTRIES)"
                )
            }
        }
    }

    /** Newest first. Blocking; call off the main thread. */
    fun entries(limit: Int? = null): List<Entry> {
        val db = helper?.readableDatabase ?: return emptyList()
        return try {
            db.query(
                TABLE,
                arrayOf(COL_ID, COL_TYPE, COL_TITLE, COL_COVER, COL_NUMBER, COL_ITEM_TITLE, COL_AT, COL_MU_ID),
                null, null, null, null, "$COL_AT DESC", limit?.toString()
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val type = runCatching { MediaType.valueOf(c.getString(1)) }.getOrNull() ?: continue
                        add(
                            Entry(
                                mediaId = c.getInt(0),
                                type = type,
                                title = c.getString(2),
                                cover = c.getString(3),
                                number = c.getString(4),
                                itemTitle = c.getString(5),
                                at = c.getLong(6),
                                muSeriesId = if (c.isNull(7)) null else c.getLong(7),
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Logger.log(e)
            emptyList()
        }
    }

    /** Blocking; call off the main thread. */
    fun remove(entry: Entry) {
        helper?.writableDatabase?.delete(
            TABLE, "$COL_ID = ? AND $COL_TYPE = ?", arrayOf(entry.mediaId.toString(), entry.type.name)
        )
    }

    /** Blocking; call off the main thread. */
    fun clear() {
        helper?.writableDatabase?.delete(TABLE, null, null)
    }

    private const val TABLE = "history"
    private const val COL_ID = "media_id"
    private const val COL_TYPE = "type"
    private const val COL_TITLE = "title"
    private const val COL_COVER = "cover"
    private const val COL_NUMBER = "number"
    private const val COL_ITEM_TITLE = "item_title"
    private const val COL_AT = "at"
    private const val COL_MU_ID = "mu_id"

    private class Helper(context: Context) : SQLiteOpenHelper(context, "history.db", null, 2) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE ($COL_ID INTEGER NOT NULL, $COL_TYPE TEXT NOT NULL, " +
                    "$COL_TITLE TEXT NOT NULL, $COL_COVER TEXT, $COL_NUMBER TEXT NOT NULL, " +
                    "$COL_ITEM_TITLE TEXT, $COL_AT INTEGER NOT NULL, $COL_MU_ID INTEGER, " +
                    "PRIMARY KEY ($COL_ID, $COL_TYPE))"
            )
            db.execSQL("CREATE INDEX history_at ON $TABLE ($COL_AT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 2: MangaUpdates series. Rows from 1 that were MU series stay as they are until
            // they're next read, which rewrites them with the id.
            if (oldVersion < 2) db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_MU_ID INTEGER")
        }
    }
}
