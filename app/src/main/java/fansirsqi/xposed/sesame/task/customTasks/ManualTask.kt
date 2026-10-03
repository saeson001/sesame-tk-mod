package fansirsqi.xposed.sesame.task.customTasks

import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.model.Model
import fansirsqi.xposed.sesame.task.antFarm.AntFarm
import fansirsqi.xposed.sesame.task.antForest.AntForest
import fansirsqi.xposed.sesame.task.antSports.AntSports
import fansirsqi.xposed.sesame.util.GlobalThreadPools
import fansirsqi.xposed.sesame.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 手动任务执行器
 */
object ManualTask {

    /**
     * 手动任务流总开关
     */
    @Volatile
    var isManualEnabled = true

    /**
     * 标记手动任务是否正在运行，用于与自动任务互斥
     */
    @Volatile
    var isManualRunning = false
        private set

    /**
     * 为 Java 提供的非 suspend 启动接口
     */
    @JvmStatic
    @JvmOverloads
    fun runSingle(task: CustomTask, extraParams: Map<String, Any> = emptyMap()) {
        GlobalThreadPools.execute {
            run(listOf(task), extraParams)
        }
    }

    /**
     * 顺序执行选中的庄园子任务
     */
    suspend fun run(tasks: List<CustomTask>, extraParams: Map<String, Any> = emptyMap()) {
        if (!isManualEnabled) {
            Log.record("ManualTask", "⚠️ 手动任务流总开关已关闭，无法执行")
            return
        }

        if (tasks.isEmpty()) {
            Log.record("ManualTask", "⚠️ 未选中任何子任务")
            return
        }

        if (isManualRunning) {
            Log.record("ManualTask", "⚠️ 手动任务已在运行中，请勿重复启动")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                isManualRunning = true
                Log.record("ManualTask", "🚀 开始执行手动任务序列...")

                for (task in tasks) {
                    try {
                        Log.record("ManualTask", "⏳ 正在执行: ${task.displayName}...")
                        when (task) {
                            // 森林类任务
                            CustomTask.FOREST_WHACK_MOLE -> {
                                val instance = getForestInstance()
                                if (instance != null) {
                                    val mode = extraParams["whackMoleMode"] as? Int ?: 1
                                    val games = extraParams["whackMoleGames"] as? Int ?: 5
                                    instance.manualWhackMole(mode, games)
                                } else {
                                    Log.record("ManualTask", "❌ 无法加载森林模块")
                                }
                            }

                            CustomTask.FOREST_ENERGY_RAIN -> {
                                val instance = getForestInstance()
                                if (instance != null) {
                                    val exchange = extraParams["exchangeEnergyRainCard"] as? Boolean ?: false
                                    instance.manualUseEnergyRain(exchange)
                                } else {
                                    Log.record("ManualTask", "❌ 无法加载森林模块")
                                }
                            }

                            // 庄园类任务
                            CustomTask.FARM_SEND_BACK_ANIMAL -> getFarmInstance()?.manualSendBackAnimal()
                            CustomTask.FARM_GAME_LOGIC -> getFarmInstance()?.manualFarmGameLogic()
                            CustomTask.FARM_CHOUCHOULE -> getFarmInstance()?.manualChouChouLeLogic()
                            CustomTask.FARM_SPECIAL_FOOD -> {
                                val count = extraParams["specialFoodCount"] as? Int ?: 0
                                getFarmInstance()?.manualUseSpecialFood(count)
                            }
                            CustomTask.FARM_USE_TOOL -> {
                                val toolType = extraParams["toolType"] as? String ?: ""
                                val toolCount = extraParams["toolCount"] as? Int ?: 1
                                getFarmInstance()?.manualUseFarmTool(toolType, toolCount)
                            }

                            // 运动类任务
                            CustomTask.SPORTS_SYNC_STEP -> {
                                val instance = getSportsInstance()
                                if (instance != null) {
                                    // 手动执行绕过"当日拒绝次数上限"，但成功后仍会清零计数
                                    instance.manualSyncStep()
                                } else {
                                    Log.record("ManualTask", "❌ 无法加载运动模块")
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Log.record("ManualTask", "❌ 执行 ${task.displayName} 出错: ${t.message}")
                        Log.printStackTrace(t)
                    }
                }
                Log.record("ManualTask", "✅ 手动任务执行完毕")
            } finally {
                isManualRunning = false
            }
        }
    }

    /**
     * 按需获取并确保蚂蚁森林实例已加载
     */
    private fun getForestInstance(): AntForest? {
        if (AntForest.instance == null) {
            val loader = ApplicationHook.classLoader ?: return null
            Model.getModel(AntForest::class.java)?.let {
                Log.record("ManualTask", "⚙️ 正在按需加载森林模块...")
                it.prepare()
                it.boot(loader)
            }
        }
        return AntForest.instance
    }

    /**
     * 按需获取并确保蚂蚁庄园实例已加载
     */
    private fun getFarmInstance(): AntFarm? {
        if (AntFarm.instance == null) {
            val loader = ApplicationHook.classLoader ?: return null
            Model.getModel(AntFarm::class.java)?.let {
                Log.record("ManualTask", "⚙️ 正在按需加载庄园模块...")
                it.prepare()
                it.boot(loader)
            }
        }
        return AntFarm.instance
    }

    /**
     * 按需获取并确保蚂蚁运动实例已加载
     *
     * 注意：AntSports 没有 companion instance 字段（不同于 AntForest/AntFarm），
     *      直接用 Model.getModel() 取实例；prepare/boot 重复调用是安全的。
     */
    private fun getSportsInstance(): AntSports? {
        val loader = ApplicationHook.classLoader ?: return null
        val model = Model.getModel(AntSports::class.java)
        if (model == null) {
            Log.record("ManualTask", "❌ 运动模块未注册")
            return null
        }
        Log.record("ManualTask", "⚙️ 正在按需加载运动模块...")
        model.prepare()
        model.boot(loader)
        return model
    }
}
