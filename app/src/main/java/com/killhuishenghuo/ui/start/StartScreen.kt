package com.killhuishenghuo.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.killhuishenghuo.ui.login.AuthUiState
import com.killhuishenghuo.ui.login.AuthViewModel
import com.killhuishenghuo.ui.login.CaptchaBox
import com.killhuishenghuo.ui.login.LoginMode
import com.killhuishenghuo.ui.login.ResendRow
import com.killhuishenghuo.ui.theme.Radii
import com.killhuishenghuo.ui.theme.Spacing
import com.killhuishenghuo.ui.theme.colors

/**
 * The screen before the app has an account.
 *
 * It is the app's name and one button, which is what the first-run screen is
 * allowed to be: the fields above the button are the form the button submits,
 * and nothing else is here. No welcome copy, no feature list, no second screen
 * to get through — the thing that has to happen is a login, so the login is the
 * screen.
 *
 * The token path is kept behind a text link rather than a tab. SMS is what the
 * user asked for and what the button does; pasting a token is a fallback for a
 * gateway that dislikes this build's `cid`, and a fallback does not deserve
 * equal billing with the main path.
 */
@Composable
fun StartScreen(
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Spacing.xxl + Spacing.xl))

        Text(
            text = "惠生活去死吧",
            style = MaterialTheme.typography.displayMedium,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Spacing.xxl))

        if (state.mode == LoginMode.SMS) {
            SmsForm(state = state, viewModel = viewModel)
        } else {
            TokenForm(state = state, viewModel = viewModel)
        }

        Spacer(Modifier.height(Spacing.md))

        TextButton(onClick = { viewModel.selectMode(state.mode.other()) }) {
            Text(
                text = if (state.mode == LoginMode.SMS) "粘贴 token 登录" else "用手机号登录",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkFaint,
            )
        }

        Spacer(Modifier.height(Spacing.xl))
    }
}

/** The main path: phone number, captcha, SMS code. */
@Composable
private fun SmsForm(
    state: AuthUiState,
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        OutlinedTextField(
            value = state.phoneInput,
            onValueChange = viewModel::onPhoneChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Placeholder("手机号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            shape = RoundedCornerShape(Radii.card),
            colors = startFieldColors(),
        )

        CaptchaBox(
            captcha = state.captcha,
            isLoading = state.isLoadingCaptcha,
            onRefresh = { viewModel.refreshCaptcha() },
        )

        OutlinedTextField(
            value = state.captchaText,
            onValueChange = viewModel::onCaptchaTextChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Placeholder("图形验证码") },
            singleLine = true,
            shape = RoundedCornerShape(Radii.card),
            colors = startFieldColors(),
        )

        if (state.isSmsSent) {
            OutlinedTextField(
                value = state.smsCodeInput,
                onValueChange = viewModel::onSmsCodeChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Placeholder("短信验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(Radii.card),
                colors = startFieldColors(),
            )
        } else {
            // Present but disabled until a code has been sent, rather than
            // appearing afterwards: the form then has its final height from the
            // first frame, so nothing below it shifts under an open keyboard,
            // and the user can see how many steps there are before starting.
            OutlinedTextField(
                value = "",
                onValueChange = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Placeholder("短信验证码") },
                singleLine = true,
                shape = RoundedCornerShape(Radii.card),
                colors = startFieldColors(),
            )
        }

        Spacer(Modifier.height(Spacing.xs))

        LoginButton(
            text = if (state.isSmsSent) "登录" else "点击登录账号",
            enabled = if (state.isSmsSent) state.canSubmitSms else state.canSendSms,
            onClick = {
                if (state.isSmsSent) viewModel.submitSmsCode() else viewModel.sendSmsCode()
            },
        )

        // Without this the screen has no way back to a send button: once a code
        // is out, the button above is "登录" for good.
        if (state.isSmsSent) {
            ResendRow(
                seconds = state.resendSeconds,
                canResend = state.canResendSms,
                onResend = viewModel::resendSmsCode,
            )
        }

        ErrorLine(message = state.errorMessage, onDismiss = viewModel::dismissError)
    }
}

/** The fallback: paste what the official app already sends. */
@Composable
private fun TokenForm(
    state: AuthUiState,
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        OutlinedTextField(
            value = state.tokenInput,
            onValueChange = viewModel::onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Placeholder("Authorization 的值") },
            singleLine = true,
            shape = RoundedCornerShape(Radii.card),
            colors = startFieldColors(),
        )

        Spacer(Modifier.height(Spacing.xs))

        LoginButton(
            text = "点击登录账号",
            enabled = state.canSubmitToken,
            onClick = viewModel::submitToken,
        )

        Text(
            text = "用抓包工具看一条 i.ilife798.com 的请求，复制 Authorization 的值。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkFaint,
            lineHeight = 19.sp,
        )

        ErrorLine(message = state.errorMessage, onDismiss = viewModel::dismissError)
    }
}

@Composable
private fun LoginButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(Radii.card),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.heated,
            contentColor = colors.onHeated,
            disabledContainerColor = colors.surfaceRaised,
            disabledContentColor = colors.inkFaint,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Placeholder(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = colors.inkFaint,
    )
}

/**
 * The failure line, in place rather than as a snackbar.
 *
 * A form error that leaves on a timer is a message that a slow reader never
 * finishes, and this app is read standing in a bathroom.
 */
@Composable
private fun ErrorLine(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message == null) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.xxs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.danger,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onDismiss) {
                    Text("知道了", color = colors.danger)
                }
            }
        }
    }
}

/**
 * The fields on the recovery form.
 *
 * Deliberately not `remember`ed: `OutlinedTextFieldDefaults.colors` is a
 * `@Composable` and cannot be called from a `remember` lambda, and the workaround
 * — capturing the palette outside and building the colour set inside — buys an
 * allocation Material already scopes per call while costing a second copy of
 * every `colors.*` read.
 */
@Composable
private fun startFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = colors.ink,
    unfocusedTextColor = colors.ink,
    focusedBorderColor = colors.water,
    unfocusedBorderColor = colors.hairlineStrong,
    cursorColor = colors.water,
    focusedContainerColor = colors.surfaceSunken,
    unfocusedContainerColor = colors.surfaceSunken,
)

private fun LoginMode.other(): LoginMode =
    if (this == LoginMode.SMS) LoginMode.TOKEN else LoginMode.SMS
