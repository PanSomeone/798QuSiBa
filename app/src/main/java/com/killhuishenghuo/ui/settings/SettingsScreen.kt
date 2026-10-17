package com.killhuishenghuo.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.killhuishenghuo.BuildConfig
import com.killhuishenghuo.core.ProjectLinks
import com.killhuishenghuo.core.log.AppLog
import com.killhuishenghuo.core.log.LogExporter
import com.killhuishenghuo.LastRefusal
import com.killhuishenghuo.data.local.ThemeMode
import com.killhuishenghuo.data.model.Device
import com.killhuishenghuo.ui.login.AuthUiState
import com.killhuishenghuo.ui.login.AuthViewModel
import com.killhuishenghuo.ui.login.CaptchaBox
import com.killhuishenghuo.ui.login.LoginMode
import com.killhuishenghuo.ui.login.ResendRow
import com.killhuishenghuo.ui.components.glassTabBarSpace
import com.killhuishenghuo.ui.theme.Radii
import com.killhuishenghuo.ui.theme.SettingsType
import com.killhuishenghuo.ui.theme.Spacing
import com.killhuishenghuo.ui.theme.colors

/**
 * Everything that is not "open the hot water".
 *
 * The account lives here rather than on a login screen of its own because
 * signing in is a thing you do *once* and then come back to look at, not a
 * destination you pass through. Putting it behind a gear also means the app's
 * first screen is the device list — which is what someone opening this to turn
 * on water at 1am actually wants, and which works without a session.
 *
 * Sections are separated by a hairline and a title, not by cards inside cards.
 * At this size a card per section is three nested rounded rectangles to express
 * one list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel,
    dark: Boolean,
    onThemeChange: (ThemeMode) -> Unit,
    host: String,
    lastError: String?,
    devices: List<Device> = emptyList(),
    selected: Device? = null,
    onDeviceSelected: (Device) -> Unit = {},
    isBinding: Boolean = false,
    bindMessage: String? = null,
    bindFailed: Boolean = false,
    onBindScanned: (String) -> Unit = {},
    updateState: UpdateUiState = UpdateUiState.Idle,
    onCheckUpdate: () -> Unit = {},
    onSignOut: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by authViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var feedback by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    // A scope tied to this screen rather than to a ViewModel: the work is a file
    // write on behalf of one press, and it should be dropped if the screen goes
    // away rather than outliving it.
    val scope = rememberCoroutineScope()

    // The confirmation is a property of the clipboard, not a timer: it says
    // "this is on your clipboard now", and it should stop saying that once
    // something else has been copied.
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(ClipboardConfirmationMillis)
            copied = false
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = colors.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.ink,
                    )
                },
                // No back arrow. Settings is a tab now, not a page pushed on top
                // of one, and a back arrow beside a bottom bar is two answers to
                // "how do I leave" — the second one implying a stack that does
                // not exist.
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
        bottomBar = {
            // Space, not a drawing. The bar that used to be assembled here is
            // drawn once for both destinations by `MainTabBar`; all this screen
            // needs from it is the height, so the version number and the sign-out
            // button can never end up permanently underneath it.
            Spacer(Modifier.height(glassTabBarSpace()))
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            // The list has to be able to scroll clear of the floating bar, or the
            // last row — which is the version number, and before that the sign-out
            // button — sits permanently under it.
            contentPadding = PaddingValues(bottom = glassTabBarSpace() + Spacing.md),
        ) {
            item(key = "account") {
                Section(title = "账号") {
                    if (state.isSignedIn) {
                        SettingRow(
                            icon = Icons.Filled.Person,
                            label = state.accountName,
                            detail = host,
                            trailing = {
                                TextButton(onClick = onSignOut) {
                                    Text("退出", color = colors.danger)
                                }
                            },
                        )
                        ErrorLine(message = state.errorMessage, onDismiss = authViewModel::dismissError)
                    } else {
                        SignInBlock(
                            state = state,
                            viewModel = authViewModel,
                        )
                    }
                }
            }

            // The section used to appear only when there was something to choose
            // between — with one device the question has one answer, and the
            // screen had already bound it. It now also has to appear when the
            // answer is *none*, because that is exactly the state this block
            // exists to get out of: a real account was found logged in, with
            // `/master` answering and `favos` empty, and its only way forward was
            // to scan the sticker on the machine.
            if (state.isSignedIn) {
                item(key = "devices") {
                    Section(title = "设备") {
                        devices.forEachIndexed { index, device ->
                            if (index > 0) Hairline()
                            SettingRow(
                                icon = Icons.Filled.DevicesOther,
                                label = device.displayName,
                                detail = if (device.deviceId == selected?.deviceId) "已选" else null,
                                trailing = {
                                    if (device.deviceId == selected?.deviceId) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = "当前控制",
                                            tint = colors.water,
                                        )
                                    }
                                },
                                onClick = { onDeviceSelected(device) },
                            )
                        }
                        if (devices.isNotEmpty()) Hairline()
                        DeviceBindBlock(
                            isBinding = isBinding,
                            message = bindMessage,
                            failed = bindFailed,
                            onBind = onBindScanned,
                        )
                    }
                }
            }

            item(key = "appearance") {
                Section(title = "外观") {
                    AppearanceRow(dark = dark, onThemeChange = onThemeChange)
                }
            }

            item(key = "log") {
                Section(title = "运行日志") {
                    LogRow()
                }
            }

            item(key = "feedback") {
                Section(title = "反馈") {
                    FeedbackBlock(
                        value = feedback,
                        onValueChange = { feedback = it },
                        copied = copied,
                        onCopy = {
                            copyToClipboard(
                                context = context,
                                text = buildReport(
                                    feedback = feedback,
                                    accountName = state.accountName.ifEmpty { "未登录" },
                                    host = host,
                                    lastError = lastError,
                                ),
                            )
                            copied = true
                        },
                    )
                }
            }

            item(key = "about") {
                Section(title = "关于") {
                    ProjectRow(
                        icon = Icons.Filled.Star,
                        label = "Star 这个项目",
                        detail = "github.com/PanSomeone/798QuSiBa",
                        url = ProjectLinks.REPOSITORY,
                    )
                    Hairline()
                    ProjectRow(
                        icon = Icons.Filled.BugReport,
                        label = "反馈问题 / 提 issue",
                        detail = "遇到问题先看这里",
                        url = ProjectLinks.NEW_ISSUE,
                    )
                    Hairline()
                    SettingRow(
                        icon = Icons.Filled.Person,
                        label = "作者",
                        detail = "PanSomeone",
                        trailing = null,
                    )
                    Hairline()
                    UpdateRow(
                        state = updateState,
                        onCheck = onCheckUpdate,
                        onOpenUrl = { openUrl(context, it) },
                    )
                    Hairline()
                    SettingRow(
                        icon = Icons.Filled.Description,
                        label = "版本",
                        detail = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        trailing = null,
                    )
                }
            }
        }
    }
}

/**
 * One titled group.
 *
 * The title sits outside the surface rather than inside it: a label inside the
 * card competes with the rows for the same visual weight, and on a screen that
 * is all rows, that reads as one more row.
 */
