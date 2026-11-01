// Android side of the macOS NSOpenPanel / NSSavePanel use in FileMenuActions
// and AutoEQMenuActions (DSPi_ConsoleApp.swift @ v1.1.6-beta5): Storage Access
// Framework reads/writes and a share sheet (FileProvider). Not JVM-tested.
package se.bofr.dspi.feature.presets

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The feature's own FileProvider (a subclass, so it never collides with another module's in the merged manifest). */
class PresetsFileProvider : FileProvider()

internal object PresetFiles {
    fun authority(context: Context) = "${context.packageName}.presets.files"

    suspend fun readText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw java.io.IOException("The file could not be opened.")
    }

    suspend fun writeText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        (context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("The file could not be opened."))
            .use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** The document's display name (what the user typed in the save picker). */
    suspend fun displayName(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    /** The FileProvider's `cache-path shared/` directory. */
    fun sharedDir(context: Context) = File(context.cacheDir, "shared")

    /** Deletes earlier shared files (review P7): run at startup and before each share. */
    fun clearShared(dir: File) {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Writes [text] to a fresh cache file named [fileName] and opens the share sheet for it. */
    suspend fun share(context: Context, fileName: String, mime: String, text: String) {
        val file =
            withContext(Dispatchers.IO) {
                val dir = sharedDir(context)
                clearShared(dir)
                dir.mkdirs()
                File(dir, fileName).apply { writeText(text) }
            }
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(fileName, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(send, fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
