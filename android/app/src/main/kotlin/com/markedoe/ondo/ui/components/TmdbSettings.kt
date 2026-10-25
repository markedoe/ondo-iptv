package com.markedoe.ondo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.data.TmdbCheck
import kotlinx.coroutines.launch

/** TMDb's page where an account's API Read Access Token is shown. */
const val TMDB_API_PAGE = "https://www.themoviedb.org/settings/api"

/**
 * What to say under the TMDb token: TMDb's answer to the one just typed, else the state of the saved one (refused when
 * TMDb no longer accepts it, accepted otherwise), else nothing. With whether it is good news.
 */
fun tmdbStatus(check: TmdbCheck?, hasToken: Boolean, refusedLater: Boolean): Pair<Int, Boolean>? = when {
    check == TmdbCheck.REFUSED -> R.string.tmdb_refused to false
    check == TmdbCheck.UNREACHABLE -> R.string.tmdb_unreachable to false
    hasToken && refusedLater -> R.string.tmdb_refused to false
    hasToken -> R.string.tmdb_accepted to true
    else -> null
}

/** The colour of a TMDb status: green when accepted, the error colour otherwise. */
@Composable
fun tmdbStatusColor(good: Boolean): Color = if (good) Color(0xFF3FB950) else MaterialTheme.colorScheme.error

/**
 * The viewer's own TMDb token (phones and tablets), named as on TMDb's API page and checked with TMDb before it is
 * saved; Remove takes it away (from the other devices too, with sync).
 */
@Composable
fun TmdbTokenSettings(
    hasToken: Boolean,
    refusedLater: Boolean,
    onCheck: suspend (String) -> TmdbCheck,
    onRemove: () -> Unit,
    onOpenApiPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var token by rememberSaveable { mutableStateOf("") }
    var check by remember { mutableStateOf<TmdbCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit() {
        if (token.isBlank() || checking) return
        checking = true
        scope.launch {
            val answer = onCheck(token)
            check = answer
            checking = false
            if (answer == TmdbCheck.ACCEPTED) token = ""
        }
    }
    Column(modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                check = null
            },
            label = { Text(stringResource(R.string.tmdb_token_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag("tmdbTokenField"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = ::submit, enabled = token.isNotBlank() && !checking, modifier = Modifier.testTag("tmdbCheck")) {
                Text(stringResource(R.string.tmdb_check_and_save))
            }
            if (checking) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.weight(1f))
            if (hasToken) {
                TextButton(
                    onClick = {
                        token = ""
                        check = null
                        onRemove()
                    },
                    modifier = Modifier.testTag("tmdbRemove"),
                ) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            }
        }
        tmdbStatus(check, hasToken, refusedLater)?.let { (text, good) ->
            Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = tmdbStatusColor(good), modifier = Modifier.testTag("tmdbStatus"))
        }
        TextButton(onClick = onOpenApiPage) { Text(stringResource(R.string.tmdb_open_api_page)) }
        Text(stringResource(R.string.tmdb_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
