package com.killhuishenghuo.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.killhuishenghuo.BuildConfig
import com.killhuishenghuo.ui.theme.Spacing
import com.killhuishenghuo.ui.theme.colors

/**
 * The 检查更新 row.
 *
 * One row with one job, so the whole interaction is: tap it and the detail line
 * underneath tells you what is true. Idle → checking → either "已是最新" or
 * "发现新版本"; when there is a new one, the same row becomes the download
 * button. No dialog, no second screen.
 *
 * It asks GitHub and nothing else — see [com.killhuishenghuo.data.update.UpdateChecker]
 * for why that request must not go out on the gateway's client.
 */
@Composable
internal fun UpdateRow(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = when (state) {
        UpdateUiState.Idle -> "当前 ${BuildConfig.VERSION_NAME}"
        UpdateUiState.Checking -> "正在检查…"
        is UpdateUiState.UpToDate -> "已是最新（${state.latest}）"
        is UpdateUiState.Available -> "发现新版本 ${state.latest}，点这里下载"
        is UpdateUiState.Failed -> state.message
    }

    SettingRow(
        icon = Icons.Filled.SystemUpdate,
        label = "检查更新",
        detail = detail,
        trailing = {
            when (state) {
                is UpdateUiState.Available -> Text(
                    text = "下载",
                    color = colors.water,
                    modifier = Modifier.padding(horizontal = Spacing.xs),
                )

                UpdateUiState.Checking -> Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = colors.inkFaint,
                    modifier = Modifier.size(16.dp),
                )

                else -> null
            }
        },
        modifier = modifier,
        onClick = {
            when (state) {
                // A new version's row is the download button; asking again would
                // be the one thing the user cannot want from it at that moment.
                is UpdateUiState.Available -> onOpenUrl(state.url)
                // Nothing to do while a request is out, and tapping again must
                // not stack a second one.
                UpdateUiState.Checking -> Unit
                else -> onCheck()
            }
        },
    )
}
