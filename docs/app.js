/* 惠生活 · 网页端
 *
 * 一个纯静态页面：没有后端、没有构建步骤、没有依赖。所有请求由这台设备的浏览器
 * 直接发给官方网关,因为那个网关允许跨域(实测:它把请求的 Origin 原样回显,并在
 * 预检里放行 authorization / applicationtype / versioncode)。
 *
 * 三件刻意的取舍:
 *
 * 1. **token 只进 localStorage,不进任何 URL。** 它等同于密码,而 URL 会进历史
 *    记录、进 referer、进截图。
 * 2. **不发送 User-Agent。** 安卓版会带 `Android_ilife798_3.1.9`,但浏览器把
 *    User-Agent 列为禁止修改的请求头,写了也会被静默丢掉 —— 与其假装设过,不如
 *    不写,并在失败时把服务器的原话显示出来。
 * 3. **不做扫码绑定。** 扫码需要摄像头和 ML Kit;网页端没有这个能力,所以设备
 *    列表为空时,它老老实实告诉用户去安卓版或官方 App 里绑一台,而不是装作能绑。
 */
'use strict';

const BASE = 'https://i.ilife798.com/api/v1/';

/**
 * 发给网关的客户端版本号(`VersionCode` 头)。
 *
 * 编码方式是 `主版本 * 10000 + 次版本 * 1000 + 补丁`:官方安卓版 3.1.9 → `31009`。
 *
 * **网关会拒掉它认为过旧的客户端**,回一句「请升级至最新版APP」。而那道校验排在
 * 鉴权**之后** —— 用假 token 探所有接口都只会得到「登录状态已过期」,所以在没有
 * 真会话的情况下只能靠往上调。这里给到 4.0.0 的地板值:如果服务端比的是
 * 「不小于某个版本」,它就能过;如果比的是精确相等,那客户端怎么改都没用,只能等
 * 服务端放宽。
 *
 * 它和下面的 `APP_VERSION` 是两件事:那个是这个网页端自己的版本,这个是"我假装
 * 自己是官方 App 的第几版"。
 */
// 服务器到底认哪个版本声明,只能靠试;每改一次都重新部署太慢,
// 所以在网址后面加 ?vc=45000 就能立刻换一个再试一次。
const VERSION_CODE = new URLSearchParams(location.search).get("vc") || "31009";

/**
 * 显示用的版本号,跟安卓端 `app/build.gradle.kts` 的 `versionName` **同步**。
 *
 * 两边各自记一个数字迟早会漂,所以 `tools/verify-v1.2.ps1` 里有一条检查:这两个值
 * 必须相等。改一处忘另一处会让校验脚本红,而不是让用户在设置里看到一个旧版本。
 */
const APP_VERSION = '1.3.6';

/**
 * 用户群群号,和安卓端 `ProjectLinks.QQ_GROUP` 是同一个数字。
 *
 * 只存数字,不存网址:QQ 群没有公开网页,`qm.qq.com` 的邀请链需要每群一个密钥,
 * 编一个出来就是死链。所以这一行是「点一下复制群号」,剩下的交给 QQ 的搜索框。
 */
const QQ_GROUP = '1126108304';

/** 网关的业务码。0 是成功,-99 是所有需鉴权接口共用的会话失效。 */
const CODE_OK = 0;
const CODE_EXPIRED = -99;

/**
 * 拒绝码翻成人话,和安卓版同一张表。
 *
 * 这张表的价值不在措辞,而在于「下一步干什么」:同样一个失败,「服务器拒绝了」
 * 让人无从下手,「账户欠费」让人知道去充值。
 */
const REFUSALS = {
  '-21': '需要重新登录（token 作用域不对）',
  '-82': '这一笔要付费',
  '-52': '账户欠费了',
  '-88': '还没签约代扣',
  '-87': '代扣签约过期了',
  '-20': '没绑一卡通',
  '-19': '设备还没准备好，稍后再试',
  '-15': '这台设备有人正在用，等它空下来',
  '-2': '上一次的使用还没结算，需要在机器上点一下结算',
};

/* ------------------------------------------------------------------ 存储 */

const store = {
  get token() { return localStorage.getItem('huishui.token') || ''; },
  set token(v) { v ? localStorage.setItem('huishui.token', v) : localStorage.removeItem('huishui.token'); },
  get deviceId() { return localStorage.getItem('huishui.device') || ''; },
  set deviceId(v) { v ? localStorage.setItem('huishui.device', v) : localStorage.removeItem('huishui.device'); },
  /** 短信登录要的 `cid`：官方 App 的构建期密钥,这里只能用稳定的随机值顶替。 */
  get cid() {
    let v = localStorage.getItem('huishui.cid');
    if (!v) {
      v = Array.from(crypto.getRandomValues(new Uint8Array(12)))
        .map((b) => b.toString(16).padStart(2, '0')).join('');
      localStorage.setItem('huishui.cid', v);
    }
    return v;
  },
};

/* ------------------------------------------------------------------ 请求 */

/**
 * 一次网关调用。
 *
 * 返回值是 `{ code, msg, data, http }`,**不是**抛出异常:这个网关把失败也报成
 * HTTP 200,真正的答案在 body 的 `code` 里,所以「请求成功」和「事情成功」是两件
 * 事,调用方必须看 code。
 */
/**
 * 最近几次调用的「我发了什么 / 它回了什么」。
 *
 * 加这一段的原因很具体:同一个包、同一个账号,在模拟器上能开阀,在手机上被
 * 「请升级最新版APP」挡住,而页面和日志里能看的东西全都一样。看不见的那一项是
 * 请求头,所以把它记下来,并且让人能一键复制走。
 *
 * 只记不敏感的东西:接口路径、ApplicationType、VersionCode、HTTP 状态、业务码和
 * 服务器原话。**token 一个字都不进这里**。
 */
const diag = [];

function recordDiag(entry) {
  diag.push(entry);
  if (diag.length > 12) diag.shift();
  renderDiag();
}

