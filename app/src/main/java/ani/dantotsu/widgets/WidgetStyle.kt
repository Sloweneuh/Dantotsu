package ani.dantotsu.widgets

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.ContextThemeWrapper
import android.widget.RemoteViews
import androidx.annotation.ColorInt
import ani.dantotsu.R
import ani.dantotsu.getThemeColor
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager

/**
 * The resolved colours one widget instance draws with.
 *
 * Every widget layout carries a `widgetBackground` image behind its content, which [applyTo] tints —
 * no bitmap is rendered anywhere. The old code built a [android.graphics.drawable.GradientDrawable],
 * measured the widget, and rasterised a bitmap that size on every single update, which is both the
 * most expensive thing the widgets did and why they smeared when resized.
 */
data class WidgetStyle(
    @ColorInt val background: Int,
    val fade: Boolean,
    @ColorInt val title: Int,
    @ColorInt val subtitle: Int,
    @ColorInt val accent: Int
) {

    /** Paints the background and the colours shared by every widget layout. */
    fun applyTo(views: RemoteViews) {
        views.setImageViewResource(
            R.id.widgetBackground,
            if (fade) R.drawable.widget_background_fade else R.drawable.widget_background
        )
        // Alpha is applied separately on purpose: a colour filter blends towards the tint without
        // making the view translucent, so a half-transparent background colour would otherwise come
        // out as flat grey rather than showing the wallpaper through it.
        views.setInt(R.id.widgetBackground, "setColorFilter", background or OPAQUE_MASK)
        views.setInt(R.id.widgetBackground, "setImageAlpha", Color.alpha(background))
    }

    /**
     * The same painting against real views, for the configure screen's live preview — so what the
     * preview shows and what the home screen shows can't drift apart.
     */
    fun applyTo(background: android.widget.ImageView) {
        background.setImageResource(
            if (fade) R.drawable.widget_background_fade else R.drawable.widget_background
        )
        background.setColorFilter(this.background or OPAQUE_MASK)
        background.imageAlpha = Color.alpha(this.background)
    }

    companion object {

        private const val OPAQUE_MASK = 0xFF000000.toInt()

        fun of(context: Context, appWidgetId: Int): WidgetStyle =
            of(context, WidgetPrefs.of(context, appWidgetId))

        fun of(context: Context, prefs: WidgetPrefs): WidgetStyle = when (prefs.themeMode) {
            WidgetThemeMode.CUSTOM -> WidgetStyle(
                background = prefs.backgroundColor,
                fade = prefs.fadeBackground,
                title = prefs.titleColor,
                subtitle = prefs.subtitleColor,
                accent = prefs.titleColor
            )

            WidgetThemeMode.APP_THEME -> appTheme(context)

            // Material You only exists from Android 12; below it the app's own palette is the closest
            // thing to "match the system", and it is what the user sees in the app anyway.
            WidgetThemeMode.MATERIAL_YOU ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) materialYou(context)
                else appTheme(context)
        }

        private fun materialYou(context: Context): WidgetStyle {
            val dark = ThemeManager.isDarkTheme(context)
            fun color(id: Int) = context.getColor(id)
            return if (dark) {
                WidgetStyle(
                    background = color(android.R.color.system_neutral1_900),
                    fade = false,
                    title = color(android.R.color.system_neutral1_50),
                    subtitle = color(android.R.color.system_neutral2_200),
                    accent = color(android.R.color.system_accent1_200)
                )
            } else {
                WidgetStyle(
                    background = color(android.R.color.system_neutral1_50),
                    fade = false,
                    title = color(android.R.color.system_neutral1_900),
                    subtitle = color(android.R.color.system_neutral2_700),
                    accent = color(android.R.color.system_accent1_600)
                )
            }
        }

        /**
         * Colours pulled straight out of the Dantotsu theme the user picked.
         *
         * [ThemeManager] can only apply a theme to an Activity — a widget update has none — so the
         * style is resolved through a wrapper instead. That covers the picked theme and its OLED
         * variant; the dynamic Material You overlay is [materialYou]'s job.
         */
        private fun appTheme(context: Context): WidgetStyle {
            val useOLED = PrefManager.getVal<Boolean>(PrefName.UseOLED) &&
                ThemeManager.isDarkTheme(context)
            val themed = ContextThemeWrapper(
                context,
                ThemeManager.styleFor(PrefManager.getVal(PrefName.Theme), useOLED)
            )
            return WidgetStyle(
                background = themed.getThemeColor(com.google.android.material.R.attr.colorSurface),
                fade = false,
                title = themed.getThemeColor(com.google.android.material.R.attr.colorOnSurface),
                subtitle = themed.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant),
                accent = themed.getThemeColor(com.google.android.material.R.attr.colorPrimary)
            )
        }
    }
}
