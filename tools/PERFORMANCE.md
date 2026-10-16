# 实测记录

这份文件是一组**可复现的测量**，不是设计说明。它回答三个问题：这个应用在没人碰它的
时候花多少渲染成本、切一次页面要多少帧、以及进程启动到第一帧有多久。

## 测量环境

模拟器 `Medium_Phone_API_37.0`（x86_64，Android 17），屏幕亮着、应用在前台、没有登录
也没有设备——也就是这个应用**绝大部分时间所处的状态**。

用 `gfxinfo` 而不是 Macrobenchmark：`androidx.benchmark` 不在本机 Gradle 缓存里、需要
联网取，而 `gfxinfo` 数的是真实渲染管线真实产出的帧，正是不想再看到的那种帧。

---

## 一、待机时不再产帧

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File tools\measure-idle.ps1 -Apk app\build\outputs\apk\benchmark\app-benchmark.apk `
  -Seconds 20 -Label "..." -Package com.killhuishenghuo.benchmark `
  -Activity com.killhuishenghuo.benchmark/com.killhuishenghuo.MainActivity
```

脚本做四件事：装 APK、启动、等 10 秒让画面静止、`dumpsys gfxinfo <pkg> reset` 之后
**不做任何输入**地坐 20 秒，再读回计数。

> `-Activity` 不能省。默认值是 `com.killhuishenghuo/.MainActivity`，而 `.MainActivity`
> 这个简写是相对 **applicationId** 展开的，所以 `benchmark` 变体会被展开成
> `com.killhuishenghuo.benchmark.benchmark.MainActivity`，`am start` 失败，而失败是
> 安静的——脚本仍然坐满 20 秒，然后报出另一个应用的帧数（或者 `n/a`）。

### 前：时钟从不挂起

```
total frames     : 1211
frames per second: 60.55     <-- 什么都没画
janky frames     : 1 (0.08%)
frame time p50   : 21ms
frame time p90   : 23ms
frame time p99   : 26ms
```

### 后：时钟在没有东西可画时挂起

```
total frames     : 0
frames per second: 0
janky frames     : 0 (0.00%)
frame time p50   : 4950ms
```

20 秒里 1211 帧，就是**每秒 60.55 帧**——满帧率。这个应用当时正在以每帧 21 毫秒的
成本，画一幅每一帧都完全相同的、不存在的画：`presence` 已经是 0，画布的 draw lambda
第一行就 `return` 了，但 `rememberInfiniteTransition` 拥有自己的 frame callback，
只要对象活着就一直要下一帧。一天待机约 **520 万帧**。

`frame time p50 = 21ms` 这一行比帧率更说明问题：一帧的预算是 16.7ms（60Hz），
21ms 意味着**每一帧都跨过了一个 vsync 边界**。应用自己的活儿很轻（没有一次
`slow UI thread`），但渲染管线从来没有空闲过——它一直在为一幅空画面走完整条流水线。

改成挂起之后，p50 变成 4950ms。那不是「一帧花了 5 秒」，而是**一帧都没有**：
`gfxinfo` 在没有新帧时保留上一次的计时，这个数字就是「没有帧」的写法。

### 那次 A/B 是怎么做的

把两处 `running = showing`（`WaterAmbience.kt` 里蒸汽与涟漪各自的时钟）临时改成
`running = true`，测完用备份覆盖回去并**核对 sha256**：

```
tools\_ambience-gated.kt.bak   原文件，27385 字节
sha256  E8027FF6D134C3F4199231A6AD83D29223EEF213F4120B3CD3265A705FA550F7
```

备份与两个对照 APK 在测量结束后都已删除；正式源码就是上面那个哈希对应的内容。

---

## 二、切页面：连续 10 次来回切 tab

待机是「应用有没有在浪费」，这一项才是「用户看得见的流畅度」。

```
Total frames rendered: 1096        (10 次来回 = 20 次切换)
Janky frames (legacy): 28 (2.55%)
50th percentile: 17ms
90th percentile: 18ms
95th percentile: 19ms
99th percentile: 29ms
Number Missed Vsync: 0
Number Slow UI thread: 14
Number Slow issue draw commands: 10

