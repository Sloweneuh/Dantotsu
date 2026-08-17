package ani.dantotsu.parsers.novel.lnreader

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * Jsoup behind a handle table, so the JS side can hold references to parsed nodes without any
 * DOM implementation existing inside the engine.
 *
 * Handles are ints and node sets are passed as comma-joined strings rather than JSON: a page
 * parse runs to hundreds of calls across this boundary (`.text` alone averages ~4 per plugin, over
 * whole result lists), and parsing a JSON array per hop was the obvious thing to not do.
 *
 * One instance belongs to one JS context and one thread; nothing here is synchronised.
 *
 * The table holds [Node], not [Element]. Text nodes have to be addressable because a large family
 * of plugins walks `.contents()` looking for `nodeType === 3` to rewrite the text in place; with an
 * element-only table those plugins have nothing to find. Selector-based calls still work on
 * elements only, which is what they mean in cheerio too.
 */
class LNReaderDom {

    private val nodes = HashMap<Int, Node>()

    /**
     * Reverse index, so one node always gets the same handle.
     *
     * Without it the same node reached twice — `find(x).addBack()`, or two contexts sharing a
     * descendant — arrives as two different handles, and a mutation runs on it twice. The second
     * run then operates on a node already detached from the tree.
     */
    private val handles = java.util.IdentityHashMap<Node, Int>()
    private var counter = 0

    private fun put(node: Node): Int = handles.getOrPut(node) {
        val id = ++counter
        nodes[id] = node
        id
    }

    private fun idsOf(handles: String): List<Int> =
        if (handles.isEmpty()) emptyList()
        else handles.split(',').mapNotNull { it.toIntOrNull() }

    /** Everything in the set, whatever kind of node it is. */
    private fun nodesOf(handles: String): List<Node> =
        idsOf(handles).mapNotNull { nodes[it] }

    /** Only the elements, for the operations that are meaningless on a text node. */
    private fun elementsOf(handles: String): List<Element> =
        nodesOf(handles).filterIsInstance<Element>()

    /** Handles are canonical, so a plain distinct is enough to drop repeats of one node. */
    private fun encode(items: List<Node>): String =
        items.map { put(it) }.distinct().joinToString(",")

    /** DOM node types, as the JS side sees them. */
    fun nodeType(handles: String): Int = when (nodesOf(handles).firstOrNull()) {
        is Element -> 1
        is TextNode, is DataNode -> 3
        is org.jsoup.nodes.Comment -> 8
        else -> 0
    }

    /** cheerio's `.contents()`: every child node, text included. */
    fun contents(handles: String): String =
        encode(nodesOf(handles).flatMap { it.childNodes() })

    /**
     * Sorts a set into document order, which jQuery guarantees for anything that combines two
     * selections. Appending one to the other instead puts an ancestor after its own descendants,
     * and a plugin walking the result then visits the tree out of order.
     */
    fun documentOrder(handles: String): String {
        val ordered = nodesOf(handles)
            .map { it to pathOf(it) }
            .sortedWith { a, b -> comparePaths(a.second, b.second) }
            .map { it.first }
        return encode(ordered)
    }

    /** Sibling indices from the root down, which orders any two nodes lexicographically. */
    private fun pathOf(node: Node): IntArray {
        val out = ArrayList<Int>()
        var current: Node? = node
        while (current?.parentNode() != null) {
            out.add(current.siblingIndex())
            current = current.parentNode()
        }
        return out.reversed().toIntArray()
    }

    private fun comparePaths(a: IntArray, b: IntArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        // A prefix means one is an ancestor of the other, and an ancestor comes first.
        return a.size.compareTo(b.size)
    }

    /** Whole-document parse; the root handle every other call descends from. */
    fun parse(html: String): Int = put(Jsoup.parse(html))

    /** Parses a fragment in the context of an existing node, for `$(html)` style calls. */
    fun parseFragment(html: String): Int = put(Jsoup.parseBodyFragment(html).body())

    /**
     * cheerio's `.find()`: descendants only.
     *
     * Jsoup's `select` includes the context element itself when it matches, which jQuery's `find`
     * never does. Left in, `find('*')` returns the root as well as everything under it — and the
     * plugins that then call `.addBack()` end up processing the root twice.
     */
    fun select(handles: String, selector: String): String = try {
        val contexts = elementsOf(handles)
        encode(contexts.flatMap { context ->
            context.select(selector).filterNot { match -> contexts.any { it === match } }
        })
    } catch (_: Exception) {
        // An invalid selector is the plugin's problem, not a crash: cheerio returns empty here.
        ""
    }

    /**
     * cheerio's `.text()`: every descendant text node concatenated, across the whole set.
     *
     * `wholeText` rather than Jsoup's `text`, which normalises runs of whitespace and trims. That
     * is usually the friendlier result, but it is not what plugins are written against — the idiom
     * `$('p').before('\n')` then `.text().split('\n')` to rebuild paragraphs collapses to a single
     * line under normalisation. Callers that want a tidy value trim it themselves, as they do
     * against cheerio.
     */
    fun text(handles: String): String = nodesOf(handles).joinToString("") { node ->
        when (node) {
            is Element -> node.wholeText()
            is TextNode -> node.wholeText
            is DataNode -> node.wholeData
            else -> ""
        }
    }

