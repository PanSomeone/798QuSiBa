# Verifying the v1.2 feedback + star changes.
#
#     powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-v1.2.ps1
#
# Run it from the project root. The exit code is the number of failed checks.
#
# Three rules this script follows, all of them learned the hard way:
#
#   * ASCII only. This machine runs Windows PowerShell 5.1 whose ANSI codepage
#     is 936 (GB2312), and PowerShell decodes a BOM-less .ps1 using that
#     codepage -- so a Chinese literal in this file is mangled before the script
#     ever runs. The first version of this file had four of them and died in
#     section 4 with "Not enough )'s"; because it died there it never reached
#     the Gradle step, and a build-breaking edit sat undetected behind a script
#     whose entire purpose was to catch it. Every other script in tools/ is
#     ASCII-only for the same reason. The Chinese strings this script has to
#     match are written below as \uXXXX escapes and decoded at run time.
#
#   * Read the sources as UTF-8 explicitly. `Get-Content -Raw` without
#     `-Encoding UTF8` decodes with the ANSI codepage too, so the file being
#     inspected would be garbled even if this script were pure ASCII.
#
#   * A failed check never stops the run. The last section compiles the project;
#     a check that aborts before it has verified nothing.
#
# Everything here is read-only except the Gradle invocation.

$ErrorActionPreference = 'Continue'
$fail = 0

function Check($name, $ok, $detail) {
    if ($ok) { Write-Host "  PASS  $name" -ForegroundColor Green }
    else { Write-Host "  FAIL  $name  -- $detail" -ForegroundColor Red; $script:fail++ }
}

# Reads a source file the way it is actually stored. Missing file yields '' so
# the checks below report a failure instead of throwing.
function Slurp([string]$path) {
    if (-not (Test-Path $path)) { return '' }
    return (Get-Content $path -Raw -Encoding UTF8)
}

# Decodes a \uXXXX-escaped ASCII string into the characters it names.
function U([string]$escaped) { return [regex]::Unescape($escaped) }

# The Chinese the checks below look for. Spelled once, in ASCII.
$T_THIS_PROJECT = U '\u8FD9\u4E2A\u9879\u76EE'                    # this project
$T_STAR_ROW     = "Star $T_THIS_PROJECT"                           # the star row label
$T_FEEDBACK     = U '\u53CD\u9988'                                 # feedback
$T_ASK_ISSUE    = U '\u53CD\u9988\u95EE\u9898 / \u63D0 issue'      # the issue row label
$T_STAR_ASK     = U '\u7ED9\u6211(\u7684)?\u9879\u76EE\u52A0\u4E2A ?Star'  # the README ask
$T_PENDING      = U '\u5F85\u8865\u5145\u6E05\u5355'                # the retired notes file
$T_BIND_ROW     = U '\u626B\u7801\u6DFB\u52A0\u8BBE\u5907'          # the scan-to-bind row label
$T_BIND_BODY    = U '\u52A0\u8FDB\u6765\u7684\u8BBE\u5907\u5C31\u662F'  # the explainer that was cut
$T_NO_CODE      = U '\u6682\u65F6\u6CA1\u6709\u626B\u5230\u4E8C\u7EF4\u7801'  # the sentence a cancel must produce
$T_QQ_ROW       = "QQ $T_FEEDBACK" + (U '\u7FA4')                    # the QQ group row label

# A top-level private function's body, so a check can ask what is in scope
# inside it rather than anywhere in the file. The terminator is the first line
# that is exactly "}" at column zero, which is where a top-level function ends.
#
# `\r?\n` rather than `\n`: the working tree is CRLF (`.gitattributes` stores LF
# and Git rewrites on checkout), and a first version of this helper that assumed
# LF reported a false failure on code that compiles.
function Body([string]$text, [string]$signature) {
    $m = [regex]::Match($text, "(?s)private fun $signature\(.*?\r?\n\}\r?\n")
    if ($m.Success) { return $m.Value }
    return ''
}

$gradle   = Slurp 'app\build.gradle.kts'
$links    = Slurp 'app\src\main\java\com\killhuishenghuo\core\ProjectLinks.kt'
$settings = Slurp 'app\src\main\java\com\killhuishenghuo\ui\settings\SettingsScreen.kt'
$readme   = Slurp 'README.md'
$manifest = Slurp 'app\src\main\AndroidManifest.xml'
$bindBlock = Slurp 'app\src\main\java\com\killhuishenghuo\ui\settings\DeviceBindBlock.kt'
$qrParser = Slurp 'app\src\main\java\com\killhuishenghuo\data\model\DeviceQr.kt'

