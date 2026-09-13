package ani.dantotsu.media

import androidx.core.view.isVisible
import ani.dantotsu.databinding.ItemQuelsBinding

/**
 * The row's weight total when only one of its two cards is visible. Below the layout's own
 * weightSum of 2, so the lone card comes out two thirds of the row instead of half — at half it
 * reads as a card missing its pair rather than a button, next to the full-width sections around it.
 */
private const val LONE_CARD_WEIGHT_SUM = 1.5f

/**
 * Sizes an [ItemQuelsBinding] row for however many of its cards ended up visible: a pair splits the
 * row between them, a lone card is widened and centred. Call it once both cards' visibility is set,
 * before the row is added to its parent.
 */
fun ItemQuelsBinding.balanceQuelRow() {
    val pair = quelStartCard.isVisible && quelEndCard.isVisible
    root.weightSum = if (pair) 2f else LONE_CARD_WEIGHT_SUM
}
