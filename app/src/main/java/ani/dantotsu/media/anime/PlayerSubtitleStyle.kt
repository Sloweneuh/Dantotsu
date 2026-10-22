package ani.dantotsu.media.anime

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DEPRESSED
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
import androidx.media3.ui.PlayerView
import ani.dantotsu.R
import ani.dantotsu.media.screenshot.ClipSubtitleOverlay
import ani.dantotsu.others.Xubtitle
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * The player's subtitle look, from the subtitle preferences: on the player's own subtitle view,
 * on the text view used instead of it, and described for burning into an exported clip.
 */
@UnstableApi
object PlayerSubtitleStyle {

    /** The user's chosen subtitle font, shared by both views and burned-in clip text. */
    fun typeface(context: Context): Typeface? = ResourcesCompat.getFont(
        context,
        when (PrefManager.getVal<Int>(PrefName.Font)) {
            1 -> R.font.poppins_bold
            2 -> R.font.poppins
            3 -> R.font.poppins_thin
            4 -> R.font.century_gothic_regular
            5 -> R.font.levenim_mt_bold
            6 -> R.font.blocky
            else -> R.font.poppins_semi_bold
        },
    )

    private fun alpha(): Float =
        if (PrefManager.getVal(PrefName.Subtitles)) PrefManager.getVal(PrefName.SubAlpha) else 0f

    /** Styles the player's own subtitle view. */
    fun applyTo(context: Context, playerView: PlayerView) {
        val outline = when (PrefManager.getVal<Int>(PrefName.Outline)) {
            1 -> EDGE_TYPE_DEPRESSED // Shine
            2 -> EDGE_TYPE_DROP_SHADOW // Drop shadow
            3 -> EDGE_TYPE_NONE // No outline
            else -> EDGE_TYPE_OUTLINE // Normal
        }
        playerView.subtitleView?.let { subtitles ->
            subtitles.setApplyEmbeddedStyles(false)
            subtitles.setApplyEmbeddedFontSizes(false)
            subtitles.setStyle(
                CaptionStyleCompat(
                    PrefManager.getVal(PrefName.PrimaryColor),
                    PrefManager.getVal(PrefName.SubBackground),
                    PrefManager.getVal(PrefName.SubWindow),
                    outline,
                    PrefManager.getVal(PrefName.SecondaryColor),
                    typeface(context),
                ),
            )
            subtitles.alpha = alpha()
            subtitles.setFixedTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                PrefManager.getVal<Int>(PrefName.FontSize).toFloat(),
            )
        }
    }

    /** Styles the text view that shows subtitles when "text view subtitles" is on. */
    fun applyTo(context: Context, textView: Xubtitle) {
        val secondaryColor = PrefManager.getVal<Int>(PrefName.SecondaryColor)
        val subStroke = PrefManager.getVal<Float>(PrefName.SubStroke)
        textView.setBackgroundColor(PrefManager.getVal(PrefName.SubBackground))
        textView.setTextColor(PrefManager.getVal<Int>(PrefName.PrimaryColor))
        textView.typeface = typeface(context)
        textView.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            PrefManager.getVal<Int>(PrefName.FontSize).toFloat(),
        )
        when (PrefManager.getVal<Int>(PrefName.Outline)) {
            1 -> textView.applyShineEffect(secondaryColor)
            2 -> textView.applyDropShadow(secondaryColor, subStroke)
            3 -> {}
            else -> textView.applyOutline(secondaryColor, subStroke)
        }
        textView.alpha = alpha()
        textView.translationY = -PrefManager.getVal<Float>(PrefName.SubBottomMargin) / 50 *
            context.resources.displayMetrics.heightPixels
    }

    /** The on-screen text subtitle styling, described so an export can reproduce it. */
    fun forClip(context: Context): ClipSubtitleOverlay.TextStyle {
        val density = context.resources.displayMetrics.density
        val screenHeight = context.resources.displayMetrics.heightPixels
        return ClipSubtitleOverlay.TextStyle(
            typeface = typeface(context),
            textColor = PrefManager.getVal(PrefName.PrimaryColor),
            outlineColor = PrefManager.getVal(PrefName.SecondaryColor),
            backgroundColor = PrefManager.getVal(PrefName.SubBackground),
            textSizePx = PrefManager.getVal<Int>(PrefName.FontSize) * density,
            strokeWidthPx = PrefManager.getVal<Float>(PrefName.SubStroke),
            alpha = alpha(),
            bottomMarginPx = PrefManager.getVal<Float>(PrefName.SubBottomMargin) / 50 * screenHeight,
            // "None" is the only outline mode with nothing drawn behind the glyphs.
            outlined = PrefManager.getVal<Int>(PrefName.Outline) != 3,
            referenceHeight = screenHeight,
        )
    }
}
