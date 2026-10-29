package ani.dantotsu.media

import android.app.Activity
import android.content.Intent
import android.graphics.PorterDuff
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.getThemeColor
import ani.dantotsu.util.materialDialog

/** A site a media page can be shared from: its name, the page's link, and its icon. */
data class ShareLink(val label: String, val url: String, val icon: Int)

/**
 * The links a media page has, in the order of its info tabs ([order], as the user arranged them).
 * Sites without a tab on that page come last; anything unknown is left out.
 */
fun mediaShareLinks(
    order: InfoTabContext,
    isAnime: Boolean,
    aniListId: Int?,
    malId: Int?,
    comickSlug: String?,
    mangaUpdatesUrl: String?,
    mangaBakaId: Long?
): List<ShareLink> {
    val type = if (isAnime) "anime" else "manga"
    val links = buildMap {
        aniListId?.let { put(InfoTabType.ANILIST, ShareLink("AniList", "https://anilist.co/$type/$it", R.drawable.ic_anilist)) }
        malId?.let { put(InfoTabType.MAL, ShareLink("MyAnimeList", "https://myanimelist.net/$type/$it", R.drawable.ic_myanimelist)) }
        // Comick's anime entries sit under /anime/ in a separate catalogue.
        comickSlug?.let {
            val comickType = if (isAnime) ComickApi.MEDIA_TYPE_ANIME else ComickApi.MEDIA_TYPE_MANGA
            put(InfoTabType.COMICK, ShareLink("Comick", ComickApi.webUrl(it, comickType), R.drawable.ic_round_comick_24))
        }
        if (!isAnime) {
            mangaUpdatesUrl?.let { put(InfoTabType.MANGAUPDATES, ShareLink("MangaUpdates", it, R.drawable.ic_round_mangaupdates_24)) }
            mangaBakaId?.takeIf { it > 0 }?.let {
                put(InfoTabType.MANGABAKA, ShareLink("MangaBaka", "https://mangabaka.org/$it", R.drawable.ic_round_mangabaka_24))
            }
        }
    }
    val tabs = order.orderedTabs()
    return (tabs + (InfoTabType.entries - tabs.toSet())).mapNotNull { links[it] }
}

/** The share dialog: one icon per link, each opening the system share sheet for it. */
fun Activity.showShareDialog(title: String, links: List<ShareLink>) {
    val dialog = materialDialog().create()
    dialog.setCustomTitle(TextView(this).apply {
        setText(R.string.share)
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding(32, 32, 32, 16)
        setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
    })

    val iconLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(32, 16, 32, 32)
    }
    links.forEach { link ->
        iconLayout.addView(ImageView(this).apply {
            setImageResource(link.icon)
            val size = (56 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(16, 0, 16, 0) }
            setPadding(12, 12, 12, 12)
            // Same color as the tab icons (bg_opp: black in light theme, white in dark theme).
            setColorFilter(ContextCompat.getColor(context, R.color.bg_opp), PorterDuff.Mode.SRC_IN)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            contentDescription = link.label
            setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link.url)
                    putExtra(Intent.EXTRA_SUBJECT, "$title - ${link.label}")
                }
                startActivity(Intent.createChooser(shareIntent, "Share ${link.label} link"))
                dialog.dismiss()
            }
        })
    }
    dialog.setView(iconLayout)
    dialog.show()
}
