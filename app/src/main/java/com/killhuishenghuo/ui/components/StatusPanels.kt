package com.killhuishenghuo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.killhuishenghuo.ui.theme.colors
import com.killhuishenghuo.ui.theme.Radii
import com.killhuishenghuo.ui.theme.Spacing

/**
 * Full-width failure panel with the one action that can help. Used for a failed
 * device load, where there is nothing else to show.
 *
 * [retryLabel] exists because not every failure is fixed by asking again: a dead
 * token is fixed by pasting a new one, and a button that says "重新加载" in front
 * of that errand promises the wrong thing.
 */
@Composable
fun ErrorPanel(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "重新加载",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.danger.copy(alpha = 0.08f), RoundedCornerShape(Radii.card))
            .border(1.dp, colors.danger.copy(alpha = 0.3f), RoundedCornerShape(Radii.card))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = colors.danger,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.danger,
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
        TextButton(onClick = onRetry) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(retryLabel)
        }
    }
}

/** Placeholder card, so the list does not jump when real data arrives. */
@Composable
fun LoadingCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .background(colors.surfaceRaised, RoundedCornerShape(Radii.card))
            .border(1.dp, colors.hairline, RoundedCornerShape(Radii.card)),
    )
}
