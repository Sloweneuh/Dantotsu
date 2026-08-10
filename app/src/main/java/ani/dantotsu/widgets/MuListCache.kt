package ani.dantotsu.widgets

import android.content.Context
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The MangaUpdates reading list, as the waiting widget last saw it.
 *
 * Kept by the widget itself so a refresh that isn't allowed to hit the network — the refresh button —
 * still has the list to work from. It is not a second source of truth: a full refresh overwrites it with
 * what MangaUpdates returns.
 */
object MuListCache {

    private const val PREFS = "ani.dantotsu.widget.mulist"
    private const val KEY = "reading"

    private val gson = Gson()
    private val type = object : TypeToken<List<MUMedia>>() {}.type

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, series: List<MUMedia>) {
        runCatching { prefs(context).edit().putString(KEY, gson.toJson(series)).apply() }
            .onFailure { Logger.log("MuListCache: save failed: $it") }
    }

    fun load(context: Context): List<MUMedia> = runCatching {
        prefs(context).getString(KEY, null)?.let { gson.fromJson<List<MUMedia>>(it, type) } ?: emptyList()
    }.onFailure { Logger.log("MuListCache: load failed: $it") }.getOrDefault(emptyList())
}