function renderDiag() {
  const out = document.getElementById('diag-out');
  if (!out) return;
  out.textContent = diag.length
    ? diag.map((d, i) => `${String(i + 1).padStart(2, '0')}  ${d}`).join('\n')
    : '还没有请求。';
}

async function copyDiag() {
  const out = document.getElementById('diag-out');
  if (!out) return;
  const text = out.textContent || '';
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    // 剪贴板 API 在非 https 或没授权时会拒绝,退回到老办法。
    const range = document.createRange();
    range.selectNodeContents(out);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    document.execCommand('copy');
  }
  const button = document.getElementById('diag-copy');
  if (button) {
    button.textContent = '已复制';
    setTimeout(() => { button.textContent = '复制诊断'; }, 1600);
  }
}

/**
 * 把群号放进剪贴板。
 *
 * 浏览器里没有「打开 QQ」这回事 —— 网页也没法唤起 App 的群卡片(那要
 * `mqqapi://`,移动版 Safari/Chrome 都会把它当协议错误拦掉)。所以这个按钮承诺
 * 的只有复制,而复制是浏览器里唯一肯定能做到的事。
 */
async function copyQQGroup() {
  const hint = $('qq-hint');
  try {
    await navigator.clipboard.writeText(QQ_GROUP);
  } catch {
    // 剪贴板 API 被拒(非 https、或用户没授权)时退回老办法:临时选中一段文本。
    const area = document.createElement('textarea');
    area.value = QQ_GROUP;
    area.setAttribute('readonly', '');
    // 放在视野外而不是 hidden:display:none 的节点选不中,也就复制不了。
    area.style.position = 'fixed';
    area.style.top = '-40px';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    try {
      document.execCommand('copy');
    } catch {
      // 两条路都堵住时不再假装成功,直接让人自己记下这个数字。
      if (hint) hint.textContent = QQ_GROUP;
      document.body.removeChild(area);
      return;
    }
    document.body.removeChild(area);
  }
  if (hint) {
    hint.textContent = '已复制，去 QQ 里搜一下';
    hint.classList.add('copied');
    setTimeout(() => {
      hint.textContent = '点一下复制群号';
      hint.classList.remove('copied');
    }, 1600);
  }
}

async function api(path, { method = 'GET', body = null, token, applicationType = '1,1' } = {}) {
  const headers = {
    'Accept': 'application/json',
    'ApplicationType': applicationType,
    'VersionCode': VERSION_CODE,
  };
  const bearer = token === undefined ? store.token : token;
  if (bearer) headers['Authorization'] = bearer;
  if (body) headers['Content-Type'] = 'application/json';

  let response;
  try {
    response = await fetch(BASE + path, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
      mode: 'cors',
      credentials: 'omit',   // 我们用 Authorization 头,不靠 cookie
    });
  } catch (e) {
    // 走到这里几乎只有两种可能:断网,或者网关把跨域放行改掉了
    recordDiag(`${method} ${path} · at=${applicationType} vc=${VERSION_CODE} → 网络错误：${e.message}`);
    return { code: null, msg: '网络连不上，或者网关不再允许跨域：' + e.message, data: null, http: 0 };
  }

  const text = await response.text();
  let parsed = null;
  try { parsed = JSON.parse(text); } catch { /* 下面统一报告 */ }

  if (!parsed) {
    return { code: null, msg: `HTTP ${response.status}：服务器没有返回 JSON`, data: null, http: response.status };
  }

  const code = parsed.code === undefined ? null : parsed.code;
  const message = parsed.msg || parsed.message || '';

  // 失败时把「哪个调用、什么码、服务器原话」写进控制台。
  //
  // 这一行是为了修一类难查的故障:网关卡在版本或环境上时,页面上只会显示它那句话,
  // 而"是哪一次调用被拒的"没地方看 —— 于是报告变成"它说请升级",谁也定位不了。
  // 打开 DevTools 的控制台就能看到完整的一条。
  if (code !== null && code !== CODE_OK) {
    console.warn(`[api] ${method} ${path} -> code=${code} msg=${message || '(empty)'}`);
  }

  // 每一次都记,成功也记:需要对比的恰恰是「同一个头,一次过、一次不过」。
  recordDiag(
    `${method} ${path} · at=${applicationType} vc=${VERSION_CODE}` +
    ` → http=${response.status} code=${code === null ? '?' : code} ${message || ''}`.trimEnd(),
  );

  return {
    code,
    msg: message,
    data: parsed.data === undefined ? null : parsed.data,
    http: response.status,
  };
}

/** 把一次调用的结果翻成一句人话;成功时返回 null。 */
function explain(result) {
  if (result.code === CODE_OK) return null;
  if (result.code === CODE_EXPIRED) return '登录过期了，重新登录一下';
  if (result.code === null) return result.msg || '请求失败';
  return REFUSALS[String(result.code)] || (result.msg || `服务器拒绝了（${result.code}）`);
}

/* ------------------------------------------------------------ 网关的四个调用 */

/** 设备列表 + 账号。`favos[]` 就是账号收藏的设备,也是这个 app 能看见的唯一列表。 */
const master = () => api('ui/app/master');

/** 开水。`args=` 必须**存在且为空** —— 淋浴器就是这样工作的,`cnt=1` 是一次扣费。 */
/**
 * 开阀 / 关阀走**账户作用域**（`ApplicationType: 1,5`），不是设备作用域。
 *
 * 2026-10 起，`1,1` 上这两个接口一律回 `{"code":-1,"msg":"请升级最新版APP"}` ——
 * 和客户端报的版本无关（3.1.9、4.0.0、4.5.0 都一样）。实测同一份会话换成 `1,5`
 * 就直接进业务层：不存在的设备号回「请求参数错误」，真设备回「设备运行中，请在设备上
 * 点击结算」。安卓端 `ApiConstants.applicationTypeFor` 里是同一个结论。
 */
