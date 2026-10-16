# 惠生活去死吧

一个**只做一件事**的「惠生活798」第三方 Android 客户端：把宿舍的热水打开。

> 自用学习工具，不发布、不商用、不对外分发。只调用官方 App 已经在用的同一个接口，
> 不做任何绕过、破解、篡改；登录方式是**复用官方 App 已有的登录状态**。

## 功能

- **一颗按钮开热水。** 主界面只有一颗按钮：没出水时写「一键启动热水」，正在出水时写「关闭热水」。
- **两屏，没有导航图。** 底部一条悬浮胶囊条在「使用 / 设置」之间切换。
- **短信登录**（手机号 + 图形验证码），以及兜底的**粘贴 token**（抓一条官方 App 的请求把
  `Authorization` 复制进来）。
- **多设备选择**（账号下有多台时才出现）、**深色 / 浅色主题**（默认深色，选择持久化）。
- **导出运行日志**：把这个进程从启动到现在做过的事写成一个 `.txt` 走系统分享面板，token 与
  手机号在写入时就已脱敏。项目没有服务器，反馈走「复制到剪贴板」。

## 核心是怎么实现的

### 只用 REST 网关，不用私有 RPC

接口有**两套**：

| | 官方 App 的私有 RPC 层 | 公开 REST 网关 |
|---|---|---|
| 端点 | `SilkwormFusion` / `FusionService.*` | `https://i.ilife798.com/api/v1/` |
| 鉴权 | `X-Garen` / `X-Nami` / `X-Ashe` 三个签名头 | `Authorization: <token>` + `ApplicationType` |
| 可否查证 | 只有抓包能看到 | 公开客户端里逐行可读，也实测探活过 |
| 本项目 | 保留为参考实现，**从不发送** | **唯一在用的线路** |

签名算法本身是已知的（实现在 `core/sign/`，有测试并与抓包样例逐字节对齐），但**不挂在 OkHttp
链上**：用它就必须知道每个方法的 `servername` / `methodname` / 路径 / 字段名，而这些在公开渠道
一个都查不到（对四个公开仓库 grep 是 0 命中）。一个知道签名算法却不知道该签什么的实现，是发不
出去的。

### 一次「打开热水」

```
点击那颗按钮
  └─ HotWaterViewModel.toggleWater()
       ├─ 已有请求在途？ → 直接丢弃这次点击，发都不发
       └─ 不在出水 → dev/start      在出水 → dev/end
            └─ ApiResultMapper  把 {"code":0} 或 {"code":-99} 翻译成 Outcome
```

网关**没有幂等键**（`dev/start` 只接受一个 `cnt`），所以**重试一次就是真的再开一次阀门**。防
重复只能放在客户端：在途期间的点击直接丢弃，连发都不发，两个方向都要挡。

`code == 0` 就是全部确认，成功之后**不回读设备状态**——那会和服务器自己的状态更新赛跑。
`code == -99` 是所有需鉴权接口共用的会话失效码，翻成 `AppError.Unauthorized`，界面直接指向
设置而不是显示一个只会同样失败的「重试」。其它拒绝码也翻成了人话：

| 码 | 意思 | 界面上的说法 |
|---|---|---|
| `-21` | token 作用域不对 | 需要重新登录 |
| `-82` / `-52` | 需要支付 / 账户欠费 | 这笔要付费 / 账户欠费 |
| `-88` / `-87` | 未签约代扣 / 签约过期 | 需要重新签约 |
| `-20` | 未绑定一卡通 | 没绑卡 |
| `-19` | 设备准备中 | 设备还没准备好，稍后再试 |
| `-15` | 设备被占用 | 这台设备有人正在用，等它空下来再试 |
| `-2` | 设备运行中，需要在设备上点击结算 | 上一次的使用还没结算 |

**「已经开着」的宽容边界是拒绝码，不是方向。** `-2` 表示本账户在这台设备上有一次没结算的会话，
开、关两个方向都算成功；`-15` 表示别人正在用，两个方向都是失败。判据钉在码上（有单测），因为
老版本曾经靠消息文本判断，而那句「当前设备正在使用」里没有「运行」两个字。

