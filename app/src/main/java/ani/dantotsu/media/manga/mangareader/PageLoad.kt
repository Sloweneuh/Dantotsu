package ani.dantotsu.media.manga.mangareader

import android.view.View

/**
 * Which page an item view is loading, and whether that page is already on screen.
 *
 * Held as the item view's tag, so a load that has been running across a suspension point can
 * tell whether its result is still wanted. Adapter positions are useless for that: in the
 * multi-chapter reader every position shifts as soon as something is inserted above it (a
 * prepended chapter, a "no previous chapter" boundary), so a position check drops perfectly
 * good loads and leaves the page spinning forever.
 */
class PageLoad(val page: Any) {
    /** Set once the bitmap is on screen; until then the view still shows its placeholder. */
    var loaded = false
}

/** Starts a load of [page] into this item view, superseding any load still in flight on it. */
fun View.beginPageLoad(page: Any): PageLoad = PageLoad(page).also { tag = it }

/** True while this load still owns [view], i.e. no newer load has taken it over. */
fun PageLoad.stillOwns(view: View): Boolean = view.tag === this

/** True when [page]'s image is already displayed by this item view. */
fun View.isShowingPage(page: Any): Boolean =
    (tag as? PageLoad)?.let { it.loaded && it.page === page } == true
