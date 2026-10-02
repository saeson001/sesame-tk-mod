package fansirsqi.xposed.sesame.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动探针（Probe）
 *
 * 用途：当闪退**不产生 Java 未捕获异常**时（native crash / ANR 后进程被杀 / Activity 被系统回收），
 * CrashLogger 是拿不到任何东西的。Probe 退一步：在启动链路的关键节点上按序号留痕，
 * 通过「最后一个写出来的 stage 文件」判断进程死在哪一步之后。
 *
 * 留痕位置：
 * A. 下载/sesame-TK-crash/stage_XX_名字.txt   —— 每个节点一个文件，用户用文件管理器即可按序号看
 * B. getExternalFilesDir("stage")/boot.txt     —— 追加写入，免权限，兜底用（文件管理器可能看不到）
 * C. logcat tag = SesameProbe                  —— 有 adb 时最直接
 *
 * 设计原则：任何情况下都不能因为探针自身异常而影响宿主启动流程。
 */
object Probe {

    private const val TAG = "SesameProbe"
    private const val DOWNLOAD_SUBDIR = "sesame-TK-crash"
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context?) {
        if (context != null && appContext == null) {
            appContext = context.applicationContext ?: context
        }
    }

    /**
     * 在启动链路上打一个点。
     * @param order 两位序号，用于文件排序，例如 1、2、10、11
     * @param name  节点名，例如 "app-create-end"
     * @param detail 附加信息（可选）
     */
    fun step(order: Int, name: String, detail: String = "") {
        val label = String.format(Locale.US, "%02d", order)
        val fileName = "stage_${label}_${name}.txt"
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val content = buildString {
            append("======== SESAME-TK PROBE ========\n")
            append("stage   : $label $name\n")
            append("time    : $stamp\n")
            append("process : ${safeProcessName()}\n")
            append("thread  : ${Thread.currentThread().name}\n")
            if (detail.isNotEmpty()) append("detail  : $detail\n")
            append("device  : ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("=================================\n\n")
        }
        try {
            android.util.Log.i(TAG, "STEP $label $name ${if (detail.isEmpty()) "" else "|$detail"}")
        } catch (_: Throwable) {
        }
        synchronized(lock) {
            runCatching { writeToDownloads(fileName, content) }
            runCatching { appendToPrivate(content) }
        }
    }

    /** A：下载目录（MediaStore，无需任何权限，任何文件管理器可见） */
    private fun writeToDownloads(fileName: String, content: String) {
        val ctx = appContext ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + File.separator + DOWNLOAD_SUBDIR
                    )
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    DOWNLOAD_SUBDIR
                )
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                FileOutputStream(f, true).use { it.write(content.toByteArray()) }
                runCatching { f.setReadable(true, false) }
            }
        } catch (_: Throwable) {
        }
    }

    /** B：自身外部私有目录，免权限兜底 */
    private fun appendToPrivate(content: String) {
        val ctx = appContext ?: return
        try {
            val dir = ctx.getExternalFilesDir("stage") ?: return
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "boot.txt")
            FileOutputStream(f, true).use { it.write(content.toByteArray()) }
        } catch (_: Throwable) {
        }
    }

    private fun safeProcessName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.app.Application.getProcessName() ?: "unknown"
            } else {
                "unknown"
            }
        } catch (_: Throwable) {
            "unknown"
        }
    }
}
