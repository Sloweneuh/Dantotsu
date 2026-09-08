package ani.dantotsu.media.novel.translation

import android.content.Context
import ani.dantotsu.media.manga.translation.MlKitTranslator
import ani.dantotsu.media.manga.translation.SourceScript
import ani.dantotsu.media.manga.translation.TextKind
import ani.dantotsu.media.manga.translation.TranslationEngine
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.util.Locale

/**
 * Machine-translates a novel chapter before the reader is given it.
 *
 * The reader is a WebView showing an EPUB this app packages, so the honest place to translate is
 * the chapter's HTML on its way into that package: the words are replaced where they sit, and
 * everything downstream — pagination, the theme, the sentence markers speech is highlighted by —
 * goes on working without knowing translation happened at all. Reaching into the rendered page
 * instead would mean translating text the reader library owns, which cannot be done without
 * driving its internals.
 *
 * The markup is left exactly as it was. Only text nodes are replaced, so italics, links, ruby and
 * the chapter's own structure survive, and a paragraph translated into twice as many words is still
 * one paragraph.
 *
 * Shares the manga feature's engine, key and target language, which is the whole reason this is
 * cheap to offer: the interesting part — four engines, a live model list, a key that is checked
 * before it is stored — already exists. All that differs is the prompt, and [TextKind.PROSE] is
 * what carries that.
 */
object NovelTranslation {

    /**
     * Characters sent in one request.
     *
     * Small enough that a chapter is several requests rather than one enormous one — a model asked
     * for twenty thousand characters in a single reply routinely stops halfway and returns a
     * truncated JSON object, which fails the whole chapter rather than one paragraph of it — and
     * large enough that consecutive paragraphs travel together and can be translated as continuous
     * prose.
     */
    private const val BATCH_CHARS = 2500

    /** Runs of text in one request, whatever their length. Long lists lose entries. */
    private const val BATCH_ITEMS = 40

    /**
     * Ceiling on a single chapter, in characters.
     *
     * A novel source occasionally serves a whole volume as one "chapter". Translating that would be
     * dozens of requests and most of a free tier's daily quota for something the reader has not
     * decided to read yet; past this the chapter is shown as it came.
     */
    private const val MAX_CHARS = 200_000

    /** Whether chapters should be translated as they are opened. */
    fun enabled(): Boolean = PrefManager.getVal(PrefName.NovelTranslateEnabled)

    /** Whether an engine that needs a key has one. */
    fun ready(): Boolean {
        val engine = TranslationEngine.fromPref()
        return !engine.needsKey || engine.storedKey().isNotBlank()
    }

    /** The language chapters are translated into — the same choice the manga pipeline uses. */
    fun target(): String = SourceScript.targetLanguage()

    /**
     * Whether translating this chapter is worth doing at all.
     *
     * A source serving English to somebody reading in English should not be run through a
     * translator to arrive at what it already was.
     */
    fun worthDoing(sourceLanguage: String?): Boolean {
        if (!enabled() || !ready()) return false
        return !SourceScript.pointless(sourceLanguage, target())
    }

    /**
     * @param chapterId what identifies this chapter across sessions, for [NovelTranslationCache].
     * @param onWorkStarted called only when there is really something to wait for — a cache hit
     *   returns at once, and announcing a translation that is not happening is worse than saying
     *   nothing.
     * @return the chapter with its text replaced, or [bodyHtml] unchanged where nothing was
     *   translated. Never throws: a chapter that could not be translated is still a chapter, and
     *   refusing to open it would be a worse outcome than opening it in its own language.
     */
    suspend fun translate(
        context: Context,
        chapterId: String,
        bodyHtml: String,
        sourceLanguage: String?,
        onWorkStarted: suspend () -> Unit = {},
    ): String {
        if (!worthDoing(sourceLanguage)) return bodyHtml
        val key = cacheKey(chapterId, sourceLanguage)
        NovelTranslationCache.get(context, key)?.let { return it }
        onWorkStarted()
        return runCatching { replace(bodyHtml, sourceLanguage) }
            .onSuccess { if (it !== bodyHtml) NovelTranslationCache.put(context, key, it) }
            .getOrElse {
                Logger.log("Novel MTL failed: ${it.stackTraceToString()}")
                bodyHtml
            }
    }

