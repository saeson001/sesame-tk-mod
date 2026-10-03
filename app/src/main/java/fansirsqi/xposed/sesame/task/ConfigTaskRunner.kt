package fansirsqi.xposed.sesame.task

import fansirsqi.xposed.sesame.hook.RequestManager
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 配置任务定时执行器
 *
 * 任务列表来自配置文件：Android/media/com.eg.android.AlipayGphone/sesame-TK/config/custom_tasks.json
 * 每条任务 = 一次通用 RPC（operationType + requestData），按类型定时执行：
 *   - type = "game"   游戏类任务，默认每 15 分钟执行一个
 *   - type = "normal" 其他任务，  默认每 3 分钟执行一次
 * 每条任务可用 intervalMinutes 单独覆盖间隔；enabled=false 可停用某条。
 *
 * 配置文件不存在时使用内置默认任务（游戏时长上报 + 抽抽乐抽一次）。
 */
object ConfigTaskRunner {

    private const val TAG = "ConfigTask"
    private const val TASK_FILE = "custom_tasks.json"
    private const val TICK_MS = 60_000L // 每分钟检查一次到期的任务

    private val lastRunMap = ConcurrentHashMap<String, Long>()

    @Volatile
    private var job: Job? = null

    const val TYPE_FLOW = "flow"
    const val TYPE_SINGLE = "single"

    data class Task(
        val id: String,
        val name: String,
        val type: String,
        val operationType: String,
        val requestData: String,
        val intervalMinutes: Int,
        val enabled: Boolean,
        /** single=单次RPC；flow=多步流程(requestData为steps数组) */
        val taskType: String = TYPE_SINGLE
    )

