# 网页端 / PWA

给 iPhone 用的那一版。同一个网关、同一套接口、同一颗按钮,装在 Safari 里加一个
「添加到主屏幕」,用起来和原生 App 差不多。

## 为什么这里没有服务器

**这一版没有后端,也不想有。** 页面里的每一行 `fetch` 都是从用户自己的浏览器
直接发给 `https://i.ilife798.com/api/v1/`。

能做这件事,是因为那个网关对跨域是放行的 —— 实测:

```
GET  + Origin: https://example.com
  → Access-Control-Allow-Origin: https://example.com
  → Access-Control-Allow-Credentials: true

OPTIONS 预检
  → Access-Control-Allow-Headers: authorization, applicationtype, versioncode, user-agent
  → Access-Control-Allow-Methods: POST, GET
```

它把请求的 Origin 原样回显,并放行 `authorization` 头。所以浏览器可以直接带
token 调它,不需要任何代理。

**这条如果哪天变了,整个网页端会立刻失效** —— 打开 [`selftest.html`](selftest.html)
一眼就能看出来:三张卡片分别验证跨域、应答内容、验证码图片。

## 文件

| 文件 | 是什么 |
|---|---|
| `index.html` | 三个屏幕:登录 / 使用 / 设置,加上全屏的扫码取景 |
| `style.css` | 和安卓端「玻璃 + 取色」同一套配色;出水时的蒸汽也在这里 |
| `app.js` | 网关调用、状态、渲染、扫码。没有框架,没有构建步骤 |
| `vendor/jsQR.js` | 二维码解码器(Safari 没有 `BarcodeDetector`),见 `vendor/README.md` |
| `manifest.webmanifest` | PWA 清单(iOS 16.4+ 才读) |
| `sw.js` | 离线外壳。**只缓存 HTML/CSS/JS/图标,绝不缓存网关响应** |
| `icon-*.png` | 主屏图标 |
| `selftest.html` | 连通性自检,不用登录 |

## 部署

GitHub Pages 只支持从 **仓库根** 或 **`/docs`** 目录发布,所以这个文件夹就叫
`docs`。

```
Settings → Pages → Build and deployment
  Source: Deploy from a branch
  Branch: main  /  Folder: /docs
```

十分钟内会出现在 `https://pansomeone.github.io/798QuSiBa/`。

## 怎么用

1. 打开就是「使用」屏。没登录时那颗按钮写着「去设置」——点它,或者直接点底栏的
   **设置**。
2. 设置里默认是**短信登录**;没有短信就切到「粘贴 token」。
3. 登录后同一张卡片变成设备卡:扫机器上的二维码把设备加进来。**只要一台** ——
   不会有人有多个热水器。
4. iPhone: Safari 分享 → **添加到主屏幕**。之后它就是一个独立图标,没有地址栏。

## 两个方便的入口

- **`?demo=1`** —— 用假数据画界面,一个请求都不发。想先看看长什么样、或者截图
  比对样式时用,不需要把 token 贴进一个刚打开的页面。
- **`?demo=1&screen=settings`** —— 同上,直接看设置页。

## 已知限制

- **扫码要求 https(或 localhost)。** 浏览器只在安全上下文里给页面用摄像头。
  GitHub Pages 是 https,所以部署上去就没问题;本地用 `python -m http.server`
  开 `http://127.0.0.1` 也被当作安全上下文。
- **短信登录可能被拒。** 那条路依赖官方 App 的构建期密钥(`cid`),这里只能生成
  一个稳定的随机值顶替。被拒就用粘贴 token。
- **token 存在 localStorage。** 它等同于密码,只存在这台设备的浏览器里;公共电脑
  上不要登录。
- **发不出 `User-Agent`。** 浏览器把它列为禁止修改的请求头,写了也会被丢掉。安卓
  版会带 `Android_ilife798_3.1.9`,网页端带不了。
- **网页端不做动态取色。** 浏览器里没有壁纸可读,所以固定用这一套配色。

## 扫码

「设置 → 扫码添加设备」会开摄像头,扫机器上那张二维码。解码器有两套,按能不能用
排序:`BarcodeDetector`(Chromium 自带)和 `vendor/jsQR.js`(Safari 的回退)。

二维码内容的解析规则和安卓端 `DeviceQr.kt` **是同一套**,包括要跳过的路由词
(`q`、`bm`、`dev` 之类)——照抄而不是重写,因为每一条边界都是踩出来的。

绑定走的是和安卓端相同的两次调用:`qr/use?id=` 换取设备号,再用
`dev/favo?did=…&remove=0` 把它加进账号;如果码本身就是设备号,顺序反过来。
