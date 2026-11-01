// The NSOpenPanel / NSSavePanel of FileMenuActions on Android: the Storage
// Access Framework's ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT, driven from
// a translucent framework Activity so :feature-presets needs no
// androidx.activity dependency (activity-compose would pull artifacts that
// gradle/verification-metadata.xml does not list for a library module).
// TODO(consolidation): with activity-compose available to features this
// becomes rememberLauncherForActivityResult.
package se.bofr.dspi.feature.presets

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred

/** Hosts one SAF picker and hands its Uri to [DocumentPicker]. */
class DocumentPickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // the picker is already up
        val request =
            if (intent.getBooleanExtra(EXTRA_CREATE, false)) {
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = intent.getStringExtra(EXTRA_MIME) ?: "*/*"
                    putExtra(Intent.EXTRA_TITLE, intent.getStringExtra(EXTRA_NAME))
                }
            } else {
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    intent.getStringArrayExtra(EXTRA_MIME_TYPES)?.let { putExtra(Intent.EXTRA_MIME_TYPES, it) }
                }
            }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(request, REQUEST)
        } catch (e: ActivityNotFoundException) {
            DocumentPicker.complete(null, e)
            finish()
        }
    }

    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        DocumentPicker.complete(if (resultCode == RESULT_OK) data?.data else null, null)
        finish()
    }

    internal companion object {
        const val REQUEST = 0x5AF
        const val EXTRA_CREATE = "se.bofr.dspi.presets.create"
        const val EXTRA_MIME = "se.bofr.dspi.presets.mime"
        const val EXTRA_NAME = "se.bofr.dspi.presets.name"
        const val EXTRA_MIME_TYPES = "se.bofr.dspi.presets.mimeTypes"
    }
}

/** Suspending front end of [DocumentPickerActivity]; one picker at a time. */
internal object DocumentPicker {
    private var pending: CompletableDeferred<Uri?>? = null

    @Synchronized
    fun complete(uri: Uri?, error: Throwable?) {
        val p = pending ?: return
        pending = null
        if (error != null) p.completeExceptionally(error) else p.complete(uri)
    }

    private suspend fun launch(context: Context, extras: Intent.() -> Unit): Uri? {
        val d = CompletableDeferred<Uri?>()
        synchronized(this) {
            pending?.complete(null)
            pending = d
        }
        val i = Intent(context, DocumentPickerActivity::class.java).apply(extras)
        if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return d.await()
    }

    /** NSOpenPanel: the picked document, null when cancelled. Throws ActivityNotFoundException without a picker. */
    suspend fun open(context: Context, mimeTypes: Array<String>): Uri? =
        launch(context) { putExtra(DocumentPickerActivity.EXTRA_MIME_TYPES, mimeTypes) }

    /** NSSavePanel with `nameFieldStringValue` = [suggestedName]. */
    suspend fun create(context: Context, suggestedName: String, mime: String): Uri? = launch(context) {
        putExtra(DocumentPickerActivity.EXTRA_CREATE, true)
        putExtra(DocumentPickerActivity.EXTRA_NAME, suggestedName)
        putExtra(DocumentPickerActivity.EXTRA_MIME, mime)
    }
}
