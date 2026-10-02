package fansirsqi.xposed.sesame.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import fansirsqi.xposed.sesame.hook.internal.RpcCaptureHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条抓包记录。
 *
 * 抓包文件（rpc_cap.txt）的写入格式由 [fansirsqi.xposed.sesame.hook.internal.RpcCaptureHelper] 产生：
 *
 * ```
 * [17:20:11.003] NEW_REQ com.alipay.xxx.yyy
 *   {"sceneId":"A"}
 * [17:20:11.208] NEW_RES com.alipay.xxx.yyy
 *   {"success":true,...}
 * ```
 *
 * 这里的解析规则必须和电脑侧工具 `tools/tk_tools.py` 里的 `LINE` 正则保持一致，
 * 否则「手机导出 → WB 分析」这条链路会对不上。
 */
data class RpcCapEntry(
    val time: String,
    val kind: String,          // NEW_REQ / NEW_RES / OLD_REQ / OLD_RES
    val method: String,
    val payload: String
) {
    val isRequest: Boolean get() = kind.endsWith("_REQ")
    val channel: String get() = if (kind.startsWith("NEW")) "新版" else "旧版"
}

/** 按方法名聚合后的一组记录 */
data class RpcCapGroup(
    val method: String,
    val entries: List<RpcCapEntry>
) {
    val count: Int get() = entries.size
    val latest: RpcCapEntry get() = entries.last()
}

object RpcCapParser {

    private const val TAG = "RpcCapParser"

    private val LINE = Regex("""^\[([\d:.]+)]\s+(NEW_REQ|NEW_RES|OLD_REQ|OLD_RES)\s+(\S+)\s*$""")

    /** 抓包文件可能存在的几个位置，按优先级排列 */
    fun candidateFiles(): List<File> {
        val out = LinkedHashMap<String, File>()
        val primary = RpcCaptureHelper.captureFile()
        out[primary.absolutePath] = primary
        // 兜底：万一 MAIN_DIR 指向的不是支付宝目录，再按包名拼一次
        val alipay = File(
            Environment.getExternalStorageDirectory(),
            "Android/media/com.eg.android.AlipayGphone/sesame-TK/log/rpc_cap.txt"
        )
        out[alipay.absolutePath] = alipay
        return out.values.filter { it.exists() && it.length() > 0 }
    }

    /** 当前实际在用的抓包文件（不存在时返回主路径，方便提示用户） */
    fun currentFile(): File = candidateFiles().firstOrNull() ?: RpcCaptureHelper.captureFile()

    /** 解析成记录列表 */
    fun parse(file: File): List<RpcCapEntry> {
        val rows = ArrayList<RpcCapEntry>()
        var cur: RpcCapEntry? = null
        val buf = ArrayList<String>()
        try {
            file.bufferedReader(charset = Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val m = LINE.matchEntire(raw.trim())
                    if (m != null) {
                        flush(rows, cur, buf)
                        cur = RpcCapEntry(m.groupValues[1], m.groupValues[2], m.groupValues[3], "")
                        buf.clear()
                    } else {
                        buf.add(raw.trim())
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
        }
        flush(rows, cur, buf)
        return rows
    }

    private fun flush(rows: MutableList<RpcCapEntry>, cur: RpcCapEntry?, buf: List<String>) {
        if (cur == null) return
        val payload = buf.joinToString("\n").trim()
        rows.add(cur.copy(payload = payload))
    }

    /** 按方法名聚合，保持首次出现顺序 */
    fun group(entries: List<RpcCapEntry>): List<RpcCapGroup> {
        val map = LinkedHashMap<String, MutableList<RpcCapEntry>>()
        for (e in entries) map.getOrPut(e.method) { ArrayList() }.add(e)
        return map.map { (method, list) -> RpcCapGroup(method, list) }
    }

    /**
     * 把 payload 尽量格式化成人类可读的 JSON。
     * 不是合法 JSON 时原样返回，绝不抛异常。
     */
    fun pretty(payload: String): String {
        val s = payload.trim()
        if (s.isEmpty()) return "(空)"
        val trimmed = s.trimEnd('.', '…')
        return try {
            when {
                s.startsWith("{") -> JSONObject(s).toString(4)
                s.startsWith("[") -> JSONArray(s).toString(4)
                else -> s
            }
        } catch (_: Throwable) {
            trimmed
        }
    }

    /**
     * 导出若干条记录到「下载/sesame-TK-rpc」目录。
     *
     * 走 MediaStore 写入，不需要任何存储权限，任何文件管理器都能看到，
     * 也可以直接通过返回的 Uri 调起系统分享发给电脑。
     *
     * 导出格式与原始 rpc_cap.txt 完全一致，这样电脑侧 tk_tools.py 无需改动即可解析。
     */
    fun export(context: Context, entries: List<RpcCapEntry>, tag: String): Uri? {
        if (entries.isEmpty()) {
            Toast.makeText(context, "没有可导出的记录", Toast.LENGTH_SHORT).show()
            return null
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val fileName = "rpc_export_${tag}_$stamp.txt"
        val body = buildString {
            append("# Sesame-TK RPC 抓包导出\n")
            append("# 时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            append("# 条数: ${entries.size}\n")
            append("# 用法: python tk_tools.py list <本文件>\n\n")
            for (e in entries) {
                append("[${e.time}] ${e.kind} ${e.method}\n")
                if (e.payload.isNotEmpty()) append("  ${e.payload.replace("\n", "\n  ")}\n")
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/sesame-TK-rpc")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Toast.makeText(context, "导出失败：无法创建文件", Toast.LENGTH_SHORT).show()
                    return null
                }
                resolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Toast.makeText(context, "已导出到 下载/sesame-TK-rpc/$fileName", Toast.LENGTH_LONG).show()
                uri
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "sesame-TK-rpc"
                )
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                f.writeText(body, Charsets.UTF_8)
                Toast.makeText(context, "已导出到 ${f.absolutePath}", Toast.LENGTH_LONG).show()
                Uri.fromFile(f)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
            Toast.makeText(context, "导出失败: ${t.message}", Toast.LENGTH_LONG).show()
            null
        }
    }
}
