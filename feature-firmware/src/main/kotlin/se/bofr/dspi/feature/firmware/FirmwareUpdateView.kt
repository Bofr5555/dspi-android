// Port of DSPi-Console (macOS) `DSPi Console/FirmwareUpdateView.swift` @
// v1.1.6-beta5 (b5fdeac): FirmwareUpdateView (header, version summary, step
// strip, status card, BOOTSEL / manual bootloader hints, button row) and
// UpdateStep. The window controller becomes the `firmware` route; the
// mismatch banner is HOME's (compat banner).
// Android deviations (UPSTREAM-DEVIATIONS "FIRMWARE"): the update is
// confirmed in a dialog (owner requirement; the ENTER_BOOTLOADER token is
// minted in its OK handler) that shows the running version and warns that an
// official release replaces a custom build, while "Enter bootloader mode
// without installing" acts on the tap as on macOS (E1); the session lives in
// a ViewModel (E9, FirmwareUpdateViewModel.kt); a local
// .uf2 is a first-class source; the write path is PICOBOOT with the board's
// drive (SAF) as the fallback; "Export Configuration..." opens PRESETS.
package se.bofr.dspi.feature.firmware

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import se.bofr.dspi.bridge.DangerousOp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.DspiRoutes
import se.bofr.dspi.usb.BootloaderLocator

/**
 * Entry point of :feature-firmware (route [DspiRoutes.FIRMWARE]).
 * The signature is the contract with :app — keep it. [bootloaderLocator] lets
 * :app share one `BootloaderLocator`; by default the screen makes its own.
 *
 * Installs firmware onto the connected device, or onto a board already in
 * BOOTSEL. The user confirms once; everything after is the installer's state
 * machine, including the reboot into BOOTSEL. Never auto-updates. The
 * session lives in a ViewModel (E9), so a rotation keeps the chosen file,
 * drive, commitment and any install in progress.
 */
@Composable
fun FirmwareUpdateView(
    bridge: DspiBridge,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bootloaderLocator: BootloaderLocator? = null
) {
    val context = LocalContext.current
    val vm: FirmwareUpdateViewModel =
        viewModel(factory = FirmwareUpdateViewModel.factory(context.applicationContext, bridge, bootloaderLocator))
    val session = vm.session
    FirmwareUpdateView(
        model = session.model,
        device = rememberDeviceInfo(bridge),
        enterBootloader = { op ->
            // Minted on the user's explicit action (Update dialog OK, or the
            // bootloader-only link as macOS acts on its click; E1), main thread.
            if (bridge.connected) bridge.enterBootloader(bridge.dangerousOps.confirm(op))
        },
        onFilePicked = session::readLocalFile,
        onDrivePicked = session::useDrive,
        onNavigate = onNavigate,
        onBack = onBack,
        modifier = modifier
    )
}

/** What the view needs to know about the connected device. */
data class FirmwareDeviceInfo(val connected: Boolean, val version: FirmwareVersion?) {
    val match: FirmwareMatch? get() = FirmwareMatch.of(connected, version)
}

@Composable
private fun rememberDeviceInfo(bridge: DspiBridge): FirmwareDeviceInfo {
    val status by bridge.status.collectAsState()
    val state by bridge.state.collectAsState()
    return FirmwareDeviceInfo(status.connected, if (status.connected) FirmwareVersion.of(state) else null)
}

/** macOS UpdateStep. */
enum class UpdateStep(val label: String) {
    PREPARE("Prepare"),
    WRITE("Write"),
    VERIFY("Verify"),
    DONE("Done");

    companion object {
        fun of(state: FirmwareInstallState): UpdateStep = when (state) {
            FirmwareInstallState.Idle, FirmwareInstallState.WaitingForBoard, is FirmwareInstallState.WaitingForVolume,
            is FirmwareInstallState.Ready, is FirmwareInstallState.WaitingForPermission -> PREPARE
            is FirmwareInstallState.Writing -> WRITE
            FirmwareInstallState.WaitingForDevice -> VERIFY
            is FirmwareInstallState.Verified -> DONE
            is FirmwareInstallState.Failed -> PREPARE
        }
    }
}

/**
 * The screen over an injectable [model] (previews and tests drive it with
 * fakes). [enterBootloader] is called with [DangerousOp.ENTER_BOOTLOADER] from
 * the Update confirmation's OK or the bootloader-only link's tap. Picked
 * documents go to [onFilePicked] / [onDrivePicked], which run on the
 * session's (app) scope.
 */
