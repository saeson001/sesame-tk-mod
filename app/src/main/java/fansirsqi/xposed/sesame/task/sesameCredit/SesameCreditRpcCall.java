package fansirsqi.xposed.sesame.task.sesameCredit;

import fansirsqi.xposed.sesame.hook.RequestManager;

/**
 * 芝麻信用 RPC 调用（信用积累/安心豆/信用资料）
 * 协议来源：芝麻糊SVIP 2.0.6.6 逆向还原（SesameCreditRpcCall）
 */
public class SesameCreditRpcCall {

    /** 芝麻信用首页 */
    public static String queryHome() {
        return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.home.api.HomeV6RpcManager.queryHome",
                "[{\"miniZmGrayInside\":\"\"}]");
    }

    /** 查询可领取的信用积累任务 */
    public static String queryCreditFeedback() {
        return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.home.creditaccumulate.api.CreditAccumulateRpcManager.queryCreditFeedback",
                "[{\"queryPotential\":false,\"size\":20,\"status\":\"UNCLAIMED\"}]");
    }

    /** 领取信用积累（提交反馈） */
    public static String collectCreditFeedback(String creditFeedbackId) {
        return RequestManager.requestString(
                "com.antgroup.zmxy.zmcustprod.biz.rpc.home.creditaccumulate.api.CreditAccumulateRpcManager.collectCreditFeedback",
                "[{\"collectAll\":false,\"creditFeedbackId\":\"" + creditFeedbackId + "\",\"status\":\"UNCLAIMED\"}]");
    }

    /** 查询信用积累策略列表 */
    public static String queryListV2() {
        return RequestManager.requestString(
                "com.antgroup.zmxy.zmmemberop.biz.rpc.creditaccumulate.CreditAccumulateStrategyRpcManager.queryListV2",
                "[{\"sceneCode\":\"creditAccumulate\"}]");
    }

    /** 安心豆 — 查用户账户信息 */
    public static String queryUserAccountInfo() {
        return RequestManager.requestString(
                "com.alipay.insmarketingbff.point.queryUserAccountInfo",
                "[{\"channel\":\"insplatform_mobilesearch_anxindou\",\"pointProdCode\":\"INS_BLUE_BEAN\",\"pointUnitType\":\"COUNT\"}]");
    }

    /** 安心豆 — 任务中心查询 */
    public static String taskCenterConsult(String sceneCode) {
        return RequestManager.requestString(
                "com.alipay.insmarketingbff.bean.taskCenterConsult",
                "[{\"bizData\":{},\"displayTaskCount\":30,\"entrance\":\"insplatform_mine_anxindou\",\"sceneCode\":\""
                        + sceneCode + "\"}]");
    }

    /** 安心豆 — 任务触发 */
    public static String taskTrigger(String sceneCode) {
        return RequestManager.requestString(
                "com.alipay.insmarketingbff.bean.taskTrigger",
                "[{\"sceneCode\":\"" + sceneCode + "\"}]");
    }

    /** 安心豆 — 签到触发 */
    public static String signInTrigger(String appletId) {
        return RequestManager.requestString(
                "com.alipay.insmarketingbff.bean.signInTrigger",
                "[{\"appletId\":\"" + appletId + "\"}]");
    }

    /** 安心豆 — 查签到进度 */
    public static String querySignInProcess(String appletId) {
        return RequestManager.requestString(
                "com.alipay.insmarketingbff.bean.querySignInProcess",
                "[{\"appletId\":\"" + appletId + "\"}]");
    }

    /** 信用租赁任务提交 */
    public static String rentTaskSubmit(String rentTaskId) {
        return RequestManager.requestString(
                "com.alipay.creditapollon.biz.rpc.api.rent.RentTaskRpcService.taskSubmit",
                "[{\"rentTaskId\":\"" + rentTaskId + "\"}]");
    }

    /** 信用+, 一键领取（计划触发） */
    public static String exchange(String itemId) {
        return RequestManager.requestString(
                "com.alipay.insmarketingbff.onestop.planTrigger",
                "[{\"extParams\":{\"itemId\":\"" + itemId + "\"}}]");
    }
}
