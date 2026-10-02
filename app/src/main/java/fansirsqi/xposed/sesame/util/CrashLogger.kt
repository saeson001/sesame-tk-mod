package fansirsqi.xposed.sesame.util

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志落盘工具
 *
 * 作用：模块无论跑在自身进程还是宿主(支付宝)进程，一旦出现未捕获异常，
 * 都把完整堆栈写到 crash 目录并弹出通知，便于无 adb 环境下排查闪退。
 *
 * 落盘位置（两处都写，互为冗余）：
 * A. Android/media/com.eg.android.AlipayGphone/sesame-TK/crash/   —— 跨进程共享目录
 * B. Android/media/fansirsqi.xposed.sesame/files/crash/          —— 模块自有目录，无需额外权限
 *
 * 设计约束：
 * 1. 只做"记录"，然后把异常原样转交给系统原有的 UncaughtExceptionHandler，
 *    不改变进程原有的崩溃行为（依旧会弹"已停止运行"）。
 * 2. 自身任何异常都必须吞掉，绝不能因为写日志导致二次崩溃。
 */
object CrashLogger {

    private const val TAG = "SesameCrash"
    private const val DIR_NAME = "crash"
    private const val CHANNEL_ID = "sesame_crash"
    private const val NOTIFY_ID = 99991

    private val lock = Any()

    @Volatile
    private var installed = false

    @Volatile
    private var appContext: Context? = null

    /** A 位置：共享主目录 */
    private fun sharedCrashDir(): File? {
        return try {
            val dir = File(Files.MAIN_DIR, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            runCatching {
                dir.setWritable(true, false)
                dir.setReadable(true, false)
                dir.setExecutable(true, false)
            }
            dir
        } catch (_: Throwable) {
            null
        }
    }

    /** B 位置：本进程 App 专属外部目录 */
    private fun privateCrashDir(): File? {
        val ctx = appContext ?: return null
        return try {
            val base = ctx.getExternalFilesDir(DIR_NAME) ?: return null
            if (!base.exists()) base.mkdirs()
            base
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 安装崩溃捕获。
     * @param scene 场景标识：module(模块自身进程) / alipay(宿主进程)
     * @param context 有则用于写自有目录与弹通知
     */
    @Synchronized
    fun install(scene: String, context: Context? = null) {
        if (context != null && appContext == null) {
            appContext = context.applicationContext ?: context
        }
        if (installed) return
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    write(scene, thread, throwable)
                } catch (_: Throwable) {
                    // 绝不二次抛错
                }
                try {
                    previous?.uncaughtException(thread, throwable)
                } catch (_: Throwable) {
                    // 原处理器失效时不处理
                }
            }
            installed = true
        } catch (_: Throwable) {
        }
    }

    /** 更新 Context（宿主 attach 之后补传，用于写自有目录与弹通知） */
    fun attachContext(context: Context?) {
        if (context != null && appContext == null) {
            appContext = context.applicationContext ?: context
        }
    }

    /** 手动记录一段异常（不中断流程） */
    fun log(scene: String, title: String, throwable: Throwable) {
        try {
            write(scene, Thread.currentThread(), throwable, extra = title)
        } catch (_: Throwable) {
        }
    }

    private fun write(scene: String, thread: Thread, throwable: Throwable, extra: String = "") {
        val sb = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val trace = android.util.Log.getStackTraceString(throwable)

        sb.append("======== SESAME-TK CRASH ========\n")
        sb.append("scene   : $scene\n")
        sb.append("time    : $stamp\n")
        sb.append("process : ${safeProcessName()}\n")
        sb.append("thread  : ${thread.name}\n")
        if (extra.isNotEmpty()) sb.append("note    : $extra\n")
        sb.append("device  : ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("exception: ${throwable.javaClass.name}\n")
        sb.append("message : ${throwable.message}\n")
        sb.append("--- top frame ---\n")
        sb.append(throwable.stackTrace.firstOrNull()?.toString() ?: "(none)")
        sb.append("\n--- stacktrace ---\n")
        sb.append(trace)

        var cause: Throwable? = throwable.cause
        var depth = 0
        while (cause != null && depth < 5) {
            sb.append("\n--- caused by: ${cause.javaClass.name}: ${cause.message} ---\n")
            sb.append(android.util.Log.getStackTraceString(cause))
            cause = cause.cause
            depth++
        }
        sb.append("\n=================================\n\n")

        val content = sb.toString()

        // 1) 同步打 logcat，方便 adb 抓取
        try {
            android.util.Log.e(TAG, content)
        } catch (_: Throwable) {
        }

        // 2) 落盘（加锁，避免多进程同时写坏文件）
        synchronized(lock) {
            val name = "crash_${scene}_${System.currentTimeMillis()}.txt"
            writeTo(sharedCrashDir(), name, content)
            writeTo(privateCrashDir(), name, content)
            // 最易取的一处：下载目录，任何文件管理器/连电脑直接可见
            writeToDownloads(appContext, name, content)
        }

        // 3) 通知栏提示，让用户在不开电脑的情况下也能看到异常类型
        notifyCrash(scene, throwable, content)
    }

    private fun writeTo(dir: File?, fileName: String, content: String) {
        try {
            if (dir == null) return
            val file = File(dir, fileName)
            FileOutputStream(file, true).use { it.write(content.toByteArray()) }
            runCatching {
                file.setReadable(true, false)
                file.setWritable(true, false)
            }
            // 追加到汇总文件，便于一次性查看最近几次
            val summary = File(dir, "latest.txt")
            FileOutputStream(summary, true).use { it.write(content.toByteArray()) }
            runCatching { summary.setReadable(true, false) }
        } catch (_: Throwable) {
        }
    }

    /**
     * 写入「下载/sesame-TK-crash/」。
     * Android 10+ 走 MediaStore，不需要任何存储权限，且在任何文件管理器和连电脑时都可见，
     * 这是用户最容易拿到日志的位置。
     */
    private fun writeToDownloads(ctx: Context?, fileName: String, content: String) {
        try {
            if (ctx == null) return
            val subDir = "sesame-TK-crash"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + subDir)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    subDir
                )
                if (!dir.exists()) dir.mkdirs()
                writeTo(dir, fileName, content)
            }
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("NotificationPermission")
    private fun notifyCrash(scene: String, throwable: Throwable, content: String) {
        val ctx = appContext ?: return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Sesame 崩溃日志", NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            val top = throwable.stackTrace.firstOrNull()?.toString() ?: "(none)"
            val pending = android.app.PendingIntent.getActivity(
                ctx,
                0,
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Sesame-TK 崩溃 [$scene]: ${throwable.javaClass.simpleName}")
                .setContentText(top)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content.take(3000)))
                .setContentIntent(pending)
                .setAutoCancel(true)
            nm.notify(NOTIFY_ID, builder.build())
        } catch (_: Throwable) {
        }
    }

    private fun safeProcessName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName() ?: "unknown"
            } else {
                "unknown"
            }
        } catch (_: Throwable) {
            "unknown"
        }
    }
}
