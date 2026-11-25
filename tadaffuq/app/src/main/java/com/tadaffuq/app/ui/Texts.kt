package com.tadaffuq.app.ui

import com.tadaffuq.app.core.sr
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tadaffuq.app.R
import com.tadaffuq.app.download.Hold
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.engine.Doctor

@Composable
fun errorText(code: String?): String = when {
    code == null -> stringResource(R.string.err_unknown)
    code == "NETWORK" -> stringResource(R.string.err_network)
    code.startsWith("HTTP_") -> sr(R.string.err_http, code.removePrefix("HTTP_"))
    code.startsWith("EXPIRED_") -> stringResource(R.string.err_expired)
    code == "NOT_FOUND" -> stringResource(R.string.err_not_found)
    code == "CHANGED" -> stringResource(R.string.err_changed)
    code == "NO_RANGE" -> stringResource(R.string.err_no_range)
    code == "SPACE" -> stringResource(R.string.err_space)
    code == "STORAGE" -> stringResource(R.string.err_storage)
    code == "CHECKSUM" -> stringResource(R.string.err_checksum)
    else -> stringResource(R.string.err_unknown)
}

@Composable
fun statusText(s: TaskStatus): String = stringResource(
    when (s) {
        TaskStatus.QUEUED -> R.string.st_queued
        TaskStatus.RUNNING -> R.string.st_running
        TaskStatus.PAUSED -> R.string.st_paused
        TaskStatus.WAITING_NETWORK -> R.string.st_waiting
        TaskStatus.VERIFYING -> R.string.st_verifying
        TaskStatus.COMPLETED -> R.string.st_completed
        TaskStatus.FAILED -> R.string.st_failed
    },
)

@Composable
fun holdText(h: Hold): String = stringResource(
    when (h) {
        Hold.NO_NETWORK -> R.string.hold_no_network
        Hold.WIFI_ONLY -> R.string.hold_wifi_only
        Hold.LOW_BATTERY -> R.string.hold_low_battery
        Hold.NOT_CHARGING -> R.string.hold_not_charging
        Hold.TOO_HOT -> R.string.hold_hot
        Hold.DAILY_LIMIT -> R.string.hold_daily
        Hold.MONTHLY_LIMIT -> R.string.hold_monthly
    },
)

/** نص التشخيص وحلّه المقترح. */
@Composable
fun doctorText(f: Doctor.Finding): Pair<String, String> = when (f) {
    Doctor.Finding.NO_RESUME -> stringResource(R.string.dr_no_resume) to stringResource(R.string.dr_no_resume_fix)
    Doctor.Finding.UNKNOWN_SIZE -> stringResource(R.string.dr_unknown_size) to stringResource(R.string.dr_unknown_size_fix)
    Doctor.Finding.LINK_EXPIRED -> stringResource(R.string.dr_expired) to stringResource(R.string.dr_expired_fix)
    Doctor.Finding.NOT_FOUND -> stringResource(R.string.dr_not_found) to stringResource(R.string.dr_not_found_fix)
    Doctor.Finding.CHANGED_ON_SERVER -> stringResource(R.string.dr_changed) to stringResource(R.string.dr_changed_fix)
    Doctor.Finding.SERVER_ERRORS -> stringResource(R.string.dr_server) to stringResource(R.string.dr_server_fix)
    Doctor.Finding.NETWORK_UNSTABLE -> stringResource(R.string.dr_network) to stringResource(R.string.dr_network_fix)
    Doctor.Finding.SLOW_SERVER -> stringResource(R.string.dr_slow_server) to stringResource(R.string.dr_slow_server_fix)
    Doctor.Finding.SINGLE_CONNECTION_CAP -> stringResource(R.string.dr_single) to stringResource(R.string.dr_single_fix)
    Doctor.Finding.LOW_SPACE -> stringResource(R.string.dr_space) to stringResource(R.string.dr_space_fix)
    Doctor.Finding.STORAGE_ERROR -> stringResource(R.string.dr_storage) to stringResource(R.string.dr_storage_fix)
    Doctor.Finding.CHECKSUM_MISMATCH -> stringResource(R.string.dr_checksum) to stringResource(R.string.dr_checksum_fix)
    Doctor.Finding.HEALTHY -> stringResource(R.string.dr_healthy) to stringResource(R.string.dr_healthy_fix)
}
