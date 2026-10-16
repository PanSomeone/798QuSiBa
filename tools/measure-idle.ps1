# Measure one APK's idle rendering, the same way every time.
#
# Why `dumpsys gfxinfo` and not a macrobenchmark: Macrobenchmark needs
# `androidx.benchmark`, which is not in the local Gradle cache and would need a
# network fetch to a repository this machine may not reach. `gfxinfo` is already
# on the device, it counts real frames produced by the real render pipeline, and
# the number it reports is exactly the one this project needs to move.
#
# The measurement is deliberately dumb: install, launch, wait for the screen to
# settle, reset the counters, sit still for N seconds, read them. No input, no
# camera, no scrolling, no network after the first device load. That is the state
# this app is in for most of its life, and it is the state in which the ambience
# used to produce sixty frames a second while drawing nothing.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\measure-idle.ps1 -Apk <path> -Seconds 30
#   ... -Label "before"    # tag the output
#   ... -Raw               # also dump the raw gfxinfo block

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Apk,

    [int]$Seconds = 30,

    [string]$Label = "",

    # `idle`  : settle, reset counters, then sit still. Measures steady-state cost.
    # `start` : reset counters first, then launch. Measures the cold start, which
    #           is the one moment the app does real work and is therefore the only
    #           window in which "is the animation smooth" is a meaningful question.
    [ValidateSet('idle', 'start')]
    [string]$Phase = 'idle',

    # Print the whole `gfxinfo` section instead of the summary lines.
    [switch]$Raw,

    [string]$Package = "com.killhuishenghuo",

    # The full component, not `.MainActivity` shorthand. The shorthand is resolved
    # against the *applicationId*, so `-Package com.killhuishenghuo.benchmark`
    # expands to `com.killhuishenghuo.benchmark.benchmark.MainActivity` and `am
    # start` fails with "Activity class ... does not exist". That failure is quiet:
    # the script goes on to measure whatever happens to be in the foreground, which
    # yields a real-looking number for the wrong app.
    [string]$Activity = "com.killhuishenghuo/.MainActivity"
)

$ErrorActionPreference = 'Stop'

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at $adb" }
if (-not (Test-Path -LiteralPath $Apk)) { throw "apk not found at $Apk" }

function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $adb @Args
}

# --- Install ---------------------------------------------------------------------
Write-Host "[1/5] installing $(Split-Path -Leaf $Apk) ($((Get-Item -LiteralPath $Apk).Length) bytes)"
Adb install -r $Apk | Select-Object -Last 1

Adb shell am force-stop $Package | Out-Null
Start-Sleep -Seconds 2

if ($Phase -eq 'idle') {
    # The app asks the gateway for the device list on launch. On this machine that
    # request fails (no account, and the emulator cannot reach the real host), which
    # is fine and is itself the point: after it fails, the screen is static.
    Write-Host "[2/5] starting, then letting it settle for 10s"
    Adb shell am start -n $Activity | Out-Null
    Start-Sleep -Seconds 10

    Write-Host "[3/5] resetting counters"
    Adb shell dumpsys gfxinfo $Package reset | Out-Null

    Write-Host "[4/5] sampling for $Seconds s with no input"
    Start-Sleep -Seconds $Seconds
}
else {
    # Cold start: reset first, so the very first frame the app draws is counted.
    Write-Host "[2/5] resetting counters while stopped"
    Adb shell dumpsys gfxinfo $Package reset | Out-Null

    Write-Host "[3/5] launching"
    Adb shell am start -n $Activity | Out-Null

    Write-Host "[4/5] sampling the first $Seconds s"
    Start-Sleep -Seconds $Seconds
}

$out = Adb shell dumpsys gfxinfo $Package
$text = $out -join "`n"

# Guard the one failure this script cannot see on its own. `am start` failing is not
# fatal to the shell, so the run would carry on and report the frame counts of
# whatever app was already on screen -- a plausible number for the wrong program.
$top = (Adb shell dumpsys activity activities |
    Select-String -Pattern 'topResumedActivity|mResumedActivity' |
    Select-Object -First 1)
if (-not $top -or $top.Line -notmatch [regex]::Escape($Package)) {
    Write-Warning "the foreground activity does not look like ${Package}: $($top.Line)"
    Write-Warning "the frame counts below may belong to another app; check -Activity"
}

if ($Raw) {
    Write-Host ""
    Write-Host $text
    Write-Host ""
}

function Field {
    param([string]$Name)
    $m = [regex]::Match($text, "(?m)^\s*$([regex]::Escape($Name)):\s*(.+?)\s*$")
    if ($m.Success) { return $m.Groups[1].Value } else { return "n/a" }
}

$frames = Field 'Total frames rendered'
$janky = Field 'Janky frames'
$missed = Field 'Number Missed Vsync'
$slowUi = Field 'Number Slow UI thread'
$slowDraw = Field 'Number Slow issue draw commands'
$p50 = Field '50th percentile'
$p90 = Field '90th percentile'
$p95 = Field '95th percentile'
$p99 = Field '99th percentile'

# The headline number. "Frames produced per second while nothing is happening" is
# the metric an idle animation shows up in, and it is the one this project set out
# to drive to zero.
$fps = if ($frames -match '^\d+$' -and $Seconds -gt 0) {
    [math]::Round([int]$frames / $Seconds, 2)
} else { 'n/a' }

Write-Host "[5/5] result"
Write-Host ""
if ($Label) { Write-Host "label            : $Label" }
Write-Host "apk              : $(Split-Path -Leaf $Apk)"
Write-Host "phase            : $Phase"
Write-Host "window           : $Seconds s, no input, screen on"
Write-Host "total frames     : $frames"
Write-Host "frames per second: $fps     <-- idle cost; 0 means the app produces no frames"
Write-Host "janky frames     : $janky"
Write-Host "missed vsync     : $missed"
Write-Host "slow UI thread   : $slowUi"
Write-Host "slow draw cmds   : $slowDraw"
Write-Host "frame time p50   : $p50"
Write-Host "frame time p90   : $p90"
Write-Host "frame time p95   : $p95"
Write-Host "frame time p99   : $p99"
