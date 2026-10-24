package ani.dantotsu.settings

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import ani.dantotsu.R
import ani.dantotsu.px
import com.google.android.material.color.MaterialColors

/**
 * Puts a search button across from a settings page's back button, opening the settings search
 * from wherever the user is rather than only from the top-level screen.
 *
 * Built here rather than in each page's layout: every page draws the same back card at the top of
 * a vertical column, so the card is lifted into a row with the search card at its far end. The
 * search card copies the back card's look, so the pair reads as one header.
 */
object SettingsSearchButton {
    fun attach(activity: Activity, back: View) {
        val backCard = back.parent as? CardView ?: return
        val column = backCard.parent as? LinearLayout ?: return
        val cardParams = backCard.layoutParams as LinearLayout.LayoutParams

        val search = ImageButton(activity).apply {
            layoutParams = ViewGroup.LayoutParams(64f.px, 64f.px)
            background = back.background?.constantState?.newDrawable()?.mutate()
            setPadding(16f.px, 16f.px, 16f.px, 16f.px)
            setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_round_search_24))
            imageTintList = android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(back, com.google.android.material.R.attr.colorOnBackground)
            )
            contentDescription = activity.getString(R.string.search_settings)
            setOnClickListener {
                activity.startActivity(
                    Intent(activity, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_SEARCH_ONLY, true)
                )
            }
        }
        val searchCard = CardView(activity).apply {
            setCardBackgroundColor(backCard.cardBackgroundColor)
            radius = backCard.radius
            cardElevation = 0f
            addView(search)
        }

        val index = column.indexOfChild(backCard)
        column.removeViewAt(index)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(backCard, LinearLayout.LayoutParams(cardParams.width, cardParams.height))
            addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(searchCard, LinearLayout.LayoutParams(cardParams.width, cardParams.height))
        }
        // The row takes the card's place and margins, mirrored at the far end so the search card
        // sits as far in from its edge as the back card does from its own.
        column.addView(row, index, LinearLayout.LayoutParams(cardParams).apply {
            width = LinearLayout.LayoutParams.MATCH_PARENT
            marginEnd = cardParams.marginStart
        })
    }
}