const startWater = (did) =>
  api(`dev/start?did=${encodeURIComponent(did)}&upgrade=true&ptype=91&args=&rcp=false&cnt=1`, {
    applicationType: '1,5',
  });

/** 关水。同上：账户作用域。 */
const endWater = (did) =>
  api(`dev/end?did=${encodeURIComponent(did)}&rcp=false`, { applicationType: '1,5' });

/** 图形验证码的地址。用 <img> 直接显示:跨域图片不需要 CORS。 */
function captchaUrl(key) {
  return `${BASE}captcha/?s=${encodeURIComponent(key)}&r=${Date.now()}`;
}

/** 10 位随机验证码 key,和安卓版同一个形状。 */
function newCaptchaKey() {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  return Array.from(crypto.getRandomValues(new Uint8Array(10)))
    .map((b) => chars[b % chars.length]).join('');
}

/* ------------------------------------------------------------------ 状态 */

const state = {
  account: null,       // { id, name, pn }
  devices: [],         // [{ id, name, status }]
  running: false,
  busy: false,
  captchaKey: '',
  smsSent: false,
  /** `?demo=1` 时置真:用假数据画界面,一个请求都不发。见 boot()。 */
  demo: false,
};

const $ = (id) => document.getElementById(id);

/**
 * 算不算已登录。
 *
 * `state.demo` 也算,但**它绝不写进 localStorage**:演示模式存在的理由就是让人不贴
 * 凭据也能看界面,而第一版的做法是往存储里写一个假 token —— 于是有人用真 token
 * 登录着、点开 `?demo=1` 看了一眼,真的那一串就被覆盖了。演示只该活在内存里。
 */
function isSignedIn() {
  return Boolean(store.token) || state.demo;
}

/* ------------------------------------------------------------------ 视图 */

function showScreen(name) {
  for (const id of ['main', 'settings']) {
    $('screen-' + id).classList.toggle('hidden', id !== name);
  }
  // 底栏一直在。未登录时「使用」屏自己会说明白:点那颗按钮去设置。
  $('tabbar').classList.remove('hidden');
  for (const b of document.querySelectorAll('.tabitem')) {
    b.classList.toggle('on', b.dataset.tab === name);
  }
  moveTabIndicator();
}

/**
 * 把高亮块滑到当前那一格。
 *
 * 它是一层独立的元素,而不是按钮自己的背景:背景只能跟着格子一起"跳",而人眼认的是
 * **那团光从左边滑过去**。位置用 `offsetLeft/offsetWidth` 量,不靠估算 —— 两个标签
 * 的字数一样但字宽不一样,算出来的会差几个像素,而差几个像素的高亮块看起来就是
 * 「没对准」。
 */
function moveTabIndicator() {
  const indicator = $('tab-indicator');
  const active = document.querySelector('.tabitem.on');
  if (!indicator || !active) return;

  // 第一次画的时候布局还没完成(offsetWidth 是 0),等下一帧再量。
  if (!active.offsetWidth) {
    requestAnimationFrame(moveTabIndicator);
    return;
  }
  indicator.style.width = `${active.offsetWidth}px`;
  indicator.style.transform = `translateX(${active.offsetLeft}px)`;
}

function setStatus(el, text, ok = false) {
  el.textContent = text || '';
  el.classList.toggle('ok', Boolean(text) && ok);
}

/** 这个账号的那一台设备。只有一台 —— 不会有人有多个热水器。 */
function selectedDevice() {
  return state.devices.length ? state.devices[0] : null;
}

/**
 * 这台设备现在在出水吗。
 *
 * 照抄安卓版的一条硬结论:`gene.status` 的 **99 是「服务器没说」**,不是「开着」。
 * 早先的版本把它读成「开着却没结算」,于是用户一登录什么都没开就被告知
 * 「上次没结算」。0 是闲着,99 是不表态,其它值才算真的在跑。
 */
function isRunning(device) {
  if (!device || device.status === null || device.status === undefined) return false;
  return device.status !== 0 && device.status !== 99;
}

function renderMain() {
  const device = selectedDevice();
  const signedIn = isSignedIn();

  const nameEl = $('device-name');
  const dotEl = document.querySelector('#device-chip .dot');
  const btn = $('btn-toggle');
  const label = $('toggle-label');
  const stateLine = $('state-line');
  const hint = $('main-hint');

  // 三种状态,不是一种:没登录、登录了但没设备、有设备。
  // 前两种下这颗按钮不是开关,是去设置 —— 让一个按不动的开关占着屏幕中央,
  // 是最常见的"看起来能用其实不能用"。
  const needsSetup = !signedIn || !device;

  const showSteam = signedIn && Boolean(device) && state.running && !state.busy;
  btn.classList.toggle('running', signedIn && Boolean(device) && state.running);
  btn.classList.toggle('setup', needsSetup);
  btn.disabled = false; // 「去设置」也要能按
  const steam = $('steam');
  if (steam) steam.classList.toggle('on', showSteam);
  dotEl.className = 'dot ' + (!signedIn ? 'unknown' : (!device ? 'unknown' : (state.running ? '' : 'off')));

  if (!signedIn) {
    nameEl.textContent = '还没登录';
    label.textContent = '去设置';
    stateLine.textContent = '还没登录';
    stateLine.classList.add('off');
    hint.textContent = '点一下，去设置里登录';
    return;
  }

  if (!device) {
    nameEl.textContent = '还没有设备';
    label.textContent = '去添加设备';
    stateLine.textContent = '还没有设备';
    stateLine.classList.add('off');
    hint.textContent = '点一下，扫机器上的二维码';
    return;
  }

  nameEl.textContent = device.name;
  label.textContent = state.running ? '关闭热水' : '一键启动热水';

  // 正常路径上一个字都不写:标题、设备、按钮、状态,四样就够。
  if (state.busy) {
    stateLine.textContent = '正在处理…';
    stateLine.classList.remove('off');
    hint.textContent = '';
  } else if (state.running) {
    stateLine.textContent = '热水已开';
    stateLine.classList.remove('off');
    hint.textContent = '';
  } else {
    stateLine.textContent = '热水已关';
    stateLine.classList.add('off');
    hint.textContent = '';
  }
}