@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = SettingsType.section,
            color = colors.inkFaint,
            modifier = Modifier.padding(
                start = Spacing.md,
                end = Spacing.md,
                top = Spacing.md,
                bottom = Spacing.xxs,
            ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceRaised)
                .padding(vertical = Spacing.xxs),
        ) {
            Column { content() }
        }
    }
}

/**
 * A row: glyph, label, detail, optional trailing control.
 *
 * The detail line is dropped entirely when [detail] is null rather than rendered
 * empty — an orphaned blank line under a label is one of those things that reads
 * as a rendering bug even though nobody can say why.
 */
@Composable
internal fun SettingRow(
    icon: ImageVector,
    label: String,
    detail: String?,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.inkFaint,
            modifier = Modifier.size(18.dp),
        )

        Spacer(Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = SettingsType.label,
                color = colors.ink,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = SettingsType.detail,
                    color = colors.inkMuted,
                )
            }
        }

        if (trailing != null) {
            Spacer(Modifier.width(Spacing.xs))
            trailing()
        }
    }
}

@Composable
private fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = Spacing.md + 18.dp + Spacing.md),
        thickness = 1.dp,
        color = colors.hairline,
    )
}

/**
 * Two options, current one filled.
 *
 * A segmented pair rather than a switch plus a label, for the same reason as on
 * the first-run screen: both states are visible at once, so choosing needs no
 * reading of which end of a switch is which.
 */
