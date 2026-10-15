package ani.dantotsu.media

/**
 * What the stack cards ([StackAdapter], [StackListAdapter]) and [StackListViewActivity] draw: a MAL
 * interest stack ([ani.dantotsu.connections.mal.MALStack]) or a MangaBaka stack
 * ([ani.dantotsu.connections.mangabaka.MangaBakaStacks.StackSummary]). [StackResolver.open] picks
 * the screen to open from which one it is.
 */
interface InterestStack {
    /** The stack's web page; long-pressing a card opens it. */
    val url: String
    val name: String
    /** Cover URLs in stack order; the cards fan out the first three. */
    val covers: List<String>
    val entries: Int
    /** HTML for MAL, Markdown for MangaBaka; null when the row doesn't carry it. */
    val description: String?
    /** Set for a MangaBaka stack, whose entries are fetched by id rather than scraped from [url]. */
    val mangaBakaId: Long? get() = null
}
