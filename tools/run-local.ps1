#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Prepare a local PostgreSQL + application JAR, create the first administrator, or start the app.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Prepare', 'CreateAdmin', 'Start', 'StartContainer', 'StopContainer')][string]$Action,
    [string]$Email,
    [string]$LocalRepository,
    [string]$ConfigurationFile,
    [Security.SecureString]$Password
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $root
try {
    $envFile = Join-Path $root '.env'
    if (-not (Test-Path -LiteralPath $envFile)) {
        if ($Action -ne 'Prepare') { throw 'Run -Action Prepare first.' }
        $dbPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
        $hmac = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $content = "SCAFFOLD_DB_PASSWORD=$dbPassword`nKOIKI_SOURCE_HMAC_KEY_ID=local-dev-1`nKOIKI_SOURCE_HMAC_KEY=$hmac`n"
        # CreateNew refuses to overwrite a file created concurrently.
        $file = [IO.FileStream]::new($envFile, [IO.FileMode]::CreateNew)
        try {
            if (-not $IsWindows) { [IO.File]::SetUnixFileMode($envFile, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite) }
            $bytes = [Text.Encoding]::UTF8.GetBytes($content)
            $file.Write($bytes)
        } finally { $file.Dispose() }
        Write-Host 'Created git-ignored .env with random local database and HMAC secrets.'
    }
    # Read data only; never execute .env as shell/PowerShell code.
    $allowed = @('SCAFFOLD_DB_PASSWORD', 'KOIKI_SOURCE_HMAC_KEY_ID', 'KOIKI_SOURCE_HMAC_KEY',
                 'SCAFFOLD_DB_URL', 'SCAFFOLD_DB_USERNAME')
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*(#.*)?$') { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'Unsupported .env syntax. Use KEY=value.' }
        $name = $Matches[1]; $value = $Matches[2].Trim()
        if ($name -notin $allowed) { throw "Unsupported .env key: $name" }
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    foreach ($name in @('SCAFFOLD_DB_PASSWORD', 'KOIKI_SOURCE_HMAC_KEY_ID', 'KOIKI_SOURCE_HMAC_KEY')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { throw "Set $name in .env." }
    }
    if ($Action -eq 'Prepare') {
        & docker compose up -d --wait db
        if ($LASTEXITCODE -ne 0) { throw 'Local PostgreSQL startup failed.' }
        $prepareArguments = @{}
        if ($ConfigurationFile) { $prepareArguments.ConfigurationFile = [IO.Path]::GetFullPath($ConfigurationFile) }
        if ($LocalRepository) { $prepareArguments.LocalRepository = [IO.Path]::GetFullPath($LocalRepository) }
        & (Join-Path $PSScriptRoot 'prepare-koiki-distribution.ps1') @prepareArguments
        $maven = if ($IsWindows) { './mvnw.cmd' } else { './mvnw' }
        $buildArguments = @('--batch-mode', '--no-transfer-progress')
        if ($LocalRepository) { $buildArguments += "-Dmaven.repo.local=$([IO.Path]::GetFullPath($LocalRepository))" }
        & $maven @buildArguments verify
        if ($LASTEXITCODE -ne 0) { throw 'Application verification failed.' }
        Write-Host 'Ready. Next: pwsh ./tools/run-local.ps1 -Action CreateAdmin'
        return
    }
    if ($Action -eq 'StopContainer') {
        & docker compose --profile app stop app
        if ($LASTEXITCODE -ne 0) { throw 'Application container stop failed.' }
        Write-Host 'Application container stopped. PostgreSQL and its data are retained.'
        return
    }
    $jar = Join-Path $root 'target/koiki-java-scaffold.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw 'Run -Action Prepare to build the application JAR.' }
    if ($Action -eq 'StartContainer') {
        $runningApp = & docker compose --profile app ps --status running -q app
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the application container.' }
        if (-not $runningApp) {
            $portInUse = $false
            $probe = [Net.Sockets.TcpClient]::new()
            try { $portInUse = $probe.ConnectAsync('127.0.0.1', 8080).Wait(1000) -and $probe.Connected }
            catch { $portInUse = $false }
            finally { $probe.Dispose() }
            if ($portInUse) { throw 'Port 8080 is already in use. Stop the JAR application or identify the existing service first.' }
        }
        & docker compose --profile app build app
        if ($LASTEXITCODE -ne 0) { throw 'Application container build failed.' }
        & docker compose --profile app up -d --wait app
        if ($LASTEXITCODE -ne 0) { throw 'Application container startup failed. Check docker compose logs app.' }
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        $ready = $false
        do {
            try {
                $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/login' -TimeoutSec 3 -NoProxy
                $ready = $response.StatusCode -eq 200
            } catch { $ready = $false }
            if (-not $ready) { Start-Sleep -Seconds 1 }
        } while (-not $ready -and [DateTime]::UtcNow -lt $deadline)
        if (-not $ready) { throw 'Login page readiness timed out. Check docker compose logs app; containers have not been deleted.' }
        Write-Host 'Application container ready: http://127.0.0.1:8080/ (use -Action StopContainer to stop the app).'
        return
    }
    if ($Action -eq 'Start') {
        $runningApp = & docker compose --profile app ps --status running -q app
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the application container.' }
        if ($runningApp) { throw 'The application container is running. Use -Action StopContainer before starting the JAR.' }
        Write-Host 'Local HTTP: http://127.0.0.1:8080/ (Ctrl+C stops Java; PostgreSQL remains running).'
        & java -jar $jar --server.address=127.0.0.1 --server.servlet.session.cookie.secure=false
        if ($LASTEXITCODE -ne 0) { throw 'Application exited with an error.' }
        return
    }
    if (-not $Email) { $Email = Read-Host 'Initial administrator email' }
    if (-not $Password) {
        $Password = Read-Host 'Initial administrator password (15-128 characters, at most 72 UTF-8 bytes)' -AsSecureString
        $confirmation = Read-Host 'Confirm password' -AsSecureString
        $one = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($Password)
        $two = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($confirmation)
        try {
            if ([Runtime.InteropServices.Marshal]::PtrToStringUni($one) -cne
                [Runtime.InteropServices.Marshal]::PtrToStringUni($two)) { throw 'Passwords do not match.' }
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($one)
            [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($two)
            $confirmation.Dispose()
        }
    }
    $info = [Diagnostics.ProcessStartInfo]::new('java')
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($argument in @('-jar', $jar, '--spring.main.web-application-type=none',
            '--spring.profiles.active=local-bootstrap', '--scaffold.bootstrap-admin.enabled=true')) {
        $info.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($Password)
    try {
        $null = $process.Start()
        $process.StandardInput.WriteLine($Email)
        $process.StandardInput.WriteLine([Runtime.InteropServices.Marshal]::PtrToStringUni($pointer))
        $process.StandardInput.Close()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'Initial registration failed. Existing users/passwords were not replaced.' }
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer)
        $process.Dispose()
    }
} finally { Pop-Location }
