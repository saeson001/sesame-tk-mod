package fansirsqi.xposed.sesame.task.backupSync

import fansirsqi.xposed.sesame.model.ModelFields
import fansirsqi.xposed.sesame.model.ModelGroup
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.StringModelField
import fansirsqi.xposed.sesame.task.ModelTask
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.Log
import kotlinx.coroutines.delay
import java.io.File

/**
 * WebDAV 配置备份同步
 * 将本机配置目录（config/）打包上传到 WebDAV；可拉取恢复
 * 协议移植自芝麻糊SVIP 2.0.6.6 的 backupSync（WebDAV 部分，上游 sesame 亦有同款）
 */
class BackupSync : ModelTask() {

    companion object {
        private const val TAG = "BackupSync"
        const val MODULE_NAME = "配置备份同步"

        @Volatile var instance: BackupSync? = null
    }

    private lateinit var enableUpload: BooleanModelField
    private lateinit var webDavUrl: StringModelField
    private lateinit var webDavUser: StringModelField
    private lateinit var webDavPass: StringModelField

    override fun getName() = MODULE_NAME
    override fun getGroup() = ModelGroup.OTHER
    override fun getIcon() = "Default.png"

    override fun getFields() = ModelFields().apply {
        addField(BooleanModelField("backupUpload", "备份 | 上传配置到 WebDAV", false).also { enableUpload = it })
        addField(StringModelField("backupUrl", "备份 | WebDAV 目录URL", "").also { webDavUrl = it })
        addField(StringModelField("backupUser", "备份 | WebDAV 账号", "").also { webDavUser = it })
        addField(StringModelField("backupPass", "备份 | WebDAV 密码", "").also { webDavPass = it })
    }

    override fun prepare() { instance = this }
    override fun destroy() { instance = null; super.destroy() }

    override suspend fun runSuspend() {
        if (!enableUpload.value) return
        val baseUrl = webDavUrl.value.trim()
        if (baseUrl.isBlank()) {
            Log.record(TAG, "未配置 WebDAV 地址，跳过")
            return
        }
        val configDir = Files.CONFIG_DIR
        if (!configDir.exists()) {
            Log.record(TAG, "配置目录不存在")
            return
        }

        var success = 0
        var fail = 0
        val files = configDir.listFiles() ?: emptyArray()
        for (file in files) {
            if (!file.isFile) continue
            delay(500)
            val target = baseUrl.trimEnd('/') + "/" + file.name
            val content = runCatching { file.readText() }.getOrElse {
                fail++; continue
            }
            val (ok, msg) = BackupSyncClient.put(target, webDavUser.value, webDavPass.value, content)
            if (ok) success++ else {
                fail++
                Log.error(TAG, "上传失败 ${file.name}: $msg")
            }
        }
        Log.record(TAG, "备份完成: 成功 $success, 失败 $fail")
    }

    /** 从 WebDAV 恢复单个配置文件（供 UI 手动触发） */
    fun restoreFile(fileName: String): Boolean {
        val baseUrl = webDavUrl.value.trim()
        if (baseUrl.isBlank()) return false
        val (ok, content) = BackupSyncClient.get(
            baseUrl.trimEnd('/') + "/" + fileName,
            webDavUser.value, webDavPass.value
        )
        if (!ok) {
            Log.error(TAG, "下载失败 $fileName: $content")
            return false
        }
        return runCatching {
            val target = File(Files.CONFIG_DIR, fileName)
            target.writeText(content)
            true
        }.getOrElse { false }
    }
}