    /**
     * What a stored translation is filed under.
     *
     * The settings are part of the identity, not just the chapter. A translation is the answer a
     * particular engine and model gave for a particular pair of languages, and serving one of those
     * answers after the reader has changed any of them would quietly ignore the change — the one
     * moment they are most likely to re-open a chapter is straight after switching engines to see
     * whether the new one reads better. A changed setting simply misses, and the old entry ages out.
     */
    private fun cacheKey(chapterId: String, sourceLanguage: String?): String {
        val engine = TranslationEngine.fromPref()
        val model = if (engine.needsKey) {
            PrefManager.getVal<String>(PrefName.OcrTranslationModel).ifBlank { engine.defaultModel() }
        } else {
            ""
        }
        return "$chapterId|${engine.name}|$model|${fromCode(sourceLanguage)}|${target()}"
    }

    private suspend fun replace(bodyHtml: String, sourceLanguage: String?): String {
        val document = Jsoup.parseBodyFragment(bodyHtml)
        val runs = ArrayList<TextNode>()
        document.body().traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is TextNode && node.text().isNotBlank()) runs += node
            }

            override fun tail(node: Node, depth: Int) = Unit
        })
        if (runs.isEmpty()) return bodyHtml

        val total = runs.sumOf { it.wholeText.length }
        if (total > MAX_CHARS) {
            Logger.log("Novel MTL: chapter is $total characters, past the $MAX_CHARS ceiling")
            return bodyHtml
        }

        val to = target()
        val from = fromCode(sourceLanguage)
        val engine = TranslationEngine.fromPref()
        engine.build(from, to, label(to), TextKind.PROSE).use { translator ->
            translator.prepare()
            batches(runs).forEach { batch ->
                // Each run keeps the whitespace it arrived with. A text node is a fragment of a
                // laid-out paragraph — the space before an italic phrase belongs to the node before
                // it — and a translator trimming that space silently welds two words together.
                val translated = translator.translate(batch.map { it.wholeText.trim() })
                batch.forEachIndexed { index, node ->
                    val raw = node.wholeText
                    val words = translated.getOrNull(index)?.trim().orEmpty()
                    if (words.isBlank()) return@forEachIndexed
                    node.text(
                        raw.takeWhile { it.isWhitespace() } +
                            words +
                            raw.takeLastWhile { it.isWhitespace() },
                    )
                }
            }
        }
        return document.body().html()
    }

    /** Consecutive runs grouped into requests, in reading order so a batch reads as prose. */
    private fun batches(runs: List<TextNode>): List<List<TextNode>> {
        val out = ArrayList<List<TextNode>>()
        var current = ArrayList<TextNode>()
        var chars = 0
        runs.forEach { node ->
            val length = node.wholeText.length
            if (current.isNotEmpty() &&
                (chars + length > BATCH_CHARS || current.size >= BATCH_ITEMS)
            ) {
                out += current
                current = ArrayList()
                chars = 0
            }
            current += node
            chars += length
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /**
     * What the chapter is being translated from.
     *
     * ML Kit and the Google endpoint both need a source language named, and the plugin's own is the
     * only thing that knows it. Where it says nothing usable the engines are handed English, which
     * is what the catalogue's catch-all languages mostly turn out to be — and is at worst a poorer
     * translation rather than a failure, since the language models ignore this and detect for
     * themselves.
     */
    private fun fromCode(sourceLanguage: String?): String {
        val code = sourceLanguage?.lowercase(Locale.ROOT)?.trim()
            ?.substringBefore('-')?.substringBefore('_')
        if (!code.isNullOrBlank() && MlKitTranslator.supportedTargets().any { it.first == code }) {
            return code
        }
        return "en"
    }

    private fun label(code: String): String =
        Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH)
}