/** 设置页那张设备卡。一台设备,一行名字一行编号,完。 */
function renderDevice() {
  const device = selectedDevice();
  const name = $('device-name-2');
  const id = $('device-id');
  if (!name || !id) return;
  name.textContent = device ? device.name : '还没有设备';
  id.textContent = device ? device.id : '';
}

/** 登录卡、设备卡、退出登录卡各自该不该出现。 */
function renderSettings() {
  const signedIn = isSignedIn();
  $('card-signin').classList.toggle('hidden', signedIn);
  $('card-device').classList.toggle('hidden', !signedIn);
  $('card-account').classList.toggle('hidden', !signedIn);
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/* ------------------------------------------------------------------ 动作 */

async function loadDevices() {
  const result = await master();
  const failure = explain(result);

  // 不带 token 时网关也返回 code:0,只是没有 account —— 所以「有 account 且带 id」
  // 才是登录判据,和安卓版同一个判断。
  const account = result.data && result.data.account;
  if (failure || !account || !account.id) {
    if (result.code === CODE_OK) {
      // 请求成功但没有账号:token 是错的,或者已经失效
      return { ok: false, msg: '这个 token 服务器不认（它返回了成功，却没有账号）' };
    }
    return { ok: false, msg: failure };
  }

  state.account = { id: account.id, name: account.name || '', pn: account.pn || '' };
  state.devices = ((result.data.favos) || [])
    .filter((f) => f && f.id)
    .map((f) => ({
      id: f.id,
      name: f.name || '未命名设备',
      status: f.gene && typeof f.gene.status === 'number' ? f.gene.status : null,
    }));

  // 只有一台设备：记住的 id 只用来确认它还在，不再用来挑。
  const device = state.devices[0];
  store.deviceId = device ? device.id : '';
  state.running = isRunning(selectedDevice());
  return { ok: true };
}

async function toggle() {
  const device = selectedDevice();

  // 没登录、或者登录了还没有设备:这颗按钮的职责是**把人送到设置**,不是开阀。
  // 一个按不动的开关占着屏幕中央,比一颗"去设置"糟得多。
  if (!isSignedIn() || !device) {
    showScreen('settings');
    $('card-signin').scrollIntoView({ block: 'start', behavior: 'smooth' });
    return;
  }

  if (state.busy) return;

  // 演示模式:不碰网络,只把按钮翻面 —— 它的用途是让人先看看界面长什么样,
  // 而不是让人把真凭据贴进一个演示里。
  if (state.demo) {
    state.running = !state.running;
    renderMain();
    return;
  }

  state.busy = true;
  renderMain();

  const result = state.running ? await endWater(device.id) : await startWater(device.id);
  state.busy = false;

  const failure = explain(result);
  if (failure) {
    // -2 是「上一次没结算」,两个方向都不算成功,但也不是设备坏了
    setStatus($('login-status'), '');
    state.running = isRunning(device);
    renderMain();
    $('main-hint').textContent = failure;
    $('state-line').textContent = '这一下没成功';
    return;
  }

  // 成功之后不回读设备状态:那会和服务器自己的状态更新赛跑。按钮直接翻面。
  state.running = !state.running;
  renderMain();
}

/* ------------------------------------------------------------------ 登录 */

async function loginWithToken(token) {
  const el = $('login-status');
  if (!token || token.length < 16) {
    setStatus(el, '这一串太短了，不像 token');
    return;
  }
  setStatus(el, '正在验证…', true);

  store.token = token;
  const result = await loadDevices();
  if (!result.ok) {
    store.token = '';
    setStatus(el, result.msg);
    return;
  }
  setStatus(el, '');
  showScreen('main');
  renderMain();
  renderDevice(); renderSettings();
}

function refreshCaptcha() {
  state.captchaKey = newCaptchaKey();
  $('captcha-img').src = captchaUrl(state.captchaKey);
}

async function sendSms() {
  const el = $('login-status');
  const phone = $('input-phone').value.trim();
  const captcha = $('input-captcha').value.trim();
  if (phone.length !== 11) { setStatus(el, '手机号要 11 位'); return; }
  if (!captcha) { setStatus(el, '图上的数字还没填'); return; }
  if (!state.captchaKey) refreshCaptcha();

  setStatus(el, '正在发送…', true);
  const result = await api('acc/login/code', {
    method: 'POST',
    body: { un: phone, authCode: captcha, s: state.captchaKey },
  });

  const failure = explain(result);
  if (failure) {
    setStatus(el, failure);
    refreshCaptcha();   // 被拒的验证码是一次性的,重发就必须换一张
    return;
  }

  state.smsSent = true;
  setStatus(el, '短信已发出', true);
  $('field-smscode').classList.remove('hidden');
  $('btn-sms-login').classList.remove('hidden');
  $('btn-sms-send').textContent = '重新发送';
}

async function loginWithSms() {
  const el = $('login-status');
  const phone = $('input-phone').value.trim();
  const code = $('input-smscode').value.trim();
  if (!code) { setStatus(el, '短信验证码还没填'); return; }

  setStatus(el, '正在登录…', true);
  const result = await api('acc/login', {
    method: 'POST',
    body: { openCode: '', authCode: code, un: phone, cid: store.cid },
  });

  const failure = explain(result);
  const token = result.data && result.data.al && result.data.al.token;
  if (failure || !token) {
    setStatus(el, failure || '登录成功但服务器没给 token');
    return;
  }
  await loginWithToken(token);
}

/* ---------------------------------------------------------------- 扫码绑定 */



/**
 * 相机 + 解码。
 *
 * 两套解码器,按能不能用排序:
 *
 * 1. **`BarcodeDetector`** —— 浏览器自带的那个。Chromium 系(安卓 Chrome、桌面
 *    Chrome/Edge)支持,而且比 JS 逐像素扫描快一个量级。
 * 2. **jsQR**(`vendor/jsQR.js`,Apache-2.0)—— Safari 没有 `BarcodeDetector`,
 *    而 iPhone 正是这个网页端存在的理由,所以必须有回退。
 *
 * 解码节奏是**慢的**:每 140ms 试一帧,不是每帧都试。逐像素扫一张 480 宽的图很便宜,
 * 但一秒扫六十次会让手机发热、掉电,而人举着手机对准一张二维码不需要 60fps。
 */
const scanner = {
  stream: null,
  timer: 0,
  decoder: null,
  canvas: null,
  ctx: null,
  busy: false,

  async open() {
    const status = $('scan-status');
    setStatus(status, '');

    // 先把上一路流关掉。连点两次扫码按钮会开两路 getUserMedia,而第一路的引用
    // 会被覆盖 —— 摄像头就一直亮着,用户也没法关(取消只会关掉第二路)。
    this.stop();

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      closeScan();
      setStatus($('settings-status'), '这个浏览器不给页面用摄像头（用 https 或 localhost 打开才行）');
      return;
    }

    $('scan-overlay').classList.remove('hidden');
    $('scan-overlay').classList.remove('album-only');

    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        // `ideal` 而不是 `exact`:没有后置摄像头的设备(比如笔记本)也应该能开,
        // 而 `exact` 会直接失败。
        video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } },
        audio: false,
      });
    } catch (e) {
      // 摄像头打不开不该堵死扫码 —— 相册那条路根本不需要摄像头。
      // 收掉取景框,只留「从相册选择」,把原因写在下面对话里。
      $('scan-overlay').classList.add('album-only');
      setStatus(status, cameraProblem(e) + '。可以从相册选一张');
      return;
    }

    const video = $('scan-video');
    video.srcObject = this.stream;
    try { await video.play(); } catch { /* 自动播放被拦也要接着试解码 */ }

    this.decoder = await makeDecoder();
    if (!this.decoder) {
      // 连解码器都没有时才真的没救:相册也用不了。
      closeScan();
      setStatus($('settings-status'), '这个浏览器既没有 BarcodeDetector，也没加载到 vendor/jsQR.js');
      return;
    }

    this.loop();
  },

  async loop() {
    if (!this.stream) return;
    const video = $('scan-video');

    if (!this.busy && video.readyState >= 2 && video.videoWidth > 0) {
      this.busy = true;
      try {
        const text = await this.read(video);
        if (text) {
          closeScan();
          onScanText(text);
          return;
        }
      } catch {
        // 一帧读不出来不算失败 —— 人还在动
      }
      this.busy = false;
    }
    this.timer = setTimeout(() => this.loop(), 140);
  },

  async read(video) {
    if (this.decoder.kind === 'native') {
      const codes = await this.decoder.detector.detect(video);
      return codes && codes.length ? codes[0].rawValue : null;
    }

    if (!this.canvas) {
      this.canvas = document.createElement('canvas');
      this.ctx = this.canvas.getContext('2d', { willReadFrequently: true });
    }
    // 缩到 480 宽再扫:二维码在 1280 里也是几十个像素一格,缩小不损可读性,
    // 但把逐像素的代价降到四分之一。
    const w = 480;
    const h = Math.max(1, Math.round(video.videoHeight * (w / video.videoWidth)));
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
    }
    this.ctx.drawImage(video, 0, 0, w, h);
    const frame = this.ctx.getImageData(0, 0, w, h);
    const found = window.jsQR(frame.data, w, h, { inversionAttempts: 'dontInvert' });
    return found ? found.data : null;
  },

  stop() {
    if (this.timer) { clearTimeout(this.timer); this.timer = 0; }
    if (this.stream) {
      for (const track of this.stream.getTracks()) track.stop();
      this.stream = null;
    }
    const video = $('scan-video');
    if (video) video.srcObject = null;
    this.busy = false;
  },
};

