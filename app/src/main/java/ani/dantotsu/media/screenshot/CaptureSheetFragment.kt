package ani.dantotsu.media.screenshot

import android.view.View
import android.view.ViewGroup
import ani.dantotsu.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * Base for the two capture composers: a bottom sheet that always occupies the whole screen.
 *
 * Left to itself a sheet sizes to its content and then to whatever its behaviour will allow, which
 * for these two is never enough — the card alone can fill a landscape screen, and the options below
 * it end up past the bottom with no room left to scroll into. Rebalancing the layout only moved the
 * problem around, because the sheet's height was never the full screen to begin with.
 *
 * Taking the whole screen removes the negotiation entirely: the layout then divides a known height
 * between the preview and the options, and both are always reachable. It is also far less upheaval
 * than promoting these to activities, which would mean marshalling the capture payload across an
 * activity boundary and rebuilding the dismissal callbacks for no visual gain.
 */
open class CaptureSheetFragment : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return

        sheet.layoutParams = sheet.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        BottomSheetBehavior.from(sheet).apply {
            // isFitToContents would cap "expanded" at the content's own height, which is the
            // behaviour being overridden here; with it off, expandedOffset decides the top edge.
            isFitToContents = false
            expandedOffset = 0
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

}