@Composable
private fun AppearanceRow(
    dark: Boolean,
    onThemeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (dark) Icons.Filled.DarkMode else Icons.Filled.LightMode,
            contentDescription = null,
            tint = colors.inkFaint,
            modifier = Modifier.size(18.dp),
        )

        Spacer(Modifier.width(Spacing.md))

        Text(
            text = "主题",
            style = SettingsType.label,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            ThemePill(
                label = "深色",
                selected = dark,
                onClick = { onThemeChange(ThemeMode.DARK) },
            )
            ThemePill(
                label = "浅色",
                selected = !dark,
                onClick = { onThemeChange(ThemeMode.LIGHT) },
            )
        }
    }
}

/**
 * Hands the run log over as a file.
 *
 * The whole reason this row exists: the person testing this app is holding a
 * phone, not a workstation, and the failure worth reporting — the gateway
 * answering `{"code":1201,"message":"设备正在运行"}` to a request to start the
 * water — is completely invisible on the screen. Asking for `adb logcat` would be
 * asking them to become a developer. Asking for a file is asking them to press a
 * button and send it.
 *
 * It shows the line count because a number that does not move is itself a
 * diagnosis: "I pressed it and it said 0 行" means the log was never written,
 * which is a different bug from the one being reported.
 */
@Composable
private fun LogRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lineCount = AppLog.size
    val scope = rememberCoroutineScope()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Description,
            contentDescription = null,
            tint = colors.inkFaint,
            modifier = Modifier.size(18.dp),
        )

        Spacer(Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "导出运行日志",
                style = SettingsType.label,
                color = colors.ink,
            )
            Text(
                text = "已记录 $lineCount 行，含请求与服务器原话",
                style = SettingsType.detail,
                color = colors.inkMuted,
            )
        }

        Spacer(Modifier.width(Spacing.xs))

        ThemePill(
            label = "发送",
            selected = true,
            onClick = {
                // Off the main thread: this writes a few thousand lines to disk,
                // clears the previous export, and only then opens the share sheet.
                //
                // Failure here is a real possibility — a phone with no chat app
                // installed has nothing to share to — and it must not look like
                // a successful export.
                scope.launch {
                    // `LogExporter.share` deletes the previous export, formats the
                    // dump and writes a few thousand lines to disk — all of which
                    // used to happen on the main thread inside this click handler,
                    // which is a dropped frame at best and an ANR on a slow phone.
                    // The `withContext(IO)` is inside the launch rather than the
                    // other way round because the share *intent* has to be started
                    // from a context the activity can see.
                    runCatching { withContext(Dispatchers.IO) { LogExporter.share(context) } }
                        .onFailure { AppLog.error("Export", it, "分享日志失败") }
                }
            },
        )
    }
}
@Composable
private fun ThemePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (selected) colors.water else colors.inkFaint

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.chip))
            .background(if (selected) colors.water.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.5f) else colors.hairline,
                shape = RoundedCornerShape(Radii.chip),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.ink else colors.inkMuted,
        )
    }
}

/**
 * Writes a diagnostic block to the clipboard.
 *
 * No send button, because there is nowhere for this build to send to: the
 * project has no server, and inventing an email address for it would produce a
 * button that silently does nothing. The clipboard is the one destination that
 * always works, and it lets the user attach the block to whichever channel they
 * already use.
 *
 * The block carries no token. That is the whole reason it is assembled here
 * rather than dumping the session: a bug report that leaks the credential it is
 * reporting about is worse than no report.
 */
