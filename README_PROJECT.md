# Sesame-AG + 移植增强版

基于 [witrer/Sesame-AG-2026](https://github.com/witrer/Sesame-AG-2026)（Kotlin, v0.9.9），
从 `芝麻糊SVIP 2.0.6.6` 逆向移植了 4 个缺失任务模块。

> ⚠️ 仅供学习研究。伪造请求跑任务违反支付宝用户协议，有黑号风险，勿用主力账号。

## 本次改动

| 文件 | 说明 |
|---|---|
| `task/gameCenter/GameCenter.kt` + `GameCenterRpcCall.java` | **游戏中心**：签到 / 批量收积分球 / 任务报名完成 |
| `task/sesameCredit/SesameCredit.kt` + `SesameCreditRpcCall.java` | **芝麻信用**：领取信用积累 / 安心豆签到 / 安心豆任务 |
| `task/welfareCenter/WelfareCenter.kt` + `WelfareCenterRpcCall.java` | **福利中心**：网商银行福利签到 / 好家无忧卡触发 / 积分查询 |
| `task/backupSync/BackupSync.kt` + `BackupSyncClient.kt` | **配置备份同步**：config 目录整体上传 WebDAV（OkHttp，不走支付宝 RPC） |
| `model/ModelOrder.kt` | 注册以上 4 个新任务 |

协议还原方式：解析 APK 中 NP 控制流混淆的 `short[]` XOR 字符串表，
提取出每个任务的全部 RPC operationType + 请求 JSON 模板（详见 `../apk_analysis/decoded_strings.txt`）。

## 构建

### 方式 A：GitHub Actions（推荐，无需本地环境）
1. Fork 本项目到你的 GitHub；
2. 仓库 Actions 页选择 "Build Sesame-AG" → `workflow_dispatch` 手动触发；
3. 完成后从该次 run 的 artifacts 下载 APK。

（JDK 17 + Android 36 已由 `.github/workflows/build.yml` 配好）

### 方式 B：本地 Android Studio
1. 用 Android Studio 打开本项目；
2. 等待 Gradle Sync（已配阿里云 Maven 镜像）；
3. `Build → Build Bundle(s)/APK(s) → Build APKs`。

### 方式 C：本地命令行（已在 Windows 上实测通过）
```bash
# 前置：JDK 17+ 与 Android SDK(platform 36 / build-tools 36)
# 1) gradle 发行包已改为腾讯镜像（gradle/wrapper/gradle-wrapper.properties）
# 2) local.properties 指向你的 SDK：
#    sdk.dir=C:\\path\\to\\Android\\Sdk
export JAVA_HOME=<你的JDK17+路径>
gradlew.bat assembleDebug --console=plain
# 产物（ABI 分包）:
#   app/build/outputs/apk/debug/Sesame-TK-universal-0.9.9-debug.apk  ← 通用包，装这个
#   app/build/outputs/apk/debug/Sesame-TK-arm64-v8a-0.9.9-debug.apk  ← 主流机型
```

> 若源码是从 zip 解包（无 `.git`），`app/build.gradle.kts` 里取 `git rev-list --count HEAD`
> 求 versionCode 会失败。本项目已加 `isIgnoreExitValue = true` 兜底为 1，可直接构建。

## 安装使用

1. 安装 APK，LSPosed 中启用模块；
2. 勾选作用域：**支付宝**；
3. 重启支付宝（或重启手机）；
4. 打开模块界面 → 任务列表 → 开启「游戏中心」「芝麻信用」「福利中心」「配置备份同步」等新模块的开关，按需调整子开关。

## 抓包（新增任务开发流程）

本基座的 RPC 抓包默认自动开启（`RpcCaptureHelper`，双通道 hook），
操作支付宝里的目标活动后拉取记录：

```bash
adb pull /storage/emulated/0/Android/media/com.eg.android.AlipayGphone/sesame-TK/
```

把抓到的 `operationType + 参数` 抄进新的 `XxxRpcCall`，注册进 `ModelOrder` 即完成一个新任务。

## 与原版芝麻糊SVIP的对应关系

| 芝麻糊SVIP 任务 | 本项目状态 |
|---|---|
| 蚂蚁森林 / 庄园 / 海洋 / 新村 / 神奇物种 / 合种 / 会员 / 农场 / 运动 / 古树 / 绿色经营 / 保护地 | ✅ 原生已有 |
| 视频红包 / AI答题 / 其他任务 | ✅ 原生已有 |
| **游戏中心 / 芝麻信用 / 福利中心** | ✅ 本次移植 |
| **配置备份(WebDAV)** | ✅ 本次移植（简化版：整目录上传/恢复） |
| 卡密登录 | ❌ 有意去掉（收费点） |

## 目录速查

```
app/src/main/java/fansirsqi/xposed/sesame/
├── hook/
│   ├── ApplicationHook.kt          # Xposed 注入入口
│   ├── RequestManager.kt           # RPC 请求管理（熔断/重试）
│   ├── internal/RpcCaptureHelper.kt# 自动抓包
│   ├── internal/SliderBypassHelper.kt # 滑块绕过
│   └── rpc/bridge/                 # NewRpcBridge/OldRpcBridge
├── model/ModelOrder.kt             # ★ 任务注册表
├── task/                           # ★ 所有任务模块
└── util/                           # 工具类
```
