package com.pocketfx.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfx.data.update.AppVersion
import com.pocketfx.data.update.DownloadProgress
import com.pocketfx.data.update.InvalidUpdateApk
import com.pocketfx.data.update.UpdateCheck
import com.pocketfx.data.update.UpdateInfo
import com.pocketfx.data.update.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

internal enum class UpdateIssue { CHECK, DOWNLOAD, VERIFICATION, INSTALL, INSTALL_CANCELLED }
internal enum class InstallStatus { NONE, READY, PERMISSION_REQUIRED, OPENING_INSTALLER }
internal enum class UpdateDialog { AVAILABLE, PERMISSION, DOWNLOAD_FAILED, INSTALL_FAILED }

internal data class UpdatesUiState(
    val installed: AppVersion,
    val configured: Boolean,
    val lastCheckedAt: Long? = null,
    val available: UpdateInfo? = null,
    val checking: Boolean = false,
    val progress: DownloadProgress? = null,
    val install: InstallStatus = InstallStatus.NONE,
    val issue: UpdateIssue? = null,
    val dialog: UpdateDialog? = null,
)

internal sealed interface UpdateAction {
    data class Install(val file: File) : UpdateAction
    data object OpenPermissionSettings : UpdateAction
}

internal class UpdateViewModel(private val repository: UpdateRepository) : ViewModel() {
    private val mutable = MutableStateFlow(UpdatesUiState(repository.installed, repository.configured))
    val state = mutable.asStateFlow()
    private val external = Channel<UpdateAction>(Channel.BUFFERED)
    val actions = external.receiveAsFlow()

    private val shownVersions = mutableSetOf<Long>()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var verified: Pair<UpdateInfo, File>? = null

    fun check(force: Boolean = false) {
        if (!repository.configured || checkJob?.isActive == true || downloadJob?.isActive == true ||
            mutable.value.install == InstallStatus.OPENING_INSTALLER
        ) return
        checkJob = viewModelScope.launch {
            mutable.update { it.copy(checking = true, issue = if (it.issue == UpdateIssue.CHECK) null else it.issue) }
            try {
                repository.check(force, ::saveCheck)?.let {
                    saveCheck(it)
                    val available = it.available
                    if (available != null && verified == null && shownVersions.add(available.version.code)) {
                        mutable.update { current -> current.copy(dialog = UpdateDialog.AVAILABLE) }
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                mutable.update { it.copy(issue = UpdateIssue.CHECK) }
            } finally {
                mutable.update { it.copy(checking = false) }
            }
        }
    }

    private fun saveCheck(check: UpdateCheck) {
        if (verified?.first != check.available) verified = null
        mutable.update {
            it.copy(
                lastCheckedAt = check.timestamp,
                available = check.available,
                install = if (verified == null) InstallStatus.NONE else it.install,
                dialog = if (check.available == null) null else it.dialog,
            )
        }
    }

    fun download() {
        val info = mutable.value.available ?: return
        if (checkJob?.isActive == true || downloadJob?.isActive == true ||
            mutable.value.install == InstallStatus.OPENING_INSTALLER
        ) return
        verified = null
        downloadJob = viewModelScope.launch {
            val progress = MutableStateFlow(DownloadProgress(0, null))
            mutable.update { it.copy(progress = progress.value, dialog = null, issue = null, install = InstallStatus.NONE) }
            val report = launch { progress.collect { value -> mutable.update { it.copy(progress = value) } } }
            try {
                val file = repository.download(info) { progress.value = it }
                verified = info to file
                mutable.update { it.copy(install = InstallStatus.READY) }
                install()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                val issue = if (failure is InvalidUpdateApk) UpdateIssue.VERIFICATION else UpdateIssue.DOWNLOAD
                mutable.update { it.copy(issue = issue, dialog = UpdateDialog.DOWNLOAD_FAILED) }
            } finally {
                report.cancel()
                mutable.update { it.copy(progress = null) }
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    fun install() {
        val file = verified?.second ?: return
        if (mutable.value.install == InstallStatus.OPENING_INSTALLER) return
        mutable.update { it.copy(install = InstallStatus.OPENING_INSTALLER, dialog = null, issue = null) }
        external.trySend(UpdateAction.Install(file))
    }

    fun installationNeedsPermission() {
        mutable.update { it.copy(install = InstallStatus.PERMISSION_REQUIRED, dialog = UpdateDialog.PERMISSION) }
    }

    fun openPermissionSettings() {
        dismiss()
        external.trySend(UpdateAction.OpenPermissionSettings)
    }

    fun permissionGranted() {
        mutable.update {
            if (it.install == InstallStatus.PERMISSION_REQUIRED) it.copy(install = InstallStatus.READY, dialog = null) else it
        }
    }

    fun installerFinished(success: Boolean, cancelled: Boolean = false) {
        if (success) verified = null
        mutable.update { it.copy(
            install = if (verified == null) InstallStatus.NONE else InstallStatus.READY,
            issue = if (success) null else if (cancelled) UpdateIssue.INSTALL_CANCELLED else UpdateIssue.INSTALL,
            dialog = if (success || cancelled) null else UpdateDialog.INSTALL_FAILED,
        ) }
    }

    fun dismiss() { mutable.update { it.copy(dialog = null) } }
}
