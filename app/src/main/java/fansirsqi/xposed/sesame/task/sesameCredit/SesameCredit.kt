package fansirsqi.xposed.sesame.task.sesameCredit

import fansirsqi.xposed.sesame.model.ModelFields
import fansirsqi.xposed.sesame.model.ModelGroup
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField
import fansirsqi.xposed.sesame.model.modelFieldExt.StringModelField
import fansirsqi.xposed.sesame.task.ModelTask
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ResChecker
import fansirsqi.xposed.sesame.util.TaskFailureTracker
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * 芝麻信用 — 信用积累领取、安心豆签到/任务
 * 协议移植自芝麻糊SVIP 2.0.6.6（逆向还原）
 */
class SesameCredit : ModelTask() {

    companion object {
        private const val TAG = "SesameCredit"
        const val MODULE_NAME = "芝麻信用"

        @Volatile var instance: SesameCredit? = null
    }

    private lateinit var enableCollect: BooleanModelField
    private lateinit var enableAnxindouSign: BooleanModelField
    private lateinit var enableAnxindouTask: BooleanModelField
    private lateinit var anxindouAppletId: StringModelField

    override fun getName() = MODULE_NAME
    override fun getGroup() = ModelGroup.OTHER
    override fun getIcon() = "Default.png"

    override fun getFields() = ModelFields().apply {
        addField(BooleanModelField("creditCollect", "芝麻信用 | 领取信用积累", true).also { enableCollect = it })
        addField(BooleanModelField("creditAnxinSign", "芝麻信用 | 安心豆签到", true).also { enableAnxindouSign = it })
        addField(BooleanModelField("creditAnxinTask", "芝麻信用 | 安心豆任务", false).also { enableAnxindouTask = it })
        addField(
            StringModelField(
                "creditAnxinAppletId",
                "芝麻信用 | 安心豆 appletId(抓包获取)",
                ""
            ).also { anxindouAppletId = it }
        )
    }

    override fun prepare() { instance = this }
    override fun destroy() { instance = null; super.destroy() }

    override suspend fun runSuspend() {
        if (enableCollect.value) doCollectCredit()
        if (enableAnxindouSign.value) doAnxindouSign()
        if (enableAnxindouTask.value) doAnxindouTask()
    }

    /** 领取待领取的信用积累 */
    private suspend fun doCollectCredit() {
        try {
            delay(1500)
            val queryResult = SesameCreditRpcCall.queryCreditFeedback()
            val queryJson = JSONObject(queryResult)
            if (!ResChecker.checkRes(TAG, queryJson)) return

            val feedbackList = queryJson.optJSONArray("feedbackList")
                ?: queryJson.optJSONObject("data")?.optJSONArray("feedbackList")
            if (feedbackList == null || feedbackList.length() == 0) {
                Log.record(TAG, "无待领取的信用积累")
                return
            }
            var collected = 0
            for (i in 0 until feedbackList.length()) {
                val item = feedbackList.optJSONObject(i) ?: continue
                val id = item.optString("creditFeedbackId", "")
                val title = item.optString("title", id)
                if (id.isBlank()) continue
                delay(2500)
                val collectResult = SesameCreditRpcCall.collectCreditFeedback(id)
                if (ResChecker.checkRes(TAG, JSONObject(collectResult))) {
                    collected++
                    Log.record(TAG, "信用积累领取: $title")
                }
            }
            Log.record(TAG, "共领取 $collected 项信用积累")
        } catch (t: Throwable) {
            Log.error(TAG, "信用积累异常: ${t.message}")
            TaskFailureTracker.record("SesameCredit", "creditCollect", t.message ?: "")
        }
    }

    /** 安心豆签到 */
    private suspend fun doAnxindouSign() {
        try {
            delay(1500)
            val appletId = anxindouAppletId.value.trim()
            if (appletId.isBlank()) {
                Log.record(TAG, "未配置安心豆 appletId，跳过签到（请在设置中填写抓包得到的 appletId）")
                return
            }
            val result = SesameCreditRpcCall.signInTrigger(appletId)
            if (ResChecker.checkRes(TAG, JSONObject(result))) {
                Log.record(TAG, "安心豆签到成功 ✅")
            }
        } catch (t: Throwable) {
            Log.error(TAG, "安心豆签到异常: ${t.message}")
            TaskFailureTracker.record("SesameCredit", "creditAnxinSign", t.message ?: "")
        }
    }

    /** 安心豆任务中心：查询并逐个触发 */
    private suspend fun doAnxindouTask() {
        try {
            delay(1500)
            val consultResult = SesameCreditRpcCall.taskCenterConsult("ANXINDOU_TASK_CENTER")
            val consultJson = JSONObject(consultResult)
            if (!ResChecker.checkRes(TAG, consultJson)) return

            val taskList = consultJson.optJSONObject("data")?.optJSONArray("taskList")
                ?: return
            for (i in 0 until taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue
                val sceneCode = task.optString("sceneCode", "")
                val title = task.optString("title", sceneCode)
                if (sceneCode.isBlank() || task.optString("taskStatus") == "FINISHED") continue
                delay(2500)
                val triggerResult = SesameCreditRpcCall.taskTrigger(sceneCode)
                if (ResChecker.checkRes(TAG, JSONObject(triggerResult))) {
                    Log.record(TAG, "安心豆任务: $title")
                }
            }
        } catch (t: Throwable) {
            Log.error(TAG, "安心豆任务异常: ${t.message}")
            TaskFailureTracker.record("SesameCredit", "creditAnxinTask", t.message ?: "")
        }
    }
}
