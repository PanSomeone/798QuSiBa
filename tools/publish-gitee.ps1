# Publish the current build to the Gitee shelf (the domestic mirror).
#
# The repo there holds APKs and nothing else: source and history live on GitHub.
# Every release is published to BOTH shelves, because the app's update check reads
# Gitee first and its download URL is derived from the tag -- a release that only
# exists on GitHub is a version the mirror cannot hand out.
#
# Three things this had to learn the hard way:
#   1. the repo came back PRIVATE (assets on a private repo need a login, which
#      defeats the point), so it is patched to public;
#   2. `releases/tags/<tag>` answers HTTP 200 with a literal `null` when the tag is
#      missing, so the release is found by listing and matching `tag_name`;
#   3. a URL built as `'https://...?access_token=' + $token` passes the token as a
#      SECOND argument to curl.exe, which then tries to resolve it as a host -- put
#      the whole URL in a variable before handing it to curl.
#
# Usage:  $env:GITEE_TOKEN = '<token>';  .\tools\publish-gitee.ps1 -Tag v1.3.7
param(
    [string]$Tag = 'v1.3.7',
    [string]$BodyFile = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$token = $env:GITEE_TOKEN
if (-not $token -or $token.Length -lt 10) { throw 'GITEE_TOKEN is not set' }
$api = 'https://gitee.com/api/v5'
$owner = 'pansomeone'
$repoName = '798QuSiBa'
$version = $Tag -replace '^v', ''
$releaseName = "798QuSiBa $Tag"

if (-not $BodyFile) { $BodyFile = Join-Path $repo 'shots\release.md' }
if (-not (Test-Path $BodyFile)) { throw "missing release notes: $BodyFile" }
$body = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($BodyFile))

function Gitee {
    param([string]$Method, [string]$Path, $Query, $Json)
    $url = "$api$Path"
    if ($Query) {
        $pairs = @()
        foreach ($k in $Query.Keys) { $pairs += ('{0}={1}' -f $k, [Uri]::EscapeDataString([string]$Query[$k])) }
        $url = $url + '?' + ($pairs -join '&')
    }
    $headers = @{ Authorization = "token $token" }
    if ($null -ne $Json) {
        $headers['Content-Type'] = 'application/json; charset=utf-8'
        return Invoke-RestMethod -Method $Method -Uri $url -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($Json))
    }
    return Invoke-RestMethod -Method $Method -Uri $url -Headers $headers
}

# 1. public, or the download link needs a login
$info = Gitee Get "/repos/$owner/$repoName" @{ access_token = $token }
if ($info.private) {
    # Gitee's PATCH wants `name` even when it is not changing it.
    $patched = Gitee Patch "/repos/$owner/$repoName" @{ access_token = $token } `
        (@{ name = $repoName; private = $false; has_issues = $false; has_wiki = $false } | ConvertTo-Json)
    Write-Host ("repo made public: private={0} full_name={1}" -f $patched.private, $patched.full_name)
} else {
    Write-Host ("repo already public: {0}" -f $info.full_name)
}

# 2. the release, found by tag_name in the list (never `releases/tags/<tag>`)
$release = $null
$list = Gitee Get "/repos/$owner/$repoName/releases" @{ access_token = $token; per_page = 50 }
if ($list) { $release = $list | Where-Object { $_.tag_name -eq $Tag } | Select-Object -First 1 }
if ($release -and $release.id) {
    Write-Host ("release exists: id {0}" -f $release.id)
    $payload = @{ name = $releaseName; body = $body; prerelease = $true } | ConvertTo-Json -Depth 3
    $release = Gitee Patch "/repos/$owner/$repoName/releases/$($release.id)" @{ access_token = $token } $payload
} else {
    $payload = @{
        tag_name         = $Tag
        name             = $releaseName
        body             = $body
        target_commitish = 'master'
        prerelease       = $true
    } | ConvertTo-Json -Depth 3
    $release = Gitee Post "/repos/$owner/$repoName/releases" @{ access_token = $token } $payload
    Write-Host ("release created: id {0} tag {1}" -f $release.id, $release.tag_name)
}
if (-not $release.id) { throw 'release id is still empty' }

# 3. attachments
$files = @(
    @{ path = 'app\build\outputs\apk\benchmark\app-benchmark.apk'; name = "huishui-$version-fast.apk" },
    @{ path = 'app\build\outputs\apk\release\app-release-unsigned.apk'; name = "huishui-$version-release-unsigned.apk" }
)
foreach ($f in $files) {
    $src = Join-Path $repo $f.path
    if (-not (Test-Path $src)) { throw "missing $($f.path)" }
    $copy = Join-Path $env:TEMP $f.name
    Copy-Item $src $copy -Force
    $uploadUrl = "https://gitee.com/api/v5/repos/$owner/$repoName/releases/$($release.id)/attach_files?access_token=$token"
    $resp = curl.exe -sS -X POST -H "Authorization: token $token" -F "file=@$copy" $uploadUrl
    Write-Host ("upload {0}: {1}" -f $f.name, $resp.Substring(0, [Math]::Min(200, $resp.Length)))
    Remove-Item $copy -Force
}

# 4. anonymous download, byte compare -- the only proof that a stranger can install it
$after = Gitee Get "/repos/$owner/$repoName/releases/$($release.id)" @{ access_token = $token }
Write-Host ("assets on the release: {0}" -f $after.assets.Count)
$verified = 0
foreach ($asset in $after.assets) {
    $match = $files | Where-Object { $_.name -eq $asset.name } | Select-Object -First 1
    if (-not $match) { continue }
    $tmp = Join-Path $env:TEMP ('gitee-dl-' + $asset.name)
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
Write-Host ("GITEE OK: {0} assets verified for {1}" -f $verified, $Tag) -ForegroundColor Green