/** 挑一个能用的解码器,没有就返回 null。 */
async function makeDecoder() {
  if ('BarcodeDetector' in window) {
    try {
      const formats = await window.BarcodeDetector.getSupportedFormats();
      if (formats.includes('qr_code')) {
        return { kind: 'native', detector: new window.BarcodeDetector({ formats: ['qr_code'] }) };
      }
    } catch {
      // 支持与否本身可能抛异常,那就往下走回退
    }
  }
  if (typeof window.jsQR === 'function') return { kind: 'jsqr' };
  return null;
}

function cameraProblem(error) {
  const name = error && error.name ? error.name : '';
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return '相机权限被拒绝了。在浏览器地址栏的权限设置里允许相机，再点一次';
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return '没找到可用的摄像头';
  if (name === 'NotReadableError') return '摄像头正被别的应用占用，先关掉它再试';
  return '摄像头打不开：' + (error && error.message ? error.message : name || '未知原因');
}

function closeScan() {
  scanner.stop();
  const overlay = $('scan-overlay');
  if (overlay) {
    overlay.classList.add('hidden');
    overlay.classList.remove('album-only');
  }
  setStatus($('scan-status'), '');
}

/**
 * 从相册里的一张图认二维码。
 *
 * 需要它是因为"实时取景"不总是可用:贴纸可能已经拍过照、截图是别人发来的、
 * 或者摄像头权限被拒(那种情况下取景框会退成"只有相册"的样子)。
 *
 * 和实时取景共用同一套解码器选择,只是喂进去的是图片而不是视频帧。裁剪范围用
 * 原图尺寸、最长边不超过 1200 —— 再大只是让逐像素扫描更慢,二维码不会因此更清楚。
 */
