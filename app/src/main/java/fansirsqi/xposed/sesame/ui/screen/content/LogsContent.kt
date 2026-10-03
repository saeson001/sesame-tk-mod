package fansirsqi.xposed.sesame.ui.screen.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.rounded.Agriculture
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Forest
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Sports
import androidx.compose.material.icons.rounded.Water
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import fansirsqi.xposed.sesame.ui.MainActivity
import fansirsqi.xposed.sesame.ui.screen.components.MenuButton

/** 日志分类项：显示名 + 图标 + 对应的事件 */
private data class LogCategory(
    val label: String,
    val icon: ImageVector,
    val event: MainActivity.MainUiEvent
)

/**
 * 日志中心
 * 顶部为分类下拉菜单（高度约两行），选中后打开对应日志文件
 */
@Composable
fun LogsContent(
    onEvent: (MainActivity.MainUiEvent) -> Unit
) {
    val categories = remember {
        listOf(
            LogCategory("全部日志", Icons.Rounded.Description, MainActivity.MainUiEvent.OpenAllLog),
            LogCategory("错误日志", Icons.Rounded.BugReport, MainActivity.MainUiEvent.OpenErrorLog),
            LogCategory("森林日志", Icons.Rounded.Forest, MainActivity.MainUiEvent.OpenForestLog),
            LogCategory("农场日志", Icons.Rounded.Agriculture, MainActivity.MainUiEvent.OpenFarmLog),
            LogCategory("海洋日志", Icons.Rounded.Water, MainActivity.MainUiEvent.OpenOtherLog),
            LogCategory("运动日志", Icons.Rounded.Sports, MainActivity.MainUiEvent.OpenOtherLog),
            LogCategory("其他日志", Icons.Rounded.History, MainActivity.MainUiEvent.OpenOtherLog),
            LogCategory("抓包日志", Icons.Rounded.History, MainActivity.MainUiEvent.OpenCaptureLog)
        )
    }

    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(categories.first()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        // ===== 分类下拉菜单（高度约两行）=====
        Box(modifier = Modifier.fillMaxWidth()) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                categories.forEach { cat ->
                    val active = selected.label == cat.label
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (active) "✓ ${cat.label}" else cat.label,
                                color = if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Bold
                                             else androidx.compose.ui.text.font.FontWeight.Normal
                            )
                        },
                        leadingIcon = {
                            Icon(
                                cat.icon,
                                contentDescription = null,
                                tint = if (active) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            expanded = false
                            selected = cat
                            onEvent(cat.event)
                        }
                    )
                }
            }

            // 下拉栏主体（约两行高度）
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        selected.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selected.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "点击切换日志分类",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = "展开",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 当前分类的打开按钮
        MenuButton(
            text = "打开${selected.label}",
            icon = selected.icon,
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            onEvent(selected.event)
        }
    }
}
