package ani.dantotsu

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.media.ComickMediaActivity
import ani.dantotsu.media.KitsuMediaActivity
import ani.dantotsu.media.MalMediaActivity
import ani.dantotsu.media.MangaBakaMediaActivity
import ani.dantotsu.media.StackResolver
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.SimklMediaActivity
import ani.dantotsu.profile.ProfileActivity
import ani.dantotsu.util.Logger
import uy.kohesive.injekt.api.get

// Opening links: in the matching in-app screen when there is one, else the browser.

fun openLinkInBrowser(link: String?) {
    link?.let {
        try {
            val emptyBrowserIntent = Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                data = Uri.fromParts("http", "", null)
            }
            val sendIntent = Intent().apply {
                action = Intent.ACTION_VIEW
                addCategory(Intent.CATEGORY_BROWSABLE)
                data = Uri.parse(link)
                selector = emptyBrowserIntent
            }
            currContext()!!.startActivity(sendIntent)
        } catch (e: ActivityNotFoundException) {
            snackString("No browser found")
        } catch (e: Exception) {
            Logger.log(e)
        }
    }
}

/**
 * Opens a MangaUpdates *series* link directly inside the app via [MUMediaDetailsActivity] instead
 * of routing it through an external browser. Opening such a link with a plain browser intent makes
 * the browser flash on screen before its deep-link filter bounces control back into the app; going
 * straight to the activity avoids that.
 *
 * Accepts both link shapes MangaUpdates uses — `/series/<slugOrId>` and `/series.html?id=<num>` —
 * and normalizes them to the `/series/<slugOrId>` form the activity's deep-link handler expects.
 *
 * @param configure extras for the activity beyond the link itself (e.g. the extension entry the
 *   series was reached from, so the page can link the two).
 * @return true if [link] was a MangaUpdates series link and was handled in-app; false otherwise, in
 *   which case the caller should fall back to [openLinkInBrowser].
 */
fun openMangaUpdatesSeriesInApp(link: String?, configure: Intent.() -> Unit = {}): Boolean {
    val normalized = mangaUpdatesSeriesUri(link) ?: return false
    return try {
        val ctx = currContext() ?: return false
        val intent = Intent(Intent.ACTION_VIEW, normalized).apply {
            setClass(ctx, ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity::class.java)
            if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            configure()
        }
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        Logger.log(e)
        false
    }
}

/** A MangaUpdates series link in the `/series/<slugOrId>` form, or null if [link] isn't one. */
private fun mangaUpdatesSeriesUri(link: String?): Uri? {
    val uri = link?.let { Uri.parse(it) } ?: return null
    val host = uri.host?.lowercase()?.removePrefix("www.")
    if (host != "mangaupdates.com") return null

    val segments = uri.pathSegments ?: emptyList()
    val slugOrId = when (segments.firstOrNull()) {
        "series" -> segments.getOrNull(1)
        "series.html" -> uri.getQueryParameter("id")
        else -> null
    }?.takeIf { it.isNotBlank() } ?: return null
    return Uri.parse("https://www.mangaupdates.com/series/$slugOrId")
}

/**
 * The intent [openLinkInApp] would start for [link], without starting it — for a tap target that
 * fires later, such as a notification's. Null when the app has no screen for [link].
 */
fun inAppIntentForLink(ctx: Context, link: String): Intent? {
    mangaUpdatesSeriesUri(link)?.let { uri ->
        return Intent(Intent.ACTION_VIEW, uri)
            .setClass(ctx, ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity::class.java)
    }
    val uri = Uri.parse(link)
    if (uri.scheme != "http" && uri.scheme != "https") return null
    return inAppIntentFor(ctx, uri)
}

/**
 * Opens [link] on the app's own screen for it, when it has one: AniList media and profiles,
 * MangaUpdates series, MyAnimeList media, and the Comick, MangaBaka, Kitsu and Simkl pages the
 * manifest also deep-links. [openLinkInBrowser] pins a browser, so those deep-link filters never
 * get a say there; and re-dispatching the URL at the app instead would land AniList and MAL links
 * on [ani.dantotsu.connections.anilist.UrlMedia], which restarts the whole task — hence explicit
 * intents.
 *
 * @return false when the app has no screen for [link]; callers usually fall back to the browser,
 *   see [openLinkInAppOrBrowser].
 */
