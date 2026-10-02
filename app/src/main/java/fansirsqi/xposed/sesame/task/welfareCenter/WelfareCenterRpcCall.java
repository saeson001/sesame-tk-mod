package fansirsqi.xposed.sesame.task.welfareCenter;

import fansirsqi.xposed.sesame.hook.RequestManager;
import org.json.JSONArray;

/**
 * 福利中心（网商银行/我的福利）RPC 调用
 * 协议来源：芝麻糊SVIP 2.0.6.6 逆向还原（WelfareCenterRpcCall）
 */
public class WelfareCenterRpcCall {

    /** 福利中心签到（生活号 PLAY100177545） */
    public static String signinPlay() {
        return RequestManager.requestString(
                "com.alipay.loanpromoweb.member.play.signinPlay",
                "[{\"channel\": \"miniApp\",\"needMultiple\": false,\"operation\": \"signApply\",\"playId\": \"PLAY100177545\"}]");
    }

    /** 营销活动触发（好家无忧卡等） */
    public static String trigger(String campId, String memo) {
        return RequestManager.requestString(
                "com.alipay.loanpromoweb.promo.camp.trigger",
                "[{\"campId\": \"" + campId + "\",\"extParams\": {\"bkPointUseMemo\": \"" + memo
                        + "\",\"pcbfcCertMemo\": \"FULICenterUSE\"}}]");
    }

    /** 互动玩法触发 */
    public static String playTrigger(String playId) {
        return RequestManager.requestString(
                "com.alipay.loanpromoweb.promo.playcenter.playTrigger.trigger",
                "[{\"extInfo\":{},\"operation\":\"MYBK_DACU_INTERACTIVE_ZHB\",\"playId\":\"" + playId + "\"}]");
    }

    /** 查询福利积分余额 */
    public static String pointBanlance(String queryExpireEndDate) {
        return RequestManager.requestString(
                "com.alipay.loanpromoweb.promo.group.point.pointBanlance",
                "[{\"queryExpireEndDate\": \"" + queryExpireEndDate + "\"}]");
    }

    /** 查询证件/权益模板 */
    public static String queryCert(String[] certTemplateIds) {
        JSONArray arr = new JSONArray();
        for (String id : certTemplateIds) {
            arr.put(id);
        }
        return RequestManager.requestString(
                "com.alipay.loanpromoweb.promo.cert.query",
                "[{\"certTemplateIdSet\":" + arr + "}]");
    }

    /** 查询可用虚拟权益（福利积分场景列表） */
    public static String queryEnableVirtualProfitV2(String signInSceneId) {
        String sceneCodes = "[\"FULICenter_JKJML\",\"FULICenter_JZN\",\"BC3_BC3V1\",\"BC3_BC3V2\",\"BC3_BC3V3\","
                + "\"SQB_SQBV0\",\"SQB_SQBV1\",\"SQB_SQBV2\",\"SQB_SQBV3\",\"SQB_SQBV4\",\"SQB_SQBV5\","
                + "\"SQB_SQBV6\",\"SQB_SQBV7\",\"SQB_SQBV8\",\"SQB_SQBV9\",\"SQB_SQBV10\",\"SQB_SQBV11\","
                + "\"SQB_SQBSIGN\",\"FULICenter_JKJQW\",\"FULICenter_WSWF\",\"FULICenter_FLKZS\","
                + "\"FULICenter_KGJXBBF\",\"FULICenter_AXHZXB\",\"FULICenter_BBF\",\"FULICenter_V1\","
                + "\"FULICenter_V2\",\"FULICenter_V3\",\"FULICenter_V4\",\"FULICenter_V5\",\"FULICenter_V6\","
                + "\"FULICenter_V7\",\"FULICenter_YulibaoAUM\",\"FULICenter_PayByMybank\","
                + "\"FULICenter_DepositAUM\",\"FULICenter_YYYYH\",\"FULICenter_QYZ\",\"FULICenter_V7PLUS\","
                + "\"FULICenter_V6PLUS\",\"FULICenter_V5PLUS\",\"FULICenter_V8\",\"FULICenter_V9\",\"FULICenter_V10\"]";
        return RequestManager.requestString(
                "com.alipay.loanpromoweb.promo.virtualProfit.queryEnableVirtualProfitV2",
                "[{\"firstSceneCode\":[],\"profitType\":\"ANTBANK_WELFARE_POINT\",\"sceneCode\":" + sceneCodes
                        + ",\"signInSceneId\":\"" + signInSceneId + "\"}]");
    }

    /** 批量使用虚拟权益（积分兑换） */
    public static String batchUseVirtualProfit(String virtualProfitIdsJsonArray) {
        return RequestManager.requestString(
                "com.alipay.loanpromoweb.promo.virtualProfit.batchUseVirtualProfitS",
                "[{\"virtualProfitIdList\":" + virtualProfitIdsJsonArray + "}]");
    }
}
