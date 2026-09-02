package ani.dantotsu.themes

import android.app.Activity
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.LayoutInflaterCompat
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import java.io.File

/**
 * The typeface the app is drawn in.
 *
 * Poppins is baked into roughly fifty `android:fontFamily` declarations across the styles, so rather
 * than route all of them through a handful of text appearances this swaps the typeface as views are
 * inflated — see [install]. Nothing in the layouts changes, and choosing the default costs nothing
 * because the swap is skipped entirely.
 *
 * Weight is the part worth care. The styles lean on four Poppins faces while most fonts on a device
 * ship one, so a chosen font is asked for a weight through a cascade (a real variable axis, then a
 * real face, then synthesis) and, where it can only manage one step, the app loses a weight level
 * rather than showing two that look identical. See [atWeight].
 */
object AppFont {

    /** Stored in [PrefName.AppFont]. */
    const val SYSTEM = "system"
    const val DEFAULT = "default"
    private const val RES_PREFIX = "res:"
    private const val FILE_PREFIX = "file:"

    /** The bundled faces, beyond Poppins, that the subtitle picker already ships. */
    val bundled = listOf(
        "century_gothic_regular" to R.font.century_gothic_regular,
        "levenim_mt_bold" to R.font.levenim_mt_bold,
        "blocky" to R.font.blocky,
    )

    fun current(): String = PrefManager.getVal(PrefName.AppFont)

    fun set(value: String) = PrefManager.setVal(PrefName.AppFont, value)

    /** A tag a view can carry to keep the font its layout gave it. */
    const val KEEP_TAG = "keepFont"

    // -----------------------------------------------------------------------------------------
    // Resolution
    // -----------------------------------------------------------------------------------------

    private var cachedKey: String? = null
    private var cachedBase: Typeface? = null

    /**
     * The chosen font's base face, or null to leave views as the layout drew them.
     *
     * Null for both [DEFAULT] (Poppins is already what the styles say) and for anything that fails
     * to load, so a font file that has gone missing degrades to the bundled look rather than to
     * nothing.
     */
    private fun base(activity: Activity): Typeface? {
        val key = current()
        if (key == DEFAULT) return null
        if (key == cachedKey) return cachedBase

        val tf = resolve(activity, key)
        cachedKey = key
        cachedBase = tf
        return tf
    }

    /** The face a key names, uncached and with no opinion about what null should fall back to. */
    private fun resolve(context: Context, key: String): Typeface? = try {
        when {
            key == SYSTEM -> Typeface.DEFAULT
            key.startsWith(RES_PREFIX) -> {
                val name = key.removePrefix(RES_PREFIX)
                bundled.firstOrNull { it.first == name }
                    ?.let { ResourcesCompat.getFont(context, it.second) }
            }

            key.startsWith(FILE_PREFIX) -> {
                val f = File(key.removePrefix(FILE_PREFIX))
                if (f.isFile) Typeface.createFromFile(f) else null
            }

            else -> null
        }
    } catch (e: Exception) {
        Logger.log("AppFont: could not load $key - ${e.message}")
        null
    }

    /**
     * The face [key] names at [weight], for a picker that draws each option in its own font.
     *
     * Not [base]: that answers for the current choice and returns null for [DEFAULT] because the
     * styles already say Poppins, which in a list would leave the default row as the only one not
     * showing what it would look like. Here it resolves to Poppins outright. Null still means the
     * font could not be loaded, and the caller decides what to draw the row in instead.
     */
    fun preview(context: Context, key: String, weight: Int = 600): Typeface? {
        val tf = if (key == DEFAULT) {
            runCatching { ResourcesCompat.getFont(context, R.font.poppins_semi_bold) }.getOrNull()
        } else {
            resolve(context, key)
        }
        return tf?.let { atWeight(it, weight) }
    }

    /** Loads the current choice, for a picker that wants to check it before committing. */
    fun probe(activity: Activity): Typeface? = base(activity)

    /** Drops the cached face, so the next inflation picks up a change. */
    fun invalidate() {
        cachedKey = null
        cachedBase = null
    }

    /**
     * [base] at [weight], through the cascade.
     *
     * A variable font is asked for the weight exactly; a family with real faces has one picked; a
     * single-face font is emboldened once. In that last case anything at 600 or above collapses onto
     * the same synthetic bold rather than trying to fake two distinct heavy weights, which at the
     * 12-14sp most of this app's bold labels sit at smears the glyphs instead of separating them.
     */
    private fun atWeight(tf: Typeface, weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(tf, weight.coerceIn(100, 900), false)
        } else {
            Typeface.create(tf, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }

    /** What weight this view was asking for, read from the face its layout gave it. */
    private fun weightOf(view: TextView): Int {
        val tf = view.typeface ?: return 400
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tf.weight
        else if (tf.isBold) 700 else 400
    }

    // -----------------------------------------------------------------------------------------
    // Applying
    // -----------------------------------------------------------------------------------------

    /**
     * Swaps the typeface of every [TextView] this activity inflates.
     *
     * Called from [ThemeManager.applyTheme], which every activity runs before `setContentView` —
     * the only point early enough to catch the whole tree without editing all sixty-eight of them.
     */
    fun install(activity: Activity) {
        if (current() == DEFAULT) return
        val appCompat = activity as? AppCompatActivity ?: return
        val inflater = LayoutInflater.from(activity)
        if (installed.containsKey(inflater)) return

        // An inflater accepts one factory and throws on the second, and AppCompat claims it during
        // super.onCreate. App installs ahead of that for every activity, so this only trips where
        // the hook could not run at all (before API 29); losing the chosen face there beats taking
        // the screen down with it.
        if (inflater.factory != null || inflater.factory2 != null) {
            Logger.log("AppFont: ${activity.javaClass.simpleName} already has an inflater factory")
            return
        }
        installed[inflater] = true

        LayoutInflaterCompat.setFactory2(inflater, object : LayoutInflater.Factory2 {
            override fun onCreateView(
                parent: View?, name: String, context: android.content.Context, attrs: AttributeSet
            ): View? {
                val view = appCompat.delegate.createView(parent, name, context, attrs)
                if (view is TextView) apply(activity, view)
                return view
            }

            override fun onCreateView(
                name: String, context: android.content.Context, attrs: AttributeSet
            ): View? = onCreateView(null, name, context, attrs)
        })
    }

    /** setFactory2 throws if called twice on one inflater, and applyTheme can run more than once. */
    private val installed = java.util.WeakHashMap<LayoutInflater, Boolean>()

    private fun apply(activity: Activity, view: TextView) {
        if (view.tag == KEEP_TAG) return
        val tf = base(activity) ?: return
        view.typeface = atWeight(tf, weightOf(view))
    }

    // -----------------------------------------------------------------------------------------
    // Guards
    // -----------------------------------------------------------------------------------------

    /**
     * Whether [tf] can draw ordinary text.
     *
     * A picker that lists every font on the device will list icon and emoji fonts too, and choosing
     * one renders the whole app — including the screen needed to undo it — as empty boxes. Checked
     * before a choice is accepted rather than after.
     */
    fun hasLatinCoverage(tf: Typeface): Boolean {
        val paint = Paint().apply { typeface = tf }
        return "AaGg09".all { paint.hasGlyph(it.toString()) }
    }
}
