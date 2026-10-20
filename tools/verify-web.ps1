# Browser assertions for the web client.
#
# Three things can only be checked in a browser:
#   1. `?demo=1` must not touch localStorage -- an earlier version wrote a fake token
#      there and overwrote the user's real one.
#   2. The demo running state turns the steam on (the .on class on #steam).
#   3. Signed out, the main button is the route to settings (.setup), not a dead switch.
#
# Every assertion is written against ASCII selectors and class names, never against
# Chinese copy: the copy changes, the class names do not. This file must stay
# ASCII-only because PowerShell 5.1 decodes a BOM-less .ps1 as ANSI, and any UTF-8
# Chinese byte sequence in here breaks the parser instead of the assertion.
param(
    [int]$Port = 8791
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$docs = Join-Path $repo 'docs'
if (-not (Test-Path $docs)) { Write-Host "no docs/ folder"; exit 1 }

$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
if (-not (Test-Path $edge)) { Write-Host "no Edge"; exit 1 }

$python = 'C:\Users\Administrator\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
if (-not (Test-Path $python)) { Write-Host "no python"; exit 1 }

$probe = Join-Path $docs '_webtest.html'
$dump = Join-Path $env:TEMP 'dsh-webtest-dom.txt'
$server = $null
$failures = 0

$probeHtml = @'
<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>webtest</title>
<style>body{margin:0;padding:14px;background:#070b12;color:#0f0;font:14px monospace;white-space:pre}</style>
</head><body><div id="out">running</div>
<script>
const EXPECTED_VERSION = '__VERSION__';

const lines = [];
function record(name, ok, detail) {
  lines.push((ok ? 'PASS ' : 'FAIL ') + name + (detail ? (' :: ' + detail) : ''));
  if (!ok) window.__failed = true;
}
function flush() {
  document.getElementById('out').textContent = lines.join('\n');
}

// Same-origin iframe, then wait: the app pulls a captcha image on boot.
function loadFrame(src, waitMs) {
  return new Promise((resolve) => {
    const frame = document.createElement('iframe');
    frame.src = src;
    frame.style.width = '420px';
    frame.style.height = '820px';
    frame.style.border = '0';
    document.body.appendChild(frame);
    frame.addEventListener('load', () => setTimeout(() => resolve(frame), waitMs));
  });
}

(async () => {
  const REAL = 'REAL-TOKEN-MUST-SURVIVE-123456';
  localStorage.setItem('huishui.token', REAL);

  // ---- demo mode: must not clobber the token, and must render running ----
  const demo = await loadFrame('index.html?demo=1&running=1', 1200);
  const after = localStorage.getItem('huishui.token');
  record('demo does not overwrite the stored token', after === REAL, 'after=' + after);

  try {
    const doc = demo.contentDocument;
    const steam = doc.getElementById('steam');
    record('demo running state turns the steam on', Boolean(steam) && steam.classList.contains('on'));
    const version = (doc.getElementById('app-version') || {}).textContent || '';
    record('version in the page matches the app', version === EXPECTED_VERSION,
      'page=' + version + ' expected=' + EXPECTED_VERSION);
    const tabbar = doc.getElementById('tabbar');
    record('tab bar is visible', Boolean(tabbar) && !tabbar.classList.contains('hidden'));
    const indicator = doc.getElementById('tab-indicator');
    record('tab indicator has a width',
      Boolean(indicator) && parseFloat(indicator.style.width || '0') > 0,
      'width=' + (indicator ? indicator.style.width : 'n/a'));
  } catch (e) {
    record('demo frame readable', false, e.message);
  }
  demo.remove();

  // ---- signed out ----
  localStorage.removeItem('huishui.token');
  const guest = await loadFrame('index.html', 900);
  try {
    const doc = guest.contentDocument;
    const button = doc.getElementById('btn-toggle');
    record('signed-out button is the setup route', Boolean(button) && button.classList.contains('setup'));
    record('signed-out screen has no steam', !doc.getElementById('steam').classList.contains('on'));
    record('sign-in card is shown', !doc.getElementById('card-signin').classList.contains('hidden'));
    record('device card is hidden', doc.getElementById('card-device').classList.contains('hidden'));
  } catch (e) {
    record('guest frame readable', false, e.message);
  }

  // ---- the default sign-in path reaches the server ----
  try {
    const d = guest.contentDocument;
    d.getElementById('input-phone').value = '13800000000';
    d.getElementById('input-captcha').value = '1234';
    d.getElementById('btn-sms-send').click();
    await new Promise((r) => setTimeout(r, 4000));
    const st = (d.getElementById('login-status') || {}).textContent || '';
    record('sms send reaches the server and reports something', st.length > 0, 'status=' + st.slice(0, 30));
  } catch (e) {
    record('sms probe ran', false, e.message);
  }

  // ---- the notice row ----
  // `?demo=1&notice=1` plants a fake notice, so this needs no network and no editing
  // of notice.json. What is being checked is the promise: the row only exists when
  // there is something to say, dismissing it hides it AND is remembered.
  localStorage.removeItem('huishui.notice');
  const noticed = await loadFrame('index.html?demo=1&notice=1', 1200);
  try {
    const doc = noticed.contentDocument;
    const pill = doc.getElementById('notice-pill');
    record('the notice row appears when there is a notice',
      Boolean(pill) && !pill.classList.contains('hidden'));
    record('the notice row carries a title',
      (((doc.getElementById('notice-title') || {}).textContent) || '').length > 0);
    // Geometry, not a screenshot: an earlier version of the big button overflowed the
    // right edge and a screenshot looked almost right. The number does not lie.
    const edge = pill.getBoundingClientRect();
    record('the notice row fits inside the page',
      edge.width > 0 && edge.left >= 0 && edge.right <= doc.documentElement.clientWidth + 1,
      'left=' + Math.round(edge.left) + ' right=' + Math.round(edge.right) +
      ' page=' + doc.documentElement.clientWidth);
    pill.click();
    record('the notice opens its full text',
      !doc.getElementById('notice-sheet').classList.contains('hidden'));
    doc.getElementById('notice-ok').click();
    record('dismissing hides the row', pill.classList.contains('hidden'));
    record('dismissing records the id', (localStorage.getItem('huishui.notice') || '') === 'demo');
  } catch (e) {
    record('notice frame readable', false, e.message);
  }

  const again = await loadFrame('index.html?demo=1&notice=1', 900);
  try {
    record('a dismissed notice stays dismissed',
      again.contentDocument.getElementById('notice-pill').classList.contains('hidden'));
  } catch (e) {
    record('notice reload readable', false, e.message);
  }
  again.remove();
  noticed.remove();
  localStorage.removeItem('huishui.notice');

  // ---- the QQ group row ----
  // The number is the whole feature: if it is not on screen, a user with a question
  // and no GitHub account has nowhere to go. Clicking it cannot be asserted down to
  // "the clipboard now holds 1126108304" -- headless Edge has no user activation, so
  // the copy may take either of two paths -- but it must always leave the number
  // readable on the page.
  const settings = await loadFrame('index.html?demo=1&screen=settings', 1200);
  try {
    const doc = settings.contentDocument;
    const qq = doc.getElementById('qq-copy');
    const hint = doc.getElementById('qq-hint');
    record('the QQ group row shows the group number',
      Boolean(qq) && ((qq.textContent || '').indexOf('1126108304') >= 0));
    record('the QQ group row has a hint beside it', Boolean(hint));
    if (qq) {
      qq.click();
      await new Promise((r) => setTimeout(r, 300));
      const said = (hint.textContent || '').trim();
      record('pressing the QQ row tells the user what happened',
        said.length > 0 && (said.indexOf('1126108304') >= 0 || said.length > 3),
        'hint=' + said.slice(0, 24));
      const box = qq.getBoundingClientRect();
      record('the QQ group row fits inside the page',
        box.width > 0 && box.left >= 0 && box.right <= doc.documentElement.clientWidth + 1,
        'left=' + Math.round(box.left) + ' right=' + Math.round(box.right) +
        ' page=' + doc.documentElement.clientWidth);
    }
  } catch (e) {
    record('settings frame readable', false, e.message);
  }
  settings.remove();

  flush();
  // The verdict goes in the title, not in the body: this page's own source contains
  // the strings 'PASS ' and 'FAIL ', so grepping the dumped document for them also
  // matches the source -- one false failure per run, which is worse than no check.
  document.title = (window.__failed ? 'FAILED' : 'ALLPASS') + ' ' + lines.length;
})();
</script></body></html>
'@

# The expected version comes from the same place the shell script reads it.
$appVersion = ''
$appJs = Join-Path $docs 'app.js'
if (Test-Path $appJs) {
    $c = Get-Content $appJs -Raw -Encoding UTF8
    if ($c -match "APP_VERSION\s*=\s*'([0-9.]+)'") { $appVersion = $Matches[1] }
}
if (-not $appVersion) { Write-Host "cannot read APP_VERSION"; exit 1 }

($probeHtml -replace '__VERSION__', $appVersion) | Set-Content -Path $probe -Encoding UTF8

try {
    $server = Start-Process -FilePath $python -ArgumentList '-m','http.server',"$Port",'--bind','127.0.0.1' `
        -WorkingDirectory $docs -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $env:TEMP 'dsh-webtest-server.log')
    Start-Sleep -Seconds 2

    Remove-Item $dump -Force -ErrorAction SilentlyContinue
    Start-Process -FilePath $edge `
        -ArgumentList '--headless=new','--disable-gpu','--virtual-time-budget=15000','--dump-dom',"http://127.0.0.1:$Port/_webtest.html" `
        -RedirectStandardOutput $dump -NoNewWindow -Wait | Out-Null

    if (-not (Test-Path $dump)) { throw "no DOM dump" }
    $dom = Get-Content $dump -Raw -Encoding UTF8

    if ($dom -notmatch '<title>([A-Z]+) (\d+)</title>') { throw "no verdict in the title" }
    $verdict = $Matches[1]
    $count = [int]$Matches[2]
    if ($verdict -ne 'ALLPASS') {
        $failures = 1
        if ($dom -match '(?s)<div id="out">(.*?)</div>') {
            foreach ($line in ($Matches[1] -split "`n")) {
                if ($line.Trim().StartsWith('FAIL ')) { Write-Host ("  " + $line.Trim()) -ForegroundColor Red }
            }
        }
    }
    Write-Host ("  web checks: {0} assertions, verdict {1}" -f $count, $verdict)
}
catch {
    Write-Host ("  web checks crashed: " + $_.Exception.Message) -ForegroundColor Red
    $failures += 1
}
finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item $probe -Force -ErrorAction SilentlyContinue
    Remove-Item $dump -Force -ErrorAction SilentlyContinue
}

if ($failures -gt 0) { Write-Host "WEB FAILED" -ForegroundColor Red; exit 1 }
Write-Host "WEB PASSED" -ForegroundColor Green
exit 0
