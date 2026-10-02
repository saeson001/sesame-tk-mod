package fansirsqi.xposed.sesame

import android.app.Application
import android.content.Context
import android.content.Intent
import fansirsqi.xposed.sesame.ui.repository.ConfigRepository
import fansirsqi.xposed.sesame.service.CommandService
import fansirsqi.xposed.sesame.ui.theme.ThemeManager
import fansirsqi.xposed.sesame.util.CrashLogger
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.Probe
import fansirsqi.xposed.sesame.util.ToastUtil

/**
 * 芝麻粒应用主类
 *
 * 负责应用初始化
 */
class SesameApplication : Application() {

    companion object {
        private const val TAG = "SesameApplication"
        public const val PREFERENCES_KEY = "sesame-tk"
        var hasPermissions: Boolean = false

    }

    /**
     * 应用进程中最早能执行代码的位置：早于 ContentProvider 安装、早于 onCreate。
     * 崩溃捕获必须放在这里，否则 ShizukuProvider 之类在 onCreate 之前崩的话根本记录不到。
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        try {
            Probe.attach(base)
            Probe.step(1, "app-attach-base", "attachBaseContext 已进入")
        } catch (_: Throwable) {
        }
        try {
            CrashLogger.install("module", base)
        } catch (_: Throwable) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            Probe.attach(this)
            Probe.step(2, "app-create-start")
        } catch (_: Throwable) {
        }
        // 保险起见再装一次（幂等），顺便补上 Context
        try { CrashLogger.install("module", this) } catch (_: Throwable) {}
        try {
            ToastUtil.init(this) // 初始化全局 Context
            Probe.step(3, "toast-init-ok")
            Log.init(this)
            Probe.step(4, "log-init-ok")
            ThemeManager.init(this)
            Probe.step(5, "theme-init-ok")
            ConfigRepository.init(this, PREFERENCES_KEY)
            Probe.step(6, "config-repo-init-ok")
        } catch (t: Throwable) {
            Probe.step(7, "app-create-FAILED", t.javaClass.name + ": " + t.message)
            CrashLogger.log("module", "Application.onCreate 失败", t)
        }
        runCatching { startCommandService() }
        Probe.step(8, "app-create-end")
    }

    /**
     * 启动 CommandService
     */
    private fun startCommandService() {
        try {
            val intent = Intent(this, CommandService::class.java)
            startService(intent)
            Log.record(TAG, "✅ CommandService 已启动")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "❌ CommandService 启动失败:", e)
        }
    }

}