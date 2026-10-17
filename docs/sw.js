/* 惠生活 · 离线外壳
 *
 * 缓存分两类,策略故意不同:
 *
 * - **页面(导航请求、.html)走"先网络、失败再用缓存"。** 第一版把页面也做成了
 *   "先缓存",于是部署一次之后,老用户会一直看到他第一次打开时那份 —— 除非有人
 *   记得把缓存名加一。靠"记得"这种东西维持的更新机制,迟早会有人在群里说"我这边
 *   还是没有扫码按钮"。
 * - **静态资源(js/css/图标)走"先缓存"。** 它们很少变,而离线时必须在。
 *
 * 网关的请求一律直接放行到网络:缓存一个设备状态,就是让页面显示一个旧的真相,
 * 而在这个 app 里"水现在是开是关"正是唯一重要的那条信息。
 *
 * 版本号仍然写在缓存名里 —— 它现在是给"旧缓存回收"用的,不再是更新的唯一开关。
 */
const CACHE = 'huishui-shell-v2';

const SHELL = [
  './',
  'index.html',
  'style.css',
  'app.js',
  'deviceqr.js',
  // 二维码解码器。它是"应用本身"的一部分(没有它,扫码按钮就只是个按钮),
  // 所以进缓存;而且它 250KB,离线时更不该去网络上找。
  'vendor/jsQR.js',
  'manifest.webmanifest',
  'icon-192.png',
  'icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

/** 静态资源:先缓存。 */
function cacheFirst(request) {
  return caches.match(request).then((hit) => {
    if (hit) return hit;
    return fetch(request).then((response) => {
      const copy = response.clone();
      caches.open(CACHE).then((cache) => cache.put(request, copy));
      return response;
    });
  });
}

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // 别人的域名(网关、GitHub)一律走网络:见文件头。
  if (url.origin !== self.location.origin) return;
  if (event.request.method !== 'GET') return;

  const isPage =
    event.request.mode === 'navigate' ||
    url.pathname.endsWith('/') ||
    url.pathname.endsWith('.html');

  if (isPage) {
    // 先网络:部署之后下一次打开就是新版
    event.respondWith(
      fetch(event.request)
        .then((response) => {
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put(event.request, copy));
          return response;
        })
        .catch(() => caches.match(event.request).then((hit) => hit || caches.match('index.html'))),
    );
    return;
  }

  event.respondWith(cacheFirst(event.request));
});