@Composable
fun FirmwareUpdateView(
    model: FirmwareUpdateModel,
    device: FirmwareDeviceInfo,
    enterBootloader: (DangerousOp) -> Unit,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onFilePicked: (Uri) -> Unit = {},
    onDrivePicked: (Uri) -> Unit = {}
) {
    val state by model.installer.state.collectAsState()
    LaunchedEffect(state) { model.observe(state) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) onFilePicked(uri)
    }
    val pickDrive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) onDrivePicked(uri)
    }

    val primaryTitle = primaryTitle(model, device)
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Header(model)
            HorizontalDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                VersionSummary(model, device)
                SourcePicker(model, onChooseFile = { pickFile.launch(arrayOf("*/*")) })
                StepDotStrip(
                    labels = UpdateStep.entries.map { it.label },
                    current = UpdateStep.of(state).ordinal,
                    dimmed = state is FirmwareInstallState.Failed
                )
                StatusCard(model, state, device, primaryTitle, onChooseDrive = { pickDrive.launch(null) })
                when {
                    showBootselHint(model, state, device) -> BootselHint()
                    showManualBootloaderHint(model, state, device) -> ManualBootloaderHint {
                        // macOS acts on the link click, no alert (E1): the tap mints the token.
                        enterBootloader(DangerousOp.ENTER_BOOTLOADER)
                    }
                }
                WriteMethodPicker(model, onChooseDrive = { pickDrive.launch(null) })
            }
        }
        HorizontalDivider()
        ButtonRow(
            model,
            state,
            device,
            primaryTitle,
            onStart = { model.confirmingUpdate = true },
            onNavigate = onNavigate,
            onClose = onBack,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }

    if (model.confirmingUpdate) {
        UpdateConfirmation(model, device, primaryTitle, onDismiss = { model.confirmingUpdate = false }) {
            model.confirmingUpdate = false
            model.start(device.connected) { enterBootloader(DangerousOp.ENTER_BOOTLOADER) }
        }
    }
}

internal fun primaryTitle(model: FirmwareUpdateModel, device: FirmwareDeviceInfo): String = when {
    model.source is FirmwareSource.Local -> "Install Firmware"
    device.match == FirmwareMatch.DEVICE_NEWER -> "Downgrade"
    else -> "Update Firmware"
}

// MARK: Header