Write-Host ""
Write-Host "1. version" -ForegroundColor Cyan
Check 'versionName is 1.3.7' ($gradle -match 'versionName\s*=\s*"1\.3\.7"') 'not found in app/build.gradle.kts'

# The web client shows a version in Settings, and it is a hand-copied number. Two
# hand-copied numbers drift; one of them is always right and nobody notices the
# other one. So the rule is a check: the page and the app must agree.
$webDir = "docs\app.js"
if (Test-Path $webDir) {
    $webJs = Get-Content $webDir -Raw -Encoding UTF8
    $androidVersion = if ($gradle -match 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"') { $Matches[1] } else { "" }
    $webVersion = if ($webJs -match "APP_VERSION\s*=\s*'([0-9]+\.[0-9]+\.[0-9]+)'") { $Matches[1] } else { "" }
    Check 'web version matches the app' ($webVersion -ne '' -and $webVersion -eq $androidVersion) `
        "docs/app.js says '$webVersion', app/build.gradle.kts says '$androidVersion'"
}

Check 'versionCode is 14'    ($gradle -match 'versionCode\s*=\s*14') `
    'Android refuses an install over an equal-or-lower code, and earlier builds are already on a phone'

Write-Host ""
Write-Host "2. the links exist and agree" -ForegroundColor Cyan
Check 'ProjectLinks.kt exists'      ($links.Length -gt 0) 'missing'
Check 'REPOSITORY is the repo'      ($links -match 'https://github\.com/PanSomeone/798QuSiBa') 'wrong or missing URL'
Check 'NEW_ISSUE derives from it'   ($links -match 'const val NEW_ISSUE = "\$REPOSITORY/issues/new"') 'the two can drift apart'

# The user group is a number, and it is typed in two source files that never import
# each other: the app's ProjectLinks and the page's app.js. Same rule as the version
# - two hand-copied values drift, so the check is the rule.
Check 'QQ_GROUP is the group the user gave' ($links -match 'const val QQ_GROUP = "1126108304"') `
    'app/src/main/java/com/killhuishenghuo/core/ProjectLinks.kt'
$androidGroup = if ($links -match 'QQ_GROUP = "([0-9]{6,12})"') { $Matches[1] } else { "" }
$webGroupJs = if (Test-Path 'docs\app.js') { Get-Content 'docs\app.js' -Raw -Encoding UTF8 } else { "" }
$webGroup = if ($webGroupJs -match "QQ_GROUP = '([0-9]{6,12})'") { $Matches[1] } else { "" }
Check 'the QQ group is one number, not two' ($webGroup -ne '' -and $webGroup -eq $androidGroup) `
    "docs/app.js says '$webGroup', ProjectLinks.kt says '$androidGroup'"

Write-Host ""
Write-Host "3. settings screen wires them up" -ForegroundColor Cyan
Check 'imports ProjectLinks'        ($settings -match 'import com\.killhuishenghuo\.core\.ProjectLinks') 'missing import'
Check 'imports Intent'              ($settings -match 'import android\.content\.Intent') 'missing import'
Check 'imports Uri'                 ($settings -match 'import android\.net\.Uri') 'missing import'
Check 'star row'                    ($settings -match [regex]::Escape($T_STAR_ROW)) 'the row the user asked for'
Check 'issue row'                   ($settings -match [regex]::Escape($T_ASK_ISSUE)) 'missing'
Check 'feedback opens the form'     ($settings -match 'openUrl\(context, ProjectLinks\.NEW_ISSUE\)') 'the button is not wired'
Check 'version row is unchanged'    ($settings -match 'BuildConfig\.VERSION_NAME') 'the version row should read from BuildConfig'

Check 'the QQ group row exists'     ($settings -match [regex]::Escape("label = `"$T_QQ_ROW`"")) `
    'missing - expected in the feedback section'
Check 'the QQ group row reads the number from ProjectLinks' `
    ($settings -match 'ProjectLinks\.QQ_GROUP\b') 'a typed-out number here would drift from the one the page shows'

# Order matters and is the whole design: the copy cannot fail, opening QQ can.
# Written backwards (open first, copy second) the row still looks right on a phone
# with QQ installed and silently does nothing on one without it.
$qqBody = Body $settings 'QQGroupRow'
$copyAt = if ($qqBody) { $qqBody.IndexOf('copyToClipboard') } else { -1 }
$openAt = if ($qqBody) { $qqBody.IndexOf('openDeepLink') } else { -1 }
Check 'the row copies before it tries to open QQ' ($copyAt -ge 0 -and $openAt -gt $copyAt) `
    'copy first: it is the half that works on a phone that has never installed QQ'

# The bug this script was written too late to catch: `openUrl(context, ...)`
# inside a function that has no `context` compiles to a call on the stdlib
# `kotlin.context` and fails the build in all three variants.
$feedbackBody = Body $settings 'FeedbackBlock'
Check 'FeedbackBlock has a Context' ($feedbackBody -match 'val context = LocalContext\.current') `
    'openUrl(context, ...) there resolves to kotlin.context and does not compile'

Write-Host ""
Write-Host "4. README" -ForegroundColor Cyan
Check 'star link near the top'      ($readme -match $T_STAR_ASK) 'the literal line the user asked for'
Check 'star badge'                  ($readme -match 'img\.shields\.io/github/stars/PanSomeone/798QuSiBa') 'missing'
Check 'a feedback section'          ($readme -match "(?m)^## $T_FEEDBACK$") 'missing'
Check 'issue links resolve'         ($readme -match '\]\(https://github\.com/PanSomeone/798QuSiBa/issues/new\)') 'missing'
Check 'no link to the retired notes' ($readme -notmatch $T_PENDING) `
    'that file is not in the repository, so the link is a dead end'

Write-Host ""
Write-Host "5. every relative link in README resolves" -ForegroundColor Cyan
$targets = [regex]::Matches($readme, '\]\(([^)#]+)\)') |
    ForEach-Object { $_.Groups[1].Value } |
    Where-Object { $_ -notmatch '^[a-z]+:' -and $_ -notmatch '^_attic/' } |
    Sort-Object -Unique
$missing = @($targets | Where-Object { -not (Test-Path ($_ -replace '/$', '')) })
Check "all $($targets.Count) relative link target(s) exist" ($missing.Count -eq 0) ("missing: " + ($missing -join ', '))

Write-Host ""
Write-Host "5b. scan to bind a device (1.2.1)" -ForegroundColor Cyan
Check 'CAMERA is declared'      ($manifest -match 'android\.permission\.CAMERA') `
    'the scanner cannot ask for a camera the manifest never declares'
Check 'a camera is optional'    ($manifest -match '(?s)android\.hardware\.camera.{0,120}required="false"') `
    'a phone without one must still install and type the code by hand'
Check 'the bind row exists'     ($bindBlock -match [regex]::Escape($T_BIND_ROW)) 'the row the user asked for'
Check 'no stray explainer'      ($bindBlock -notmatch [regex]::Escape($T_BIND_BODY)) `
    'the block is a label, two controls and a result line; the paragraph under the label told nobody anything'
Check 'a cancel is reported'    (($bindBlock -match 'QRUserCanceled ->') -and ($bindBlock -match [regex]::Escape($T_NO_CODE))) `
    'the scanner has no timeout, so a silent QRUserCanceled is exactly what "it exited by itself and swallowed the try" looks like'
Check 'the scan path is logged' (($bindBlock -match 'AppLog\.log\("Scan", "opened') -and ($bindBlock -match 'AppLog\.log\("Scan", "code ')) `
    'a scan attempt that leaves no trace cannot be diagnosed from an exported log -- the first report of this had not one line about it'
Check 'the scanner is portrait' ($manifest -match '(?s)QRScannerActivity.{0,200}screenOrientation="portrait"') `
    'a sideways phone gets a letterboxed preview and a needless activity recreation'
Check 'the payload parser'      ($qrParser -match 'object DeviceQr') 'missing'
Check 'the device list is read' ($settings -match 'DeviceBindBlock\(') 'the block is not wired into settings'
Check 'publish is arm64 only'   ($gradle -match 'abiFilters \+= "arm64-v8a"') `
    'the bundled ML Kit model is ~5MB per ABI; a release with four of them is ~22MB'
Check 'debug keeps x86_64'      ($gradle -match 'abiFilters \+= "x86_64"') `
    'without it the scanner ends in UnsatisfiedLinkError on the emulator, which is the only place it can be opened before shipping'

Write-Host ""
Write-Host "6. build and test" -ForegroundColor Cyan
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
& .\gradlew.bat --console=plain :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark :app:assembleRelease
if ($LASTEXITCODE -ne 0) { Write-Host "  BUILD FAILED (exit $LASTEXITCODE)" -ForegroundColor Red; $fail++ }
else { Write-Host "  build ok" -ForegroundColor Green }

$results = Get-ChildItem 'app\build\test-results\testDebugUnitTest' -Filter *.xml -ErrorAction SilentlyContinue
if ($results) {
    $tests = 0; $bad = 0
    foreach ($r in $results) {
        $xml = [xml](Get-Content $r.FullName -Encoding UTF8)
        $tests += [int]$xml.testsuite.tests
        $bad += [int]$xml.testsuite.failures + [int]$xml.testsuite.errors
    }
    Check "unit tests: $tests in $($results.Count) class(es), $bad failing" ($bad -eq 0) 'see app/build/test-results'
}

Write-Host ""
Write-Host "6b. R8 kept the ML Kit scanner" -ForegroundColor Cyan
# A separate script because it is the check that would have caught the bug this
# release is about, and it is worth running on its own after any dependency bump.
# Called as a child process so its `exit` cannot take this run with it.
& powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-scan-keeps.ps1
if ($LASTEXITCODE -ne 0) { $fail++ }

Write-Host ""
Write-Host "6b2. the web client, in a real browser" -ForegroundColor Cyan
# Same reasoning as above: a sibling script, run in its own process. It starts a
# static server, drives headless Edge, and asserts against the DOM -- the demo does
# not touch localStorage, the steam follows the running state, the signed-out button
# is the route to settings, and the version in the page matches the app's.
if (Test-Path 'docs\index.html') {
    & powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-web.ps1
    if ($LASTEXITCODE -ne 0) { $fail++ }
}

Write-Host ""
Write-Host "6c. the update check cannot leak the session token" -ForegroundColor Cyan
$updateChecker = Slurp 'app\src\main\java\com\killhuishenghuo\data\update\UpdateChecker.kt'
Check 'it talks to GitHub'          ($updateChecker -match 'api\.github\.com/repos/PanSomeone/798QuSiBa/releases') 'wrong or missing endpoint'
Check 'it builds its own client'    ($updateChecker -match 'OkHttpClient\.Builder\(\)') `
    'without its own client it would inherit the gateway one, and that one attaches Authorization to every request'
# The check is on the **call**, not on the word. The first version also forbade the
# string `AuthInterceptor` anywhere in the file, and the file's own KDoc explains
# that hazard by name -- so the check failed on the comment that documents the rule
# it was enforcing. Grepping source text cannot tell code from prose; grep the
# thing that would actually leak, which is a call to the gateway's factory.
Check 'it never borrows the gateway client' ($updateChecker -notmatch 'NetworkModule\.okHttpClient\(') `
    'a session token for a water account must never be sent to api.github.com; UpdateCheckerTest pins it over a real socket too'
Check 'the row is wired in'         ($settings -match 'UpdateRow\(') 'the block is not on the settings screen'

# The mirror is not a nicety: api.github.com is rate limited per exit IP, and the
# download it points at is 5MB from objects.githubusercontent.com. The people this
# app is for can reach gitee.com and often nothing on GitHub, so the mirror has to
# be a road of its own, not just a second URL on the same answer.
Check 'it knows the domestic mirror'  ($updateChecker -match 'gitee\.com/api/v5/repos/pansomeone/798QuSiBa/releases') `
    'the mirror is the only release list that answers from a dormitory'
Check 'the mirror can answer on its own' ($updateChecker -match 'fun newestViaMirror\(') 'missing the mirror road'
$otherRoads = [regex]::Match($updateChecker, '(?s)private fun otherRoads\(.*?\n    \}').Value
Check 'the mirror is tried before the feed' `
    ($otherRoads -ne '' -and $otherRoads.IndexOf('newestViaMirror') -ge 0 -and $otherRoads.IndexOf('newestViaAtom') -gt $otherRoads.IndexOf('newestViaMirror')) `
    'github.com and api.github.com are blocked together; gitee.com is not'
Check 'the download comes from the mirror when it has it' ($updateChecker -match 'download from the mirror') `
    'a check that finds the version and then downloads it at 20KB/s has not helped anybody'

Write-Host ""
Write-Host "6d. the notice archive" -ForegroundColor Cyan
# The banner can be dismissed, which used to mean the sentence was gone for good.
# The archive is the fix, so what is checked here is its two failure modes: rows that
# disappear when they expire or are acknowledged (then it archives nothing), and a
# history press that rewrites the dismissed id (then reading an old notice makes the
# current banner pop back up).
$noticeKt  = Slurp 'app\src\main\java\com\killhuishenghuo\data\notice\Notice.kt'
$noticeVm  = Slurp 'app\src\main\java\com\killhuishenghuo\ui\notice\NoticeViewModel.kt'
$historyUi = Slurp 'app\src\main\java\com\killhuishenghuo\ui\settings\NoticeHistorySection.kt'
$webApp    = Slurp 'docs\app.js'
$noticeKtPrefs = Slurp 'app\src\main\java\com\killhuishenghuo\data\local\PreferencesStore.kt'

Check 'a notice can carry a date' ($noticeKt -match '@SerialName\("date"\)') 'null is allowed, but the feed we publish should fill it'
Check 'the archive rule exists'   ($noticeKt -match 'fun history\(') 'missing NoticeRules.history'
$historyBody = [regex]::Match($noticeKt, '(?s)fun history\(.*?\}').Value
Check 'the archive ignores until and dismissed' ($historyBody -ne '' -and $historyBody -notmatch 'until|dismissed') `
    'filtering by expiry or by "read it" removes exactly the notices an archive exists for'
Check 'the view model publishes it'  ($noticeVm -match 'val history: StateFlow<List<Notice>>') 'missing history flow'
Check 'settings shows the archive'   ($settings -match 'NoticeHistorySection\(') 'the block is not on the settings screen'
Check 'the archive rows reuse the banner sheet' ($historyUi -match 'NoticeSheet\(') `
    'two sheets means the banner and the archive can disagree about the same notice'
Check 'the page has the archive too' ($webApp -match 'function renderNoticeHistory') 'docs/app.js'
Check 'dismissals are a set, not one id' ($noticeKtPrefs -match 'var noticeDismissedIds: Set<String>') `
    'with a single remembered id, two published notices take turns forever'
Check 'the view model adds instead of overwriting' ($noticeVm -match 'noticeDismissedIds \+ id') `
    'overwriting drops the earlier dismissal, so the older notice returns on the next launch'
Check 'the page remembers a list too' ($webApp -match 'function rememberDismissed') 'docs/app.js'
Check 'reading an old notice keeps the dismissal' `
    (($webApp -match 'sheetDismissable') -and ($webApp -match 'textContent = sheetDismissable')) `
    'a history press that writes huishui.notice makes the dismissed banner reappear'

if (Test-Path 'docs\notice.json') {
    $feed = Get-Content 'docs\notice.json' -Raw -Encoding UTF8 | ConvertFrom-Json
    $ids = @($feed.notices | ForEach-Object { $_.id })
    Check 'the feed carries the re-login notice' ($ids -contains 'notice-relogin-1') ('ids: ' + ($ids -join ','))
    # Order is priority in both clients. If the new advice is not first, the launch
    # notice keeps the top slot and nobody sees the sentence that was just written.
    Check 'the newest notice is first' ($ids.Count -gt 0 -and $ids[0] -eq 'notice-relogin-1') `
        ('first is ' + ($ids | Select-Object -First 1) + '; notice.json order is the display order')
    $undated = @($feed.notices | Where-Object { -not $_.date })
    Check 'every published notice has a date' ($undated.Count -eq 0) ('undated: ' + $undated.Count)
}

Write-Host ""
Write-Host "7. what the build actually produced" -ForegroundColor Cyan
foreach ($v in 'debug', 'benchmark', 'release') {
    $dir = "app\build\outputs\apk\$v"
    $apk = Get-ChildItem $dir -Filter *.apk -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $apk) { Write-Host "  (no $v apk)"; continue }
    $hash = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash
    Write-Host ("  {0,-24} {1,10:N0} B  {2}" -f $apk.Name, $apk.Length, $hash)
    if ($v -eq 'debug') {
        $aapt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt2.exe"
        if (Test-Path $aapt) {
            & $aapt dump badging $apk.FullName 2>$null | Select-String 'versionName|versionCode' |
                ForEach-Object { "    $_" }
        }
    }
}
Write-Host "  (this run's build outputs. -fast.apk is not byte-reproducible: three clean builds"
Write-Host "   of one commit give three different sha256 at an identical size, because it is the"
Write-Host "   variant that is both shrunk and signed -- the difference lands in the signing"
Write-Host "   block while all 88 zip entries stay byte-identical. debug and release DO rebuild"
Write-Host "   byte-for-byte, so a hash here is comparable for those two and not for -fast.)"

Write-Host ""
if ($fail -eq 0) { Write-Host "all checks passed" -ForegroundColor Green }
else { Write-Host "$fail check(s) failed" -ForegroundColor Red }
exit $fail
