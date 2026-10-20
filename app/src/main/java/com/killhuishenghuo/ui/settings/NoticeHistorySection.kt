package com.killhuishenghuo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.killhuishenghuo.data.notice.Notice
import com.killhuishenghuo.ui.components.NoticeSheet
import com.killhuishenghuo.ui.theme.Spacing
import com.killhuishenghuo.ui.theme.colors

/**
 * 设置里的「历史公告」。
 *
 * 存在的理由很具体:主屏顶端那条公告是可以点掉的,而点掉之后**那句话就找不回来了**
 * ——「刚才说的那个群号是多少来着」。这个模块是那句话的存档。
 *
 * 列表为空时整块不出现,而不是显示「暂无公告」:一块永远空着的区块会让人以为加载
 * 失败或者功能坏了,而空着的意思确实只是"还没发过什么"。
 *
 * 每条都是同样的一行(小圆点 + 标题 + 日期),点开才看全文 —— 用主屏那条公告同一个
 * 弹层,所以两处看到的东西一定一样。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun NoticeHistorySection(
    notices: List<Notice>,
    modifier: Modifier = Modifier,
) {
    if (notices.isEmpty()) return

    var opened by remember { mutableStateOf<Notice?>(null) }

    Section(title = "历史公告", modifier = modifier) {
        notices.forEachIndexed { index, notice ->
            if (index > 0) Hairline()
            NoticeHistoryRow(notice = notice, onClick = { opened = notice })
        }
    }

    // 弹层放在 Section 外面:它是盖在屏幕上的东西,不是列表里的一行。
    val shown = opened
    if (shown != null) {
        NoticeSheet(
            notice = shown,
            onDismissRequest = { opened = null },
            actionLabel = "关闭",
            onAction = { opened = null },
        )
    }
}

/**
 * 一行:圆点、标题(最多两行)、日期。
 *
 * 标题限两行而不是一行省略:公告标题常常是"一句话的结论",截成一行就只剩半句。
 */
@Composable
private fun NoticeHistoryRow(
    notice: Notice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = notice.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!notice.date.isNullOrBlank()) {
                Text(
                    text = notice.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkFaint,
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.inkFaint,
            modifier = Modifier.size(16.dp),
        )
    }
}