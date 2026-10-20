# 惠生活去死吧

[![Star](https://img.shields.io/github/stars/PanSomeone/798QuSiBa?style=social&label=Star)](https://github.com/PanSomeone/798QuSiBa)
[![Issue](https://img.shields.io/github/issues/PanSomeone/798QuSiBa)](https://github.com/PanSomeone/798QuSiBa/issues)

**⭐ [给我的项目加个 Star 吧](https://github.com/PanSomeone/798QuSiBa)** —— 无服务器、无广告、无数据分析，一个 star 是它唯一能被看到的途径。

一个只做一件事的「惠生活798」第三方 Android 客户端：把宿舍热水打开。

> 自用学习工具，不发布、不商用、不对外分发。只调用官方 App 已在用的同一接口，不做绕过、破解、篡改；登录复用官方 App 已有登录状态。

## 下载

- **国内**：[Gitee 发行版](https://gitee.com/pansomeone/798QuSiBa/releases) —— 同一个包，国内拉得动。
- **国外**：[GitHub Releases](https://github.com/PanSomeone/798QuSiBa/releases)。
- **网页版**（给 iPhone）：<https://pansomeone.github.io/798QuSiBa/>。

## 功能

- **一键开关热水。** 主界面单按钮：未出水显示「一键启动热水」，出水显示「关闭热水」。
- **两屏。** 底部悬浮胶囊条切换「使用 / 设置」。
- **短信登录**（手机号 + 图形验证码），兜底**粘贴 token**。
- **扫码添加设备**：扫机器上的二维码，或把二维码内容粘进来，设备就进了账号 —— 官方 App 扫同一张贴纸做的是同一件事（`qr/use` 换取设备号 + `dev/favo` 加入收藏，而收藏列表就是 `ui/app/master` 的 `favos[]`）。没有相机权限或扫不出来时可以手动输入。
- **多设备选择**、**深色 / 浅色主题**（默认深色，持久化）。
- **导出脱敏运行日志**；反馈走 GitHub issue。

## 实现要点

- 只用公开 REST 网关：`https://i.ilife798.com/api/v1/`，鉴权 `Authorization: <token>` + `ApplicationType`。私有 RPC 签名仅作参考，**从不发送**。
- 开热水：`HotWaterViewModel.toggleWater()`；在途请求直接丢弃，按状态调 `dev/start` / `dev/end`。网关无幂等键，防重必须在客户端。
- `code == 0` 成功；`-99` 会话失效 → 重新登录；其他拒绝码映射为人话：`-21` 重新登录、`-82/-52` 付费/欠费、`-88/-87` 签约、`-20` 未绑卡、`-19` 设备准备中、`-15` 被占用、`-2` 未结算。
- `gene.status` 的 `99` 是「服务器未上报」，不推断开关。
- 登录必须验证响应含带 id 的 `account`；短信登录依赖 `cid`，本项目用随机值顶替，**可能被拒**；粘贴 token 不依赖。
- 日志在写入时脱敏，保留首尾各 4 位；`LogRedactor` 有测试覆盖。

## 构建与运行

环境：JDK 25、Gradle 9.8.1、AGP 9.4.1、Kotlin 2.4.21、Android SDK platform 37 / build-tools 36.0.0。AGP 9 起需删除 `org.jetbrains.kotlin.android` 插件；Compose / serialization 插件须与 KGP 同版本。

接口地址构建期注入，优先级：`-PhuishuiBaseUrl` → 根 `gradle.properties` → `local.properties`（推荐）→ 兜底 `https://example.invalid/`。

```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
huishuiBaseUrl=https://i.ilife798.com/api/v1/
```

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat assembleDebug
```

R8 后 release 约 9.5MB（其中约 5MB 是扫码用的 ML Kit 原生模型），debug 约 40MB；arm64 单 ABI，release 与 debug 的差别只在 ABI 与是否混淆。启动中位约 434–439ms；待机动画优化后 0 帧。基线配置文件手写 23 条，两遍校验通过；模拟器上启动提升在噪声内。

## 代码结构

```
app/src/main/java/com/killhuishenghuo/
├── KillHuiShengHuoApp.kt
├── MainActivity.kt
├── core/ log net sign
├── data/ local model remote repository
└── ui/ theme components hotwater settings start login
```

47 个主源文件、7223 行；16 个测试文件、3164 行；1 个仪器测试、141 行。`minSdk 26`，`targetSdk 37`，JVM 17。

## 已知限制

- 「按了按钮、热水真的出来」未端到端验证。
- `cid` 是猜的，短信登录可能被拒。
- `args` 取值未穷尽；`ApplicationType` 映射按路径猜。
- 多设备界面只编译过、未走查。
- 仪器测试只覆盖氛围动画是否产帧；切页 jank 只有测量、无回归测试。
- 按钮防重仅应用侧：防同一根手指连点，不防两台手机同时操作同一设备。
- **扫码只发 arm64**：bundled ML Kit 每个 ABI 约 5MB，四个全留会让包涨到约 22MB，所以 32 位设备装不上（debug 变体额外保留 x86_64，只为模拟器能打开扫码页）。
- **扫码那条路没在真机上走过**：解析、绑定与线格式都有单测钉着（`DeviceQrTest`、`GatewayContractTest`），扫码页在模拟器上打开过、也真的解码过一张二维码，但「对着机器上的贴纸扫」这一步只有你能验。
- `dev/favo` 是否真能让设备出现在 `favos[]` 里，来自一个能用的第三方客户端，**尚未在本项目自己的账号上成功过一次**。
- **R8 会删掉 ML Kit 扫码器的内部类**：它按名字在运行时拼装那些对象，R8 看不见，于是删掉——构建不报错、单测不报错，只有**压缩包**在打开相机的第一帧抛 `NullPointerException`（`benchmark` 包曾经因此 446ms 就自己退出）。`app/proguard-rules.pro` 里那几条 keep 规则就是为它写的，`tools/verify-scan-keeps.ps1` 每个构建都拿 `mapping.txt` 复核一次，代价约 +131KB。

## 反馈

无服务器、无邮箱，反馈走 [GitHub Issues](https://github.com/PanSomeone/798QuSiBa/issues/new)，或者在 QQ 群 `1126108304` 里直接说。设置页「反馈」可生成脱敏诊断文本，不含 token。想支持就 [点个 Star](https://github.com/PanSomeone/798QuSiBa)。

## 许可与用途

个人学习研究用。不发布、不分发、不商用。只调用官方 App 已在用的接口，不绕过付费、伪造凭证、破坏设备或攻击服务器。