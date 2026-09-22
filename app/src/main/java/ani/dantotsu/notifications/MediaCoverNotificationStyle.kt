package ani.dantotsu.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import ani.dantotsu.R

/**
 * Lays a media cover out to the left of the title/text, the way the in-app notification list
 * does — the framework's own large icon is always pinned to the right and cropped to a square,
 * regardless of the source image's aspect ratio, which looks wrong for a portrait cover.
 */
object MediaCoverNotificationStyle {
    /** Matches the in-app notification card's own cover (108dp x 160dp). */
    private const val COVER_ASPECT_RATIO = 108f / 160f
    private const val CORNER_RADIUS_RATIO = 16f / 108f

    /** A dub/sub badge (icon + short code, e.g. "EN"), the same pairing used elsewhere in the app. */
    data class LanguageBadge(val iconRes: Int, val code: String)

    /**
     * Applies the layout when [cover] loaded successfully; otherwise leaves [builder] untouched
     * so callers can fall back to their own (text-only) presentation. [text] is the chapter/count
     * line only. The expanded view has room to give title, that line, and the source/language
     * line each their own row; the collapsed one only fits two, so it shows just the [languageBadge]
     * inline next to the chapter line when there is one — [sourceText] doesn't fit there at all.
     * Exactly one of [languageBadge] (a dub/sub icon+code, instead of spelling the language out) or
     * [sourceText] (a plain source string) should be given. [label], when given (e.g. "New Chapter
     * Available"), is shown above the title in the collapsed view — its custom content view gets
     * no header decoration of its own (no app name, no subText) for [label] to go in instead. The
     * expanded view's decoration does show subText next to the app name, but only for a genuinely
     * standalone notification; a child expanded inside a same-app group gets just a bare
     * timestamp divider there regardless of subText, so callers should pass [showLabelInExpanded]
     * `true` only when this notification will actually render bundled with others.
     */
    fun apply(
        context: Context,
        builder: NotificationCompat.Builder,
        title: String,
        text: CharSequence,
        cover: Bitmap?,
        languageBadge: LanguageBadge? = null,
        sourceText: String? = null,
        label: String? = null,
        showLabelInExpanded: Boolean = false,
    ): NotificationCompat.Builder {
        if (cover == null) return builder
        val rounded = cover
            .centerCropToAspect(COVER_ASPECT_RATIO)
            .let { it.withRoundedCorners(it.width * CORNER_RADIUS_RATIO) }

        // Collapsed only has room for two lines (plus the label) before it starts clipping (its
        // own layout has the details): the source doesn't fit there at all — only the dub/sub
        // badge, which stays inline next to the chapter line rather than getting a row of its own.
        val collapsed = RemoteViews(context.packageName, R.layout.notification_media_cover_small).apply {
            setLabel(label)
            setTextViewText(R.id.notificationTitle, title)
            setTextViewText(R.id.notificationText, text)
            setImageViewBitmap(R.id.notificationCover, rounded)
            setLanguageBadge(languageBadge)
        }
        val expanded = RemoteViews(context.packageName, R.layout.notification_media_cover_big).apply {
            setLabel(if (showLabelInExpanded) label else null)
            setTextViewText(R.id.notificationTitle, title)
            setTextViewText(R.id.notificationText, text)
            setImageViewBitmap(R.id.notificationCover, rounded)
            setLanguageBadge(languageBadge)
            if (languageBadge == null && sourceText != null) {
                setViewVisibility(R.id.notificationSourceText, View.VISIBLE)
                setTextViewText(R.id.notificationSourceText, sourceText)
            } else {
                setViewVisibility(R.id.notificationSourceText, View.GONE)
            }
        }
        // No setLargeIcon here: DecoratedCustomViewStyle already reserves a large-icon slot in its
        // own header decoration, on top of whatever the custom content views draw — setting one
        // would duplicate the cover instead of replacing the square icon.
        return builder
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
    }

    /** Both cover layouts share this id for the (optional) generic label above the title. */
    private fun RemoteViews.setLabel(label: String?) {
        if (label == null) {
            setViewVisibility(R.id.notificationLabel, View.GONE)
            return
        }
        setViewVisibility(R.id.notificationLabel, View.VISIBLE)
        setTextViewText(R.id.notificationLabel, label)
    }

    /** Both cover layouts share these ids for the (optional) dub/sub badge. */
    private fun RemoteViews.setLanguageBadge(badge: LanguageBadge?) {
        if (badge == null) {
            setViewVisibility(R.id.notificationLanguageBadge, View.GONE)
            return
        }
        setViewVisibility(R.id.notificationLanguageBadge, View.VISIBLE)
        setImageViewResource(R.id.notificationLanguageIcon, badge.iconRes)
        // The icon's own fill is a near-white gray meant for a tinted background chip; plain
        // here, it needs an explicit, theme-agnostic tint to stay visible on both a light and
        // dark shade.
        setInt(R.id.notificationLanguageIcon, "setColorFilter", Color.GRAY)
        setTextViewText(R.id.notificationLanguageCode, badge.code)
    }

    private fun Bitmap.centerCropToAspect(targetRatio: Float): Bitmap {
        val currentRatio = width.toFloat() / height.toFloat()
        return when {
            currentRatio > targetRatio -> {
                val newWidth = (height * targetRatio).toInt().coerceIn(1, width)
                Bitmap.createBitmap(this, (width - newWidth) / 2, 0, newWidth, height)
            }
            currentRatio < targetRatio -> {
                val newHeight = (width / targetRatio).toInt().coerceIn(1, height)
                Bitmap.createBitmap(this, 0, (height - newHeight) / 2, width, newHeight)
            }
            else -> this
        }
    }

    private fun Bitmap.withRoundedCorners(radiusPx: Float): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, width, height)
        canvas.drawRoundRect(RectF(rect), radiusPx, radiusPx, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(this, rect, rect, paint)
        return output
    }
}
