# Publish the current build to the GitHub shelf.
#
# Every release goes to both shelves (this one and Gitee). The app checks Gitee
# first and only falls back here, so a half-published release is worse than none:
# the mirror would answer 404 and the update check would point people back at a
# 5MB download from objects.githubusercontent.com.
#
# Usage:  .\tools\publish-github.ps1 -Tag v1.3.7
#
# Notes this had to learn:
#   * the token comes from `git credential fill` (there is no gh CLI here) and is
#     never printed;
#   * asset names must be ASCII -- GitHub silently strips non-ASCII from `name`, so
#     the Chinese description travels in the separate `label` parameter;
#   * downloading an asset back needs Accept: application/octet-stream; with
#     application/vnd.github+json the API answers with asset metadata instead.
param(
    [string]$Tag = 'v1.3.7',
    [string]$BodyFile = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$owner = 'PanSomeone'
$repoName = '798QuSiBa'
$version = $Tag -replace '^v', ''
$releaseName = "798QuSiBa $Tag"
$api = "https://api.github.com/repos/$owner/$repoName"

if (-not $BodyFile) { $BodyFile = Join-Path $repo 'shots\release.md' }
if (-not (Test-Path $BodyFile)) { throw "missing release notes: $BodyFile" }
$body = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($BodyFile))

# The token never touches this file or the console.
$cred = "protocol=https`nhost=github.com`n`n" | git -c credential.helper=manager credential fill 2>$null
$token = ($cred | Where-Object { $_ -like 'password=*' }) -replace '^password=', ''
if (-not $token -or $token.Length -lt 20) { throw 'no github token from git credential fill' }

function Api {
    param([string]$Method, [string]$Url, $Json)
    $headers = @{
        Authorization          = "token $token"
        Accept                 = 'application/vnd.github+json'
        'User-Agent'           = '798QuSiBa-publish'
        'X-GitHub-Api-Version' = '2022-11-28'
    }
    if ($null -eq $Json) {
        return Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers
    }
    $headers['Content-Type'] = 'application/json; charset=utf-8'
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($Json))
}

# 1. release, by tag (404 here, unlike Gitee's `null`)
$release = $null
try {
    $release = Api Get "$api/releases/tags/$Tag"
    Write-Host ("release exists: id {0}" -f $release.id)
    $patch = @{ name = $releaseName; body = $body; prerelease = $true } | ConvertTo-Json
    $release = Api Patch "$api/releases/$($release.id)" $patch
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 404) { throw }
    $payload = @{ tag_name = $Tag; name = $releaseName; body = $body; prerelease = $true; draft = $false } | ConvertTo-Json
    $release = Api Post "$api/releases" $payload
    Write-Host ("release created: id {0} tag {1}" -f $release.id, $release.tag_name)
}

# 2. assets
$label = [regex]::Unescape('\u514d\u5b89\u88c5\u4f53\u9a8c\u7248')      # free-install build
$files = @(
    @{ path = 'app\build\outputs\apk\benchmark\app-benchmark.apk'; name = "huishui-$version-fast.apk"; label = $label },
    @{ path = 'app\build\outputs\apk\release\app-release-unsigned.apk'; name = "huishui-$version-release-unsigned.apk"; label = $label }
)

$existing = Api Get "$api/releases/$($release.id)/assets?per_page=100"
foreach ($f in $files) {
    $src = Join-Path $repo $f.path
    if (-not (Test-Path $src)) { throw "missing $($f.path)" }
    $old = $existing | Where-Object { $_.name -eq $f.name } | Select-Object -First 1
    if ($old) {
        Api Delete "$api/releases/assets/$($old.id)" | Out-Null
        Write-Host ("replaced the old {0}" -f $f.name)
    }
    $size = (Get-Item $src).Length
    $uploadUrl = "https://uploads.github.com/repos/$owner/$repoName/releases/$($release.id)/assets?name=$($f.name)&label=$([Uri]::EscapeDataString($f.label))"
    $headers = @{
        Authorization        = "token $token"
        Accept               = 'application/vnd.github+json'
        'User-Agent'         = '798QuSiBa-publish'
        'Content-Type'       = 'application/vnd.android.package-archive'
        'Content-Length'     = "$size"
    }
    $resp = Invoke-RestMethod -Method Post -Uri $uploadUrl -Headers $headers -InFile $src -TimeoutSec 900
    Write-Host ("uploaded {0} state={1} {2} B" -f $resp.name, $resp.state, $resp.size)
}

# 3. anonymous download, byte compare
$after = Api Get "$api/releases/$($release.id)/assets?per_page=100"
$verified = 0
foreach ($asset in $after) {
    $match = $files | Where-Object { $_.name -eq $asset.name } | Select-Object -First 1
    if (-not $match) { continue }
    $tmp = Join-Path $env:TEMP ('gh-dl-' + $asset.name)
    $code = curl.exe -sS -L --max-time 300 -o $tmp -w '%{http_code}' $asset.browser_download_url
    $size = if (Test-Path $tmp) { (Get-Item $tmp).Length } else { 0 }
    $localPath = Join-Path $repo $match.path
    $localSha = (Get-FileHash $localPath -Algorithm SHA256).Hash
    $localSize = (Get-Item $localPath).Length
    $sha = if ($size -gt 0) { (Get-FileHash $tmp -Algorithm SHA256).Hash } else { '' }
    $same = ($sha -eq $localSha) -and ($size -eq $localSize)
    Write-Host ("{0} http={1} {2} B identical={3}" -f $asset.name, $code, $size, $same)
    if ($same) { $verified++ }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
}
if ($verified -ne $files.Count) { throw ("only {0} of {1} assets downloaded back identical" -f $verified, $files.Count) }
Write-Host ''
Write-Host ("GITHUB OK: {0} assets verified for {1}" -f $verified, $Tag) -ForegroundColor Green