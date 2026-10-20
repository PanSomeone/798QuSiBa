package com.killhuishenghuo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import com.killhuishenghuo.ui.theme.Radii
import com.killhuishenghuo.ui.theme.Spacing
import com.killhuishenghuo.ui.theme.colors

/**
 * 「有新版本」那一条。
 *
 * 一句话加一个动作,没有关闭按钮 —— 它只在真的存在新版时出现,而那时用户的下一步
 * 只有一个:去下载。给一个「×」只会让人多点一下,过几天再问一次。
 *
 * 它画在底栏上方,不弹窗、也不占任何屏的结构:自动检查应该是**通知**,不是拦路。
 */
@Composable
fun UpdateBanner(
    latest: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val shape = remember { RoundedCornerShape(Radii.card) }

    Text(
        text = "有新版本 $latest · 点这里更新",
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised)
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        textAlign = TextAlign.Center,
        color = colors.water,
        style = MaterialTheme.typography.labelLarge,
    )
}
