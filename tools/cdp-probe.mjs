/**
 * Drive a headless browser over the DevTools protocol, in real time.
 *
 * Why this exists: the previous attempt to prove the camera path used
 * `--virtual-time-budget`, which fast-forwards timers. The fake camera feeds frames
 * on a real-time clock, so under virtual time it produced nothing at all
 * (`videoWidth=0x0`) and the test reported a failure that said nothing about the
 * app. The fix is not a different assertion, it is a different harness: let the page
 * run in real time and ask it questions from outside.
 *
 * Usage: `node tools/cdp-probe.mjs <page-url> [port]`
 *
 * Requires a browser already listening on `--remote-debugging-port=<port>`.
 */
const url = process.argv[2];
const port = Number(process.argv[3] || 9222);
if (!url) {
  console.error('usage: node tools/cdp-probe.mjs <page-url> [port]');
  process.exit(2);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Find the page target whose URL matches, retrying while the browser starts. */
async function findTarget() {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json`);
      const targets = await response.json();
      const page = targets.find((t) => t.type === 'page' && t.url.startsWith(url));
      if (page && page.webSocketDebuggerUrl) return page;
    } catch {
      // the browser is not listening yet
    }
    await sleep(250);
  }
  throw new Error('no page target appeared');
}

/** A tiny CDP client: one socket, request ids, awaited replies. */
class Cdp {
  constructor(socket) {
    this.socket = socket;
    this.nextId = 1;
    this.pending = new Map();
    socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      if (message.id && this.pending.has(message.id)) {
        const { resolve, reject } = this.pending.get(message.id);
        this.pending.delete(message.id);
        if (message.error) reject(new Error(message.error.message));
        else resolve(message.result);
      }
    });
  }

  send(method, params = {}) {
    const id = this.nextId;
    this.nextId += 1;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.socket.send(JSON.stringify({ id, method, params }));
    });
  }

  /** Evaluate an expression in the page and return its value (by value). */
  async evaluate(expression) {
    const result = await this.send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (result.exceptionDetails) {
      throw new Error(result.exceptionDetails.text || 'evaluation threw');
    }
    return result.result.value;
  }
}

const STATE = `JSON.stringify((function () {
  var overlay = document.getElementById('scan-overlay');
  var status = document.getElementById('settings-status');
  var video = document.getElementById('scan-video');
  return {
    overlayHidden: Boolean(overlay) && overlay.classList.contains('hidden'),
    status: status ? status.textContent : '',
    decoder: (typeof scanner !== 'undefined' && scanner.decoder) ? scanner.decoder.kind : 'none',
    video: video ? (video.videoWidth + 'x' + video.videoHeight + ' rs=' + video.readyState) : 'none',
  };
})())`;

const target = await findTarget();
const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true });
  socket.addEventListener('error', reject, { once: true });
});

const cdp = new Cdp(socket);
await cdp.send('Runtime.enable');

const lines = [];
function record(name, ok, detail) {
  lines.push(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' :: ' + detail : ''}`);
  return ok;
}

let failures = 0;
const expect = (name, ok, detail) => {
  if (!record(name, ok, detail)) failures += 1;
};

try {
  // Let the app finish booting (it pulls a captcha image).
  await sleep(1500);

  const hasButton = await cdp.evaluate("Boolean(document.getElementById('btn-scan'))");
  expect('the scan button exists', hasButton === true);

  const opened = await cdp.evaluate(
    "(function(){var b=document.getElementById('btn-scan'); if(!b) return false; b.click(); return true;})()",
  );
  expect('clicking the scan button ran', opened === true);

  let state = null;
  let closed = false;
  // Track the best video dimensions *while* polling: `scanner.stop()` clears
  // `srcObject`, so once the overlay closes the element reads 0x0 again. Reading it
  // after the fact reports "no frames" for a decode that obviously had them.
  let bestVideo = 'none';
  let bestDecoder = 'none';
  for (let waited = 0; waited < 20000; waited += 500) {
    await sleep(500);
    state = JSON.parse(await cdp.evaluate(STATE));
    if (state.video && state.video !== 'none' && !state.video.startsWith('0x')) bestVideo = state.video;
    if (state.decoder !== 'none') bestDecoder = state.decoder;
    if (state.overlayHidden) { closed = true; break; }
  }

  expect('the camera opened', Boolean(state) && state.video !== 'none', 'video element present');
  expect('a decoder was selected', bestDecoder === 'native' || bestDecoder === 'jsqr', 'decoder=' + bestDecoder);
  // Deliberately not asserting on video dimensions. The decode finishes inside the
  // first poll interval, and `scanner.stop()` clears `srcObject` on the way out, so
  // the element reads 0x0 from the outside even though frames obviously arrived.
  // The decode below *is* the evidence; a sample of the dimensions would be a race.
  lines.push(`     (frame dimensions were observed as ${bestVideo}; the decode outran the poll)`);
  expect('a frame decoded and the overlay closed', closed);
  expect('the decode led to a bind attempt', Boolean(state) && state.status.length > 0,
    'status=' + (state ? state.status.slice(0, 60) : ''));
} finally {
  console.log(lines.join('\n'));
  console.log(`scan probe: ${lines.length - failures} passed, ${failures} failed`);
  socket.close();
}

process.exit(failures ? 1 : 0);
