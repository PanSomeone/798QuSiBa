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
Check 'versionName is 1.2.1' ($gradle -match 'versionName\s*=\s*"1\.2\.1"') 'not found in app/build.gradle.kts'
Check 'versionCode is 3'    ($gradle -match 'versionCode\s*=\s*3') `
    'Android refuses an install over an equal-or-lower code, and 1.2 (code 2) is already on a phone'

Write-Host ""
Write-Host "2. the links exist and agree" -ForegroundColor Cyan
Check 'ProjectLinks.kt exists'      ($links.Length -gt 0) 'missing'
Check 'REPOSITORY is the repo'      ($links -match 'https://github\.com/PanSomeone/798QuSiBa') 'wrong or missing URL'
Check 'NEW_ISSUE derives from it'   ($links -match 'const val NEW_ISSUE = "\$REPOSITORY/issues/new"') 'the two can drift apart'

Write-Host ""
Write-Host "3. settings screen wires them up" -ForegroundColor Cyan
Check 'imports ProjectLinks'        ($settings -match 'import com\.killhuishenghuo\.core\.ProjectLinks') 'missing import'
Check 'imports Intent'              ($settings -match 'import android\.content\.Intent') 'missing import'
Check 'imports Uri'                 ($settings -match 'import android\.net\.Uri') 'missing import'
Check 'star row'                    ($settings -match [regex]::Escape($T_STAR_ROW)) 'the row the user asked for'
Check 'issue row'                   ($settings -match [regex]::Escape($T_ASK_ISSUE)) 'missing'
Check 'feedback opens the form'     ($settings -match 'openUrl\(context, ProjectLinks\.NEW_ISSUE\)') 'the button is not wired'
Check 'version row is unchanged'    ($settings -match 'BuildConfig\.VERSION_NAME') 'the version row should read from BuildConfig'

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
