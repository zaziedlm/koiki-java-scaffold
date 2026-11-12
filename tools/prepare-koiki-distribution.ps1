#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Downloads the pinned KOIKI Framework release ZIP, verifies it and installs it into the
    local Maven repository.

.DESCRIPTION
    Method 1 (default): downloads from the public GitHub Release without authentication.
    Method 2 (-Source GitHubCli): downloads with an authenticated GitHub CLI, e.g. from a
    private repository on GitHub Enterprise (set GH_HOST, log in with `gh auth login`;
    in CI provide GH_TOKEN). Override the repository with -Repository when it differs.

    The ZIP SHA-256 and the reviewed tools/prepare-koiki.ps1 are both pinned in
    tools/koiki-distribution.properties. Registration is delegated to the reviewed
    prepare-koiki.ps1 (-LocalArchive), which verifies every payload before installing it.
#>
[CmdletBinding()]
param(
    [ValidateSet('PublicRelease', 'GitHubCli')][string]$Source = 'PublicRelease',
    [ValidatePattern('^[\w.-]+/[\w.-]+$')][string]$Repository,
    [string]$LocalRepository,
    [string]$CacheDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$settings = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $PSScriptRoot 'koiki-distribution.properties')) {
    if ($line -match '^\s*([\w.-]+)\s*=\s*(.*?)\s*$') { $settings[$Matches[1]] = $Matches[2] }
}
$version = $settings['koiki.version']
$archiveSha256 = $settings['koiki.archive.sha256']
if (-not $Repository) { $Repository = $settings['koiki.distribution.repository'] }

$pomVersion = ([xml](Get-Content -LiteralPath (Join-Path $root 'pom.xml') -Raw)).project.parent.version
if ($pomVersion -ne $version) {
    throw "pom.xml koiki-parent version '$pomVersion' differs from koiki.version '$version'."
}

$prepareScript = Join-Path $PSScriptRoot 'prepare-koiki.ps1'
if ((Get-Hash $prepareScript) -ne $settings['koiki.prepare-script.sha256']) {
    throw 'tools/prepare-koiki.ps1 differs from the reviewed SHA-256. Do not edit the reviewed copy.'
}

if (-not $CacheDirectory) { $CacheDirectory = Join-Path $root '.koiki/downloads' }
$null = New-Item -ItemType Directory -Force -Path $CacheDirectory
$asset = "koiki-framework-$version.zip"
$archive = Join-Path $CacheDirectory $asset

if ((Test-Path -LiteralPath $archive) -and (Get-Hash $archive) -ne $archiveSha256) {
    Write-Warning "Cached $asset does not match the pinned SHA-256; downloading again."
    Remove-Item -LiteralPath $archive
}
if (-not (Test-Path -LiteralPath $archive)) {
    $partial = "$archive.partial"
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial }
    if ($Source -eq 'PublicRelease') {
        $url = "$($settings['koiki.distribution.base-url'])/$Repository/releases/download/framework-$version/$asset"
        Write-Host "Downloading $url"
        Invoke-WebRequest -Uri $url -OutFile $partial -UseBasicParsing
    } else {
        $null = Get-Command gh -ErrorAction Stop
        $downloadDirectory = Join-Path $CacheDirectory ('gh-' + [guid]::NewGuid().ToString('N'))
        try {
            & gh release download "framework-$version" --repo $Repository --pattern $asset --dir $downloadDirectory
            if ($LASTEXITCODE -ne 0) { throw 'gh release download failed.' }
            Move-Item -LiteralPath (Join-Path $downloadDirectory $asset) -Destination $partial
        } finally {
            if (Test-Path -LiteralPath $downloadDirectory) { Remove-Item -LiteralPath $downloadDirectory -Recurse -Force }
        }
    }
    if ((Get-Hash $partial) -ne $archiveSha256) {
        Remove-Item -LiteralPath $partial
        throw "Downloaded $asset does not match the pinned SHA-256; nothing was installed."
    }
    Move-Item -LiteralPath $partial -Destination $archive
}

# The reviewed script copy must be the one shipped with this distribution.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    $entry = $zip.GetEntry("koiki-framework-$version/prepare-koiki.ps1")
    if ($null -eq $entry) { throw 'The distribution does not contain prepare-koiki.ps1.' }
    $sha = [Security.Cryptography.SHA256]::Create()
    $stream = $entry.Open()
    try { $bundled = ([BitConverter]::ToString($sha.ComputeHash($stream)) -replace '-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $sha.Dispose() }
} finally { $zip.Dispose() }
if ($bundled -ne $settings['koiki.prepare-script.sha256']) {
    throw 'The prepare-koiki.ps1 bundled in the distribution differs from the reviewed copy.'
}

$maven = if ($IsWindows) { Join-Path $root 'mvnw.cmd' } else { Join-Path $root 'mvnw' }
$arguments = @{
    Version        = $version
    ExpectedSha256 = $archiveSha256
    LocalArchive   = $archive
    Maven          = $maven
}
if ($LocalRepository) { $arguments.LocalRepository = $LocalRepository }
& $prepareScript @arguments
