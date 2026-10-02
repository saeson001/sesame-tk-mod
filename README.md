# Sesame-AG（TK 修改版 · 1.0.0）

基于 [witrer/Sesame-AG-2026](https://github.com/witrer/Sesame-AG-2026)（Kotlin, v0.9.9），
从 `芝麻糊 SVIP 2.0.6.6` 逆向移植了 4 个缺失任务模块，并做了一系列稳定性与易用性增强。

> ⚠️ 仅供学习研究。伪造请求跑任务违反支付宝用户协议，存在黑号风险，请勿使用主力账号。

> **许可证**：本项目基于 [witrer/Sesame-AG-2026](https://github.com/witrer/Sesame-AG-2026)（**AGPL-3.0**）修改，沿用 AGPL-3.0。正式条款见 [`LICENSE`](LICENSE)，白话解读见 [`LEGAL.md`](LEGAL.md)；参与讨论请看 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

---

## 本次改动一览

### v0.9.9 · 移植任务（基础）
| 文件 | 说明 |
|---|---|
| `task/gameCenter/` | **游戏中心**：签到 / 批量收积分球 / 任务报名完成 |
| `task/sesameCredit/` | **芝麻信用**：领取信用积累 / 安心豆签到 / 安心豆任务 |
| `task/welfareCenter/` | **福利中心**：网商银行福利签到 / 好家无忧卡触发 / 积分查询 |
| `task/backupSync/` | **配置备份同步**：config 目录整体上传 WebDAV（OkHttp，不走支付宝 RPC） |
| `model/ModelOrder.kt` | 注册以上 4 个新任务 |

### v1.0.0 · 稳定性 + 易用性
- **修闪退**：默认跳过 `Detector.initDetector()`（原 `libchecker.so` 的 JNI SIGABRT 根因），LSPosed 开关可正常启用 TK。
- **版本号规范**：正式版递增 `1.0.0 / 1.0.1 / 1.1.0 ...`，不再挂 `-fix1 / -tool1` 后缀；一次性排查包才用 `-PverName=1.0.0-diag1`。
- **出包 ABI**：默认只打 `arm64-v8a`（主流机型），需要 universal 包时加 `-Puniversal=true`。
- **运动步数**：基座已原生支持「修改运动步数」（hook `PedometerAgent.readDailyStep()` 取 max(真实, 设定) + `RpcManager.a(step,...)` 同步服务端），开箱即用。
- **手机端抓包分析**：新增「抓包分析(挑接口→导出)」界面，可在手机上搜索 RPC、展开格式化 JSON、一键导出 `rpc_export_*.txt`（MediaStore，免存储权限），无需连电脑取文件。
- **加任务工具**：`tools/tk_tools.py`（list / show / newtask / wizard），一条命令生成任务类并自动注册进 `ModelOrder.kt`。

---

## 构建

### 出包约定
```bat
cd Sesame-AG
gradlew.bat assembleDebug                  :: 默认只出 arm64-v8a
gradlew.bat assembleDebug -Puniversal=true  :: 额外再出 universal 包
gradlew.bat assembleDebug -PverName=1.0.0-diag1  :: 临时排查包
```
产物在 `app/build/outputs/apk/debug/`，文件名带时间戳不会互相覆盖。

### 方式 A：GitHub Actions（推荐，无需本地环境）
1. Fork / 克隆本项目；
2. 仓库 Actions 页选择 "Build Sesame-AG" → `workflow_dispatch` 手动触发；
3. 完成后从该次 run 的 artifacts 下载 APK。

（JDK 17 + Android 36 已由 `.github/workflows/build.yml` 配好）

### 方式 B：本地命令行（Windows 实测通过）
```bat
:: 前置：JDK 17+ 与 Android SDK(platform 36 / build-tools 36)
:: 1) gradle 发行包已改腾讯镜像（gradle/wrapper/gradle-wrapper.properties）
:: 2) local.properties 指向你的 SDK（本仓库已 .gitignore，需自行创建）：
::    sdk.dir=C:\\path\\to\\Android\\Sdk
set JAVA_HOME=<你的JDK17+路径>
gradlew.bat assembleDebug --console=plain
```

> 若源码是从 zip 解包（无 `.git`），`app/build.gradle.kts` 取 `git rev-list --count HEAD`
> 求 versionCode 会失败，本项目已加 `isIgnoreExitValue = true` 兜底为 1，可直接构建。

---

## 安装使用
1. 安装 APK，LSPosed 中启用模块；
2. 勾选作用域：**支付宝**；
3. 重启支付宝（或重启手机）；
4. 打开模块界面 → 任务列表 → 开启「游戏中心」「芝麻信用」「福利中心」「配置备份同步」等新模块开关，按需调整子开关。

---

## 抓包 / 加任务开发流程

TK 注入支付宝后所有 RPC 请求/响应实时写入（不需要开关，支付宝启动即记录）：
```
/sdcard/Android/media/com.eg.android.AlipayGphone/sesame-TK/log/rpc_cap.txt
```

**首选（手机上直接看 + 导出）**：TK → 设置页 →「抓包分析(挑接口→导出)」
→ 搜索接口 → 展开看格式化 JSON → 导出到 `下载/sesame-TK-rpc/rpc_export_*.txt`（可直接分享给电脑）。

**电脑侧分析 / 一键加任务**（`tools/tk_tools.py`）：
```bat
python tk_tools.py list   rpc_export_all.txt          :: 列出所有 RPC 方法与次数
python tk_tools.py show   rpc_export_all.txt queryXxx  :: 看某条请求/响应原文
python tk_tools.py newtask MyTask 中文名 --rpc com.xxx.query --params "[{...}]"  :: 生成并注册任务
```
生成后回到本仓库 `gradlew.bat assembleDebug` 编译即可在界面看到新开关。
详见 `tools/使用说明.md`。

---

## 与原版芝麻糊 SVIP 的对应关系
| 芝麻糊 SVIP 任务 | 本项目状态 |
|---|---|
| 蚂蚁森林 / 庄园 / 海洋 / 新村 / 神奇物种 / 合种 / 会员 / 农场 / 运动 / 古树 / 绿色经营 / 保护地 | ✅ 原生已有 |
| 视频红包 / AI 答题 / 其他任务 | ✅ 原生已有 |
| **游戏中心 / 芝麻信用 / 福利中心** | ✅ 本次移植 |
| **配置备份(WebDAV)** | ✅ 本次移植（精简版：整目录上传/恢复） |
| 卡密登录 | ❌ 有意去掉（收费点） |

---

## 目录速查
```
app/src/main/java/fansirsqi/xposed/sesame/
├── hook/
│   ├── ApplicationHook.kt          # Xposed 注入入口
│   ├── RequestManager.kt           # RPC 请求管理（熔断/重试）
│   ├── internal/RpcCaptureHelper.kt# 自动抓包
│   └── rpc/bridge/                 # NewRpcBridge/OldRpcBridge
├── model/ModelOrder.kt             # ★ 任务注册表
├── task/                           # ★ 所有任务模块
├── ui/                             # ★ 手机端界面（含抓包分析）
└── util/                           # 工具类
```