    fun ownText(handles: String): String =
        elementsOf(handles).joinToString("") { it.ownText() }

    /** Reads from the first node only, matching cheerio. Absent attribute is null, not "". */
    fun attr(handles: String, name: String): String? =
        elementsOf(handles).firstOrNull()
            ?.takeIf { it.hasAttr(name) }
            ?.attr(name)

    fun html(handles: String): String =
        elementsOf(handles).firstOrNull()?.html() ?: ""

    fun outerHtml(handles: String): String =
        elementsOf(handles).joinToString("") { it.outerHtml() }

    // Structural edits apply to text nodes as much as elements: rewriting a stretch of text in
    // place is exactly what the plugins that walk `.contents()` are doing.
    fun remove(handles: String) {
        nodesOf(handles).forEach { it.remove() }
    }

    fun next(handles: String): String =
        encode(elementsOf(handles).mapNotNull { it.nextElementSibling() })

    fun prev(handles: String): String =
        encode(elementsOf(handles).mapNotNull { it.previousElementSibling() })

    fun parent(handles: String): String =
        encode(nodesOf(handles).mapNotNull { it.parent() })

    fun children(handles: String): String =
        encode(elementsOf(handles).flatMap { it.children() })

    fun siblings(handles: String): String =
        encode(elementsOf(handles).flatMap { el -> el.siblingElements() })

    fun closest(handles: String, selector: String): String =
        encode(elementsOf(handles).mapNotNull { it.closest(selector) })

    fun matches(handles: String, selector: String): Boolean =
        elementsOf(handles).any { runCatching { it.`is`(selector) }.getOrDefault(false) }

    fun hasClass(handles: String, name: String): Boolean =
        elementsOf(handles).any { it.hasClass(name) }

    fun addClass(handles: String, name: String) {
        elementsOf(handles).forEach { it.addClass(name) }
    }

    fun removeClass(handles: String, name: String) {
        elementsOf(handles).forEach { it.removeClass(name) }
    }

    fun append(handles: String, html: String) {
        elementsOf(handles).forEach { it.append(html) }
    }

    fun prepend(handles: String, html: String) {
        elementsOf(handles).forEach { it.prepend(html) }
    }

    fun removeAttr(handles: String, name: String) {
        elementsOf(handles).forEach { it.removeAttr(name) }
    }

    fun before(handles: String, html: String) {
        nodesOf(handles).forEach { it.before(html) }
    }

    fun after(handles: String, html: String) {
        nodesOf(handles).forEach { it.after(html) }
    }

    /**
     * Replaces each node with the given content.
     *
     * Insert-then-remove rather than swapping in a parsed element, because the content is often
     * plain text — `replaceWith("\n")` to turn `<br>` into a line break is a common idiom, and it
     * produces no element to swap in at all.
     */
    fun replaceWith(handles: String, html: String) {
        nodesOf(handles).forEach { node ->
            node.before(html)
            node.remove()
        }
    }

    fun empty(handles: String) {
        elementsOf(handles).forEach { it.empty() }
    }

    /** `data-*` lookup; cheerio's `.data(k)` reads the `data-k` attribute. */
    fun data(handles: String, key: String): String? =
        attr(handles, "data-$key")

    /** Position among its siblings, as cheerio's `.index()` reports it. */
    fun index(handles: String): Int =
        elementsOf(handles).firstOrNull()?.elementSiblingIndex() ?: -1

    fun tagName(handles: String): String =
        elementsOf(handles).firstOrNull()?.tagName() ?: ""

    /**
     * Flattens a document into the SAX event stream `htmlparser2`'s streaming `Parser` emits.
     *
     * Several plugins pull one value out of a page with a `Parser` rather than a selector, so the
     * shim needs open/text/close events in document order. Rather than tokenise HTML in JS, this
     * reuses the parser already here: Jsoup builds the tree, and a traversal replays it as events.
     *
     * Encoded as `{t,n,a,v}` — type, tag name, attributes, text value — because the JS side
     * replays it once and discards it.
     */
    fun parseEvents(html: String): String {
        val events = JSONArray()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is Element -> {
                        val attrs = JSONObject()
                        node.attributes().forEach { attrs.put(it.key, it.value) }
                        events.put(
                            JSONObject().put("t", "o").put("n", node.tagName()).put("a", attrs)
                        )
                    }
                    // Script/style bodies arrive as DataNode, and htmlparser2 reports those as
                    // text too — dropping them would silently empty out inline-JSON sources.
                    is TextNode -> if (!node.isBlank) {
                        events.put(JSONObject().put("t", "x").put("v", node.wholeText))
                    }
                    is DataNode -> events.put(JSONObject().put("t", "x").put("v", node.wholeData))
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element) {
                    events.put(JSONObject().put("t", "c").put("n", node.tagName()))
                }
            }
        }, Jsoup.parse(html))
        return events.toString()
    }
}
