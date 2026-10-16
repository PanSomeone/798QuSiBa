# DESIGN

**这是开发笔记，不是对外文档。** 想了解这个应用是什么、怎么构建，看 [`README.md`](README.md)。

配套阅读：[`PRODUCT.md`](PRODUCT.md)（要做什么）。

---

## 1. 主题与色板

两套调色板：**深色（默认）与浅色**，定义在
[`app/src/main/java/com/killhuishenghuo/ui/theme/KhsColors.kt`](app/src/main/java/com/killhuishenghuo/ui/theme/KhsColors.kt)。
下面列出的是深色一套（浅色那一套的角色名相同、值不同）。

### 背景三层

| 名字 | 值 | 用途 |
|---|---|---|
| `Slate` | `#0E1420` | 屏幕底色 |
| `SlateRaised` | `#161E2C` | 卡片、抬起的面 |
| `SlateSunken` | `#0A0F18` | 输入框内部 |
| `Hairline` | `#243044` | 默认描边 |
| `HairlineStrong` | `#33415A` | 输入框未聚焦描边 |

`Palette.Slate` 同时以 `@color/window_background` 出现在
[`app/src/main/res/values/colors.xml`](app/src/main/res/values/colors.xml) 里，
供主题在 Compose 起来之前使用。**两处必须保持一致**（注释里互相指了路）。

### 文字三层

| 名字 | 值 | 用途 |
|---|---|---|
| `Ink` | `#E9EEF6` | 正文、标题 |
| `InkMuted` | `#9AA8BE` | 次要说明 |
| `InkFaint` | `#6C7C96` | 提示、占位、禁用 |

### 语义色

| 名字 | 值 | 含义 |
|---|---|---|
| `Heated` | `#FF6B35` | **唯一主强调色**：主按钮、本次开阀的那张卡 |
| `HeatedDeep` | `#C8451A` | 橙色的按下 / 深色变体 |
| `Water` | `#4CC2F1` | 水在流、链接、选中态 |
| `WaterDeep` | `#1E7FB0` | 「可开启」的描边 chip |
| `Warm` | `#FFB020` | 警告（构建未配置主机） |
| `Cooling` | `#4ADE80` | 已登录身份 |
| `Pending` | `#8B93A7` | 请求中。**中性灰，不是橙色** |
| `Danger` | `#FF5A5F` | 错误 |
| `DangerDeep` | `#B03035` | 错误的深色变体 |

**为什么「请求中」不用橙色**：橙色是「按这里」的意思。如果它同时表示「已经按了」，
它就不再传达任何信息。进行中的状态用中性灰，把橙色留给唯一的主操作。

## 2. 尺度

```kotlin
object Radii   { hairline 2.dp; chip 10.dp; card 16.dp; sheet 24.dp; hero 28.dp }
object Spacing { xxs 4.dp; xs 8.dp; sm 12.dp; md 16.dp; lg 24.dp; xl 32.dp; xxl 48.dp }
```

`Spacing` 的类型是 `Dp` 而不是 `Int`，这是有意的：每个消费者都是 Compose modifier，
转换只在这里做一次，而不是在一百个调用点各做一次（漏掉一个就是类型错误）。
`Radii` 同理必须是 `Dp`（写成 `sp` 会让 `RoundedCornerShape` 编译不过）。

## 3. 布局

### 首屏 = 初始化（[`ui/start/StartScreen.kt`](app/src/main/java/com/killhuishenghuo/ui/start/StartScreen.kt)）

```
Column(fillMaxSize + imePadding + verticalScroll, horizontal = lg)
├─ Spacer(xxl + xl)
├─ 「惠生活去死吧」    displayMedium，居中，唯一的一行标题
├─ Spacer(xxl)
├─ SmsForm（主）       手机号 / 图形验证码图+换一张 / 图形验证码 / 短信验证码 / 「点击登录账号」
│   或 TokenForm（次）  Authorization 的值 / 「点击登录账号」 / 一行抓包提示
├─ TextButton          「粘贴 token 登录」 ⇄ 「用手机号登录」（灰字，不是 tab）
└─ ErrorLine           失败时
```

界面上的字一共就这么几处：应用名、四个占位符、一个按钮、一个切换链接。**没有副标题、
没有免责声明面板、没有欢迎语**——第一屏是一个表单，表单上方是应用名，仅此而已。

`imePadding()` 在 `verticalScroll` **之前**，否则键盘弹出时按钮会被挡在下面。

短信验证码那格在未发送时**存在但禁用**（不是发送后才出现）：这样表单从第一帧起就是最终高度，
键盘弹出时下面没有任何东西会跳，用户也能一眼看出这条路上有几步。

