# Fails when R8 has deleted part of the ML Kit barcode scanner again.
#
#     powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-scan-keeps.ps1
#
# Run it from the project root, after a build that produced
# app\build\outputs\mapping\<variant>\mapping.txt.
#
# Why this exists. ML Kit assembles its scanner at runtime from factories that a
# small component framework looks up by name, so R8 cannot see which of its
# classes are reachable and deletes them. Nothing fails at build time and nothing
# fails in a JVM test: the app throws on the first camera frame, in a **minified
# build only**. That is how `benchmark` shipped with a scanner that closed itself
# 446ms after opening --
#
#     Scan  opened (permission=true)
#     Scan  error NullPointerException: Attempt to read from field 'xw3 zr3.a'
#         on a null object reference in method 'mu3 i54.b(xk)'
#
# -- and `mapping.txt` is the artefact that shows it. Every minified build writes
# one, so this reads that rather than waiting for a phone to prove it.
#
# ASCII only, and UTF-8 read explicitly, for the reason documented at the top of
# tools\verify-v1.2.ps1: this machine decodes a BOM-less .ps1 with codepage 936.

$ErrorActionPreference = 'Continue'
$fail = 0

function Check($name, $ok, $detail) {
    if ($ok) { Write-Host "  PASS  $name" -ForegroundColor Green }
    else { Write-Host "  FAIL  $name  -- $detail" -ForegroundColor Red; $script:fail++ }
}

# Everything the scanner is built out of, plus the GMS internals it delegates to.
$removedPattern = '^(com\.google\.mlkit\.|com\.google\.android\.gms\.internal\.mlkit_).*R8\$\$REMOVED\$\$CLASS\$\$'

$inspected = 0
foreach ($variant in @('benchmark', 'release')) {
    $mapping = "app\build\outputs\mapping\$variant\mapping.txt"
    if (-not (Test-Path $mapping)) {
        Check "$variant has a mapping.txt" $false "build :app:assemble$variant first"
        continue
    }
    $inspected++

    $removed = @(Select-String -Path $mapping -Pattern $removedPattern)
    $sample = ($removed | Select-Object -First 3 | ForEach-Object { ($_.Line -split ' -> ')[0] }) -join ', '
    Check "$variant deleted no ML Kit class" ($removed.Count -eq 0) "$($removed.Count) deleted: $sample"

    $kept = @(Select-String -Path $mapping -Pattern '^com\.google\.mlkit\.')
    Check "$variant kept the scanner's own names" ($kept.Count -gt 0) `
        'nothing under com.google.mlkit survived; the keep rules in app\proguard-rules.pro are gone or wrong'
}

if ($inspected -eq 0) { Check 'a mapping.txt was inspected' $false 'no minified build output to check' }

Write-Host ""
if ($fail -eq 0) { Write-Host "the scanner's classes survived R8" -ForegroundColor Green }
else { Write-Host "$fail check(s) failed" -ForegroundColor Red }
exit $fail
