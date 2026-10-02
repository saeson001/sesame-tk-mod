package fansirsqi.xposed.sesame.ui.screen

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.RpcCapEntry
import fansirsqi.xposed.sesame.util.RpcCapGroup
import fansirsqi.xposed.sesame.util.RpcCapParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "RpcCaptureScreen"

private data class CapState(
    val file: File,
    val exists: Boolean,
    val sizeKb: Long,
    val entries: List<RpcCapEntry>,
    val groups: List<RpcCapGroup>
)

/**
 * 抓包分析界面。
 *
 * 用途：在手机上直接查看支付宝 RPC 抓包结果，挑出想要的接口，
 * 然后导出成文本文件交给电脑侧的 tk_tools.py 生成任务代码。
 *
 * 说明：Android 上没法在运行时编译 Kotlin，所以「生成任务」这一步
 * 必须回到电脑编译 APK，本界面只负责「看清楚 + 导出来」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpcCaptureScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var reloadKey by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var expandedMethod by remember { mutableStateOf<String?>(null) }

    val state = produceState<CapState?>(initialValue = null, key1 = reloadKey) {
        value = withContext(Dispatchers.IO) { loadCap() }
    }

    fun doExport(entries: List<RpcCapEntry>, tag: String) {
        val uri: Uri? = RpcCapParser.export(context, entries, tag)
        if (uri != null) {
            runCatching {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, "发送抓包导出文件"))
            }.onFailure { Log.printStackTrace(TAG, it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("抓包分析") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    val s = state.value
                    if (s != null && s.entries.isNotEmpty()) {
                        IconButton(onClick = { doExport(s.entries, "all") }) {
                            Icon(Icons.Filled.Share, contentDescription = "导出全部")
                        }
                    }
                }
            )
        }
    ) { padding ->

        val s = state.value
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {

            // 概览
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (s.exists) "抓到 ${s.entries.size} 条记录 · ${s.groups.size} 个接口 · ${s.sizeKb} KB"
                        else "还没有抓包文件",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        s.file.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!s.exists) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "先在支付宝里逛一遍想做的页面，\n再回来点右上角刷新。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                return@Column
            }

            // 搜索
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索接口名，如 sports / member / sign") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )

            val filtered = remember(s.groups, query) {
                if (query.isBlank()) s.groups
                else s.groups.filter { it.method.contains(query.trim(), ignoreCase = true) }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (filtered.isEmpty()) {
                    item { Text("没有匹配的接口", color = MaterialTheme.colorScheme.outline) }
                }
                items(filtered, key = { it.method }) { g ->
                    RpcGroupCard(
                        group = g,
                        expanded = expandedMethod == g.method,
                        onToggle = {
                            expandedMethod = if (expandedMethod == g.method) null else g.method
                        },
                        onExport = { doExport(g.entries, safeTag(g.method)) }
                    )
                }
            }
        }
    }
}

private fun safeTag(method: String): String {
    val last = method.substringAfterLast('.')
    return last.filter { it.isLetterOrDigit() || it == '_' }.take(24).ifEmpty { "rpc" }
}

@Composable
private fun RpcGroupCard(
    group: RpcCapGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onExport: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().selectable(selected = expanded, onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(12.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        group.method,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${group.latest.channel} · ${group.count} 次 · 最近 ${group.latest.time}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("▾", color = MaterialTheme.colorScheme.outline)
            }

            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                val req = group.entries.lastOrNull { it.isRequest }
                val res = group.entries.lastOrNull { !it.isRequest }

                if (req != null) {
                    Text("请求", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    PayloadBlock(RpcCapParser.pretty(req.payload))
                    Spacer(Modifier.height(8.dp))
                }
                if (res != null) {
                    Text("响应", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    PayloadBlock(RpcCapParser.pretty(res.payload))
                    Spacer(Modifier.height(8.dp))
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onExport) { Text("导出这条") }
                }
            }
        }
    }
}

@Composable
private fun PayloadBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        val scroll = rememberScrollState()
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(scroll)
                    .padding(8.dp)
            )
        }
    }
}

private fun loadCap(): CapState {
    val file = RpcCapParser.currentFile()
    val exists = file.exists() && file.length() > 0
    val entries = if (exists) RpcCapParser.parse(file) else emptyList()
    return CapState(
        file = file,
        exists = exists,
        sizeKb = if (exists) file.length() / 1024 else 0,
        entries = entries,
        groups = RpcCapParser.group(entries)
    )
}