### 主界面 = 一颗按钮（[`ui/hotwater/HotWaterScreen.kt`](app/src/main/java/com/killhuishenghuo/ui/hotwater/HotWaterScreen.kt)）

```
Scaffold(containerColor = surface)          ← 没有 TopAppBar
├─ Column(fillMaxSize, horizontal = lg, centered)
│  ├─ [会话失效 / 没有设备] → 按钮写「去设置」，琥珀色（needsSetup）
│  ├─ [加载不出来] ErrorPanel（按钮 = 「重新加载」）
│  ├─ [加载中] LoadingCard
│  ├─ [空] 水滴字形 + 「这个账号名下没有热水设备」+ 「去设置」
│  └─ OneTap
│     ├─ Spacer(xxl)
│     ├─ 设备名                headlineSmall，最多两行
│     ├─ 「热水已开 / 热水已关 / 正在处理 / 还没有可用的设备」  bodyLarge
│     ├─ Spacer(weight 1f)    ← 把按钮推到下半屏
│     ├─ [多台设备时] DeviceChoice   一排可点的芯片
│     ├─ [失败时] 错误条（在按钮**下方**，不推挤按钮）
│     └─ 那颗按钮             112dp 高，Radii.hero 圆角
└─ bottomBar = GlassScrim（内容淡出）+ GlassTabBar（使用 / 设置）
```

**顶栏删掉了。** 标题是应用自己的名字，launcher 已经在上一屏说过一遍；齿轮变成了第二个 tab。

按钮在**下半屏**而不是居中：拇指不用换手就能够到，上方那片空白是让按钮能被摸到、
而不是被看到的东西。

按钮的颜色与文字**一起**承载状态——「水开着没有」是这一屏存在的理由，应该隔着浴室就能回答：

| 状态 | 文字 | 底色 | 字色 |
|---|---|---|---|
| 正常、关着 | 一键启动热水 | `ctaFill` | `onCta` |
| 正常、开着 | 关闭热水 | `water` | `onWater` |
| 需要配置 | 去设置 | `ctaFill` | `onCta` |
| 处理中 | （转圈） | `ctaFill` | — |

### 悬浮条（[`ui/components/GlassTabBar.kt`](app/src/main/java/com/killhuishenghuo/ui/components/GlassTabBar.kt)）

```
MainActivity 的 MainTabBar（全局唯一一份，画在两个目的地之上）
└─ GlassSurface(shape = RoundedCornerShape(Radii.pill))
   └─ Row(padding = Spacing.xxs)
      ├─ TabButton(使用)   weight(1f)，48dp 高
      └─ TabButton(设置)   weight(1f)
```

**这条只能在根上画一份。** 早先的版本是两个屏各自在 `Scaffold(bottomBar = …)` 里画一条，
于是切目的地时**旧的那条被销毁、新的那条从零开始组合**——高亮胶囊在新标签底下被「画出来」，
而不是「移过去」。`animateDpAsState` 一直在，只是它每次都在一个新的组合里从 0 开始跑。
各屏现在只在 `bottomBar` 里放 `Spacer(Modifier.height(glassTabBarSpace()))`，**留空间不画东西**。

选中态的底是 `colors.water.copy(alpha = 0.18f)`，`role = Role.Tab`，`indication = null`
（水波纹在深色半透明底上是一层灰洗，而选中态本身会在手指下移动，那才是这里的反馈）。

`glassTabBarSpace()` 导出给各屏幕留底部空间——否则最后一行（设置里的版本号，以及它前面的
退出按钮）会永久躺在悬浮条下面。它本身要读 `WindowInsets.navigationBars`，所以是一个
`@Composable` 函数而不是常量。

## 4. 动效的三条硬规则

这三条都有测试或实测数字守着，不是口味问题。

### 4.1 静止时不许产帧

氛围动画（[`ui/components/WaterAmbience.kt`](app/src/main/java/com/killhuishenghuo/ui/components/WaterAmbience.kt)）
的两个时钟由 `rememberAmbienceClock(running, cycleSeconds)` 驱动，`running = false` 时在
`LaunchedEffect` 开头就 `return` —— 没有 frame callback，就一帧都不产。

这条规则是从一个实测数字来的：同一个 APK 只差这两个 `running` 参数，待机 20 秒的
`dumpsys gfxinfo` 是 **1211 帧（60.55 fps）/ p50 21ms** 对 **0 帧**。21ms 意味着一帧的
预算（16.7ms）**每一帧都被跨过**，为一幅空画面走完整条流水线。原始数据在
[`tools/PERFORMANCE.md`](tools/PERFORMANCE.md)。

以前用的是 `rememberInfiniteTransition`：它自己拥有 frame callback，只要对象活着就一直要
下一帧，**和画布是否提前 `return` 无关**。「动画对象存在」和「有东西可见」是两件事，把它们
当成一件事就会得到上面那个 60.55。

