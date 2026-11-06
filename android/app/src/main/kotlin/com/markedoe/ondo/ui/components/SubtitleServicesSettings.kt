package com.markedoe.ondo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R
import com.markedoe.ondo.core.subtitles.CredentialCheck
import com.markedoe.ondo.core.subtitles.SubtitleService
import com.markedoe.ondo.data.SubtitleCredential
import com.markedoe.ondo.data.SubtitleServices
import com.markedoe.ondo.ui.tv.TvActionButton
import com.markedoe.ondo.ui.tv.TvSwitchRow
import com.markedoe.ondo.ui.tv.isTelevision
import com.markedoe.ondo.ui.tv.TvTextInput
import kotlinx.coroutines.launch

/** What a service's check is doing, or said. */
private sealed interface KeyState {
    data object Checking : KeyState
    data class Answer(val check: CredentialCheck) : KeyState
}

/**
 * What to say under a service's fields: its answer to what was just typed, else that the saved key is accepted, else
 * nothing; with whether it is good news. A refusal names what the service refused: the key, or the key or the login.
 */
fun subtitleStatus(check: CredentialCheck?, saved: Boolean, withLogin: Boolean): Pair<Int, Boolean>? = when (check) {
    CredentialCheck.ACCEPTED -> R.string.subtitle_accepted to true
    CredentialCheck.REFUSED -> (if (withLogin) R.string.subtitle_refused_login else R.string.subtitle_refused_key) to false
    CredentialCheck.UNREACHABLE -> R.string.subtitle_unreachable to false
    CredentialCheck.LIMITED -> R.string.subtitle_limited to false
    CredentialCheck.INCOMPLETE -> R.string.subtitle_incomplete to false
    null -> if (saved) R.string.subtitle_accepted to true else null
}

/** The help under a service's fields: where its key is, in the service's own words. */
private fun help(service: SubtitleService): Int = when (service) {
    SubtitleService.OPEN_SUBTITLES -> R.string.subtitle_help_opensubtitles
    SubtitleService.SUBDL -> R.string.subtitle_help_subdl
    SubtitleService.SUBSOURCE -> R.string.subtitle_help_subsource
    SubtitleService.ADDIC7ED -> R.string.subtitle_help_addic7ed
}

private fun label(field: SubtitleCredential): Int = when (field) {
    SubtitleCredential.OPEN_SUBTITLES_USERNAME -> R.string.subtitle_username
    SubtitleCredential.OPEN_SUBTITLES_PASSWORD -> R.string.subtitle_password
    else -> R.string.subtitle_api_key
}

/**
 * Settings → Subtitle services, on phones and TVs: for OpenSubtitles.com (API key, and an optional username and
 * password), SubDL and SubSource (API key), the fields named as each service names them, checked with the service when
 * saved (accepted, refused, or the service out of reach: only an accepted key is kept); Addic7ed's switch; and what a
 * search sends. The fields start empty: a saved key shows as accepted, with Remove.
 */
