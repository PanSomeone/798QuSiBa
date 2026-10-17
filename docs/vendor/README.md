# vendor/

第三方代码。只有一份,而且它是**解码器**这件事值得说明。

## jsQR 1.4.0

| | |
|---|---|
| 文件 | `jsQR.js`(UMD,未压缩,256,885 B) |
| 来源 | `https://unpkg.com/jsqr@1.4.0/dist/jsQR.js` |
| 版本 | 1.4.0 |
| 许可证 | Apache-2.0 |
| SHA-256 | `BC40C8A15196236B2314DB0856F72CA0B49980CD5413B8C852A7349F5FEE0859` |
| 上游 | https://github.com/cozmo/jsQR |

**为什么需要它:** iPhone 的 Safari 没有 `BarcodeDetector`(Chromium 系有),
而 iPhone 正是这个网页端存在的理由。没有它,扫码就只剩安卓 Chrome 能用。

**为什么放在仓库里而不是引 CDN:** 引 CDN 会让页面在运行时多一个第三方请求,而
这个项目的整个卖点是"没有服务器、没有第三方"。放进仓库,它就只有 250KB 的静态
字节,并且会和其它资源一起进 Service Worker 的缓存,离线也在。

**没有改动过它。** 升级时用上面的地址重新下载,并用 sha256 核对。
