package com.killhuishenghuo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.killhuishenghuo.BuildConfig
import com.killhuishenghuo.core.log.AppLog
import com.killhuishenghuo.data.model.Outcome
import com.killhuishenghuo.data.update.UpdateChecker
import com.killhuishenghuo.data.update.UpdateInfo
import com.killhuishenghuo.data.update.Version
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the 检查更新 row is showing. */
sealed interface UpdateUiState {

    /** Nothing asked yet. The row offers the check. */
    data object Idle : UpdateUiState

    /** A request is out; the row is not tappable again until it lands. */
    data object Checking : UpdateUiState

    /** The release is not newer than this build. */
    data class UpToDate(val latest: String) : UpdateUiState

    /** A newer release exists; tapping the row opens it. */
    data class Available(val latest: String, val url: String) : UpdateUiState

    /** The check itself failed — offline, rate-limited, or a bad payload. */
    data class Failed(val message: String) : UpdateUiState
}

/**
 * The update check, and nothing else.
 *
 * Its own ViewModel rather than a field on one of the others because it shares no
 * state with them: not the session, not the device list, not the valve.
 *
 * [currentVersion] is a parameter so a test can be a version older than the
 * release it stubs, which is the only way to exercise the interesting branch.
 */
class UpdateViewModel(
    private val checker: UpdateChecker,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /**
     * Asks GitHub, and reports what it says.
     *
     * Re-entrant calls are dropped rather than queued: the row is disabled while
     * [UpdateUiState.Checking], and a second tap that slipped through would only
     * spend another of GitHub's 60 unauthenticated requests per hour.
     */
    fun check() {
        if (_state.value is UpdateUiState.Checking) return
        _state.value = UpdateUiState.Checking

        viewModelScope.launch {
            when (val result = checker.newest()) {
                is Outcome.Success -> _state.value = decide(result.value)
                is Outcome.Failure -> {
                    AppLog.log("Update", "check failed error=${result.error::class.simpleName}")
                    _state.value = UpdateUiState.Failed(result.error.toChinese())
                }
            }
        }
    }

    /** Clears a failure so the row goes back to offering a fresh attempt. */
    fun dismiss() {
        if (_state.value is UpdateUiState.Failed) _state.value = UpdateUiState.Idle
    }

    private fun decide(info: UpdateInfo): UpdateUiState {
        val newer = Version.isNewer(candidate = info.tag, current = currentVersion)
        AppLog.log("Update", "check current=$currentVersion latest=${info.tag} newer=$newer")

        if (!newer) return UpdateUiState.UpToDate(latest = info.tag)

        // Prefer the APK for a one-tap download, but a release page is a usable
        // destination too — and the only one there is when nothing is attached.
        val route = info.apkUrl ?: info.releaseUrl
        return if (route.isBlank()) {
            UpdateUiState.Failed("发现了新版本 ${info.tag}，但发布页上没有可下载的地址")
        } else {
            UpdateUiState.Available(latest = info.tag, url = route)
        }
    }
}
