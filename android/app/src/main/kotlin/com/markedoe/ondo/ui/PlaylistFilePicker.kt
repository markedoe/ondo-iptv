package com.markedoe.ondo.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.markedoe.ondo.ui.screens.PickedFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens the system file picker and copies the chosen playlist into the app's storage (the picker's
 * permission does not last), then hands back its `file:` address and a name from the file name. Null on a device
 * without a file picker (most TVs): the button is not shown.
 */
@Composable
fun rememberPlaylistFilePicker(onPicked: (PickedFile) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { importPlaylist(context, uri)?.let(onPicked) }
    }
    // M3U files come with many types (audio/x-mpegurl, text/plain, application/octet-stream…).
    val picker = remember(context) {
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").resolveActivity(context.packageManager) != null
    }
    if (!picker) return null
    return {
        try {
            launcher.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            // Gone meanwhile: the address field still works.
        }
    }
}

private suspend fun importPlaylist(context: Context, uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
    runCatching {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val folder = File(context.filesDir, "imports").apply { mkdirs() }
        val target = File(folder, "${UUID.randomUUID()}.m3u")
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
        PickedFile(url = target.toURI().toString(), name = name?.substringBeforeLast('.').orEmpty())
    }.getOrNull()
}