@Composable
private fun FeedbackBlock(
    value: String,
    onValueChange: (String) -> Unit,
    copied: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Read here rather than taken as a parameter: the copy button is hoisted
    // (`onCopy`) because it is the screen's business, but "open a browser" is
    // not — it is the same thing `ProjectRow` below does, and it needs the same
    // Context to do it. Without this line `context` resolves to the stdlib
    // `kotlin.context`, and the link button does not compile.
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "遇到了什么？",
                    style = SettingsType.placeholder,
                    color = colors.inkFaint,
                )
            },
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(Radii.card),
            colors = settingsFieldColors(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (copied) ProjectLinks.FEEDBACK_HINT else "",
                style = SettingsType.detail,
                color = colors.cooling,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCopy) {
                Text("复制报告", color = colors.water)
            }
        }

        // The clipboard is where the report goes, not where it ends. Without
        // this the button copies text into a place the user then has to guess a
        // destination for, which is how a feedback button ends up unused.
        TextButton(onClick = { openUrl(context, ProjectLinks.NEW_ISSUE) }) {
            Text("去 GitHub 提 issue", color = colors.water)
        }
    }
}

/**
 * A row that leaves the app.
 *
 * Outlined in the settings list because every other row here changes something
 * in this app, and this one does not: it opens a browser. Tapping it with no
 * browser installed is possible on a stripped-down device, so the failure is
 * logged rather than thrown — a settings screen that crashes on a device
 * without Chrome would be a worse bug than the one being reported.
 */
@Composable
private fun ProjectRow(
    icon: ImageVector,
    label: String,
    detail: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    SettingRow(
        icon = icon,
        label = label,
        detail = detail,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = colors.inkFaint,
                modifier = Modifier.size(16.dp),
            )
        },
        modifier = modifier,
        onClick = { openUrl(context, url) },
    )
}

/**
 * Hands a URL to whatever can open it.
 *
 * `runCatching` rather than a `resolveActivity` pre-check: the check and the
 * start are two separate questions on Android, and a device can answer the
 * first one yes and then throw on the second. Catching the throw answers both.
 */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { AppLog.error("Link", it, "打不开 $url") }
}

/**
 * Shared field styling so the settings fields and the sign-in fields match.
 *
 * Deliberately *not* wrapped in a `remember`, though the obvious instinct is to
 * cache it: `OutlinedTextFieldDefaults.colors` is itself a `@Composable`, so it
 * cannot be called from inside a `remember` lambda, and the usual workaround —
 * capturing the palette outside and rebuilding the colour set inside — would
 * mean writing out nine `colors.*` reads twice over to save an allocation that
 * Material already scopes per call.
 */
@Composable
private fun settingsFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = colors.ink,
    unfocusedTextColor = colors.ink,
    focusedBorderColor = colors.water,
    unfocusedBorderColor = colors.hairlineStrong,
    focusedLabelColor = colors.water,
    unfocusedLabelColor = colors.inkMuted,
    cursorColor = colors.water,
    focusedContainerColor = colors.surfaceSunken,
    unfocusedContainerColor = colors.surfaceSunken,
)

/**
 * Sign-in, inline in the account section.
 *
 * SMS is first and is what the section opens on, because it needs nothing the
 * user has to go and obtain. Pasting a token is kept as the second tab: it is
 * the escape hatch for a `cid` the gateway dislikes, or for a session that
 * already exists in the official app.
 */
