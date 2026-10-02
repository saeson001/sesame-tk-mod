package fansirsqi.xposed.sesame.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import fansirsqi.xposed.sesame.SesameApplication.Companion.PREFERENCES_KEY
import fansirsqi.xposed.sesame.SesameApplication.Companion.hasPermissions
import fansirsqi.xposed.sesame.hook.internal.RpcCaptureHelper
import fansirsqi.xposed.sesame.ui.extension.openUrl
import fansirsqi.xposed.sesame.ui.extension.performNavigationToSettings
import fansirsqi.xposed.sesame.ui.screen.MainScreen
import fansirsqi.xposed.sesame.ui.theme.AppTheme
import fansirsqi.xposed.sesame.ui.theme.ThemeManager
import fansirsqi.xposed.sesame.ui.viewmodel.MainViewModel
import fansirsqi.xposed.sesame.util.CommandUtil
import fansirsqi.xposed.sesame.util.Detector
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.IconManager
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.PermissionUtil
import fansirsqi.xposed.sesame.util.Probe
import fansirsqi.xposed.sesame.util.ToastUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** 仅用于探针：标记 Compose 首帧是否已经进入 */
    @Volatile
    private var composeEntered = false

    // Shizuku 监听器
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1234) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ToastUtil.showToast(this, "Shizuku 授权成功！")

                // 关键修改：
                lifecycleScope.launch {
                    CommandUtil.executeCommand(this@MainActivity, "echo init_shizuku")
                    delay(200)
                    viewModel.refreshDeviceInfo(this@MainActivity)
                }
            } else {
                ToastUtil.showToast(this, "Shizuku 授权被拒绝")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Probe.step(10, "activity-create-start")

        // 2. 检查权限并初始化逻辑
        hasPermissions = PermissionUtil.checkOrRequestFilePermissions(this)
        Probe.step(11, "permission-checked", "hasPermissions=$hasPermissions")
        if (hasPermissions) {
            runCatching { viewModel.initAppLogic() }
                .onFailure { Probe.step(90, "viewmodel-init-FAILED", it.stackTraceToString().take(500)) }
            Probe.step(12, "viewmodel-init-done")
            runCatching { initNativeDetector() }
                .onFailure { Probe.step(91, "native-detector-FAILED", it.stackTraceToString().take(500)) }
            Probe.step(13, "native-detector-done")
        }

        // 3. 初始化 Shizuku
        runCatching { setupShizuku() }
        Probe.step(14, "shizuku-done")

        // 4. 同步图标状态
        runCatching {
            val prefs = getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE)
            IconManager.syncIconState(this, prefs.getBoolean("is_icon_hidden", false))
        }
        Probe.step(15, "icon-sync-done")

        // 5. 设置 Compose 内容
        Probe.step(16, "before-setcontent")
        setContent {
            // 收集 ViewModel 状态
            val oneWord by viewModel.oneWord.collectAsStateWithLifecycle()
            val activeUser by viewModel.activeUser.collectAsStateWithLifecycle()
            val moduleStatus by viewModel.moduleStatus.collectAsStateWithLifecycle()
            //  获取实时的 UserEntity 列表
            val userList by viewModel.userList.collectAsStateWithLifecycle()
            // 使用 derivedStateOf 优化性能，只在 userList 变化时重新映射
            val uidList by remember {
                derivedStateOf { userList.map { it.userId } }
            }
            val isDynamicColor by ThemeManager.isDynamicColor.collectAsStateWithLifecycle()

            // AppTheme 会处理状态栏颜色
            AppTheme(dynamicColor = isDynamicColor) {
                if (!composeEntered) {
                    composeEntered = true
                    Probe.step(17, "compose-first-composition")
                }
                MainScreen(
                    oneWord = oneWord,
                    activeUserName = activeUser?.showName ?: "未载入",
                    moduleStatus = moduleStatus,
                    viewModel = viewModel,
                    isDynamicColor = isDynamicColor, // 传给 MainScreen
                    // 传入回调
                    userList = userList, // 传入列表
                    // 🔥 处理跳转逻辑
                    onNavigateToSettings = { selectedUser ->
                        performNavigationToSettings(selectedUser)
                    },
                    onEvent = { event -> handleEvent(event) }
                )
            }
        }
    }

    // 在 Activity 中执行 Native 检测
    private fun initNativeDetector() {
        // ⚠️⚠️ 不要恢复 Detector.init() 的调用！！！见下方说明
        //
        // 崩溃现场（2026-10-02 抓到的 native tombstone）：
        //   signal 6 (SIGABRT), pid=tid=主线程, >>> fansirsqi.xposed.sesame <<<
        //   Abort message: JNI DETECTED ERROR IN APPLICATION: JNI FindClass called with
        //     pending exception java.lang.SecurityException:
        //     MATCH_ANY_USER flag requires INTERACT_ACROSS_USERS permission
        //   backtrace:
        //     #10 libchecker.so (_JNIEnv::FindClass)
        //     #11 libchecker.so (checkForBannedApps)
        //     #12 libchecker.so (Java_fansirsqi_xposed_sesame_util_Detector_init)
        //
        // 原理：libchecker.so 内部用 JNI 反射调用 PackageManager.getApplicationInfo。
        // 该方法被 Xposed 模块 hook 后参数里带上了 MATCH_ANY_USER，system_server 直接抛
        // SecurityException；而 so 内部**没有做 ExceptionClear**，异常一直处于 pending 状态，
        // 紧接着再调 FindClass 时 CheckJNI 检测到 pending exception → JniAbort → SIGABRT。
        //
        // 关键点：这是 JNI 层的 abort，**Java 层的 try/catch 完全拦不住**
        // （异常从未被抛到 Java 层，进程直接从 native 崩掉），也不会有"已停止运行"对话框，
        // 表现就是"打开就闪退回桌面，什么都没有"。唯一可靠的办法就是不调用它。
        //
        // 这也解释了为什么"没开 LSPosed 开关时不闪退"：不开开关时 so 认为环境未就绪，
        // 不会走到 checkForBannedApps 这条分支。
        val enabled = getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE)
            .getBoolean("enable_native_detector", false)
        if (!enabled) {
            Probe.step(13, "native-detector-skipped", "默认关闭，避免 libchecker.so 的 JNI abort")
            return
        }
        try {
            if (Detector.loadLibrary("checker")) {
                Detector.initDetector(this)
                Probe.step(13, "native-detector-forced-on")
            }
        } catch (e: Exception) {
            Log.error("MainActivity", "Native detector init failed: ${e.message}")
        }
    }

    /**
     * 定义 UI 事件
     */
    sealed class MainUiEvent {
        data object RefreshOneWord : MainUiEvent()
        data object OpenForestLog : MainUiEvent()
        data object OpenFarmLog : MainUiEvent()
        data object OpenGithub : MainUiEvent()
        data object OpenErrorLog : MainUiEvent()
        data object OpenOtherLog : MainUiEvent()
        data object OpenAllLog : MainUiEvent()
        data object OpenDebugLog : MainUiEvent()
        data class ToggleIconHidden(val isHidden: Boolean) : MainUiEvent()
        data object OpenCaptureLog : MainUiEvent()
        data object OpenExtend : MainUiEvent()
        data object ClearConfig : MainUiEvent()
    }

    /**
     * 统一处理事件
     */
    private fun handleEvent(event: MainUiEvent) {
        when (event) {
            MainUiEvent.RefreshOneWord -> viewModel.fetchOneWord()
            MainUiEvent.OpenForestLog -> openLogFile(Files.getForestLogFile())
            MainUiEvent.OpenFarmLog -> openLogFile(Files.getFarmLogFile())
            MainUiEvent.OpenOtherLog -> openLogFile(Files.getOtherLogFile())
            MainUiEvent.OpenGithub -> openUrl("https://github.com/Fansirsqi/Sesame-TK")
            MainUiEvent.OpenErrorLog -> openLogFile(Files.getErrorLogFile())
            MainUiEvent.OpenAllLog -> openLogFile(Files.getRecordLogFile())
            MainUiEvent.OpenDebugLog -> openLogFile(Files.getDebugLogFile())
            is MainUiEvent.ToggleIconHidden -> {
                val shouldHide = event.isHidden
                getSharedPreferences(PREFERENCES_KEY, MODE_PRIVATE).edit { putBoolean("is_icon_hidden", shouldHide) }
                viewModel.syncIconState(shouldHide)
                Toast.makeText(this, "设置已保存，可能需要重启桌面才能生效", Toast.LENGTH_SHORT).show()
            }

            // 抓包日志：优先打开 hook 侧实时写的 rpc_cap.txt，没有再回落 capture.log
            MainUiEvent.OpenCaptureLog -> {
                val cap = RpcCaptureHelper.captureFile()
                openLogFile(if (cap.exists()) cap else Files.getCaptureLogFile())
            }
            MainUiEvent.OpenExtend -> startActivity(Intent(this, _root_ide_package_.fansirsqi.xposed.sesame.ui.ExtendActivity::class.java))
            MainUiEvent.ClearConfig -> {
                // 🔥 这里只负责执行逻辑，不再负责弹窗
                if (Files.delFile(Files.CONFIG_DIR)) {
                    ToastUtil.showToast(this, "🙂 清空配置成功")
                    // 可选：重载配置或刷新 UI
                    viewModel.refreshUserConfigs()
                } else {
                    ToastUtil.showToast(this, "😭 清空配置失败")
                }
            }
        }
    }

    // --- 辅助方法 (替代 BaseActivity) ---

    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        if (Shizuku.pingBinder() && checkSelfPermission(ShizukuProvider.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1234)
        }
    }

    override fun onResume() {
        super.onResume()
        Probe.step(18, "activity-resume-start")
        runCatching { if (hasPermissions) viewModel.refreshUserConfigs() }
            .onFailure { Probe.step(92, "refresh-configs-FAILED", it.stackTraceToString().take(500)) }
        Probe.step(19, "activity-resume-end")
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
    }

    private fun openLogFile(logFile: File) {
        if (!logFile.exists()) {
            ToastUtil.showToast(this, "日志文件不存在: ${logFile.name}")
            return
        }
        val intent = Intent(this, LogViewerActivity::class.java).apply {
            data = logFile.toUri()
        }
        startActivity(intent)
    }
}
