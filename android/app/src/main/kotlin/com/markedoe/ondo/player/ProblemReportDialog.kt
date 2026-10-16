package com.markedoe.ondo.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.markedoe.ondo.R

/**
 * More, Report a Problem…: the report of this playback, shown in full before it leaves the device, to copy, to share
 * (mail, messages…) or to paste into a GitHub issue.
 */
@Composable
fun ProblemReportDialog(
    report: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onOpenIssue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.problem_report)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("problemReport")) {
                Text(
                    stringResource(R.string.problem_report_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The report is English in every language: left to right.
                SelectionContainer(Modifier.padding(top = 16.dp)) {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                        textAlign = TextAlign.Left,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        // The buttons wrap onto more lines when they do not fit side by side.
        confirmButton = {
            TextButton(onClick = onCopy, modifier = Modifier.testTag("copyReport")) { Text(stringResource(R.string.copy_report)) }
            TextButton(onClick = onShare, modifier = Modifier.testTag("shareReport")) { Text(stringResource(R.string.share)) }
            TextButton(onClick = onOpenIssue, modifier = Modifier.testTag("openIssue")) { Text(stringResource(R.string.open_issue)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
