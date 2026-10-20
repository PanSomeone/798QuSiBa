package com.killhuishenghuo.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.killhuishenghuo.data.notice.Notice
import com.killhuishenghuo.ui.theme.Radii
import com.killhuishenghuo.ui.theme.Spacing
import com.killhuishenghuo.ui.theme.colors

/**
 * 主界面顶部那一条公告。
 *
 * 和 [UpdateBanner] 一样是**一行,不是弹窗**:公告的作用是让人知道一件事,而不是拦住
 * 一次打水。所以它只在真有公告时出现,一句话,点开才看全文。
 *
 * 展开用底部弹层而不是就地撑开:正文长度不可控,就地撑开会把「开始」按钮往下推,
 * 而这个 app 的唯一一件正事就是那个按钮。弹层遮住它一会儿是可以的,把它挤走不行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeBanner(
    notice: Notice,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val shape = remember { RoundedCornerShape(Radii.card) }
    // 按 id 记住展开状态:换了公告,弹层不该自己弹出来。
    var expanded by remember(notice.id) { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised)
            .clickable { expanded = true }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.water),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = notice.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = "查看",
            style = MaterialTheme.typography.labelMedium,
            color = colors.water,
        )
    }

    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            containerColor = colors.surfaceRaised,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.lg),
            ) {
                Text(
                    text = notice.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                )
                if (notice.body.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = notice.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (notice.url.isNotBlank()) {
                        TextButton(onClick = { runCatching { uriHandler.openUri(notice.url) } }) {
                            Text("打开链接", color = colors.water)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            expanded = false
                            onDismiss(notice.id)
                        },
                        shape = RoundedCornerShape(Radii.chip),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.water,
                            contentColor = colors.onWater,
                        ),
                    ) {
                        Text("知道了")
                    }
                }
            }
        }
    }
}