@Composable
private fun Header(model: FirmwareUpdateModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("▣", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Firmware Update", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when (val s = model.source) {
                    FirmwareSource.Bundled -> "Install firmware ${model.bundledVersion} onto a DSPi board"
                    is FirmwareSource.Local -> "Install ${s.image.name} onto a DSPi board"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// MARK: Version summary

@Composable
private fun VersionSummary(model: FirmwareUpdateModel, device: FirmwareDeviceInfo) {
    Column(
        Modifier.fillMaxWidth().setupCard().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LabeledValueRow("This Console", model.bundledVersion)
        LabeledValueRow("Connected device", device.version?.description ?: "None", secondary = device.version == null)
        if (device.match == FirmwareMatch.DEVICE_NEWER && model.source == FirmwareSource.Bundled) {
            Text(
                "↓ The device is newer than this Console, so this would be a downgrade.",
                fontSize = 12.sp,
                color = Orange,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

// MARK: Source (Android: a local .uf2 is first class)

@Composable
private fun SourcePicker(model: FirmwareUpdateModel, onChooseFile: () -> Unit) {
    val enabled = model.canChangeSetup
    Column(Modifier.fillMaxWidth().setupCard().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("FIRMWARE TO INSTALL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceRow(
            selected = model.source == FirmwareSource.Bundled,
            enabled = enabled,
            title = "Official release ${model.bundledVersion}",
            detail = "Included with this app",
            onClick = { model.chooseBundled() }
        )
        val local = model.source as? FirmwareSource.Local
        ChoiceRow(
            selected = local != null,
            enabled = enabled,
            title = "A .uf2 file on this phone",
            detail = local?.let { l -> l.image.name + (l.image.chip?.let { " — for the ${it.displayName}" } ?: "") }
                ?: "For example a custom DSPi build",
            onClick = onChooseFile
        )
        model.localFileError?.let { Text(it, fontSize = 12.sp, color = Orange, modifier = Modifier.padding(start = 48.dp, bottom = 4.dp)) }
        if (local != null && enabled) {
            TextButton(onClick = onChooseFile, modifier = Modifier.padding(start = 40.dp)) { Text("Choose Another File...") }
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, enabled: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, fontSize = 14.sp)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WriteMethodPicker(model: FirmwareUpdateModel, onChooseDrive: () -> Unit) {
    val enabled = model.canChangeSetup
    Column(Modifier.fillMaxWidth()) {
        Text("WRITE THROUGH", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceRow(
            selected = !model.useDrive,
            enabled = enabled,
            title = "USB (recommended)",
            detail = "Writes the board directly through its boot loader's USB interface",
            onClick = { model.usePicoboot() }
        )
        ChoiceRow(
            selected = model.useDrive,
            enabled = enabled,
            title = "The board's drive",
            detail =
            model.driveVolume?.let { "Drive: ${it.name}" } ?: "Copies the file to the RP2350 or RPI-RP2 drive, if this phone shows it",
            onClick = { if (model.driveVolume == null) onChooseDrive() else model.useDrive() }
        )
    }
}

// MARK: Status card

@Composable
private fun StatusCard(
    model: FirmwareUpdateModel,
    state: FirmwareInstallState,
    device: FirmwareDeviceInfo,
    primaryTitle: String,
    onChooseDrive: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (state) {
        FirmwareInstallState.Idle, FirmwareInstallState.WaitingForBoard -> when {
            model.confirmed -> InstallStateCard(
                InstallIcon.SEARCH,
                accent,
                "Looking for the board",
                "Waiting for it to appear in bootloader mode. If nothing happens after a few seconds, " +
                    "unplug the board, hold BOOTSEL, and plug it back in.",
                spinning = true
            )
            device.connected -> InstallStateCard(
                InstallIcon.CHECK_CIRCLE,
                accent,
                "Ready when you are",
                "Tap $primaryTitle to begin. The device will restart into bootloader mode, " +
                    "and audio will stop until the update finishes. Nothing is written without this tap."
            )
            else -> InstallStateCard(
                InstallIcon.CABLE,
                muted,
                "Connect a board",
                "No device is connected. Hold the BOOTSEL button while plugging a board in, and it will appear here."
            )
        }
        is FirmwareInstallState.WaitingForVolume -> InstallStateCard(
            InstallIcon.DRIVE,
            accent,
            "${state.chip.displayName} found",
            "The board is in bootloader mode. Choose its ${state.chip.volumeName} drive to write the firmware through it.",
            accessory = { OutlinedButton(onClick = onChooseDrive) { Text("Choose Drive...") } }
        )
        is FirmwareInstallState.WaitingForPermission -> InstallStateCard(
            InstallIcon.LOCK,
            accent,
            "${state.chip.displayName} found",
            "Allow DSPi to access the board in the dialog Android shows.",
            spinning = true
        )
        is FirmwareInstallState.Ready -> if (model.confirmed) {
            InstallStateCard(
                InstallIcon.DRIVE_CHECK,
                accent,
                "Preparing to write",
                if (model.useDrive) {
                    "Opening the ${state.board.chip.displayName}'s ${state.board.chip.volumeName} drive."
                } else {
                    "Opening the ${state.board.chip.displayName}'s USB interface."
                },
                spinning = true
            )
        } else {
            InstallStateCard(
                InstallIcon.DRIVE_CHECK,
                Green,
                "${state.board.chip.displayName} ready",
                "The board is in bootloader mode and ready to receive " +
                    (if (model.source is FirmwareSource.Local) model.imageLabel else "firmware ${model.imageLabel}") +
                    ". Tap $primaryTitle to begin."
            )
        }
        is FirmwareInstallState.Writing -> InstallWritingCard(
            fraction = state.fraction,
            boardName = model.lastSeenChip?.displayName,
            version = model.imageLabel,
            viaDrive = model.useDrive
        )
        FirmwareInstallState.WaitingForDevice -> InstallStateCard(
            InstallIcon.CYCLE,
            accent,
            "Firmware written",
            "The board is restarting with its new firmware. This can take up to half a minute; leave it plugged in.",
            spinning = true
        )
        is FirmwareInstallState.Verified -> InstallStateCard(
            InstallIcon.SEAL,
            Green,
            "Update complete",
            if (model.source is FirmwareSource.Local) {
                // Nothing to compare a local file's version with.
                "The device is back, running firmware ${state.version}."
            } else {
                "The device is back and confirmed running firmware ${state.version}."
            },
            iconSize = 36.dp
        )
        is FirmwareInstallState.Failed -> InstallFailureCard(state.error)
    }
}

// MARK: Hints

internal fun showBootselHint(model: FirmwareUpdateModel, state: FirmwareInstallState, device: FirmwareDeviceInfo): Boolean = when (state) {
    FirmwareInstallState.Idle, FirmwareInstallState.WaitingForBoard -> model.confirmed || !device.connected
    is FirmwareInstallState.Failed -> state.error == FirmwareInstallError.NoBoardFound
    else -> false
}

internal fun showManualBootloaderHint(model: FirmwareUpdateModel, state: FirmwareInstallState, device: FirmwareDeviceInfo): Boolean {
    if (!device.connected || model.confirmed) return false
    return state == FirmwareInstallState.Idle || state == FirmwareInstallState.WaitingForBoard
}

@Composable
private fun ManualBootloaderHint(onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Flashing a UF2 of your own?", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClick) { Text("Enter bootloader mode without installing", fontSize = 12.sp) }
    }
}

// MARK: Buttons

@Composable
private fun ButtonRow(
    model: FirmwareUpdateModel,
    state: FirmwareInstallState,
    device: FirmwareDeviceInfo,
    primaryTitle: String,
    onStart: () -> Unit,
    onNavigate: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state is FirmwareInstallState.Verified) {
            // A second board is a second decision: back to detection, nothing armed.
            OutlinedButton(onClick = { model.resetRun() }) { Text("Update Another Board") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onClose) { Text("Done") }
        } else {
            TextButton(onClick = onClose) { Text("Cancel") }
            // Presets are not written by the installer, but a wire-format
            // change can leave them unreadable: offered, not forced.
            if (device.connected && !model.confirmed) {
                TextButton(onClick = { onNavigate(DspiRoutes.PRESETS) }) { Text("Export Configuration...") }
            }
            Spacer(Modifier.weight(1f))
            when (state) {
                is FirmwareInstallState.Failed -> Button(onClick = { model.resetRun() }) { Text("Try Again") }
                is FirmwareInstallState.Writing, FirmwareInstallState.WaitingForDevice, is FirmwareInstallState.WaitingForPermission -> Unit
                else -> Button(onClick = onStart, enabled = !model.confirmed) { Text(primaryTitle) }
            }
        }
    }
}

// MARK: Confirmations

/**
 * The one confirmation of an update (gated ENTER_BOOTLOADER; owner
 * requirement): what runs now, what will be written, and, for the official
 * release, that it replaces a custom build.
 */
@Composable
private fun UpdateConfirmation(
    model: FirmwareUpdateModel,
    device: FirmwareDeviceInfo,
    primaryTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(primaryTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledValueRow("Running now", runningNowLabel(device), labelWidth = 104.dp)
                    LabeledValueRow("Will install", willInstallLabel(model), labelWidth = 104.dp)
                }
                Text(confirmationText(model, device), fontSize = 14.sp)
                if (model.source == FirmwareSource.Bundled) {
                    Text(CUSTOM_BUILD_WARNING, fontSize = 14.sp, color = Orange)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(primaryTitle) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Owner requirement: an official release replaces a custom build. */
internal const val CUSTOM_BUILD_WARNING =
    "If this board runs a custom DSPi build (for example one that raises the core voltage because the chip hangs " +
        "at the stock 1.15 V), installing the official release replaces it, and that instability may come back. " +
        "To keep a custom build, choose its .uf2 file instead."

internal fun runningNowLabel(device: FirmwareDeviceInfo): String =
    device.version?.description ?: if (device.connected) "Unknown" else "Unknown (no DSPi connected)"

internal fun willInstallLabel(model: FirmwareUpdateModel): String = when (val s = model.source) {
    FirmwareSource.Bundled -> "Official ${model.bundledVersion}"
    is FirmwareSource.Local -> s.image.name
}

private const val RESTART_SENTENCE = "The device will restart into bootloader mode, and audio will stop until the update finishes."

internal fun confirmationText(model: FirmwareUpdateModel, device: FirmwareDeviceInfo): String {
    val replaces = "This replaces the firmware on the board."
    val restart = if (device.connected) " $RESTART_SENTENCE" else ""
    val downgrade = if (device.match == FirmwareMatch.DEVICE_NEWER && model.source == FirmwareSource.Bundled) {
        " The device is newer than this Console, so this would be a downgrade."
    } else {
        ""
    }
    return replaces + restart + downgrade
}
