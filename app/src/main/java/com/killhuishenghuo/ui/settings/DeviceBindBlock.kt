package com.killhuishenghuo.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.killhuishenghuo.core.log.AppLog
import com.killhuishenghuo.ui.theme.Radii
import com.killhuishenghuo.ui.theme.SettingsType
import com.killhuishenghuo.ui.theme.Spacing
import com.killhuishenghuo.ui.theme.colors
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanCustomCode
import io.github.g00fy2.quickie.config.BarcodeFormat
import io.github.g00fy2.quickie.config.ScannerConfig

/**
 * Adds the machine whose sticker was scanned to this account.
 *
 * Exists because of a real screen: an account that is signed in, whose
 * `ui/app/master` answers normally, and whose `favos[]` — the only device list
 * this app can read — is empty. The account's answer to "which device?" was
 * 还没有可用的设备, and there was nothing the user could do about it.
 *
 * Two ways in, and the second is not a fallback in name only:
 *
 * - **The camera.** The sticker on the machine is the thing the official app
 *   scans, and it is the path that needs no typing.
 * - **The text field.** A refused permission, a camera that cannot focus on a
 *   scratched sticker, a phone whose scan yields a string this client has not
 *   seen before — all three end in the same place, and the user can see and fix
 *   the string here rather than being told that something went wrong somewhere.
 *   The field is also what makes this feature testable without a camera.
 */
@Composable
internal fun DeviceBindBlock(
    isBinding: Boolean,
    message: String?,
    failed: Boolean,
    onBind: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }

    /**
     * What went wrong *inside this block* — a refused permission, a scan that
     * produced nothing readable.
     *
     * Kept here rather than pushed up into the ViewModel because it is not a fact
     * about the account or the device; it is a fact about the last thing the user
     * did on this row, and it should not survive a trip to the other screen.
     */
    var notice by remember { mutableStateOf<String?>(null) }

    // Read out of the palette here, outside `remember`: `colors` is a composable
    // getter, and the config builder below is not a composable.
    val frameColor = colors.water.toArgb()
    val highlightColor = colors.warm.toArgb()
    val buttonColor = colors.surfaceSunken.toArgb()
    val inkColor = colors.ink.toArgb()

    val config = remember(frameColor, highlightColor, buttonColor, inkColor) {
        ScannerConfig.build {
            // QR only: the sticker carries one, and narrowing the format list is
            // what keeps a low-contrast code on a curved surface quick to read.
            setBarcodeFormats(listOf(BarcodeFormat.QR_CODE))
            setHapticSuccessFeedback(true)
            setShowTorchToggle(true)
            setShowCloseButton(true)
            setKeepScreenOn(true)
            setColors(
                buttonTint = AndroidColor.WHITE,
                buttonBackground = buttonColor,
                frameHighlighted = highlightColor,
                frame = frameColor,
                topIcon = inkColor,
                topText = inkColor,
            )
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanCustomCode()) { result ->
        // Every ending is logged, and the timestamps are the point: they are the
        // only way to answer the question a real report could not — **how long
        // was the scanner actually open**. The library has no timeout
        // (`ScannerConfig` has no such field and the activity runs no timer), so
        // "it comes back too fast" can only be one of these four endings, and
        // which one it was is otherwise invisible. The log exported after the
        // first report of this had not one line about the scan in it.
        when (result) {
            is QRResult.QRSuccess -> {
                val scanned = result.content.rawValue.orEmpty().trim()
                // The raw payload goes in on purpose. It is the one fact about a
                // real sticker this project has never had — the parser was
                // written from two other clients' source, not from one — and a
                // log line is cheaper than another round of guessing.
                AppLog.log("Scan", "code ${scanned.length} chars: $scanned")
                if (scanned.isEmpty()) {
                    notice = "这个二维码是空的，换一个或手动输入设备号"
                } else {
                    code = scanned
                    notice = null
                }
            }

            is QRResult.QRError -> {
                val cause = result.exception
                AppLog.log("Scan", "error ${cause::class.java.simpleName}: ${cause.message}")
                // Deliberately *not* the same sentence as "no code in view".
                // QRError is what the library returns when the camera or ML Kit
                // failed to set up — and it finishes the activity in that case,
                // which is exactly what "it exits by itself" looks like from the
                // outside. Telling the user to hold the phone closer would be
                // advice about a problem they do not have.
                notice = "相机没能打开，再点一次试试；一直不行就把日志发我"
            }

            QRResult.QRUserCanceled -> {
                AppLog.log("Scan", "canceled (closed with no code)")
                notice = "暂时没有扫到二维码，凑近一点再试"
            }

            QRResult.QRMissingPermission -> {
                AppLog.log("Scan", "missing camera permission")
                notice = "没有相机权限。可以在系统设置里打开，或直接把二维码内容粘进来"
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        AppLog.log("Scan", "camera permission granted=$granted")
        if (granted) scanner.launch(config) else notice = "没有相机权限。可以手动输入设备号"
    }

    val shownNotice = notice
    val shownMessage = if (shownNotice != null) shownNotice else message
    val messageIsFailure = shownNotice != null || failed

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = "扫码添加设备",
            style = SettingsType.label,
            color = colors.ink,
        )

        OutlinedButton(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                AppLog.log("Scan", "opened (permission=$granted)")
                if (granted) scanner.launch(config) else cameraPermission.launch(Manifest.permission.CAMERA)
            },
            enabled = !isBinding,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radii.card),
        ) {
            Icon(
                imageVector = Icons.Filled.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text("扫描二维码", modifier = Modifier.padding(start = Spacing.xs))
        }

        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "设备号 / 二维码内容",
                    style = SettingsType.placeholder,
                    color = colors.inkFaint,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            shape = RoundedCornerShape(Radii.card),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.water,
                unfocusedBorderColor = colors.hairline,
                focusedContainerColor = colors.surfaceSunken,
                unfocusedContainerColor = colors.surfaceSunken,
                cursorColor = colors.water,
            ),
        )

        PrimaryButton(
            text = if (isBinding) "正在添加…" else "添加设备",
            enabled = code.isNotBlank() && !isBinding,
            onClick = { onBind(code.trim()) },
        )

        shownMessage?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = it,
                    style = SettingsType.detail,
                    color = if (messageIsFailure) colors.danger else colors.cooling,
                )
            }
        }
    }
}
