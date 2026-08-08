package com.muxuechat.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    modelLabel: String,
    subtitle: String?,
    isRpActive: Boolean,
    onSelectModel: () -> Unit,
    onMenu: () -> Unit,
    onOpenRp: () -> Unit,
    onOpenContext: () -> Unit,
    onOpenSettings: () -> Unit,
    contextUsagePercent: Int? = null,
) {
    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // 整个标题区都是模型选择入口，保证触控目标足够大。
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onSelectModel)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onMenu) {
                Icon(
                    imageVector = Icons.Outlined.Menu,
                    contentDescription = "会话列表",
                )
            }
        },
        actions = {
            IconButton(onClick = onOpenContext) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.DataUsage,
                        contentDescription = contextUsagePercent
                            ?.let { "上下文占用 $it%" }
                            ?: "上下文",
                        // 接近或超过预算时变红，用户不点开也能看到警示。
                        tint = if (contextUsagePercent != null && contextUsagePercent >= 90) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            IconButton(onClick = onOpenRp) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.TheaterComedy,
                        contentDescription = if (isRpActive) "角色扮演（已开启）" else "角色扮演",
                        tint = if (isRpActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isRpActive) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .offset(x = 8.dp, y = (-8).dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "设置",
                )
            }
        },
    )
}