HISTOGRAM: 16ms=16 17ms=333 18ms=315 19ms=44 ... 24ms=8 25ms=2 ... 150ms=1
```

读法：**333 + 315 = 648 帧落在 17–18ms**，也就是 20 次切换里绝大多数帧稳定贴在
vsync 上（60Hz 下 17ms 是「这一帧赶上了、下一帧开始前交了」的正常读数）。
`Missed Vsync = 0` 是关键：没有任何一帧因为合成器等不到内容而错过垂直同步。

尾巴上有 28 帧 janky（2.55%），其中 14 次 `slow UI thread`、10 次
`slow issue draw commands`，最长一帧 150ms。这 28 帧集中在每次切换的第一帧：那一帧
要组合一整个新页面（`SettingsScreen` 的 `LazyColumn`、悬浮条、系统栏），而后面所有帧
都只是在移动一个 `offset`。**没有为它做动画**（项目里没有 `Crossfade` /
`AnimatedContent`），所以新页面是硬切的，代价一次性付在第一帧上。

这个数字对本项目来说是可以接受的：切页面是低频动作，而它换来的是「静止时 0 帧」。
但它也不是完美的——如果哪天要压掉这 2.55%，办法是让两个页面都留在组合里、只做位移，
而不是给切换加动画。

---

## 三、启动

### 进程启动到第一帧（`am start -W`，各跑 5 次取中位数）

| 构建 | 中位 TotalTime |
|---|---|
| `benchmark`（R8 压缩过，1.58MB） | **434–439ms** |
| `debug`（未压缩，19.8MB） | 1996–2284ms（早前的记录） |

`am start -W` 会等到首个绘制/异步回调，所以它比首帧时间略长。两个变体都会在启动时
跑一次注定失败的网络请求（没有配置接口地址），两边的这个成本是一样的。

### 用框架自己的数字

`MainActivity.logFirstFrame()` 注册一个 `Window.OnFrameMetricsAvailableListener`，
把 `FrameMetrics.FIRST_DRAW_FRAME` 写进应用自己的日志，然后立刻摘掉监听。

用框架的 `FIRST_DRAW_FRAME` 而不是外部的 `am start -W`：前者是「进程启动到首帧提交」
本身，后者还要算上 shell 往返，而且**只有前者会出现在用户能导出的日志里**——也就是说
「这版比上版快」这句话在出问题的时候可以从用户的日志里读回来，不必要求他复现测量。

日志格式是 `Startup  first frame XXms`。它不写 logcat，只进 `AppLog` 的环形缓冲，
所以要读它得走「设置 → 导出日志」——这是有意的：一个只在 logcat 里可见的探针会在
用户报告问题时正好不在他手上。

---

## 四、基线配置文件（baseline profile）

`app/src/main/baseline-prof.txt` 是手写的，覆盖进程启动到第一帧那一段。手写而不是用
`androidx.baselineprofile` 插件生成，理由写在文件头：生成路径要下载
`androidx.benchmark`，而这个项目的依赖集是刻意压小的。

手写的代价是**签名可能写错，而错了不会报错**。dexopt 和 R8 都只是静默丢弃不匹配的
条目，构建照样绿，产物照样出。所以真正的问题不是「怎么生成」，而是「怎么知道它指
对了东西」。为此这个文件被两遍校验，两个都通过才算数：

```powershell
py tools\check-profile.py --classes    # 对编译器产出的 class 文件核名字与参数个数
py tools\check-profile.py --mapping    # 对 R8 的 mapping.txt 核它是否还存在
```

两遍都要，因为它们抓的是不同的错：

- `--classes` 抓**写错的描述符**。它读
  `app/build/intermediates/compile_app_classes_jar/debug/.../classes.jar` 的方法表。
- `--mapping` 抓**写对了但已经不再指向任何东西**的条目。它读
  `app/build/outputs/mapping/benchmark/mapping.txt`，也就是 R8 跑完之后的程序。

第二遍不是多余的。第一版 29 条里有 5 条在**源码里完全正确、在 APK 里却不存在**：
`Radii` 与 `Spacing` 是字面量 `val` 的 `object`，编译器把每次使用都内联掉了，R8 直接
删了这两个类；`LightPalette`、`Motion` 只剩内联帧，声明本身没了；`KhsThemeKt
.getAppFontFamily()` 返回 `FontFamily.Default`，同样是内联后删除。这 5 条已删掉，
理由写在文件里——留着的成本不是性能，是它会读起来像有意为之，于是下一个人会接着留。

当前状态：**23 条，两遍校验都全数通过。**

### 它进包了吗

进了，而且**只进压缩过的变体**。用 `unzip -l` 读设备上已安装的 APK：

```
7361  1981-01-01 01:01   assets/dexopt/baseline.prof
 828  1981-01-01 01:01   assets/dexopt/baseline.profm
