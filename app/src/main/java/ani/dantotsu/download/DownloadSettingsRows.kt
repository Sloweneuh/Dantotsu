package ani.dantotsu.download

import android.content.Context
import ani.dantotsu.R
import ani.dantotsu.media.MediaType
import ani.dantotsu.settings.Settings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The download settings, as rows.
 *
 * These lived only inside [DownloadActivity]'s own settings tab, which meant there was no path to
 * them from Settings at all — the search bar could route to the tab but could not even highlight
 * the row once it arrived, because a ViewPager fragment is not a settings list. They are now also a
 * group on the Sources &amp; Downloads screen.
 *
 * Defined once here rather than copied, so the two entry points cannot drift: the downloads screen
 * keeps its tab, which is the convenient place to reach them while looking at downloads, and
 * Settings gains the path it was missing.
 *
 * @param compact tightens row spacing for the rows nested inside a settings card.
 */
fun Context.downloadSettingsRows(compact: Boolean = false): List<Settings> {
    val context = this
    val downloadsManager: DownloadsManager = Injekt.get()

    fun purgeRow(
        type: MediaType,
        titleRes: Int,
        descRes: Int,
        mediaNameRes: Int,
        iconRes: Int,
        key: String,
    ) = Settings(
        type = 1,
        name = getString(titleRes),
        desc = getString(descRes),
        icon = iconRes,
        compact = compact,
        anchorKey = key,
        onClick = {
            context.customAlertDialog().apply {
                setTitle(titleRes)
                setMessage(R.string.purge_confirm, getString(mediaNameRes))
                setPosButton(R.string.yes) { downloadsManager.purgeDownloads(type) }
                setNegButton(R.string.no)
                show()
            }
        },
    )

    return listOf(
        Settings(
            type = 1,
            name = getString(R.string.download_manager_select),
            desc = getString(R.string.download_manager_select_desc),
            icon = R.drawable.ic_round_download_manager_24,
            compact = compact,
            anchorKey = "download_manager",
            onClick = {
                val managers = arrayOf("Default", "1DM", "ADM")
                context.customAlertDialog().apply {
                    setTitle(getString(R.string.download_manager))
                    singleChoiceItems(managers, PrefManager.getVal(PrefName.DownloadManager)) { i ->
                        PrefManager.setVal(PrefName.DownloadManager, i)
                    }
                    show()
                }
            },
        ),
        Settings(
            type = 2,
            name = getString(R.string.allow_metered_downloads),
            desc = getString(R.string.allow_metered_downloads_desc),
            icon = R.drawable.ic_round_download_metered_24,
            compact = compact,
            anchorKey = "metered_downloads",
            isChecked = PrefManager.getVal(PrefName.AllowMeteredDownloads),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.AllowMeteredDownloads, isChecked)
            },
        ),
        // The media type badged with a bin. All three rows are the same irreversible action and used
        // to share one bin between them, which left the only thing that differs — which library it
        // empties — carried by the label alone.
        purgeRow(
            MediaType.ANIME, R.string.purge_anime_downloads, R.string.purge_anime_downloads_desc,
            R.string.anime, R.drawable.ic_round_purge_anime_24, "purge_anime",
        ),
        purgeRow(
            MediaType.MANGA, R.string.purge_manga_downloads, R.string.purge_manga_downloads_desc,
            R.string.manga, R.drawable.ic_round_purge_manga_24, "purge_manga",
        ),
        purgeRow(
            MediaType.NOVEL, R.string.purge_novel_downloads, R.string.purge_novel_downloads_desc,
            R.string.novels, R.drawable.ic_round_purge_novel_24, "purge_novel",
        ),
    )
}