@Composable
fun SubtitleServicesSettings(services: SubtitleServices, modifier: Modifier = Modifier) {
    val state by services.state.collectAsState()
    val typed = remember { mutableStateMapOf<SubtitleCredential, String>() }
    val answers = remember { mutableStateMapOf<SubtitleService, KeyState>() }
    val scope = rememberCoroutineScope()
    val television = isTelevision()
    fun check(service: SubtitleService) {
        if (answers[service] == KeyState.Checking) return
        val fields = SubtitleCredential.fields(service)
        // Any field: with a key saved, a username and a password alone are checked with it.
        if (fields.all { typed[it].isNullOrBlank() }) return
        answers[service] = KeyState.Checking
        scope.launch {
            val answer = services.checkAndSave(service, fields.associateWith { typed[it].orEmpty() })
            if (answer == null) answers.remove(service) else answers[service] = KeyState.Answer(answer)
            // Saved: the fields empty again, the secret out of the screen.
            if (answer == CredentialCheck.ACCEPTED) fields.forEach { typed.remove(it) }
        }
    }
    Column(modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (service in listOf(SubtitleService.OPEN_SUBTITLES, SubtitleService.SUBDL, SubtitleService.SUBSOURCE)) {
            val fields = SubtitleCredential.fields(service)
            val saved = service in state.saved
            val answer = answers[service]
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(service.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                for (field in fields) {
                    val secret = field != SubtitleCredential.OPEN_SUBTITLES_USERNAME
                    val onValue: (String) -> Unit = {
                        typed[field] = it
                        answers.remove(service)
                    }
                    val tag = Modifier.fillMaxWidth().testTag("subtitleField-${field.name}")
                    if (television) {
                        // On a TV, the keyboard goes on from a service's field to the next; the last one's Done checks.
                        val last = field == fields.last()
                        TvTextInput(
                            value = typed[field].orEmpty(),
                            onValueChange = onValue,
                            label = stringResource(label(field)),
                            secret = secret,
                            imeAction = if (last) ImeAction.Done else ImeAction.Next,
                            onImeAction = { if (last) check(service) },
                            modifier = tag,
                        )
                    } else {
                        OutlinedTextField(
                            value = typed[field].orEmpty(),
                            onValueChange = onValue,
                            label = { Text(stringResource(label(field))) },
                            singleLine = true,
                            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onDone = {
                                if (field == fields.last()) check(service)
                                defaultKeyboardAction(ImeAction.Done)
                            }),
                            modifier = tag,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val enabled = fields.any { !typed[it].isNullOrBlank() } && answer != KeyState.Checking
                    val checkTag = Modifier.testTag("subtitleCheck-${service.name}")
                    if (television) {
                        TvActionButton(onClick = { check(service) }, enabled = enabled, modifier = checkTag) { androidx.tv.material3.Text(stringResource(R.string.tmdb_check_and_save)) }
                    } else {
                        Button(onClick = { check(service) }, enabled = enabled, modifier = checkTag) { Text(stringResource(R.string.tmdb_check_and_save)) }
                    }
                    if (saved) {
                        val remove = {
                            fields.forEach { typed.remove(it) }
                            answers.remove(service)
                            services.remove(service)
                        }
                        val removeTag = Modifier.testTag("subtitleRemove-${service.name}")
                        if (television) {
                            TvActionButton(onClick = remove, modifier = removeTag) { androidx.tv.material3.Text(stringResource(R.string.remove)) }
                        } else {
                            TextButton(onClick = remove, modifier = removeTag) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                if (answer == KeyState.Checking) {
                    Text(stringResource(R.string.tmdb_checking), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("subtitleChecking-${service.name}"))
                }
                val withLogin = service == SubtitleService.OPEN_SUBTITLES && !typed[SubtitleCredential.OPEN_SUBTITLES_USERNAME].isNullOrBlank()
                subtitleStatus((answer as? KeyState.Answer)?.check, saved, withLogin)?.let { (text, good) ->
                    Text(stringResource(text, service.displayName), style = MaterialTheme.typography.bodyMedium, color = tmdbStatusColor(good), modifier = Modifier.testTag("subtitleStatus-${service.name}"))
                }
                Text(stringResource(help(service)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val addic7ed = stringResource(R.string.subtitle_addic7ed)
        val addic7edHelp = stringResource(R.string.subtitle_help_addic7ed)
        if (television) {
            TvSwitchRow(addic7ed, addic7edHelp, state.addic7ed, services::setAddic7ed, Modifier.testTag("addic7edSwitch"))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(addic7ed, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(addic7edHelp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.addic7ed, onCheckedChange = services::setAddic7ed, modifier = Modifier.padding(start = 12.dp).testTag("addic7edSwitch"))
            }
        }
        Text(stringResource(R.string.subtitle_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