```

`debug` 变体里没有这两个文件——AGP 会主动把它们从 debuggable 变体剥掉，这是正确的，
但也意味着**调试包永远不会体现 profile 的效果**。

`androidx.profileinstaller` 是传递依赖（经 `androidx.startup`），不需要显式声明。
它对 `minSdk 26` 是必需的：API 31 以下没有 ART Service，profile 得靠它在运行时安装。

### 它让启动变快了吗：**在模拟器上量不出来**

```powershell
# 无 profile：全新安装，跑 5 次
# 中位 TotalTime = 439ms
adb shell cmd package compile -m speed-profile -f com.killhuishenghuo.benchmark
# 有 profile：再跑 5 次
# 中位 TotalTime = 434ms
```

**5 毫秒，在噪声里。** 这份记录不打算把这个数字说成一次胜利：模拟器上 ART 的
dexopt 行为本来就与真机不同，而这个应用的启动路径很短、很浅，profile 能省的
那部分工作占比很小。能证明的是**每一行都指向一个真实存在的方法**（见上），
不能证明 ART 因此少做了多少工作。

**真机上的第一帧时间可以从「设置 → 导出日志」里读到**（`Startup  first frame XXms`），
需要那个数字时以它为准。

---

## 五、一次只有压缩构建才会暴露的崩溃

这一节留在这里，是因为它是这个项目里**唯一一个测试全绿、装机才炸**的 bug，而且
它正好长在那个启动探针上。

`MainActivity` 的第一版探针在回调里 `window.removeOnFrameMetricsAvailableListener(this)`
把自己摘掉，`onDestroy()` 里又摘一次。两个调用点单独看都是对的，`debug` 包里从冷启动
到退出一次都没出过问题。

`benchmark`/`release` 上**每次启动都崩**：

```
java.lang.IllegalArgumentException: attempt to remove OnFrameMetricsAvailableListener
that was never added
    at android.view.View.removeFrameMetricsListener(View.java:8146)
    at aq0.onFrameMetricsAvailable(r8-map-id-e29296d0...:46)
    at android.view.FrameMetricsObserver.onFrameMetricsAvailable(FrameMetricsObserver.java:59)
```

原因不是「摘了两次」这件事本身——`removeOnFrameMetricsAvailableListener` 对
**同一个监听器**的第二次移除是硬错误，不是静默忽略。原因是 R8 把回调内联进了
`onDestroy`，于是「监听器通常在 Activity 消失之前就触发了」变成了「监听器总是先触发」，
两个摘除点从"互斥"变成了"串行"。

修法是一个幂等的摘除函数：先把字段置空，再移除。这样`frameListener` 已经为 `null` 时
第二次调用就是空操作。

**教训**：调试包不能代表正式包的行为，尤其是当代码里有"通常"、"一般"这类时序假设时。
这个 bug 不是靠测试发现的，是靠**把压缩包装上去点一下**发现的——所以那一步不能省。
