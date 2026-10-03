package fansirsqi.xposed.sesame.util

import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField
import org.json.JSONObject
import java.io.File

/**
 * 任务异常追踪器
 *
 * 记录各设置项（modelCode.fieldCode）的执行异常次数，持久化到 config/task_failures.json。
 *  - 连续异常达到阈值(3次) → 自动把该设置项开关置为 false（运行时立即停用）
 *  - 进入设置时应用自动禁用并持久化；前端据此标红提示，用户重新勾选时提示"需重新抓包"
 */
object TaskFailureTracker {

    private const val TAG = "TaskFailure"
    private const val FILE = "task_failures.json"
    const val AUTO_DISABLE_THRESHOLD = 3

    // key = "modelCode.fieldCode" -> {count, lastReason, lastTime, autoDisabled}
    private val data = LinkedHashMap<String, JSONObject>()

    private fun file() = File(Files.CONFIG_DIR, FILE)

    @Synchronized
    private fun load() {
        data.clear()
        val f = file()
        if (!f.exists() || f.length() == 0L) return
        try {
            val root = JSONObject(Files.readFromFile(f))
            val obj = root.optJSONObject("failures")
            if (obj != null) {
                for (key in obj.keys()) {
                    val v = obj.optJSONObject(key) ?: continue
                    data[key] = v
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "读取失败记录异常:", t)
        }
    }

    @Synchronized
    private fun save() {
        try {
            val obj = JSONObject()
            for ((k, v) in data) obj.put(k, v)
            val root = JSONObject().apply { put("failures", obj) }
            val dir = Files.CONFIG_DIR
            if (!dir.exists()) dir.mkdirs()
            Files.write2File(root.toString(), file())
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "保存失败记录异常:", t)
        }
    }

    /** 记录一次异常 */
    @Synchronized
    fun record(modelCode: String, fieldCode: String, reason: String) {
        if (data.isEmpty() && file().exists()) load()
        val key = "$modelCode.$fieldCode"
        val obj = data.getOrPut(key) { JSONObject() }
        val count = obj.optInt("count") + 1
        obj.put("count", count)
        obj.put("lastReason", (reason ?: "").take(80))
        obj.put("lastTime", System.currentTimeMillis())
        if (count >= AUTO_DISABLE_THRESHOLD) {
            if (!obj.optBoolean("autoDisabled")) {
                obj.put("autoDisabled", true)
                Log.record(TAG, "任务[$modelCode.$fieldCode]连续异常${count}次，已自动关闭该功能")
            }
            // 运行时立即停用
            disableField(modelCode, fieldCode)
        }
        save()
    }

    /** 成功时清零（连续异常重新计数） */
    @Synchronized
    fun clear(modelCode: String, fieldCode: String) {
        if (data.isEmpty() && file().exists()) load()
        val key = "$modelCode.$fieldCode"
        if (data.containsKey(key)) {
            data.remove(key)
            save()
        }
    }

    /**
     * 进入设置时应用自动禁用（把已达阈值的开关置 false），返回是否有改动
     * 改动后由调用方用 Config.save(userId, true) 持久化
     */
    @Synchronized
    @JvmStatic
    fun applyAutoDisable(): Boolean {
        load()
        var changed = false
        for ((key, obj) in data) {
            if (obj.optInt("count") >= AUTO_DISABLE_THRESHOLD) {
                val idx = key.indexOf('.')
                if (idx <= 0) continue
                if (disableField(key.substring(0, idx), key.substring(idx + 1))) changed = true
            }
        }
        return changed
    }

    /** 把某设置项开关置为 false（返回是否发生改动） */
    private fun disableField(modelCode: String, fieldCode: String): Boolean {
        return try {
            val mc = Model.getModelConfigMap()[modelCode] ?: return false
            val field = mc.getModelFieldExt<BooleanModelField>(fieldCode) ?: return false
            if (field.value == true) {
                field.setObjectValue(false)
                Log.record(TAG, "已自动关闭: $modelCode.$fieldCode")
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "自动关闭 $modelCode.$fieldCode 异常:", t)
            false
        }
    }

    /** 给前端：返回 { "modelCode.fieldCode": {count, autoDisabled, lastReason, lastTime} } */
    @JvmStatic
    fun getFailuresJson(): String {
        load()
        val obj = JSONObject()
        for ((k, v) in data) obj.put(k, v)
        return JSONObject().apply { put("failures", obj) }.toString()
    }

    /** 前端重新勾选时调用：清除该字段的失败记录（用户已确认重新抓包） */
    @JvmStatic
    fun resetField(modelCode: String, fieldCode: String) {
        clear(modelCode, fieldCode)
    }
}
