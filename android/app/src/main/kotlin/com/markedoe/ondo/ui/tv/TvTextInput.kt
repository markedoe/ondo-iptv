package com.markedoe.ondo.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.markedoe.ondo.R
import com.markedoe.ondo.ui.theme.OndoColors

/** The hint a focused [TvTextInput] shows until OK opens its keyboard ("Press OK to type"). */
const val TV_FIELD_HINT_TAG = "tvFieldHint"

/** The button beside a secret [TvTextInput] that shows or hides what is typed. */
const val TV_FIELD_REVEAL_TAG = "tvFieldReveal"

/**
 * A single-line text field of the TV pages, typed with the remote and the TV's keyboard (Gboard for TV), the way the
 * TV's own fields work: a row with its [label] and its value (or its [placeholder]); with the remote on it, a white
 * ring, a pencil and "Press OK to type". The keyboard never opens as the remote passes over it ([rememberTvTextField]):
 * OK opens it, the caret blinking at the end of the text. The keyboard's action key ([imeAction]) runs [onImeAction],
 * then Next goes on to the field below with the keyboard still open, and Done (or Search) closes it and goes on below
 * ([moveOn]; or stays on the field). Back closes the keyboard and leaves the remote on the field.
 *
 * A [secret] field (a key, a password, a PIN) shows dots, and a button beside it, Right from the field, shows what is
 * typed. [keyboardType] chooses the keyboard's layout: digits for a PIN ([KeyboardType.NumberPassword]); Gboard for TV
 * has no address layout and shows its letters for [KeyboardType.Uri].
 *
 * [modifier] goes to the field itself (its focus, its tag); with [secret], the field and its button share a row as
 * wide as their parent.
 */
@Composable
fun TvTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    moveOn: Boolean = true,
    openOnFirstFocus: Boolean = false,
) {
    // The text with its caret: at the end once OK opens the keyboard, whatever was there before (a name to change).
    var typed by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val shown = if (typed.text == value) typed else TextFieldValue(value, TextRange(value.length))
    val latest by rememberUpdatedState(shown)
    val field = rememberTvTextField(openOnFirstFocus) { typed = latest.copy(selection = TextRange(latest.text.length)) }
    var focused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val handoff = LocalKeyboardHandoff.current
    val television = isTelevision()
    val act: () -> Unit = {
        onImeAction()
        if (imeAction == ImeAction.Next) {
            // On to the field below, its keyboard still open (a form typed field after field).
            if (television) handoff.give()
            if (!focusManager.moveFocus(FocusDirection.Down)) focusManager.moveFocus(FocusDirection.Next)
        } else {
            field.stop()
            if (moveOn) focusManager.moveFocus(FocusDirection.Down)
        }
    }
    val editing = field.editing
    val shape = RoundedCornerShape(12.dp)
    val input: @Composable (Modifier) -> Unit = { fieldModifier ->
        BasicTextField(
            value = shown,
            onValueChange = { next ->
                typed = next
                if (next.text != value) onValueChange(next.text)
            },
            readOnly = field.readOnly,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = if (secret || keyboardType != KeyboardType.Text) false else null,
                keyboardType = if (secret && keyboardType == KeyboardType.Text) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onDone = { act() }, onNext = { act() }, onSearch = { act() }, onGo = { act() }, onSend = { act() }),
            modifier = fieldModifier.defaultMinSize(minWidth = 280.dp).onFocusChanged { focused = it.isFocused }.then(field.modifier),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .heightIn(min = 56.dp)
                        .background(if (focused) OndoColors.SurfaceHigh else OndoColors.Surface, shape)
                        .border(if (editing) 3.dp else if (focused) 2.dp else 1.dp, if (focused) Color.White else OndoColors.Outline, shape)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leadingIcon?.let {
                        Icon(it, contentDescription = null, tint = OndoColors.Secondary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        label?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = if (focused) Color.White else OndoColors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty() && placeholder != null) {
                                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = OndoColors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            inner()
                        }
                    }
                    when {
                        // The remote on the field, its keyboard closed: how to type, beside a pencil.
                        focused && !editing -> {
                            Spacer(Modifier.width(12.dp))
                            Row(Modifier.testTag(TV_FIELD_HINT_TAG), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.tv_field_press_ok), style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
                            }
                        }
                        editing -> {
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.Rounded.Keyboard, contentDescription = null, tint = OndoColors.Secondary, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            },
        )
    }
    if (!secret) {
        input(modifier)
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        input(Modifier.weight(1f).then(modifier))
        val description = stringResource(if (revealed) R.string.tv_field_hide else R.string.tv_field_show)
        Button(
            onClick = { revealed = !revealed },
            modifier = Modifier.semantics { contentDescription = description }.testTag(TV_FIELD_REVEAL_TAG),
            shape = ButtonDefaults.shape(CircleShape),
            scale = ButtonDefaults.scale(focusedScale = 1.1f),
            colors = ButtonDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.2f),
                contentColor = Color.White,
                focusedContainerColor = Color.White,
                focusedContentColor = TvTokens.Ink,
            ),
            contentPadding = PaddingValues(12.dp),
        ) {
            androidx.tv.material3.Icon(if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}