仪器测试 [`app/src/androidTest/…/WaterAmbienceFrameTest.kt`](app/src/androidTest/java/com/killhuishenghuo/ui/components/WaterAmbienceFrameTest.kt)
用一个每帧都重组的 `remember` 计数器数帧：水关着时断言**零帧**，水开着时断言**帧数大于零**
（后面这条同样重要——只断言「安静」的测试，把动画整个删掉也能通过）。

### 4.2 绘制阶段是纯函数，重活不许在里面做

`DrawScope` 里的 lambda 每帧跑一次。所以 `Brush`、`Path`、`Color` 一律在 `remember` 里
建好传进去，绘制时只做算术：蒸汽的径向渐变以**满 alpha** 建一次，十五个 puff 共用它，
各自的淡出交给 `drawCircle(alpha = …)`；涟漪的环在**基础半径**上建一次，生长靠画一个
更大的圆。

相位存在 `MutableFloatState` 里、在绘制 lambda 里读 `.value`，所以一动画帧只让**画布节点**
失效，而不是让整个效果**重组**。这一行之差，是每帧一次重组对每帧一次重绘。

### 4.3 可以停，不可以重置

时钟在 `running = false` 时只是不再推进，**相位留在原地**；恢复时第一帧只当基准、不累加，
所以停 40 分钟不会让蒸汽瞬间跳过一个循环的四分之三。淡出（260ms）期间时钟继续走，
否则蒸汽会冻在半空再溶掉——那是卡顿，不是淡出。

## 5. 组件契约

| 组件 | 文件 | 签名要点 |
|---|---|---|
| `GlassSurface` | [`ui/components/Glass.kt`](app/src/main/java/com/killhuishenghuo/ui/components/Glass.kt) | `(modifier, shape, content)`；elevation 按主题内部决定（深 18dp / 浅 8dp），**不能叠两层** |
| `GlassScrim` | 同上 | 内容在到达悬浮条之前淡出，避免一行字被压掉一半 |
| `GlassTabBar` | [`ui/components/GlassTabBar.kt`](app/src/main/java/com/killhuishenghuo/ui/components/GlassTabBar.kt) | `(selected: MainTab, onSelect, modifier)`；两个标签文字始终可见 |
| `MainTab` | 同上 | `enum { USE("使用", Home), SETTINGS("设置", Settings) }` |
| `SettingsScreen` | [`ui/settings/SettingsScreen.kt`](app/src/main/java/com/killhuishenghuo/ui/settings/SettingsScreen.kt) | `(authViewModel, dark, onThemeChange, host, lastError, devices, selected, onDeviceSelected, onSignOut)`；四个/五个 `Section`，**没有返回箭头**，**也没有切换 tab 的参数**——那条悬浮条不在任何一屏里 |
| `StartScreen` | [`ui/start/StartScreen.kt`](app/src/main/java/com/killhuishenghuo/ui/start/StartScreen.kt) | `(viewModel: AuthViewModel)`；两套表单共用一个 `LoginButton` |
| `ErrorPanel` | [`ui/components/StatusPanels.kt`](app/src/main/java/com/killhuishenghuo/ui/components/StatusPanels.kt) | `(title, message, onRetry, retryLabel = "重新加载")`；**必须有重试**，且按钮文字要说对用户接下来要干的事 |
| `LoadingCard` | 同上 | 骨架屏 |
| `CaptchaBox` | [`ui/login/LoginPanels.kt`](app/src/main/java/com/killhuishenghuo/ui/login/LoginPanels.kt) | `(captcha, isLoading, onRefresh)`；宽度由位图自身宽高比推导；刷新时保留旧图并降到 `alpha 0.35f` |
| `StatusChip` / `MetaRow` | **已删除**（原 `ui/components/Chips.kt`） | 一列设备卡片删除后没有调用者 |
| `DeviceCard` | **已删除** | 主界面改成一颗按钮后没有列表了 |
| `CenterAlignedTopAppBar` | **已删除** | 标题是应用名，launcher 已经说过；齿轮变成第二个 tab |
| `IdentityStrip` | **已删除** | 账号住进设置；主界面不需要再说「你是谁」 |
| `EmptyGlyph` | **已删除** | 只剩一个空状态，字形直接写在 `EmptyState` 里 |
| `TemperatureGauge` | **已删除** | 见 design-brief §3 |

## 6. 交互状态

主界面只有一颗按钮，所以只有两个状态，加上两个过渡：

