package com.pocketfx.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pocketfx.R
import com.pocketfx.data.update.DownloadProgress
import com.pocketfx.ui.components.ErrorText
import com.pocketfx.ui.components.LocalAppLocale
import com.pocketfx.ui.components.MutedText
import com.pocketfx.ui.components.Panel
import com.pocketfx.ui.components.SectionHeading
import com.pocketfx.ui.components.formatTimestamp
import java.text.NumberFormat

@Composable
internal fun AppUpdatesPanel(state: UpdatesUiState, model: UpdateViewModel?) {
    val locale = LocalAppLocale.current
    val available = state.available
    val progress = state.progress
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading(stringResource(R.string.app_updates))
            MutedText(stringResource(R.string.update_installed, state.installed.name))
            state.lastCheckedAt?.let { MutedText(stringResource(R.string.update_checked_at, formatTimestamp(it, locale))) }
            when {
                !state.configured -> MutedText(stringResource(R.string.update_unconfigured))
                state.checking -> MutedText(stringResource(R.string.update_checking))
                progress != null -> UpdateProgress(progress)
                state.install == InstallStatus.PERMISSION_REQUIRED -> MutedText(stringResource(R.string.update_permission))
                state.install == InstallStatus.READY -> MutedText(stringResource(R.string.update_ready))
                state.install == InstallStatus.OPENING_INSTALLER -> MutedText(stringResource(R.string.update_opening_installer))
                available != null -> MutedText(stringResource(R.string.update_available_version, available.version.name))
                state.lastCheckedAt != null && state.issue == null -> MutedText(stringResource(R.string.update_current))
            }
            state.issue?.let { ErrorText(it.label) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { model?.check(force = true) },
                    enabled = state.configured && model != null && !state.checking &&
                        progress == null && state.install != InstallStatus.OPENING_INSTALLER,
                ) { Text(stringResource(R.string.update_check)) }
                if (model != null) {
                    when {
                        progress != null -> TextButton(onClick = model::cancelDownload) { Text(stringResource(R.string.update_cancel_download)) }
                        state.install == InstallStatus.PERMISSION_REQUIRED -> Button(onClick = model::openPermissionSettings) { Text(stringResource(R.string.update_allow_installation)) }
                        state.install == InstallStatus.READY -> Button(onClick = model::install, enabled = !state.checking) { Text(stringResource(R.string.update_install)) }
                        available != null && !state.checking && state.install == InstallStatus.NONE ->
                            Button(onClick = model::download) { Text(stringResource(R.string.update_download)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun UpdateDialogs(state: UpdatesUiState, model: UpdateViewModel) {
    val progress = state.progress
    if (progress != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading)) },
            text = { UpdateProgress(progress) },
            confirmButton = { TextButton(onClick = model::cancelDownload) { Text(stringResource(R.string.update_cancel_download)) } },
        )
        return
    }
    val available = state.available
    when (state.dialog) {
        UpdateDialog.AVAILABLE -> if (available != null) {
            val notes = available.notesFor(LocalAppLocale.current)
            AlertDialog(
                onDismissRequest = model::dismiss,
                title = { Text(stringResource(R.string.update_available)) },
                text = {
                    Column(
                        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.update_available_version, available.version.name))
                        MutedText(stringResource(R.string.update_installed, state.installed.name))
                        if (notes != null) {
                            SectionHeading(stringResource(R.string.update_notes))
                            Text(notes)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = model::download) { Text(stringResource(R.string.update_download)) } },
                dismissButton = { Later(model::dismiss) },
            )
        }
        UpdateDialog.PERMISSION -> AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(stringResource(R.string.update_permission)) },
            text = { Text(stringResource(R.string.update_permission_detail)) },
            confirmButton = { TextButton(onClick = model::openPermissionSettings) { Text(stringResource(R.string.update_allow_installation)) } },
            dismissButton = { Later(model::dismiss) },
        )
        UpdateDialog.DOWNLOAD_FAILED -> AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(stringResource(R.string.update_failed)) },
            text = { Text(stringResource((state.issue ?: UpdateIssue.DOWNLOAD).label)) },
            confirmButton = { TextButton(onClick = model::download) { Text(stringResource(R.string.retry)) } },
            dismissButton = { Later(model::dismiss) },
        )
        UpdateDialog.INSTALL_FAILED -> AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(stringResource(R.string.update_failed)) },
            text = { Text(stringResource(R.string.update_install_failed)) },
            confirmButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.done)) } },
        )
        null -> Unit
    }
}

@Composable
private fun Later(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.update_later)) }
}

@Composable
private fun UpdateProgress(progress: DownloadProgress) {
    val locale = LocalAppLocale.current
    val ratio = progress.total?.takeIf { it > 0 }?.let { (progress.bytes.toDouble() / it).coerceIn(0.0, 1.0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ratio == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            val size = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(progress.bytes / 1_048_576.0)
            MutedText(stringResource(R.string.update_downloaded_size, size))
        } else {
            LinearProgressIndicator(progress = { ratio.toFloat() }, Modifier.fillMaxWidth())
            MutedText(stringResource(R.string.update_download_percent, NumberFormat.getPercentInstance(locale).format(ratio)))
        }
    }
}

private val UpdateIssue.label: Int get() = when (this) {
    UpdateIssue.CHECK -> R.string.update_check_failed
    UpdateIssue.DOWNLOAD -> R.string.update_download_failed
    UpdateIssue.VERIFICATION -> R.string.update_verification_failed
    UpdateIssue.INSTALL -> R.string.update_install_failed
    UpdateIssue.INSTALL_CANCELLED -> R.string.update_install_cancelled
}