@Composable
private fun SignInBlock(
    state: AuthUiState,
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ModeTab(
                label = "短信登录",
                selected = state.mode == LoginMode.SMS,
                onClick = { viewModel.selectMode(LoginMode.SMS) },
            )
            ModeTab(
                label = "粘贴 token",
                selected = state.mode == LoginMode.TOKEN,
                onClick = { viewModel.selectMode(LoginMode.TOKEN) },
            )
        }

        if (state.mode == LoginMode.TOKEN) {
            OutlinedTextField(
                value = state.tokenInput,
                onValueChange = viewModel::onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "Authorization 的值",
                        style = SettingsType.placeholder,
                        color = colors.inkFaint,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(Radii.card),
                colors = settingsFieldColors(),
            )

            PrimaryButton(
                text = "登录",
                enabled = state.canSubmitToken,
                onClick = viewModel::submitToken,
            )

            Text(
                text = "从抓包里复制 Authorization 的值。",
                style = SettingsType.detail,
                color = colors.inkFaint,
                lineHeight = 22.sp,
            )
        } else {
            OutlinedTextField(
                value = state.phoneInput,
                onValueChange = viewModel::onPhoneChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "手机号",
                        style = SettingsType.placeholder,
                        color = colors.inkFaint,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                shape = RoundedCornerShape(Radii.card),
                colors = settingsFieldColors(),
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
                placeholder = {
                    Text(
                        text = "图形验证码",
                        style = SettingsType.placeholder,
                        color = colors.inkFaint,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(Radii.card),
                colors = settingsFieldColors(),
            )

            if (state.isSmsSent) {
                OutlinedTextField(
                    value = state.smsCodeInput,
                    onValueChange = viewModel::onSmsCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "短信验证码",
                            style = SettingsType.placeholder,
                            color = colors.inkFaint,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(Radii.card),
                    colors = settingsFieldColors(),
                )
            }

            PrimaryButton(
                text = if (state.isSmsSent) "登录" else "获取验证码",
                enabled = if (state.isSmsSent) state.canSubmitSms else state.canSendSms,
                onClick = { if (state.isSmsSent) viewModel.submitSmsCode() else viewModel.sendSmsCode() },
            )

            if (state.isSmsSent) {
                ResendRow(
                    state = state.resendState,
                    seconds = state.resendSeconds,
                    onResend = viewModel::resendSmsCode,
                )
            }        }

        ErrorLine(message = state.errorMessage, onDismiss = viewModel::dismissError)
    }
}

/** The one filled button shape this screen uses, so the two modes cannot drift. */
@Composable
internal fun PrimaryButton(
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
            .height(48.dp),
        shape = RoundedCornerShape(Radii.card),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.heated,
            contentColor = colors.onHeated,
            disabledContainerColor = colors.surfaceSunken,
            disabledContentColor = colors.inkFaint,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ModeTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) colors.water else colors.inkFaint,
        modifier = modifier
            .clip(RoundedCornerShape(Radii.chip))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
    )
}

/**
 * One line of failure, and a way to make it go away.
 *
 * Sits inside the section it belongs to rather than floating over the screen: a
 * snackbar for a form error is a message that leaves before a slow reader has
 * finished it, and this app is read by someone standing in a bathroom.
 */
@Composable
private fun ErrorLine(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message == null) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.xxs)
            .background(colors.danger.copy(alpha = 0.08f), RoundedCornerShape(Radii.chip))
            .padding(start = Spacing.sm, top = Spacing.xxs, bottom = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = SettingsType.detail,
            color = colors.danger,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text("知道了", color = colors.danger)
        }
    }
}

/**
 * The block that goes on the clipboard.
 *
 * Assembled field by field rather than by serialising app state, so that adding
 * a field is a deliberate act. The token is not in here and the uid is not in
 * here; both would make the report itself the leak.
 */
private fun buildReport(
    feedback: String,
    accountName: String,
    host: String,
    lastError: String?,
): String = buildString {
    appendLine("【惠生活去死吧】问题反馈")
    appendLine()
    if (feedback.isNotBlank()) {
        appendLine(feedback.trim())
        appendLine()
    }
    appendLine("---")
    appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("账号: $accountName")
    appendLine("接口: $host")
    appendLine("最近一次错误: ${lastError ?: "无"}")
    append("最近一次被拒: ${LastRefusal.format() ?: "无"}")
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("惠生活去死吧 反馈", text))
}

/** Long enough to read, short enough not to feel stuck. */
private const val ClipboardConfirmationMillis = 1_800L
