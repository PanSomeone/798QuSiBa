/**
 * DeviceQr 的规则测试。
 *
 * 网页端这份解析是照着安卓端 data/model/DeviceQr.kt 手抄的,而手抄的东西必须被钉住:
 * 抄错一条边界不会报错,只会在某一张二维码上安静地什么都不做。
 *
 * 跑法:`node tools/test-deviceqr.mjs`。不需要浏览器,也不需要装依赖。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(here, '..', 'docs', 'deviceqr.js'), 'utf8');
const DeviceQr = new Function(`${source}; return DeviceQr;`)();

let passed = 0;
const failures = [];

/** 期望 {@link DeviceQr.parse} 给出什么。 */
function expect(raw, kind, id) {
  const got = DeviceQr.parse(raw);
  const label = JSON.stringify(raw);
  if (got.kind !== kind || (id !== undefined && got.id !== id)) {
    failures.push(`${label} -> ${JSON.stringify(got)}, expected ${kind}${id === undefined ? '' : ' ' + id}`);
  } else {
    passed += 1;
  }
}

// —— 形状 1:码本身就是设备号 ——————————————————————————————
expect('865137068739782', 'device', '865137068739782');
expect('  865137068739782  ', 'device', '865137068739782'); // 贴纸被扫出来的前后空白
expect('1', 'unreadable');                                   // 太短:路由里的那个数字
expect('12345', 'unreadable');                               // 短一位仍然不行

// —— 形状 2:/q/<n>/<token> ——————————————————————————————
expect('https://i.ilife798.com/q/1/aBcDeF123456', 'opaque', 'aBcDeF123456');
expect('http://i.ilife798.com/q/1/aBcDeF123456', 'opaque', 'aBcDeF123456');
expect('ilife://i.ilife798.com/q/1/aBcDeF123456', 'opaque', 'aBcDeF123456');

// —— 形状 3:bm 那种写法,查询串不是查询串 ————————————————
expect('https://i.ilife798.com/q/1/bm?/aBcDeF123456', 'opaque', 'aBcDeF123456');

// —— 形状 4:token 带了名字 ————————————————————————————
expect('https://i.ilife798.com/q/1/bm?id=aBcDeF123456', 'opaque', 'aBcDeF123456');

// 路径里最后一段是设备号时,应当被认成设备号 —— 这决定了先调哪个接口
expect('https://i.ilife798.com/q/1/865137068739782', 'device', '865137068739782');

// —— 路由词必须被跳过 ————————————————————————————————
expect('https://i.ilife798.com/dev/device/bind', 'unreadable');

// —— 读不出东西的那些 ————————————————————————————————
expect('', 'unreadable');
expect('   ', 'unreadable');
expect('WIFI:S:some-network;T:WPA;P:hunter2;;', 'unreadable'); // 一张 wifi 二维码
expect('这是一句话，不是设备码', 'unreadable');
expect('https://i.ilife798.com/', 'unreadable');
expect('ftp://i.ilife798.com/q/1/aBcDeF123456', 'unreadable'); // 不认的 scheme

// —— 百分号编码 ————————————————————————————————————
expect('https://i.ilife798.com/q/1/aBcDeF%2D123456', 'opaque', 'aBcDeF-123456');

console.log(`DeviceQr: ${passed} passed, ${failures.length} failed`);
for (const failure of failures) console.log(`  FAIL  ${failure}`);
if (failures.length) process.exit(1);
