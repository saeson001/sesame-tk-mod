package fansirsqi.xposed.sesame.task.welfareCenter

import fansirsqi.xposed.sesame.model.ModelFields
import fansirsqi.xposed.sesame.model.ModelGroup
import fansirsqi.xposed.sesame.model.modelFieldExt.BooleanModelField
import fansirsqi.xposed.sesame.task.ModelTask
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ResChecker
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 福利中心 — 网商银行福利（签到/互动玩法/积分查询）
 * 协议移植自芝麻糊SVIP 2.0.6.6（逆向还原）
 */
class WelfareCenter : ModelTask() {

    companion object {
        private const val TAG = "WelfareCenter"
        const val MODULE_NAME = "福利中心"

        /** 好家无忧卡营地 ID（来自原版协议） */
        private const val CAMP_ID_HJWY = "CP15205657"

        @Volatile var instance: WelfareCenter? = null
    }

    private lateinit var enableSign: BooleanModelField
    private lateinit var enableCampTrigger: BooleanModelField
    private lateinit var enableQueryPoint: BooleanModelField

    override fun getName() = MODULE_NAME
    override fun getGroup() = ModelGroup.OTHER
    override fun getIcon() = "Default.png"

    override fun getFields() = ModelFields().apply {
        addField(BooleanModelField("welfareSign", "福利中心 | 签到", true).also { enableSign = it })
        addField(BooleanModelField("welfareCamp", "福利中心 | 好家无忧卡触发", false).also { enableCampTrigger = it })
        addField(BooleanModelField("welfarePoint", "福利中心 | 查积分余额", true).also { enableQueryPoint = it })
    }

    override fun prepare() { instance = this }
    override fun destroy() { instance = null; super.destroy() }

    override suspend fun runSuspend() {
        if (enableSign.value) doSign()
        if (enableCampTrigger.value) doCampTrigger()
        if (enableQueryPoint.value) doQueryPoint()
    }

    /** 福利中心签到 */
    private suspend fun doSign() {
        try {
            delay(1500)
            val result = WelfareCenterRpcCall.signinPlay()
            if (ResChecker.checkRes(TAG, JSONObject(result))) {
                Log.record(TAG, "福利中心签到成功 ✅")
            }
        } catch (t: Throwable) {
            Log.error(TAG, "签到异常: ${t.message}")
        }
    }

    /** 好家无忧卡营地触发 */
    private suspend fun doCampTrigger() {
        try {
            delay(1500)
            val result = WelfareCenterRpcCall.trigger(CAMP_ID_HJWY, "网商银行福利")
            if (ResChecker.checkRes(TAG, JSONObject(result))) {
                Log.record(TAG, "好家无忧卡触发成功 ✅")
            }
        } catch (t: Throwable) {
            Log.error(TAG, "营地触发异常: ${t.message}")
        }
    }

    /** 查询福利积分余额 */
    private suspend fun doQueryPoint() {
        try {
            delay(1500)
            val result = WelfareCenterRpcCall.pointBanlance("")
            val json = JSONObject(result)
            if (ResChecker.checkRes(TAG, json)) {
                val point = json.optJSONObject("data")?.optString("pointAmount", "") ?: ""
                Log.record(TAG, "福利积分余额: $point")
            }
        } catch (t: Throwable) {
            Log.error(TAG, "积分查询异常: ${t.message}")
        }
    }
}
