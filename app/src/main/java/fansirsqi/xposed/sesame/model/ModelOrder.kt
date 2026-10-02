package fansirsqi.xposed.sesame.model

import fansirsqi.xposed.sesame.task.AnswerAI.AnswerAI
import fansirsqi.xposed.sesame.task.EcoProtection.EcoProtection
import fansirsqi.xposed.sesame.task.antCooperate.AntCooperate
import fansirsqi.xposed.sesame.task.antDodo.AntDodo
import fansirsqi.xposed.sesame.task.antFarm.AntFarm
import fansirsqi.xposed.sesame.task.antForest.AntForest
import fansirsqi.xposed.sesame.task.antMember.AntMember
import fansirsqi.xposed.sesame.task.antOcean.AntOcean
import fansirsqi.xposed.sesame.task.antOrchard.AntOrchard
import fansirsqi.xposed.sesame.task.antSports.AntSports
import fansirsqi.xposed.sesame.task.antStall.AntStall
import fansirsqi.xposed.sesame.task.backupSync.BackupSync
import fansirsqi.xposed.sesame.task.browseVideo.BrowseVideo
import fansirsqi.xposed.sesame.task.gameCenter.GameCenter
import fansirsqi.xposed.sesame.task.greenFinance.GreenFinance
import fansirsqi.xposed.sesame.task.other.OtherTask
import fansirsqi.xposed.sesame.task.reserve.Reserve
import fansirsqi.xposed.sesame.task.sesameCredit.SesameCredit
import fansirsqi.xposed.sesame.task.welfareCenter.WelfareCenter

object ModelOrder {
    private val array = arrayOf(
        BaseModel::class.java,       // 基础设置
        AntForest::class.java,       // 森林
        AntFarm::class.java,         // 庄园
        AntOcean::class.java,        // 海洋
        AntStall::class.java,      // 蚂蚁新村
        AntDodo::class.java,       // 神奇物种
        AntCooperate::class.java,    // 合种
        AntMember::class.java,     // 会员
        AntOrchard::class.java,    // 农场
        AntSports::class.java,       // 运动
        EcoProtection::class.java,     // 古树
        GreenFinance::class.java,  // 绿色经营
        Reserve::class.java,       // 保护地
        BrowseVideo::class.java,   // 视频红包
        GameCenter::class.java,    // 游戏中心 (移植自芝麻糊SVIP)
        SesameCredit::class.java,  // 芝麻信用 (移植自芝麻糊SVIP)
        WelfareCenter::class.java, // 福利中心 (移植自芝麻糊SVIP)
        OtherTask::class.java,      // 其他
        AnswerAI::class.java,       // AI答题
        BackupSync::class.java      // 配置备份同步 (移植自芝麻糊SVIP)

    )

    val allConfig: List<Class<out Model>> = array.toList()
}