fun openLinkInApp(link: String?): Boolean {
    if (openMangaUpdatesSeriesInApp(link)) return true
    val uri = link?.let { Uri.parse(it) } ?: return false
    if (uri.scheme != "http" && uri.scheme != "https") return false
    val ctx = currContext() ?: return false
    val intent = inAppIntentFor(ctx, uri) ?: return false
    return try {
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        Logger.log(e)
        false
    }
}

/** [openLinkInApp], and the browser for everything else. */
fun openLinkInAppOrBrowser(link: String?) {
    if (!openLinkInApp(link)) openLinkInBrowser(link)
}

/**
 * The screen for a `<site>/<kind>/<id-or-slug>` link, or null when the app has none. MangaUpdates
 * isn't here: its two link shapes are [openMangaUpdatesSeriesInApp]'s.
 */
private fun inAppIntentFor(ctx: Context, uri: Uri): Intent? {
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
    val segments = uri.pathSegments.orEmpty()
    val kind = segments.getOrNull(0) ?: return null
    val target = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    return when (host) {
        "anilist.co" -> when (kind) {
            "anime", "manga" -> target.toIntOrNull()?.let {
                Intent(ctx, MediaDetailsActivity::class.java).putExtra("mediaId", it)
            }
            "user" -> Intent(ctx, ProfileActivity::class.java).apply {
                val id = target.toIntOrNull()
                if (id != null) putExtra("userId", id) else putExtra("username", target)
            }
            else -> null
        }
        "myanimelist.net" -> when (kind) {
            "anime", "manga" -> target.toIntOrNull()?.let {
                Intent(ctx, MalMediaActivity::class.java)
                    .putExtra(MalMediaActivity.EXTRA_MEDIA_ID, it)
                    .putExtra(MalMediaActivity.EXTRA_IS_ANIME, kind == "anime")
            }
            else -> null
        }
        "comick.io", "comick.dev", "comick.app", "comick.cc" -> when (kind) {
            "comic", "anime" -> Intent(ctx, ComickMediaActivity::class.java)
                .putExtra(ComickMediaActivity.EXTRA_SLUG, target)
                .putExtra(
                    ComickMediaActivity.EXTRA_MEDIA_TYPE,
                    if (kind == "anime") ComickApi.MEDIA_TYPE_ANIME else ComickApi.MEDIA_TYPE_MANGA
                )
            else -> null
        }
        "mangabaka.org" -> when (kind) {
            "series" -> target.toLongOrNull()?.let {
                Intent(ctx, MangaBakaMediaActivity::class.java)
                    .putExtra(MangaBakaMediaActivity.EXTRA_SERIES_ID, it)
            }
            "stacks" -> StackResolver.mangaBakaStackId(target)?.let {
                StackResolver.mangaBakaIntent(ctx, it, title = null, description = null)
            }
            else -> null
        }
        "kitsu.io", "kitsu.app" -> when (kind) {
            "anime", "manga" -> Intent(ctx, KitsuMediaActivity::class.java)
                .putExtra(KitsuMediaActivity.EXTRA_MEDIA_ID, target)
                .putExtra(KitsuMediaActivity.EXTRA_IS_ANIME, kind == "anime")
            else -> null
        }
        "simkl.com" -> if (kind == "anime") target.toLongOrNull()?.let {
            Intent(ctx, SimklMediaActivity::class.java)
                .putExtra(SimklMediaActivity.EXTRA_SIMKL_ID, it)
        } else null
        else -> null
    }
}

fun openLinkInYouTube(link: String?) {
    link?.let {
        try {
            val videoIntent = Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                data = Uri.parse(link)
                setPackage("com.google.android.youtube")
            }
            currContext()!!.startActivity(videoIntent)
        } catch (e: ActivityNotFoundException) {
            openLinkInBrowser(link)
        } catch (e: Exception) {
            Logger.log(e)
        }
    }
}

/**
 * The link resolver for user-written AniList content (comments, activities): AniList media and
 * profiles open in-app, YouTube in its app, and anything else is only copied — never opened.
 * @param link the link to open
 */
fun openOrCopyAnilistLink(link: String) {
    val ctx = currContext()
    val uri = Uri.parse(link)
    val anilist = if (ctx != null && uri.host?.removePrefix("www.") == "anilist.co") {
        inAppIntentFor(ctx, uri)
    } else null
    when {
        anilist != null -> ctx!!.startActivity(anilist)
        getYoutubeId(link).isNotEmpty() -> openLinkInYouTube(link)
        else -> copyToClipboard(link, true)
    }
}
