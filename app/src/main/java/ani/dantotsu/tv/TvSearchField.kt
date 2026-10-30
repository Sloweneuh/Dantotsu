package ani.dantotsu.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * TV mode's search box: large, outlined in the accent colour while focused, typed into with the TV's
 * keyboard. [onSubmit] runs on the keyboard's search key, after the keyboard is put away.
 */
@Composable
fun TvSearchField(
    query: String,
    onQuery: (String) -> Unit,
    hint: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onBackground),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            onSubmit()
        }),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { input ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
                    .border(
                        FOCUS_OUTLINE,
                        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(28.dp),
                    )
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            ) {
                if (query.isEmpty()) Text(
                    hint,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                input()
            }
        },
    )
}