| 状态 | 按钮文字 | 按钮色 | 状态行 |
|---|---|---|---|
| 设备不在出水 | 一键启动热水 | `ctaFill` / `onCta` | 热水已关 |
| 设备在出水 | 关闭热水 | `water` / `onWater` | 热水已开 |
| 请求在途 | 一键启动 或 关闭（保持原状） | 同上，`enabled = false` | 正在处理 |
| 需要配置（`needsSetup`） | 去设置 | `ctaFill` / `onCta` | 还没有可用的设备 |

**禁用态是 `surfaceSunken` 底 + `inkMuted` 字**，不是把活按钮调暗——所以它天然比活按钮安静，
而且这条差距有测试守着（`ContrastTest` 断言禁用对的对比度**小于**活按钮对的对比度）。

**在途期间按钮是禁用的，这是安全属性而不是美化**：网关没有幂等键，第二次请求就是物理上
真的再开一次阀。禁用同时挡住两个方向——关水在途时再点一下，如果不挡就会变成一次意外的开阀。

设备名下方不出现 `maxSeconds`：单次上限是设备自己会执行的事，不是这个屏幕上要读的东西。

## 7. 无障碍

- 那颗按钮是**唯一的**可点区域，而且它有 112dp 高、占满宽度：湿手、暗光、不看屏幕都能按中。
- 图标按钮都有 `contentDescription`（「设置」「换一张验证码」）。
- 可点区域 ≥ 48dp（悬浮条的每个 tab 是 48dp 高）。
- token 输入框是 `KeyboardType.Password`：抑制密码管理器收走凭据，
  也抑制输入法对大小写敏感的串做首字母大写。
- **对比度全部按 WCAG 公式现算**，见 [`ContrastTest.kt`](app/src/test/java/com/killhuishenghuo/ui/theme/ContrastTest.kt)。

## 8. 已验证的事实

- 模拟器安装、启动、界面走查截图：[`_attic/_proof/`](_attic/_proof/)（`_attic/` 被
  `.gitignore` 排除，clone 下来不会有这个目录）
- **待机产帧数：60.55 fps → 0**。同一份代码只改 `WaterAmbience` 两个时钟的 `running`
  参数，`tools/measure-idle.ps1` 20 秒窗口。数字、命令、以及这份测量证明不了什么，全在
  [`tools/PERFORMANCE.md`](tools/PERFORMANCE.md)。
- **冷启动首帧 426ms**（`am start -W`，`benchmark` 变体，模拟器）；`release`/`benchmark`
  安装启动**无异常抛出**——这条要单独验，因为 R8 断掉反射式序列化只在运行时炸。
- **系统栏跟随应用主题，不跟随手机**。`MainActivity` 里那个 `SystemBars(dark)` composable 用
  `SystemBarStyle.auto(lightScrim = <surface>, darkScrim = <surface>, detectDarkMode = { dark })`
  重设，所以换主题时它跟着变（触发它的是 `LaunchedEffect(activity, dark, background)`；这里
  原本写的是 `SideEffect`，那会在每次重组后都重写一遍 window flag）。`onCreate` 里那句**不带参数的
  `enableEdgeToEdge()` 已经删掉**——它会从**手机**的明暗设置推断图标颜色，于是浅色模式的
  手机上会在近黑底上画深色图标，时间与电量一起消失。决定颜色的只有 `SystemBars`。
- **主题不能用 `DayNight` 父样式。** 系统处于浅色模式时，`Theme.DeviceDefault.DayNight`
  会解析成浅色变体，在 Compose 绘制之前先画一条白色 action bar 和黑色应用名。
  现在父样式是 `android:Theme.DeviceDefault.NoActionBar`。
- 图形验证码实测可用：真机日志确认 `GET /api/v1/captcha/?s=…&r=…` 返回 `200 image/jpeg`，
  并在界面上正确渲染出五位数字。
- **短信登录打到了真网关**：填入手机号与图形码后
  `POST /api/v1/acc/login/code` → `200 (73ms)`，界面随即出现短信验证码那一格并把按钮改成
  「登录」。`ApplicationType` 在这一条上也是 `1,1`。
- **悬浮条的抬升是量出来的，不是看出来的**。深色：页面 `14,20,32` 对条 `32,39,53`；
  浅色：页面 `236,238,244` 对条白色。中间试过三次都失败（`0xC7` 太亮像块板砖、直接用
  `surface` 只差 7 个色阶等于没有、半透明被 `Modifier.shadow` 的 scrim 吃掉约 46%）。
- **`Modifier.shadow` 会在视图自己的层级画一层 scrim**，所以它会**吃掉填充色**而不只是落在
  它后面。探针证据：把填充设成纯红 `0xFFFF0000`，屏幕上量到 `138,13,19`——红色到了屏幕上，
  但被压掉约 46%。**不透明填充能幸存，半透明填充会被吃掉**，所以玻璃的抬升必须靠明度。
