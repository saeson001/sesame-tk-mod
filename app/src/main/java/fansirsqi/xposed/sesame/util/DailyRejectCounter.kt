package fansirsqi.xposed.sesame.util

import org.json.JSONObject
import java.io.File

/**
 * 每日拒绝计数工具
 *
 * 背景：部分任务（如运动步数同步、果园施肥）服务端会拒绝，但拒绝原因往往是
 *       "今天已经做过了" 这类业务限制，继续重试无意义还会刷错误日志。
 *
 * 规则：同一任务当天被拒绝达到 [MAX_REJECT] 次后，当天不再执行（次日自动恢复）。
 *       手动执行可绕过该限制；执行成功后计数清零。
 */
object DailyRejectCounter {

    /** 拒绝多少次后当天停止 */
    const val MAX_REJECT = 2

    private const val FILE_NAME = "daily_reject.json"

    /** 当天剩余可尝试次数；0 表示今天已停 */
    fun remaining(taskKey: String): Int {
        val count = countOf(taskKey)
        return (MAX_REJECT - count).coerceAtLeast(0)
    }

    /** 今天是否已对该任务停止 */
    fun isStoppedToday(taskKey: String): Boolean = remaining(taskKey) <= 0

    /**
     * 记录一次拒绝。
     * @return true 表示本次拒绝后已达到上限（今天不再自动执行）
     */
    fun recordReject(taskKey: String, reason: String): Boolean {
        val day = today()
        val root = load()
        val dayObj = root.optJSONObject(day) ?: JSONObject()
        val node = dayObj.optJSONObject(taskKey) ?: JSONObject()
        val cnt = node.optInt("count", 0) + 1
        node.put("count", cnt)
        node.put("lastReason", reason)
        node.put("lastTime", System.currentTimeMillis())
        dayObj.put(taskKey, node)
        root.put(day, dayObj)
        save(root)
        return cnt >= MAX_REJECT
    }

    /** 任务成功执行后清零（当日可继续尝试） */
    fun clearReject(taskKey: String) {
        val day = today()
        val root = load()
        val dayObj = root.optJSONObject(day) ?: return
        dayObj.remove(taskKey)
        root.put(day, dayObj)
        save(root)
    }

    // ---------------- 内部 ----------------

    private fun countOf(taskKey: String): Int {
        val day = today()
        val root = load()
        val dayObj = root.optJSONObject(day) ?: return 0
        val node = dayObj.optJSONObject(taskKey) ?: return 0
        return node.optInt("count", 0)
    }

    private fun file(): File {
        val dir = Files.CONFIG_DIR
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    private fun load(): JSONObject {
        return try {
            val f = file()
            if (f.exists() && f.length() > 0) JSONObject(f.readText()) else JSONObject()
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    private fun save(data: JSONObject) {
        try {
            file().writeText(data.toString())
        } catch (_: Throwable) {
        }
    }

    private fun today(): String {
        val cal = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }
}