### `gene.status` 的 99 是「服务器没说」

设备状态字段在真机上一直报 **99**：出水时是 99，关着时是 99，刚 `dev/start` 成功之后还是 99
——它完全不跟踪阀门。所以常量叫 `Device.STATUS_UNREPORTED`，`isRunning` 和 `hasStatus` 都为假，
界面按「不知道」措辞，不替服务器表态。（早先的版本把它解释成「开着却没结算的会话」，于是用户
一登录、什么都没开就被告知「上次没结算」；两份真机日志否掉了这个因果。）

「上一次没结算」只在 `dev/end` 被 `-2` 拒绝时才会被说出来，并且一次被接受的 `dev/start` 会清掉它。

### 登录：不验证 token 就等于没登录

网关对**不认识的 token 返回 `code:0` 且不含 `account`**，和完全不带 token 时一模一样。拿「请求
成功了」当「token 是对的」，就会用一个打错的 token 把用户「登录成功」，然后显示一个永远是空的
设备列表。所以判据是：响应里必须有一个**带 id 的 account**。

短信登录那条路依赖请求体里的 `cid`——官方 App 的构建期密钥，本项目只能给每个安装生成一个稳定的
随机值顶替，**可能被服务器拒绝**；粘贴 token 那条路不依赖它。

### 日志在写入时就脱敏

脱敏发生在 `AppLog.log` 里，不在导出时：那个环形缓冲区本身会被崩溃报告、被下一个加日志的人、
被任何一次 `dump()` 读，一个装着活 token 的缓冲区是一份等着被泄露的凭据。保留首尾各 4 位以便
回答「是不是同一个 token」，而不是「token 是什么」。`LogRedactor` 是纯函数，18 个测试钉着它，
包括一条「不含凭据的字符串必须原样通过」——脱敏器最常见的失败模式是把正常内容也吃掉，那种失败
不会报错，只会让日志变得没用。

## 构建与运行

环境（本机实测通过的组合）：**JDK 25**（Android Studio 自带的 JBR）、Gradle **9.8.1**（wrapper
已提交）、AGP **9.4.1**、Kotlin **2.4.21**、Android SDK platform **37** / build-tools **36.0.0**。

**AGP 9 的坑**：从 AGP 9.0 起 Kotlin 由 AGP 自己编译，`org.jetbrains.kotlin.android` 插件必须
删掉；Compose / serialization 两个编译器插件仍要，但必须和 AGP 加载的 KGP 同版本，所以顶层
`build.gradle.kts` 里显式钉了 `kotlin-gradle-plugin:2.4.21`。

**接口地址是构建期注入的**，按优先级找 `-PhuishuiBaseUrl` → 根 `gradle.properties` →
**`local.properties`（推荐，不会被提交）** → 兜底 `https://example.invalid/`：

```properties
# local.properties —— 结尾的斜杠不能少，Retrofit 拿它拼相对路径
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
huishuiBaseUrl=https://i.ilife798.com/api/v1/
```

兜底用 `.invalid`（RFC 2606 保留域名，永远解析不了），所以一个没配置过的构建会在第一次请求时
明确失败，而不是悄悄把凭据发到某个地方去；界面顶部也会直接说明没有配置接口地址。

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