async function scanFromAlbum(file) {
  const status = $('scan-status');
  setStatus(status, '正在识别…', true);

  let source = null;
  try {
    source = await createImageBitmap(file);
  } catch {
    // 老一点的 Safari 没有 createImageBitmap,用 <img> 也一样能画进 canvas。
    try {
      source = await new Promise((resolve, reject) => {
        const image = new Image();
        image.onload = () => resolve(image);
        image.onerror = () => reject(new Error('decode failed'));
        image.src = URL.createObjectURL(file);
      });
    } catch {
      setStatus(status, '这张图读不出来，换一张试试');
      return;
    }
  }

  let text = null;
  try {
    if (!scanner.decoder) scanner.decoder = await makeDecoder();
    text = await decodeImage(source, source.width, source.height);
  } catch (e) {
    setStatus(status, '识别失败：' + e.message);
    return;
  }

  if (!text) {
    setStatus(status, '这张图里没有认出二维码，换一张试试');
    return;
  }

  console.log(`Scan code ${text.length} chars (from album): ${text}`);
  closeScan();
  onScanText(text);
}

/** 把一张图交给当前解码器。`source` 可以是 ImageBitmap 或 HTMLImageElement。 */
async function decodeImage(source, width, height) {
  if (scanner.decoder.kind === 'native') {
    const codes = await scanner.decoder.detector.detect(source);
    return codes && codes.length ? codes[0].rawValue : null;
  }

  const scale = Math.min(1, 1200 / Math.max(width, height));
  const w = Math.max(1, Math.round(width * scale));
  const h = Math.max(1, Math.round(height * scale));

  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  ctx.drawImage(source, 0, 0, w, h);
  const frame = ctx.getImageData(0, 0, w, h);

  // 照片可能是深色模式下的截图(白码黑底),所以这一路要两种极性都试;
  // 实时取景不开这个,它对每一帧都翻一次,纯属浪费。
  const found = window.jsQR(frame.data, w, h, { inversionAttempts: 'attemptBoth' });
  return found ? found.data : null;
}

/**
 * 绑定一次扫描结果。
 *
 * 和安卓端同样的两条路,顺序由码的形状决定,而且互为回退:一个长得像设备号的码
 * 可能其实是 `qr/use` 的 id,反过来也一样。只报告两次里最后一次失败 —— 它是
 * 两种解读共同的结果。
 */
async function bindScanned(raw) {
  const candidate = DeviceQr.parse(raw);
  if (candidate.kind === 'unreadable') {
    return { ok: false, msg: '这一张不是设备码，对准机器上那张再试一次' };
  }

  const asDevice = async (id) => {
    const result = await api(`dev/favo?did=${encodeURIComponent(id)}&remove=0`);
    return result.code === CODE_OK ? { ok: true, deviceId: id } : { ok: false, result };
  };

  const asOpaque = async (id) => {
    const result = await api(`qr/use?id=${encodeURIComponent(id)}`);
    const did = result.code === CODE_OK && result.data && result.data.dev && result.data.dev.id
      ? result.data.dev.id
      : null;
    if (!did) return { ok: false, result };
    return asDevice(did);
  };

  const order = candidate.kind === 'device' ? [asDevice, asOpaque] : [asOpaque, asDevice];
  let last = null;
  for (const attempt of order) {
    const outcome = await attempt(candidate.id);
    if (outcome.ok) {
      return { ok: true, deviceId: outcome.deviceId };
    }
    last = outcome;
  }

  return { ok: false, msg: explain(last.result) || '服务器没有接受这个设备码' };
}

/** 解码出文字之后:绑定,刷新列表,把新的那台设成正在控制的。 */
async function onScanText(raw) {
  // 和安卓端同一个习惯:把扫到的原文留在控制台里。解析猜错时,这一行是唯一能查的东西。
  console.log(`Scan code ${raw.length} chars: ${raw}`);
  const settingsStatus = $('settings-status');
  setStatus(settingsStatus, '正在绑定…', true);

  const outcome = await bindScanned(raw);
  if (!outcome.ok) {
    setStatus(settingsStatus, outcome.msg);
    return;
  }

  const loaded = await loadDevices();
  if (!loaded.ok) {
    setStatus(settingsStatus, loaded.msg);
    return;
  }

  // 刚绑的那台就是唯一那台,直接把出水状态刷新一下。
  if (outcome.deviceId) {
    store.deviceId = outcome.deviceId;
    state.running = isRunning(selectedDevice());
  }

  renderMain();
  renderDevice(); renderSettings();
  setStatus(settingsStatus, '设备已加进来', true);
  showScreen('main');
}

/* ------------------------------------------------------------------ 绑定 */

function bind() {
  const el = $('login-status');

  document.querySelectorAll('.tab[data-mode]').forEach((b) => {
    b.onclick = () => {
      document.querySelectorAll('.tab[data-mode]').forEach((x) => x.classList.toggle('on', x === b));
      const sms = b.dataset.mode === 'sms';
      $('form-token').classList.toggle('hidden', sms);
      $('form-sms').classList.toggle('hidden', !sms);
      if (sms && !state.captchaKey) refreshCaptcha();
    };
  });

  $('form-token').onsubmit = (e) => {
    e.preventDefault();
    loginWithToken($('input-token').value.trim());
  };
  $('btn-captcha').onclick = refreshCaptcha;
  $('btn-sms-send').onclick = sendSms;
  $('btn-sms-login').onclick = loginWithSms;

  $('btn-toggle').onclick = toggle;

  for (const b of document.querySelectorAll('.tabitem')) {
    b.onclick = () => showScreen(b.dataset.tab);
  }

  $('btn-refresh').onclick = async () => {
    const ok = await loadDevices();
    if (ok.ok) { renderMain(); renderDevice(); renderSettings(); }
  };

  $('btn-scan').onclick = () => scanner.open();
  $('btn-scan-cancel').onclick = () => closeScan();
  $('btn-scan-album').onclick = () => $('scan-file').click();
  $('scan-file').onchange = async (event) => {
    const file = event.target.files && event.target.files[0];
    // 清空 value:同一张图连选两次,第二次不会触发 change。
    event.target.value = '';
    if (file) await scanFromAlbum(file);
  };

  $('btn-logout').onclick = () => {
    store.token = '';
    store.deviceId = '';
    state.account = null;
    state.devices = [];
    state.running = false;
    renderMain();
    renderDevice();
    renderSettings();
    showScreen('settings');
    setStatus(el, '');
  };

  $('app-version').textContent = APP_VERSION;

  const copyButton = $('diag-copy');

  if (copyButton) copyButton.onclick = copyDiag;

  const qqButton = $('qq-copy');

  if (qqButton) qqButton.onclick = copyQQGroup;

  renderDiag();

  // 公告那一行:点开看全文,点「知道了」就不再出现。
  $('notice-pill').onclick = openNotice;
  $('notice-ok').onclick = dismissNotice;
  // 点卡片外面也关:弹层不是目的地,关掉它不该需要找按钮。
  $('notice-sheet').onclick = (event) => {
    if (event.target === $('notice-sheet')) closeNotice();
  };

  // 默认那页是短信登录,而它的图形验证码要自己拉一张 —— 否则用户看到的是一个空框。
  refreshCaptcha();
}

