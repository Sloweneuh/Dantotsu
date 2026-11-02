package ani.dantotsu.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * TV mode's search box: large, outlined in the accent colour while focused, typed into with the TV's
 * keyboard. [onSubmit] runs on the keyboard's search key, after the keyboard is put away. Up and
 * down leave it, as they would any row: a one-line field took them for the cursor, to its start or
 * end, and a remote had no way out of it downwards. Left and right do too from either end of
 * the text (to a button beside it); within it they move the cursor.
 *
 * [compact]: the size of the button it stands in for (the extension list's search), its icon and
 * all, rather than the search screen's large box, which came up far bigger than the button had been.
 */
@Composable
fun TvSearchField(
    query: String,
    onQuery: (String) -> Unit,
    hint: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.headlineSmall
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // The cursor's place, to know when left or right has nowhere left to go in the text.
    var value by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(query, androidx.compose.ui.text.TextRange(query.length))) }
    if (value.text != query) value = value.copy(text = query, selection = androidx.compose.ui.text.TextRange(query.length))
    BasicTextField(
        value = value,
        onValueChange = {
            value = it
            if (it.text != query) onQuery(it.text)
        },
        singleLine = true,
        textStyle = style.copy(color = MaterialTheme.colorScheme.onBackground),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            onSubmit()
        }),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val direction = when (event.key) {
                    androidx.compose.ui.input.key.Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
                    androidx.compose.ui.input.key.Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
                    androidx.compose.ui.input.key.Key.DirectionRight ->
                        if (value.selection.collapsed && value.selection.end == value.text.length) androidx.compose.ui.focus.FocusDirection.Right
                        else return@onPreviewKeyEvent false
                    androidx.compose.ui.input.key.Key.DirectionLeft ->
                        if (value.selection.collapsed && value.selection.start == 0) androidx.compose.ui.focus.FocusDirection.Left
                        else return@onPreviewKeyEvent false
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) focusManager.moveFocus(direction)
                true
            },
        decorationBox = { input ->
            val shape = RoundedCornerShape(if (compact) 50 else 28)
            androidx.compose.foundation.layout.Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape)
                    .border(
                        if (compact) 2.dp else FOCUS_OUTLINE,
                        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape,
                    )
                    // The button's own padding, so the row keeps its height as one becomes the other.
                    .padding(horizontal = if (compact) 16.dp else 24.dp, vertical = if (compact) 10.dp else 14.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if (compact) androidx.tv.material3.Icon(
                    androidx.compose.ui.res.painterResource(ani.dantotsu.R.drawable.ic_round_search_24),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 10.dp).size(20.dp),
                )
                Box {
                    if (query.isEmpty()) Text(hint, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    input()
                }
            }
        },
    )
}
