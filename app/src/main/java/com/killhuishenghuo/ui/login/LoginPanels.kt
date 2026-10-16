package com.killhuishenghuo.ui.login

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.killhuishenghuo.data.repository.CaptchaChallenge
import com.killhuishenghuo.ui.theme.colors
import com.killhuishenghuo.ui.theme.Radii
import com.killhuishenghuo.ui.theme.SettingsType
import com.killhuishenghuo.ui.theme.Spacing

/**
 * The captcha picture plus a way to replace it.
 *
 * Decoded straight from the bytes the gateway returned: `BitmapFactory` reads
 * the JPEG in-process, so there is no Android resource plumbing and no disk
 * cache for a picture that is valid for exactly one attempt.
 *
 * [CaptchaChallenge.jpegBytes] is a `ByteArray`, whose `equals` is identity —
 * which is why the decode is keyed on the challenge object (whose equality *is*
 * by content) rather than on the array.
 *
 * While [isLoading] the previous picture stays visible, dimmed: blanking it
 * would make a refresh on a slow connection look like a failure.
 */
@Composable
fun CaptchaBox(
    captcha: CaptchaChallenge?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bitmap = remember(captcha) {
            captcha?.jpegBytes?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            // The frame is sized to the picture instead of the picture being
            // stretched to the frame. The gateway returns a wide, short JPEG
            // (1013 bytes, roughly 1.9:1), so `fillMaxWidth` inside a fixed 52dp
            // row letterboxed it into two solid black bands — a frame that looked
            // broken for a reason that had nothing to do with the image.
            // The border therefore lives on the image's own box.
            Box(
                modifier = Modifier
                    .background(colors.surfaceSunken, RoundedCornerShape(Radii.chip))
                    .border(1.dp, colors.hairline, RoundedCornerShape(Radii.chip))
                    .clickable(enabled = !isLoading && bitmap != null, onClick = onRefresh)
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    bitmap != null -> Image(
                        bitmap = bitmap,
                        contentDescription = "图形验证码，点一下换一张",
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                        alpha = if (isLoading) 0.35f else 1f,
                        // A fixed height with a width derived from the bitmap's own
                        // aspect ratio: `Fit` alone cannot shrink a box that was
                        // already told to be as wide as its parent.
                        modifier = Modifier
                            .height(CaptchaImageHeight)
                            .width(CaptchaImageHeight * bitmap.width / bitmap.height),
                    )

                    isLoading -> CircularProgressIndicator(
                        modifier = Modifier
                            .size(52.dp)
                            .padding(Spacing.sm),
                        color = colors.water,
                        strokeWidth = 2.dp,
                    )

                    else -> Text(
                        text = "验证码没加载出来，点这里重试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkFaint,
                    )
                }
            }
        }

        IconButton(onClick = onRefresh, enabled = !isLoading) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "换一张验证码",
                tint = if (isLoading) colors.inkFaint else colors.water,
            )
        }
    }
}

/**
 * Height of the captcha picture itself, inside the 52dp row.
 *
 * The row's height minus the frame's own vertical padding, so the picture fills
 * the frame rather than floating inside it.
 */
private val CaptchaImageHeight = 40.dp

/**
 * The second chance at a code, counted down out loud.
 *
 * The countdown is on screen rather than implied by a disabled button. A greyed
 * out "重新发送" that goes live at some unknown moment invites the user to sit
 * and tap at it; a number sends them to look at the SMS app instead, which is
 * where the thing they actually want already is.
 *
 * It lives here because both sign-in forms need it, and the bug it prevents is
 * the same in both: once a code has been sent, the big button stops being a way
 * to get another one. A screen without this row has no way back to the send
 * button short of signing out.
 *
 * **[ResendState.NEEDS_INPUT] is the state this row used to get wrong.** At the
 * end of the cooldown the ViewModel fetches a fresh captcha and clears the digits
 * that belonged to the spent one, so a resend is not yet possible — and the row
 * only knew "counting down" and "ready", so it fell into the countdown branch and
 * rendered `0 秒后可重新发送` under no button, permanently. Reported from a phone
 * as "重发验证码没有成功刷新". The wait being over is now its own sentence, and
 * that sentence says what is actually missing.
 */
@Composable
fun ResendRow(
    state: ResendState,
    seconds: Int,
    onResend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            ResendState.READY -> TextButton(onClick = onResend) {
                Text("重新发送", color = colors.water)
            }

            ResendState.COUNTING_DOWN -> ResendHint("$seconds 秒后可重新发送")

            // One sentence for every reason the send is not ready — a cleared
            // captcha, a captcha that failed to load, a phone number edited back
            // down — because the fix is the same for all of them and naming only
            // one would be wrong the rest of the time.
            ResendState.NEEDS_INPUT -> ResendHint("填好手机号与验证码后可重新发送")
        }
    }
}

/** The row's quiet half: a sentence instead of a button. */
@Composable
private fun ResendHint(text: String) {
    Text(
        text = text,
        style = SettingsType.detail,
        color = colors.inkFaint,
        modifier = Modifier.padding(vertical = Spacing.sm, horizontal = Spacing.sm),
    )
}

/**
 * The honesty panel.
 *
 * This build is a third-party client of a service it does not own, and the
 * sign-in path it recommends works only because the official app exists and is
 * already signed in. Saying that on the screen — rather than in a README the
 * user never opens — is the difference between a tool and a trap.
 */
@Composable
fun DisclaimerPanel(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.warm.copy(alpha = 0.08f), RoundedCornerShape(Radii.card))
            .border(1.dp, colors.warm.copy(alpha = 0.28f), RoundedCornerShape(Radii.card))
            .padding(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.Build,
            contentDescription = null,
            tint = colors.warm,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(15.dp),
        )
        Spacer(Modifier.width(Spacing.xs))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "自用学习工具，不对外发布",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.warm,
            )
            Text(
                text = "它只调用官方 App 已经在用的接口，不做任何绕过；唯一的登录方式是复用官方 App 的登录状态。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )
        }
    }
}

/**
 * Shown under the token field: how to get a token in the first place.
 *
 * Deliberately describes the *official* app's own storage rather than offering
 * to extract it. This client does not read another app's private data — that
 * would need root and would be a different program entirely.
 */
@Composable
fun TokenHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(
            text = "token 从哪来",
            style = MaterialTheme.typography.titleSmall,
            color = colors.inkMuted,
        )
        Text(
            text = "官方 App 登录后在它自己的网络请求里带着这个 token。用抓包工具（比如 HttpCanary / mitmproxy）看一条 i.ilife798.com 的请求，" +
                "把 Authorization 请求头的值整段复制过来即可。本应用不会、也无法自己去读别的应用的私有数据。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkFaint,
            lineHeight = 19.sp,
        )
    }
}