    /** 启动后台调度（游戏类 15 分钟 / 其他 3 分钟） */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = GlobalScope.launch {
            Log.record(TAG, "配置任务定时执行[已启动: 游戏类15分钟/其他3分钟, 配置文件 $TASK_FILE]")
            while (true) {
                delay(TICK_MS)
                try {
                    runDueTasks()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.printStackTrace(TAG, "配置任务调度异常:", t)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 手动立即执行一轮（供「立即检查」用） */
    suspend fun runOnce() {
        runDueTasks()
    }

    private suspend fun runDueTasks() {
        val tasks = loadTasks()
        if (tasks.isEmpty()) return
        val now = System.currentTimeMillis()
        for (task in tasks) {
            if (!task.enabled) continue
            val last = lastRunMap[task.id] ?: 0L
            if (now - last < task.intervalMinutes * 60_000L) continue
            try {
                executeTask(task)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "任务[${task.name}]异常:", t)
            }
            // 无论成功失败都记录时间，避免频繁重试
            lastRunMap[task.id] = now
        }
    }

    private suspend fun executeTask(task: Task) {
        // 流程型任务：按 flow.steps 顺序执行多步 RPC
        if (task.taskType == TYPE_FLOW) {
            executeFlowTask(task)
            return
        }
        val resp = withContext(Dispatchers.IO) {
            try {
                RequestManager.requestString(task.operationType, task.requestData)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "任务[${task.name}]RPC异常:", t)
                ""
            }
        }
        val ok = resp.isNotBlank()
        Log.record(
            TAG,
            "配置任务[${task.name}][${task.type}/每${task.intervalMinutes}分钟] " +
                    if (ok) "已执行" else "执行失败(空响应)"
        )
    }

    /** 流程型任务：依次执行 steps 中的每个 RPC（供 taskToken 等动态参数场景使用） */
    private suspend fun executeFlowTask(task: Task) {
        val steps = parseFlowSteps(task.requestData)
        if (steps.isEmpty()) {
            Log.record(TAG, "流程任务[${task.name}]未配置步骤, 跳过")
            return
        }
        var okCount = 0
        for ((i, step) in steps.withIndex()) {
            val rd = if (step.has("requestData") && !step.isNull("requestData")) {
                step.opt("requestData").toString()
            } else "[]"
            val resp = withContext(Dispatchers.IO) {
                try {
                    RequestManager.requestString(step.optString("operationType"), rd)
                } catch (t: Throwable) {
                    Log.printStackTrace(TAG, "流程[${task.name}]步骤${i + 1}异常:", t)
                    ""
                }
            }
            if (resp.isNotBlank()) {
                okCount++
                Log.record(TAG, "流程[${task.name}]步骤${i + 1}/${steps.size} ${step.optString("operationType").substringAfterLast('.')} 已完成")
            } else {
                Log.record(TAG, "流程[${task.name}]步骤${i + 1}/${steps.size} ${step.optString("operationType").substringAfterLast('.')} 失败(空响应)")
            }
            delay(1500)
        }
        Log.record(TAG, "配置任务[${task.name}][${task.type}/每${task.intervalMinutes}分钟] 流程完成 $okCount/${steps.size}")
    }

    /** 解析 flow steps：requestData 为 {"steps":[{operationType,requestData}]} 或直接数组 */
    private fun parseFlowSteps(raw: String): List<JSONObject> {
        val list = ArrayList<JSONObject>()
        try {
            val trimmed = raw.trim()
            val arr = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> JSONObject(trimmed).optJSONArray("steps") ?: JSONArray()
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("operationType").isNotEmpty()) list.add(o)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "解析流程步骤失败:", t)
        }
        return list
    }

    /** 加载任务：优先读配置文件，失败/不存在则用内置默认 */
    fun loadTasks(): List<Task> {
        val f = File(Files.CONFIG_DIR, TASK_FILE)
        if (f.exists() && f.length() > 0L) {
            try {
                val json = JSONObject(Files.readFromFile(f))
                val arr = json.optJSONArray("tasks")
                if (arr != null && arr.length() > 0) {
                    return parseTasks(arr)
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "解析 $TASK_FILE 失败, 回退默认任务:", t)
            }
        }
        return defaultTasks()
    }

    private fun parseTasks(arr: JSONArray): List<Task> {
        val list = ArrayList<Task>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val op = o.optString("operationType")
            if (op.isEmpty() && o.optString("taskType", TYPE_SINGLE) != TYPE_FLOW) continue
            val type = o.optString("type", "normal")
            val id = o.optString("id").ifEmpty { "task_$i" }
            // requestData 支持数组对象或字符串两种写法
            val rd = if (o.has("requestData")) o.opt("requestData").toString() else "[]"
            list.add(
                Task(
                    id = id,
                    name = o.optString("name", id),
                    type = type,
                    operationType = op,
                    requestData = rd,
                    intervalMinutes = o.optInt("intervalMinutes", defaultInterval(type)),
                    enabled = o.optBoolean("enabled", true),
                    taskType = o.optString("taskType", TYPE_SINGLE)
                )
            )
        }
        return list
    }

    private fun defaultInterval(type: String) = if (type == "game") 15 else 3

    /** 导入：返回配置 JSON；文件不存在则返回内置默认任务 JSON（供 UI 展示/编辑） */
    @JvmStatic
    fun getTasksJson(): String {
        val f = File(Files.CONFIG_DIR, TASK_FILE)
        if (f.exists() && f.length() > 0L) {
            try {
                return Files.readFromFile(f)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "读取 $TASK_FILE 失败, 返回默认:", t)
            }
        }
        return defaultTasksJson()
    }

    /** 导出：保存配置 JSON（校验 operationType 非空，规范化后写文件） */
    @JvmStatic
    fun saveTasksJson(json: String): Boolean {
        return try {
            val root = JSONObject(json)
            val src = root.optJSONArray("tasks")
            val out = JSONArray()
            if (src != null) {
                for (i in 0 until src.length()) {
                    val o = src.optJSONObject(i) ?: continue
                    val op = o.optString("operationType")
                    val taskType = o.optString("taskType", TYPE_SINGLE)
                    if (op.isEmpty() && taskType != TYPE_FLOW) continue
                    val type = o.optString("type", "normal")
                    val rd = if (o.has("requestData") && !o.isNull("requestData")) {
                        o.opt("requestData").toString()
                    } else "[]"
                    out.put(JSONObject().apply {
                        put("id", o.optString("id").ifEmpty { "task_$i" })
                        put("name", o.optString("name", "未命名任务"))
                        put("type", type)
                        put("taskType", taskType)
                        put("operationType", op)
                        put("requestData", rd)
                        put("intervalMinutes", o.optInt("intervalMinutes", defaultInterval(type)))
                        put("enabled", o.optBoolean("enabled", true))
                    })
                }
            }
            val dir = Files.CONFIG_DIR
            if (!dir.exists()) dir.mkdirs()
            val ok = Files.write2File(JSONObject().apply { put("tasks", out) }.toString(), File(dir, TASK_FILE))
            Log.record(TAG, "配置任务已保存(${out.length()}条) 结果=$ok")
            ok
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "保存配置任务失败:", t)
            false
        }
    }

    private fun defaultTasksJson(): String {
        val arr = JSONArray()
        defaultTasks().forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("name", t.name); put("type", t.type)
                put("operationType", t.operationType); put("requestData", t.requestData)
                put("intervalMinutes", t.intervalMinutes); put("enabled", t.enabled)
            })
        }
        return JSONObject().apply { put("tasks", arr) }.toString()
    }

    /** 内置默认任务：配置文件不存在时使用（全部基于抓包真实参数） */
    private fun defaultTasks(): List<Task> = listOf(
        Task(
            id = "game_play_duration",
            name = "游戏时长上报(抽抽乐游戏)",
            type = "game",
            operationType = "com.alipay.gamecenteruprod.biz.rpc.v3.submitUserPlayDurationAction",
            requestData = "[{\"gameAppId\":\"2060170000373873\",\"playTime\":30,\"source\":\"lianyun_zhuangyuan_ccl\"}]",
            intervalMinutes = 15,
            enabled = true
        ),
        Task(
            id = "draw_lottery_plus",
            name = "抽抽乐抽一次",
            type = "normal",
            operationType = "com.alipay.antfarm.drawLotteryPlus",
            requestData = "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ANTFARM\",\"source\":\"H5 \",\"version\":\"\"}]",
            intervalMinutes = 3,
            enabled = true
        ),
        Task(
            id = "leyuan_daily_award",
            name = "限定乐园任务领奖(领10个)",
            type = "normal",
            operationType = "com.alipay.antieptask.receiveTaskAwardantfarm",
            requestData = "[{\"awardCountForReceive\":10,\"ignoreLimit\":true,\"requestType\":\"RPC\",\"sceneCode\":\"ANTFARM_LEYUAN_DAILY_TASK\",\"source\":\"antfarm\",\"taskType\":\"2026cc_lyqd\"}]",
            intervalMinutes = 3,
            enabled = true
        ),
        Task(
            id = "game_center_draw5",
            name = "开宝箱×5",
            type = "normal",
            operationType = "com.alipay.charitygamecenter.drawGameCenterAward",
            requestData = "[{\"batchDrawCount\":5,\"bizType\":\"ANTFARM\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ANTFARM\",\"source\":\"H5\",\"version\":\"10.7.88.8000\"}]",
            intervalMinutes = 3,
            enabled = true
        ),
        Task(
            id = "game_center_click",
            name = "点游戏(抽抽乐乐园)",
            type = "game",
            operationType = "com.alipay.charitygamecenter.clickGame",
            requestData = "[{\"appId\":\"2021005187627410\",\"bizType\":\"ANTFARM\",\"requestType\":\"RPC\",\"sceneCode\":\"ANTFARM\",\"source\":\"H5\",\"version\":\"10.7.88.8000\"}]",
            intervalMinutes = 15,
            enabled = true
        ),
        Task(
            id = "forest_draw_sign_award",
            name = "森林抽抽乐每日签到领奖",
            type = "normal",
            operationType = "com.alipay.antieptask.receiveTaskAwardopengreen",
            requestData = "[{\"ignoreLimit\":true,\"requestType\":\"RPC\",\"sceneCode\":\"ANTFOREST_NORMAL_DRAW_TASK\",\"source\":\"task_entry\",\"taskType\":\"FOREST_NORMAL_DRAW_DAILY_SIGN\"}]",
            intervalMinutes = 3,
            enabled = true
        ),
        Task(
            id = "orchard_sign",
            name = "果园签到",
            type = "normal",
            operationType = "com.alipay.antfarm.orchardSign",
            requestData = "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"signScene\":\"ANTFARM_ORCHARD_SIGN_V2\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"20251209.01\"}]",
            intervalMinutes = 3,
            enabled = true
        ),
        Task(
            id = "forest_draw_lottery",
            name = "森林抽抽乐抽奖",
            type = "normal",
            operationType = "com.alipay.antiepdrawprod.drawopengreen",
            requestData = "[{\"activityId\":\"2026093001_v1\",\"requestType\":\"RPC\",\"sceneCode\":\"ANTFOREST_NORMAL_DRAW\",\"source\":\"task_entry\",\"userId\":\"2088632608116322\"}]",
            intervalMinutes = 3,
            enabled = true
        ),
        Task(
            id = "forest_energy_sign",
            name = "森林能量任务签到",
            type = "normal",
            operationType = "com.alipay.antiep.sign",
            requestData = "[{\"entityId\":\"03q897s0hmoou21bsv6ya0kuhck7632SIGN0\",\"requestType\":\"rpc\",\"sceneCode\":\"ANTFOREST_ENERGY_TASK_SIGN\",\"source\":\"ANTFOREST\",\"userId\":\"2088632608116322\"}]",
            intervalMinutes = 3,
            enabled = true
        ),
        Task(
            id = "yeb_exp_gold_sign",
            name = "余额宝体验金签到",
            type = "normal",
            operationType = "com.alipay.yebscenebff.needle.yebExpGold.signIn",
            requestData = "[{\"signInPlayId\":\"PLAY102253251\"}]",
            intervalMinutes = 3,
            enabled = true
        ),
        // 流程型任务：游戏中心P2E(查任务列表→报名→完成→领奖)，taskToken 由服务端签发，需多步串联
        Task(
            id = "gamecenter_p2e_task",
            name = "游戏中心P2E任务(报名+完成+领奖)",
            type = "game",
            operationType = "",
            requestData = "{\"steps\":[" +
                    "{\"operationType\":\"com.alipay.gamecenteruprod.biz.rpc.p2e.queryTaskList\",\"requestData\":[{\"appId\":\"2021003125685383\",\"deviceLevel\":\"high\",\"frontEndVersion\":\"20260820\",\"p2eVersion\":\"\",\"source\":\"reVisitTask\",\"unityDeviceLevel\":\"high\"}]}," +
                    "{\"operationType\":\"com.alipay.gamecenteruprod.biz.rpc.platformTaskSignUp\",\"requestData\":[{\"actionChannel\":\"taskList\",\"activityId\":\"P2E_PLATFORM_TASK\",\"source\":\"reVisitTask\",\"taskId\":\"addDesktop\",\"taskToken\":\"79094C40003602A8AE2420D9C4A9B526\"}]}," +
                    "{\"operationType\":\"com.alipay.gamecenteruprod.biz.rpc.platformTaskComplete\",\"requestData\":[{\"actionChannel\":\"taskList\",\"activityId\":\"P2E_PLATFORM_TASK\",\"source\":\"reVisitTask\",\"taskId\":\"addDesktop\",\"taskToken\":\"79094C40003602A8AE2420D9C4A9B526\"}]}," +
                    "{\"operationType\":\"com.alipay.gamecenteruprod.biz.rpc.p2e.gameP2eTaskReceive\",\"requestData\":[{\"actionChannel\":\"taskList\",\"activityId\":\"P2E_PLATFORM_TASK\",\"appId\":\"2021003125685383\",\"source\":\"reVisitTask\",\"taskId\":\"addDesktop\",\"taskToken\":\"79094C40003602A8AE2420D9C4A9B526\",\"taskType\":\"PLATFORM_TRAN_TASK\"}]}" +
                    "]}",
            intervalMinutes = 15,
            enabled = false,
            taskType = TYPE_FLOW
        )
    )
}
