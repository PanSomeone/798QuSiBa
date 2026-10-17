/* 二维码内容的解析。
 *
 * 单独一个文件,不只是为了整洁:它不碰 DOM,所以 `node` 里可以直接测(见
 * tools/test-deviceqr.mjs)。安卓端对应的是 data/model/DeviceQr.kt。
 */
/**
 * 二维码内容的解析。
 *
 * **这是安卓端 `DeviceQr.kt` 的同一套规则,不是"看着差不多"的重写。** 里面每一条
 * 边界都是有理由的,照抄比重新发明便宜:
 *
 * ```
 * 865137068739782                          设备号本身
 * https://<host>/q/<n>/<token>             一个 token,要先过 qr/use
 * https://<host>/q/<n>/bm?/<token>         同上,`bm` 那种写法
 * https://<host>/q/<n>/<kind>?id=<token>   同上,token 带了名字
 * ```
 *
 * `/q/<n>/` 是路由,所以 `q`、那个数字、尾巴上的 `bm` 全都要跳过 —— 跳过它们才能
 * 让 `/q/1/bm` 交出 token 而不是 `bm`。
 */
const DeviceQr = (() => {
  const MIN_LENGTH = 6;
  const MAX_LENGTH = 32;
  const DIGITS = /^\d+$/;
  const ID_SHAPE = /^[A-Za-z0-9][A-Za-z0-9_-]*$/;
  const SCHEMES = new Set(['http', 'https', 'ilife']);
  const ROUTE_WORDS = new Set([
    'q', 'qr', 'bm', 'b', 's', 'billing', 'vip', 'wallet',
    'dev', 'device', 'devices', 'did', 'bind', 'binding',
  ]);

  const decodePart = (value) => {
    try { return decodeURIComponent(value); } catch { return value; }
  };
  const isDeviceId = (v) => v.length >= MIN_LENGTH && v.length <= MAX_LENGTH && DIGITS.test(v);
  const isPlausibleId = (v) => v.length >= MIN_LENGTH && v.length <= MAX_LENGTH && ID_SHAPE.test(v);

  /** `k=v&k=v` 里 key 的值。不是 URL 解析器:不认 `+`,不认重复。 */
  function queryValue(rawQuery, key) {
    for (const part of String(rawQuery || '').split('&')) {
      const eq = part.indexOf('=');
      const name = (eq < 0 ? part : part.slice(0, eq)).toLowerCase();
      if (name !== key) continue;
      const value = decodePart(eq < 0 ? '' : part.slice(eq + 1)).trim();
      if (value) return value;
    }
    return null;
  }

  function parse(raw) {
    const text = String(raw || '').trim();
    if (!text) return { kind: 'unreadable' };
    if (isDeviceId(text)) return { kind: 'device', id: text };

    let url;
    try { url = new URL(text); } catch { return { kind: 'unreadable' }; }
    if (!SCHEMES.has(String(url.protocol || '').replace(':', '').toLowerCase())) {
      return { kind: 'unreadable' };
    }

    // `?id=<x>` 是唯一一种自己说明带了什么的形状
    const named = queryValue(url.search.replace(/^\?/, ''), 'id');
    if (named) return { kind: 'opaque', id: named };

    const fromPath = url.pathname
      .split('/')
      .map(decodePart)
      .filter((s) => s && !ROUTE_WORDS.has(s.toLowerCase()) && isPlausibleId(s))
      .pop();
    if (fromPath) {
      return { kind: isDeviceId(fromPath) ? 'device' : 'opaque', id: fromPath };
    }

    // `?/<token>`:这个查询串不是查询串,它就是载荷
    const loose = decodePart(url.search.replace(/^\?/, '').replace(/^\/+|\/+$/g, ''));
    if (isPlausibleId(loose)) return { kind: 'opaque', id: loose };

    return { kind: 'unreadable' };
  }

  return { parse };
})();
