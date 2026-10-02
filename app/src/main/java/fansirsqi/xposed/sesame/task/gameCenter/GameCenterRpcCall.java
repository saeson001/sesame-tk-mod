package fansirsqi.xposed.sesame.task.gameCenter;

import fansirsqi.xposed.sesame.hook.RequestManager;

/**
 * 游戏中心 RPC 调用（积分球/签到/任务）
 * 协议来源：芝麻糊SVIP 2.0.6.6 逆向还原（GameCenterRpcCall）
 */
public class GameCenterRpcCall {
    private static final String SOURCE = "ch_appcenter__chsub_9patch";

    /** 批量领取积分球 */
    public static String batchReceivePointBall() {
        return RequestManager.requestString(
                "com.alipay.gamecenteruprod.biz.rpc.v3.batchReceivePointBall", "[{}]");
    }

    /** 游戏中心签到 */
    public static String continueSignIn() {
        return RequestManager.requestString(
                "com.alipay.gamecenteruprod.biz.rpc.continueSignIn",
                "[{\"sceneId\":\"GAME_CENTER\",\"signType\":\"NORMAL_SIGN\",\"source\":\"" + SOURCE + "\"}]");
    }

    /** 查询签到球（可领取状态） */
    public static String querySignInBall() {
        return RequestManager.requestString(
                "com.alipay.gamecenteruprod.biz.rpc.v3.querySignInBall",
                "[{\"source\":\"" + SOURCE + "\"}]");
    }

    /** 查询模块化任务列表 */
    public static String queryModularTaskList() {
        return RequestManager.requestString(
                "com.alipay.gamecenteruprod.biz.rpc.v3.queryModularTaskList",
                "[{\"source\":\"" + SOURCE + "\"}]");
    }

    /** 查询积分球列表 */
    public static String queryPointBallList() {
        return RequestManager.requestString(
                "com.alipay.gamecenteruprod.biz.rpc.v3.queryPointBallList",
                "[{\"source\":\"" + SOURCE + "\"}]");
    }

    /** 查询积分聚合页 */
    public static String queryPointBenefitAggPage() {
        return RequestManager.requestString(
                "com.alipay.gamecenteruprod.biz.rpc.v3.queryPointBenefitAggPage",
                "[{\"source\":\"" + SOURCE + "\"}]");
    }

    /** 任务报名 */
    public static String doTaskSignup(String taskId) {
        return RequestManager.requestString(
                "com.alipay.gamecenteruprod.biz.rpc.v3.doTaskSignup",
                "[{\"source\":\"" + SOURCE + "\",\"taskId\":\"" + taskId + "\"}]");
    }

    /** 完成任务发送 */
    public static String doTaskSend(String taskId) {
        return RequestManager.requestString(
                "com.alipay.gamecenteruprod.biz.rpc.v3.doTaskSend",
                "[{\"taskId\":\"" + taskId + "\"}]");
    }

    /** 点击发送应用权益 */
    public static String clickSendAppBenefit(String appId) {
        return RequestManager.requestString(
                "alipay.mobileappconfig.biz.app.clickSendAppBenefit",
                "[{\"appId\":\"" + appId + "\"}]");
    }
}
