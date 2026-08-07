package ani.dantotsu.util

import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.EditText
import androidx.annotation.StringRes

/**
 * Drives a code field split across several boxes: the boxes hold the characters, the layout holds
 * whatever is drawn between them.
 *
 * For codes of a fixed shape — the sync code's [ani.dantotsu.connections.sync.SyncCrypto.CODE_CHARS]
 * in groups of four, the handoff sharing code's six singles — the shape belongs to the format rather
 * than to the person typing it. A single free-text box makes the user reproduce it, and makes any
 * other way of writing it look wrong.
 *
 * Every edit is handled the same way, which is what keeps the odd cases from needing rules of their
 * own: the boxes are read back as one code with the edit applied, then redealt into the boxes.
 * Typing appends, backspace shortens, an edit in the middle of a full group pushes the rest along
 * instead of overwriting it, and a pasted code — separators or not, into whichever box happened to
 * have focus — simply fills the row. The caret rides along as a count of code characters, since that
 * is the one position meaning the same thing before and after a redeal.
 *
 * @param groups the boxes, in order; the code's length is theirs times [groupChars].
 * @param alphabet the characters a code is made of. Anything else the user types or pastes —
 *   separators, spaces, punctuation — is dropped, so every way of writing the code is accepted.
 * @param groupChars how many characters each box holds. One box per character (the default) needs
 *   no separators at all.
 * @param label formatted with the 1-based box number for each box's content description. The
 *   separators are drawn rather than typed, so without one a screen reader announces a row of
 *   unlabelled fields.
 */
class CodeEntry(
    private val groups: List<EditText>,
    private val alphabet: String,
    private val groupChars: Int = 1,
    @StringRes label: Int? = null,
) {

    private val total = groups.size * groupChars

    /** Guards the redeal below, whose own [EditText.setText] calls land back here. */
    private var dealing = false

    init {
        groups.forEachIndexed { index, box ->
            // No length filter, deliberately: it would cut a pasted code down to one box before
            // [redeal] ever saw the rest of it. Overflow is what drives the distribution.
            box.filters = arrayOf(InputFilter.AllCaps())
            label?.let { box.contentDescription = box.context.getString(it, index + 1) }
            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable) {
                    if (dealing) return
                    val before = groups.take(index).joinToString("") { it.text.toString() }
                    val after = groups.drop(index + 1).joinToString("") { it.text.toString() }
                    val caret = before.length + codeCharsBefore(s, box.selectionStart)
                    redeal(clean(before + s.toString() + after), caret)
                }
            })
            box.setOnKeyListener { _, keyCode, event ->
                // Backspace at the start of an empty box: without this it does nothing at all and
                // the field appears stuck, since there is no text here for the watcher to react to.
                val atStart = index > 0 && box.text.isEmpty()
                if (keyCode == KeyEvent.KEYCODE_DEL &&
                    event.action == KeyEvent.ACTION_DOWN && atStart
                ) {
                    val previous = groups[index - 1]
                    if (previous.text.isNotEmpty()) {
                        previous.text.delete(previous.text.length - 1, previous.text.length)
                    }
                    previous.requestFocus()
                    previous.setSelection(previous.text.length)
                    true
                } else false
            }
        }
    }

    /** The code as entered, unseparated — the form the validators expect. */
    fun code(): String = groups.joinToString("") { it.text.toString() }

    /** Everything that can't be part of a code dropped, and the rest capped at its length. */
    private fun clean(input: String): String =
        input.uppercase().filter { it in alphabet }.take(total)

    /** How many code characters — anything else excluded — precede [offset] in [text]. */
    private fun codeCharsBefore(text: CharSequence, offset: Int): Int =
        text.take(offset.coerceIn(0, text.length)).count { it.uppercaseChar() in alphabet }

    private fun redeal(code: String, caret: Int) {
        dealing = true
        groups.forEachIndexed { index, box ->
            val part = code.drop(index * groupChars).take(groupChars)
            if (box.text.toString() != part) box.setText(part)
        }
        dealing = false
        // A caret at the end of a full group belongs at the start of the next one — that is what
        // makes the field advance on its own, rather than needing a rule of its own to do it.
        val landed = caret.coerceIn(0, code.length)
        val target = (landed / groupChars).coerceAtMost(groups.lastIndex)
        val box = groups[target]
        if (!box.hasFocus()) box.requestFocus()
        box.setSelection((landed - target * groupChars).coerceIn(0, box.text.length))
    }
}
