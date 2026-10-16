# Move this project to another directory (or another machine) instead of dragging
# the folder in Explorer.
#
# Dragging works most of the time, but it carries four things that belong to *this*
# machine: build output, the Gradle cache, Android Studio's window layout, and
# `local.properties`. The first three only slow down the first build or make the IDE
# complain about paths that no longer exist. The fourth changes behaviour: without
# it the app's top bar says "this build has no base URL configured", which looks
# like broken code.
#
# Usage (Windows PowerShell 5.1 needs the bypass; see tools\move-project.cmd):
#     powershell -NoProfile -ExecutionPolicy Bypass -File tools\move-project.ps1 -Destination D:\code\798QuSiBa
#     ... -SkipAttic          # leave the 215MB of dev material behind
#     ... -DryRun             # print what would happen, copy nothing
#
# Then, in the new directory:
#     .\gradlew.bat assembleDebug testDebugUnitTest
#
# NOTE: this file is deliberately pure ASCII. Windows PowerShell 5.1 reads .ps1
# files using the system ANSI code page unless they start with a UTF-8 BOM, so a
# comment written in Chinese turns into mojibake and then into a parse error.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Destination,

    # `_attic/` holds the decompiler tooling and the reference implementations
    # (~215MB). Nothing in the build reads it.
    [switch]$SkipAttic,

    # Print the plan without writing anything.
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$source = Split-Path -Parent $PSScriptRoot
$source = (Resolve-Path -LiteralPath $source).Path

# --- The destination must be empty or absent. Never overwrite an existing tree. --
$destFull = [System.IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $destFull) {
    $existing = @(Get-ChildItem -LiteralPath $destFull -Force)
    if ($existing.Count -gt 0) {
        throw "Destination is not empty: $destFull`nPick another path, or empty it yourself first."
    }
} elseif (-not $DryRun) {
    New-Item -ItemType Directory -Path $destFull -Force | Out-Null
}

# --- What does not travel: everything here is rebuildable or machine-specific. ---
# `gradle/` is deliberately NOT in this list: the wrapper jar and properties have to
# come along, or `gradlew.bat` cannot even start in the new directory.
$excludedDirs = @(
    'build',
    'app\build',
    '.gradle',
    '.kotlin',
    '.idea'
)
if ($SkipAttic) { $excludedDirs += '_attic' }

# `local.properties` is also regenerated rather than copied verbatim; see below.
$excludedFiles = @('local.properties')

Write-Host "from: $source"
Write-Host "to:   $destFull"
Write-Host ""

# --- Copy -----------------------------------------------------------------------
$copied = 0
$skipped = 0

Get-ChildItem -LiteralPath $source -Recurse -Force -File | ForEach-Object {
    $relative = $_.FullName.Substring($source.Length).TrimStart('\')

    foreach ($dir in $excludedDirs) {
        if ($relative -eq $dir -or $relative.StartsWith("$dir\", [StringComparison]::OrdinalIgnoreCase)) {
            $skipped++
            return
        }
    }
    if ($excludedFiles -contains $relative) {
        $skipped++
        return
    }

    $target = Join-Path $destFull $relative
    if (-not $DryRun) {
        $targetDir = Split-Path -Parent $target
        if (-not (Test-Path -LiteralPath $targetDir)) {
            New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        }
        Copy-Item -LiteralPath $_.FullName -Destination $target -Force
    }
    $copied++
}

# --- local.properties: copy it byte for byte ------------------------------------
# Not `Set-Content`/`Out-File`: both write a UTF-8 BOM (and Windows PowerShell 5.1
# writes UTF-16 for some of its encodings), and Gradle's properties parser reads
# `local.properties` as ISO-8859-1. A BOM then lands on the front of the first key,
# so `sdk.dir` silently becomes `\ufeffsdk.dir` and the reader of this script gets a
# "SDK location not found" error from a file that looks perfect in an editor.
# Get-Content below is only used to report what is in it; the file itself is copied
# as bytes.
$oldProps = Join-Path $source 'local.properties'
if (Test-Path -LiteralPath $oldProps) {
    if (-not $DryRun) {
        Copy-Item -LiteralPath $oldProps -Destination (Join-Path $destFull 'local.properties') -Force
    }
    $lines = Get-Content -LiteralPath $oldProps

    # `sdk.dir` is an absolute path to this machine's Android SDK. It stays valid when
    # only the project moves; on a different machine it has to be regenerated.
    $sdkLine = $lines | Where-Object { $_ -match '^\s*sdk\.dir\s*=' }
    $urlLine = $lines | Where-Object { $_ -match '^\s*huishuiBaseUrl\s*=' }

    Write-Host "local.properties copied as-is, containing:"
    Write-Host "  $sdkLine"
    Write-Host "  $urlLine"
    if (-not $sdkLine) {
        Write-Host "  [!] no sdk.dir in it -- Android Studio will add one on the next sync."
    }
    if (-not $urlLine) {
        Write-Host "  [!] no huishuiBaseUrl in it -- the app will fall back to https://example.invalid/"
    }
} else {
    Write-Host "[!] No local.properties in the source; the new directory needs one (README section 2.2)."
    Write-Host "    Without it BASE_URL falls back to https://example.invalid/ and every request fails."
}

Write-Host ""
Write-Host ("Copied {0} file(s), skipped {1} (build output / caches / IDE config)." -f $copied, $skipped)
if ($DryRun) { Write-Host "(DryRun: nothing was actually written.)" }

if ($SkipAttic) {
    Write-Host ""
    Write-Host "Note: _attic/ was not copied. It holds the decompiler tooling and the"
    Write-Host "reference implementations; nothing in the build reads it."
}