.\gradlew.bat testDebugUnitTest        # 196 个单元测试
.\gradlew.bat connectedDebugAndroidTest # 2 个仪器测试（要模拟器/真机）
.\gradlew.bat assembleDebug            # 日常构建

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.killhuishenghuo/.MainActivity
```

### 三个构建变体，按「要不要 R8」分

| 变体 | 产物 | `applicationId` | 大小 | 什么时候用 |
|---|---|---|---|---|
| `debug` | `app-debug.apk` | `com.killhuishenghuo` | 19,823,911 B | 平时开发：可调试、无混淆，栈里有真名字 |
| `benchmark` | `app-benchmark.apk` | `…`.benchmark | 1,582,974 B | **想要快的就用这个**：和 release 同一份代码，debug key 签名，可与 debug 共存 |
| `release` | `app-release-unsigned.apk` | `com.killhuishenghuo` | 1,574,770 B | 要自己签名发布时用；没配 keystore 时产物是未签名的 |

`benchmark` 是用 `initWith(getByName("release"))` 从 `release` 派生的，所以**两者不可能漂移
成两个不同的程序**——任何加进 `release` 的东西自动也在 `benchmark` 里，而它是 debug key 签名的，
谁都能构建、安装、卸载，不碰读者的真实应用。

**R8 打开之后体积是 1/12.6**（19.8MB → 1.58MB）。项目**没有反射式依赖注入、JSON 解析全部走
Kotlin 编译器插件生成的序列化器**，所以 `proguard-rules.pro` 不需要任何 keep 规则；
minSdk 26 也不需要 desugaring 之外的特殊处理。

发布页上那三个产物的 sha256：

```
惠生活去死吧-fast.apk             1,582,974 B  B7E5E40F7D469E005B791443C63E57CE2A5273D13DFA33243B9BE73A4BE49B3C
惠生活去死吧-release-unsigned.apk 1,574,770 B  07DB01A05511310B70255CF2DA79DB856F3E5B8A68A71288442B36BD9CB8EE67
惠生活去死吧-debug.apk           19,823,911 B  82473ED5CC78E7151CCB253B5FF9D623DB204586B5AD10E2525C2FE51776CCCD
```

**实测状态**（本机模拟器 `Medium_Phone_API_37.0`，Android 17）：

- `assembleDebug assembleBenchmark assembleRelease testDebugUnitTest` BUILD SUCCESSFUL；
  **196 个单元测试 / 14 个类，0 失败**。
- `connectedDebugAndroidTest`：**2 个仪器测试通过**。它们用 `ActivityScenario` 加
  `Window.OnFrameMetricsAvailableListener` 数真实帧，断言水关着时氛围动画一帧都不请求、
  水开着时请求。（不是 Compose 的测试时钟：一个「水开着就一直跑」的效果永远不 idle，
  `createComposeRule` 在退出时会等一个不会到来的空档。）
- **三个变体都在模拟器上装过、点过。** `release` 用 debug key 签了一份装上去走查，冷启动
  **连开两次都没有异常**——这条特意做过，因为 R8 断掉反射式序列化、以及下面那个探针的崩溃，
  都只会在压缩包里炸，构建期和 `debug` 包都是绿的。
- 冷启动 `am start -W`：`benchmark` 中位 **434–439ms**（5 次），
  `debug` 1996–2284ms（早前记录）。切页面连续 10 次来回：**1096 帧、Missed Vsync 0**、
  p50 17ms / p90 18ms、janky 2.55%（集中在每次切换的第一帧，因为它要组合整个新页面）。
- 真网关的 `captcha/` 与 `acc/login/code` 都返回过 200。`GatewayContractTest` 用真实 Retrofit +
  MockWebServer 断言**上线线格式**（路径、query 参数、三个客户端头、`Authorization` 裸发）。

### 待机时不再产帧

底部的氛围动画（蒸汽 / 涟漪）曾经是 `rememberInfiniteTransition`：它拥有自己的帧回调，
只要对象存在就**一直**要下一帧——哪怕画布的 draw lambda 第一行就已经 `return` 了。实测
20 秒待机 **1211 帧（60.55 fps）**，p50 帧时间 21ms，也就是每一帧都跨过一个 vsync 边界，
为一幅空画面走完整条渲染流水线。

改成「没有东西可画就挂起」之后，同样条件：**0 帧**。原始数据、完整命令与这份测量的
能证明/不能证明什么，见 [`tools/PERFORMANCE.md`](tools/PERFORMANCE.md)。

### 启动路径上的基线配置文件

`app/src/main/baseline-prof.txt` 是手写的，覆盖进程启动到第一帧。手写而不是用
`androidx.baselineprofile` 插件生成，是因为生成路径要下载 `androidx.benchmark`，而这个项目的
依赖集是刻意压小的。

手写的风险是**签名写错不会报错**：dexopt 与 R8 都只是静默丢掉不匹配的行，构建照样成功。
所以这个文件被两遍校验，两个都通过才算数：

```powershell
py tools\check-profile.py --classes    # 对编译产物的 class 文件核名字与参数个数
py tools\check-profile.py --mapping    # 对 R8 的 mapping.txt 核它是否还存在
```

第二遍不是多余的，第一版 29 条里有 5 条**源码里正确、APK 里不存在**（字面量 `object` 与
返回 `FontFamily.Default` 的取值器都被内联后删除）。当前 **23 条，两遍都全数通过**。

它只进压缩过的变体：`unzip -l` 设备上装好的 `benchmark` 包能看到
`assets/dexopt/baseline.prof`，而 `debug` 包里没有——AGP 会把 profile 从 debuggable 变体剥掉，
所以**调试包永远不会体现它的效果**。

**它让启动变快了吗：在模拟器上量不出来。** 全新安装中位 439ms，装上 profile 后中位 434ms，
5 毫秒在噪声里。这里不打算把这个数字说成一次胜利——模拟器上 ART 的 dexopt 行为与真机不同，
而这个应用的启动路径本来就短。能证明的是每一行都指向一个真实存在的方法。细节与完整数字见
[`tools/PERFORMANCE.md`](tools/PERFORMANCE.md)。

应用自己会在首帧提交时记一行 `Startup  first frame XXms` 到日志里，所以真机上的启动时间可以
从「设置 → 导出日志」读出来，不需要复现测量。这个探针**崩过一次，而且只有压缩包才崩**：
它在回调里摘掉自己、`onDestroy` 里又摘一次，两个调用点单独看都对，但 R8 把回调内联进
`onDestroy` 之后，两个摘除点从互斥变成了串行，于是每次启动都在首帧
`IllegalArgumentException: attempt to remove OnFrameMetricsAvailableListener that was never added`。
修法是一个幂等的摘除函数（先清字段再移除）。这一条留在文档里，是因为它是这个项目里唯一一个
**单测全绿、装机才炸**的 bug。

### 搬到另一个目录、或另一台机器

**不要在资源管理器里拖文件夹。** 会带上四类属于这台机器的东西：`build/`、`.gradle/`、`.idea/`、
`local.properties`。前三类只是拖慢第一次构建；第四类会**改变行为**——新目录里没有它，应用顶部会
显示「这个构建没有配置接口地址」，看起来像代码坏了。

```powershell
.\tools\move-project.cmd -Destination "D:\code\798QuSiBa" -DryRun    # 只打印计划
.\tools\move-project.cmd -Destination "D:\code\798QuSiBa" -SkipAttic # 真搬
```

搬完在新目录里 `.\gradlew.bat assembleDebug testDebugUnitTest`。**这条验证过**：搬到纯 ASCII 路径
后 196 个单测全过，APK 与原目录**逐字节相同**（上面那个 sha256）。换机器还要改 `sdk.dir`、确认
`huishuiBaseUrl` 在、装 JDK 25。

**目标路径不要有中文**，AGP 会在配置阶段直接拒绝（`Your project path contains non-ASCII
characters`）。它有个逃生开关 `android.overridePathCheck=true`，实测也确实能过，但它是逃生开关
而不是默认：路径会一路传到 `aapt2` / `d8`，那些工具对代码页的处理和 Gradle 不是一回事。
**空格是安全的**，只有非 ASCII 触发检查。也别放在同步网盘里——它会锁住 `build/` 里的文件。

## 代码结构

```
app/src/main/java/com/killhuishenghuo/
├── KillHuiShengHuoApp.kt        手写依赖装配（session / gateway / repository，全 by lazy）
├── MainActivity.kt              两个目的地 + 全局唯一的那条悬浮条
├── core/
│   ├── log/                     AppLog（内存环形缓冲）/ LogRedactor（写入时脱敏）
│   │                            / WireLogInterceptor（每次请求与响应）/ LogExporter
│   ├── net/                     ApiConstants（路径、参数、业务码，全带出处注释）
│   │                            Interceptors / NetworkModule / ApiResultMapper
│   └── sign/                    私有 RPC 签名 ← 参考实现，不发送
├── data/
│   ├── local/                   SessionStore + SharedPreferences 实现
│   ├── model/                   ApiEnvelope / Device / DeviceArgsWire / AppError / Outcome
│   ├── remote/                  HuiShengHuoApi（Retrofit 接口）
│   └── repository/              HuiShengHuoRepository / AuthRepository + 实现
└── ui/
    ├── theme/                   KhsColors / KhsTheme（深 + 浅双调色板，默认深色）+ Palette
    ├── components/              Glass（悬浮材质）/ GlassTabBar / WaterAmbience / LoginPanels
    ├── hotwater/                一颗按钮（主界面）
    ├── settings/                账号 / 设备 / 外观 / 反馈 / 关于
    ├── start/                   只剩「token 过期了，换一个」这一屏
    └── login/                   登录表单的状态与面板，被设置页和上面那一屏共用
