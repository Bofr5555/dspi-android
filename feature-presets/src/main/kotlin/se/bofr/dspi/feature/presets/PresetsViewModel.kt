// Decision E9 (review P4): the Presets controller — its alert slot, progress
// sheet and every multi-call device sequence — lives in a ViewModel, so a
// configuration change (rotation, dark mode, window resize) neither cancels a
// sequence between two flash writes nor loses an open alert. Device sequences
// are additionally not cancelled when the screen is left (launchDeviceSequence).
package se.bofr.dspi.feature.presets

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.feature.presets.autoeq.AutoEQManager

class PresetsViewModel(
    bridge: DspiBridge,
    autoEQ: AutoEQManager,
    /** Application context for SAF reads/writes and the share sheet; null in JVM tests. */
    private val app: Context?,
    io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    val controller = PresetsController(bridge, viewModelScope, autoEQ, io)
    private val files get() = controller.files
    private val host get() = controller.host

    init {
        // Review P7: nothing shared earlier stays readable through the FileProvider.
        app?.let { ctx -> viewModelScope.launch(io) { PresetFiles.clearShared(PresetFiles.sharedDir(ctx)) } }
    }

    /** An open picker was started; read the picked document and hand it to [use]. */
    fun importFrom(picked: Deferred<Uri?>, use: (String) -> Unit) {
        val ctx = app ?: return
        viewModelScope.launch {
            val uri = awaitPick(picked) ?: return@launch
            val text =
                try {
                    PresetFiles.readText(ctx, uri)
                } catch (e: Exception) {
                    files.readFailed(e)
                    return@launch
                }
            use(text)
        }
    }

    /** A save picker was started; [produce] the document for the picked Uri, write it, then [done]. */
    fun exportTo(picked: Deferred<Uri?>, produce: suspend (Uri) -> String?, done: (String) -> Unit) {
        val ctx = app ?: return
        viewModelScope.launch {
            val uri = awaitPick(picked) ?: return@launch
            val text = produce(uri) ?: return@launch
            try {
                PresetFiles.writeText(ctx, uri, text)
            } catch (e: Exception) {
                files.writeFailed(e)
                return@launch
            }
            done(text)
        }
    }

    /** The picked document's display name (the configuration's `name`). */
    suspend fun displayName(uri: Uri): String? = app?.let { PresetFiles.displayName(it, uri) }

    /** "Share Device Configuration...": export to a cache file and open the share sheet. */
    fun shareConfiguration() {
        val ctx = app ?: return
        if (!files.canExportConfiguration()) return
        viewModelScope.launch {
            val name = PresetDocumentFiles.DEFAULT_CONFIGURATION_FILE_NAME
            val text = files.configurationDocument(name) ?: return@launch
            try {
                PresetFiles.share(ctx, name, PresetDocumentFiles.CONFIGURATION_MIME, text)
            } catch (e: Exception) {
                files.writeFailed(e)
            }
        }
    }

    private suspend fun awaitPick(picked: Deferred<Uri?>): Uri? = try {
        picked.await()
    } catch (e: android.content.ActivityNotFoundException) {
        host.showError("No app is available to pick a file.")
        null
    }

    companion object {
        fun factory(bridge: DspiBridge, autoEQ: AutoEQManager, app: Context?): ViewModelProvider.Factory = viewModelFactory {
            initializer { PresetsViewModel(bridge, autoEQ, app) }
        }
    }
}
