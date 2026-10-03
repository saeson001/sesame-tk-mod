package fansirsqi.xposed.sesame.task.gameCenter

import fansirsqi.xposed.sesame.model.ModelFields
import fansirsqi.xposed.sesame.model.ModelGroup
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField
import fansirsqi.xposed.sesame.task.ModelTask
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ResChecker
import fansirsqi.xposed.sesame.util.TaskFailureTracker
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 游戏中心 — 签到领积分球、积分球批量收取
 * 协议移植自芝麻糊SVIP 2.0.6.6（逆向还原）
 */
class GameCenter : ModelTask() {

    companion object {
        private const val TAG = "GameCenter"
        const val MODULE_NAME = "游戏中心"

        @Volatile var instance: GameCenter? = null
    }

    private lateinit var enableSignIn: BooleanModelField
    private lateinit var enablePointBall: BooleanModelField
    private lateinit var enableTask: BooleanModelField

    override fun getName() = MODULE_NAME
    override fun getGroup() = ModelGroup.OTHER
    override fun getIcon() = "Default.png"

    override fun getFields() = ModelFields().apply {
        addField(BooleanModelField("gameCenterSignIn", "游戏中心 | 自动签到", true).also { enableSignIn = it })
        addField(BooleanModelField("gameCenterPointBall", "游戏中心 | 收积分球", true).also { enablePointBall = it })
        addField(BooleanModelField("gameCenterTask", "游戏中心 | 任务报名并完成", false).also { enableTask = it })
    }

    override fun prepare() { instance = this }
    override fun destroy() { instance = null; super.destroy() }

    override suspend fun runSuspend() {
        if (enableSignIn.value) doSignIn()
        if (enablePointBall.value) doReceivePointBall()
        if (enableTask.value) doTasks()
    }

    /** 游戏中心每日签到 */
    private suspend fun doSignIn() {
        try {
            delay(1500)
            val signResult = GameCenterRpcCall.continueSignIn()
            if (ResChecker.checkRes(TAG, JSONObject(signResult))) {
                Log.record(TAG, "游戏中心签到成功 ✅")
            }
        } catch (t: Throwable) {
            Log.error(TAG, "签到异常: ${t.message}")
            TaskFailureTracker.record("GameCenter", "gameCenterSignIn", t.message ?: "")
        }
    }

    /** 批量领取积分球 */
    private suspend fun doReceivePointBall() {
        try {
            // 先查询有哪些可领的球
            delay(1500)
            val listResult = GameCenterRpcCall.queryPointBallList()
            val listJson = JSONObject(listResult)
            if (!ResChecker.checkRes(TAG, listJson)) return

            val ballList = listJson.optJSONObject("data")?.optJSONArray("pointBallList")
            if (ballList != null && ballList.length() > 0) {
                Log.record(TAG, "发现 ${ballList.length()} 个积分球")
            }

            // 直接批量领取（服务端会自动跳过不可领的）
            delay(2000)
            val receiveResult = GameCenterRpcCall.batchReceivePointBall()
            val receiveJson = JSONObject(receiveResult)
            if (ResChecker.checkRes(TAG, receiveJson)) {
                val received = receiveJson.optJSONObject("data")?.optString("receivedAmount", "")
                Log.record(TAG, "积分球批量领取完成 ${if (!received.isNullOrBlank()) "金额:$received" else ""}")
            }
        } catch (t: Throwable) {
            Log.error(TAG, "积分球异常: ${t.message}")
            TaskFailureTracker.record("GameCenter", "gameCenterPointBall", t.message ?: "")
        }
    }

    /** 查询任务列表 → 报名 → 完成 */
    private suspend fun doTasks() {
        try {
            delay(1500)
            val listResult = GameCenterRpcCall.queryModularTaskList()
            val listJson = JSONObject(listResult)
            if (!ResChecker.checkRes(TAG, listJson)) return

            val taskList = listJson.optJSONObject("data")?.optJSONArray("taskList") ?: return
            for (i in 0 until taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue
                val taskId = task.optString("taskId", "")
                val title = task.optString("title", taskId)
                val status = task.optString("processStatus", "")
                if (taskId.isBlank() || status == "COMPLETED") continue

                delay(2500)
                // 报名
                val signupResult = GameCenterRpcCall.doTaskSignup(taskId)
                if (ResChecker.checkRes(TAG, JSONObject(signupResult))) {
                    Log.record(TAG, "任务报名: $title")
                }
                delay(2000)
                // 完成
                val sendResult = GameCenterRpcCall.doTaskSend(taskId)
                if (ResChecker.checkRes(TAG, JSONObject(sendResult))) {
                    Log.record(TAG, "任务完成: $title")
                }
            }
        } catch (t: Throwable) {
            Log.error(TAG, "任务异常: ${t.message}")
            TaskFailureTracker.record("GameCenter", "gameCenterTask", t.message ?: "")
        }
    }
}