/* ------------------------------------------------------------------ 公告 */

/**
 * 公告读的是仓库里的 `notice.json`,和安卓端**同一个文件**。
 *
 * 走 GitHub Pages 而不是 `api.github.com`,理由和安卓端一样:那条线路的未鉴权额度
 * 是每小时 60 次、按出口 IP 算,宿舍一栋楼常常共用一两个 IP —— 检查更新已经因为 403
 * 失败过一次,公告不该再踩一遍。静态文件没有额度。
 *
 * 读不到就当没有公告:一条读不出来的公告不该变成一个弹窗。
 */
const NOTICE_URL = 'notice.json';
/* 已经点过「知道了」的那几条 id,存成 JSON 数组。见 dismissedIds()。 */
const NOTICE_KEY = 'huishui.notice';

let notices = [];       /* 最近一次读到的 feed */
let shownNotice = null; /* 屏幕上正在说的那条 */

/** 弹层里现在是谁,以及「知道了」那一下该不该记账。见 openNoticeSheet。 */
let sheetNotice = null;
let sheetDismissable = false;

/** `?demo=1` 下的「知道了」只活在内存里,理由见 rememberDismissed。 */
let demoDismissed = [];

/**
 * 点过「知道了」的 id 集合。
 *
 * 存集合而不是一个 id:feed 里同时挂着两条公告时,只记最后一条会让两条轮流冒出来
 * (点掉新的 → 旧的出来 → 点掉旧的 → 新的又回来),而这个循环没有出口。
 *
 * 旧版本存的是**单个字符串**,所以读的时候两种形状都认:升级后同一条公告不该又冒出来
 * 一次,那会像"按钮坏了"。
 */
function dismissedIds() {
  if (state.demo) return demoDismissed;
  const raw = localStorage.getItem(NOTICE_KEY) || '';
  if (!raw) return [];
  const str = String(raw).trim();
  if (str.charAt(0) === '[') {
    try {
      const parsed = JSON.parse(str);
      return Array.isArray(parsed) ? parsed.map(String) : [];
    } catch (e) {
      return [];
    }
  }
  return [str];
}

function rememberDismissed(id) {
  const next = dismissedIds().filter((it) => it !== id);
  next.push(id);
  /* 只留最近 20 条:这是一份"别看第二遍"的清单,不是历史记录。 */
  const kept = next.slice(-20);
  if (state.demo) {
    /* 演示不写 localStorage:那会把真的「已经看过」清单弄脏,换回自己账号时
       就会莫名少一条公告。 */
    demoDismissed = kept;
    return;
  }
  localStorage.setItem(NOTICE_KEY, JSON.stringify(kept));
}

/** 版本号比大小。读不出来按 0 处理,和安卓端 `Version.compare` 的宽松程度一致。 */
function compareVersion(a, b) {
  const pa = String(a).match(/\d+/g) || [];
  const pb = String(b).match(/\d+/g) || [];
  for (let i = 0; i < Math.max(pa.length, pb.length); i += 1) {
    const x = Number(pa[i] || 0);
    const y = Number(pb[i] || 0);
    if (x !== y) return x < y ? -1 : 1;
  }
  return 0;
}

/**
 * 哪条该显示。
 *
 * 和安卓端 `NoticeRules.visible` 是同一套规则:id/title 非空、点过「知道了」的跳过、
 * 过期的不显示、`minVersion` 高于当前版本的不显示(旧版看不懂也做不到)。两端说法
 * 必须一致,否则同一份 notice.json 会在手机上出现、在浏览器里不出现。
 */
function pickNotice() {
  const dismissed = dismissedIds();
  const today = new Date().toISOString().slice(0, 10);
  for (const n of notices) {
    if (!n || !n.id || !n.title) continue;
    if (dismissed.indexOf(n.id) >= 0) continue;
    /* ISO 日期字符串的字典序就是时间序 */
    if (n.until && today > n.until) continue;
    if (n.minVersion && compareVersion(APP_VERSION, n.minVersion) < 0) continue;
    return n;
  }
  return null;
}

/** 把「该不该有那一行」画出来。没有公告就整行不占位置。 */
function renderNotice() {
  shownNotice = pickNotice();
  const pill = $('notice-pill');
  if (!pill) return;
  if (!shownNotice) {
    pill.classList.add('hidden');
    return;
  }
  $('notice-title').textContent = shownNotice.title;
  pill.classList.remove('hidden');
}

function openNotice() {
  openNoticeSheet(shownNotice, true);
}

/**
 * 打开某一篇全文。
 *
 * [dismissable] 是「知道了」和「关闭」的区别:从顶上一行点进来,那句"知道了"是要
 * 记账的(以后不再出现在顶上);从「历史公告」点进来只是回看,按同一个按钮记账会把
 * `huishui.notice` 改成那条老公告的 id,顶上那条被顶掉的又会冒出来 —— 读一段旧文字
 * 不该让屏幕变样。
 */