```

47 个主源文件、7223 行；16 个测试文件、3164 行；1 个仪器测试文件、141 行（另有 1 个 60 行的
`src/debug` 量具 Activity，它进不了正式包）。`applicationId` 为
`com.killhuishenghuo`，`minSdk 26`，`targetSdk 37`，JVM target 17，依赖走
`gradle/libs.versions.toml`。

源码之外只有三样：`tools/`（搬工程脚本、待机帧测量脚本、两个校对基线配置文件的脚本、
[`PERFORMANCE.md`](tools/PERFORMANCE.md)）、
`DESIGN.md`（开发笔记：色板、尺度、组件契约，**不是对外文档**）、`_attic/`（反编译工具、参考实现、
真机截图，被 `.gitignore` 排除，编译不读它）。

## 已知限制
- **「按了按钮、热水真的出来」没有被端到端验证过。** 验证到的是线格式与抓包样例逐字一致、登录
  对真网关返回过 200；没有验证的是物理结果——水有没有真的流出来。这需要真账号 + 真绑定的设备。
- **`cid` 是猜的**，短信登录可能被服务器拒绝（粘贴 token 不受影响）。
- **`args` 的取值没有穷尽**：淋浴器（`dtype` 5/6/8）发空串实测符合；热水主机（`dtype 9`）可能
  要求非空参数，没有那类设备可验。
- **`ApplicationType` 的映射是按路径猜的**（参考实现证明这个头是按 token 作用域分的：`1,1` 设备
  控制 / `1,5` 账户服务）；写成 `ApiConstants.applicationTypeFor(path)` 是为了让这条规则有个名字
  可以改。
- **多台设备的界面只编译过、没走过查**：那个分组只有在账号下挂着两台以上设备时才渲染。
- **仪器测试只覆盖一件事**（氛围动画该不该产帧）。其余都是 JVM 单元测试；界面走查靠模拟器
  安装 + 截图人工看，没有自动化。切页面那 2.55% 的 janky 帧因此只有测量、没有回归测试。
- **按钮的空档只在应用这一侧**（三秒内不重复开始，但从不拦结束）：它防的是同一根手指连点，不是
  两台手机同时操作同一台设备。

需要补充的信息（`cid` 的真值、`dtype=9` 的 `args` 形状、真实 `ui/app/master` 响应体样例等）见
[`待补充清单.md`](待补充清单.md)。

## 许可与用途

个人学习研究用。不发布、不分发、不商用。只调用官方 App 已经在用的接口，不做任何绕过付费、
伪造凭证、破坏设备或攻击服务器的行为。
