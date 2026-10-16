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
        when (result) {
            is QRResult.QRSuccess -> {
                val scanned = result.content.rawValue.orEmpty().trim()
                if (scanned.isEmpty()) {
                    notice = "这个二维码是空的，换一个或手动输入设备号"
                } else {
                    code = scanned
                    notice = null
                }
            }

            is QRResult.QRError -> notice =
                "没扫出来：${result.exception.localizedMessage ?: "凑近一点、调亮屏幕再试"}"

            // The one that used to say nothing at all, and the reason a scanner
            // that has no timeout still looked like it "gave up": `RESULT_CANCELED`
            // is what comes back when the scanner closes without a code — a
            // brushed X, a back gesture, or the system reclaiming the activity
            // while the user walks over to the machine. Silence there reads as
            // "it exited on its own and swallowed the attempt", so it says what
            // happened and leaves the row ready for another try.
            QRResult.QRUserCanceled -> notice = "暂时没有扫到二维码，凑近一点再试"

            QRResult.QRMissingPermission -> notice = "没有相机权限。可以在系统设置里打开，或直接把二维码内容粘进来"
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
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