function openNoticeSheet(notice, dismissable) {
  if (!notice) return;
  sheetNotice = notice;
  sheetDismissable = Boolean(dismissable);
  $('notice-heading').textContent = notice.title;
  $('notice-body').textContent = notice.body || '';
  const when = $('notice-date');
  if (when) {
    if (notice.date) {
      when.textContent = notice.date;
      when.classList.remove('hidden');
    } else {
      when.textContent = '';
      when.classList.add('hidden');
    }
  }
  const link = $('notice-link');
  if (notice.url) {
    link.href = notice.url;
    link.classList.remove('hidden');
  } else {
    link.removeAttribute('href');
    link.classList.add('hidden');
  }
  $('notice-ok').textContent = sheetDismissable ? '知道了' : '关闭';
  $('notice-sheet').classList.remove('hidden');
}

function closeNotice() {
  $('notice-sheet').classList.add('hidden');
  sheetNotice = null;
}

/** 点过「知道了」只记 id:否则点一次之后,以后发什么都看不见了。 */
function dismissNotice() {
  if (sheetDismissable && sheetNotice) {
    rememberDismissed(sheetNotice.id);
    closeNotice();
    renderNotice();
    return;
  }
  closeNotice();
}

/**
 * 这个版本读得懂的公告,含已过期的 —— 和安卓端 `NoticeRules.history` 同一条规则。
 *
 * 过期的不算作废,正好是"历史";点过「知道了」的也留着,因为这里就是来找它的。
 */
function noticeHistory() {
  return notices.filter((n) => (
    n
    && n.id
    && n.title
    && (!n.minVersion || compareVersion(APP_VERSION, n.minVersion) >= 0)
  ));
}

/** 把「历史公告」那一块画出来。没有就整块不占位置。 */
function renderNoticeHistory() {
  const card = $('card-notice-history');
  const list = $('notice-history');
  if (!card || !list) return;

  const rows = noticeHistory();
  if (!rows.length) {
    card.classList.add('hidden');
    list.replaceChildren();
    return;
  }

  list.replaceChildren(...rows.map((notice) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'notice-row';
    row.addEventListener('click', () => openNoticeSheet(notice, false));

    const title = document.createElement('span');
    title.className = 'notice-row-title';
    title.textContent = notice.title;
    row.append(title);

    if (notice.date) {
      const when = document.createElement('span');
      when.className = 'notice-row-date';
      when.textContent = notice.date;
      row.append(when);
    }

    return row;
  }));
  card.classList.remove('hidden');
}

async function loadNotice() {
  try {
    const res = await fetch(NOTICE_URL, { cache: 'no-store' });
    if (!res.ok) return;
    const feed = await res.json();
    notices = Array.isArray(feed && feed.notices) ? feed.notices : [];
    renderNotice();
    renderNoticeHistory();
  } catch (_) {
    /* 没网、文件没发布、JSON 写错 —— 一律当作没有公告 */
  }
}

/* ------------------------------------------------------------------ 启动 */

async function boot() {
  bind();
  showScreen('main');

  // `?demo=1`：用一组假数据把「使用」和「设置」两屏画出来,一个网络请求都不发。
  // 它存在的理由很具体 —— 想先看看长什么样的人,不应该为了看一眼就把自己的 token
  // 贴进一个刚打开的页面;而截图、比对样式也需要一个不依赖账号的状态。
  if (new URLSearchParams(location.search).has('demo')) {
    state.demo = true;
    state.account = { id: 'demo', name: '潘某人', pn: '153****5248' };
    // 一台设备。演示也不假装有多台 —— 这个 app 的模型就是一台。
    state.devices = [{ id: '865137068739782', name: '荟园13栋134', status: 99 }];
    // 演示用的两个开关:一个是「正在出水」(蒸汽),一个是直接打开取景框看排版。
    // 它们都只在 ?demo=1 下生效,不会碰真账号。
    const params = new URLSearchParams(location.search);
    state.running = params.get('running') === '1';
    const want = params.get('screen');
    if (want === 'scan') {
      showScreen('settings');
      $('scan-overlay').classList.remove('hidden');
    } else {
      showScreen(want === 'settings' ? 'settings' : 'main');
    }
    renderMain();
    renderDevice(); renderSettings();

    // 公告也能用假的看一眼:`?demo=1&notice=1`。演示里不发请求,理由同上 ——
    // 想看一眼排版的人不该为此碰网络。
    if (params.has('notice')) {
      notices = [{
        id: 'demo',
        title: '今晚 22:00–23:00 停水检修',
        body: '水泵房例行检修,这段时间阀门可能没反应。洗完的记得早一点。',
        url: '',
        minVersion: '1.3.0',
        date: '2026-10-20',
      }, {
        id: 'demo-old',
        title: '周末水管检修通知',
        body: '这一条是假的,只为让「历史公告」里有第二条可看。',
        url: '',
        date: '2026-10-12',
      }];
      renderNotice();
      renderNoticeHistory();
    }
    return;
  }

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('sw.js').catch(() => { /* 离线缓存失败不影响使用 */ });
  }

  // 先画一遍,不管有没有登录。
  //
  // 这一行是补一个真的漏洞:没有 token 时下面会 return,于是「使用」屏上那些字全部
  // 来自 HTML 里的静态默认值 —— 看起来对,但那个状态点是绿的(绿 = 正在出水),
  // 而按钮也还是橙色而不是"去设置"的暖黄。静态默认值不该是这个状态的实现。
  renderMain();
  renderDevice();
  renderSettings();

  // 公告不等它:那一行出现得晚一点无所谓,而"等一个静态文件"不该拖住主界面。
  loadNotice();

  if (!store.token) return;

  const result = await loadDevices();
  if (!result.ok) {
    // token 失效:回到登录页,并说明原因,而不是留一个空按钮在那儿
    store.token = '';
    showScreen('login');
    setStatus($('login-status'), result.msg || '登录过期了');
    return;
  }
  renderMain();
  renderDevice(); renderSettings();
}

boot();
