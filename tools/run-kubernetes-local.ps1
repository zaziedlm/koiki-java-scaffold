#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Rancher Desktop only: prepare, bootstrap, start, forward, stop or inspect the local Kubernetes app.
.DESCRIPTION
    Uses a dedicated namespace, Secret, PostgreSQL PVC and image tag. Never calls Docker Compose.
    No delete/reset operation is provided. Run each action in PowerShell 7 at the repository root.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Prepare', 'CreateAdmin', 'Start', 'Forward', 'Stop', 'Status')]
    [string]$Action,
    [string]$LocalRepository,
    [string]$ConfigurationFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$clusterContext = 'rancher-desktop'
$namespace = 'koiki-scaffold-local'
$image = 'koiki-java-scaffold:k8s-local'
$stateDirectory = Join-Path $root '.koiki/kubernetes-local'
$secretFile = Join-Path $stateDirectory 'secrets.json'
$buildFile = Join-Path $stateDirectory 'build.json'
$jar = Join-Path $root 'target/koiki-java-scaffold.jar'
$overlay = Join-Path $root 'deploy/kubernetes/overlays/local'

function Invoke-Kube([string[]]$Arguments) {
    & kubectl --context $clusterContext --namespace $namespace @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'kubectl failed. Stop here and inspect the preceding error; no data was deleted.' }
}

function Assert-LocalCluster {
    $null = Get-Command kubectl -ErrorAction Stop
    $configuration = (Invoke-Kube -Arguments @('config', 'view', '--minify', '-o', 'json')) -join "`n" | ConvertFrom-Json
    $server = [uri]$configuration.clusters[0].cluster.server
    if (-not $server.IsLoopback) { throw 'This script requires a loopback API endpoint in the rancher-desktop context.' }
    $version = (Invoke-Kube -Arguments @('version', '-o', 'json')) -join "`n" | ConvertFrom-Json
    if ($version.serverVersion.gitVersion -notmatch '^v1\.37\.2(?:\+.*)?$') {
        throw 'This local verification targets Kubernetes v1.37.2. Check Rancher Desktop settings.'
    }
    Invoke-Kube -Arguments @('wait', '--for=condition=Ready', 'node', '--all', '--timeout=60s')
}

function Read-LocalSecrets {
    if (-not (Test-Path -LiteralPath $secretFile)) { throw 'Local Kubernetes secrets are missing. Run Prepare; do not regenerate secrets for an existing database.' }
    $values = Get-Content -LiteralPath $secretFile -Raw | ConvertFrom-Json -AsHashtable
    if ($values.Count -ne 3 -or $values['SCAFFOLD_DB_PASSWORD'] -notmatch '^[a-f0-9]{48}$' -or
        $values['KOIKI_SOURCE_HMAC_KEY_ID'] -ne 'k8s-local-1' -or
        $values['KOIKI_SOURCE_HMAC_KEY'] -notmatch '^[A-Za-z0-9+/]{43}=$') {
        throw 'Invalid local Kubernetes secret file. Values were not printed or changed.'
    }
    return $values
}

function Get-ClusterSecret {
    $json = (Invoke-Kube -Arguments @('get', 'secret', 'scaffold-runtime', '--ignore-not-found', '-o', 'json')) -join "`n"
    if ([string]::IsNullOrWhiteSpace($json)) { return $null }
    return ($json | ConvertFrom-Json)
}

function Assert-SecretMatches($Values, $Existing) {
    if ($null -eq $Existing) { throw 'The Kubernetes Secret is missing. Run Prepare.' }
    $expected = @{
        SCAFFOLD_DB_URL = 'jdbc:postgresql://scaffold-db:5432/scaffold'
        SCAFFOLD_DB_USERNAME = 'scaffold'
    }
    foreach ($key in $Values.Keys) { $expected[$key] = $Values[$key] }
    foreach ($key in $expected.Keys) {
        $entry = $Existing.data.PSObject.Properties[$key]
        if ($null -eq $entry -or [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($entry.Value)) -cne $expected[$key]) {
            throw 'Local secret file and cluster Secret differ. Do not rotate credentials or delete the database to bypass this check.'
        }
    }
}

function Set-UpSecrets {
    $existing = Get-ClusterSecret
    if (-not (Test-Path -LiteralPath $secretFile)) {
        # Refuse to silently replace credentials for a retained Kubernetes database.
        $claims = (Invoke-Kube -Arguments @('get', 'pvc', '-o', 'json')) -join "`n" | ConvertFrom-Json
        if ($null -ne $existing -or @($claims.items).Count -gt 0) {
            throw 'Cluster Secret or PVC already exists but the local secret file is missing. Recover the original file; nothing was replaced.'
        }
        $values = @{
            SCAFFOLD_DB_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
            KOIKI_SOURCE_HMAC_KEY_ID = 'k8s-local-1'
            KOIKI_SOURCE_HMAC_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        }
        $null = New-Item -ItemType Directory -Force -Path $stateDirectory
        $stream = [IO.FileStream]::new($secretFile, [IO.FileMode]::CreateNew)
        try {
            if (-not $IsWindows) { [IO.File]::SetUnixFileMode($secretFile, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite) }
            $bytes = [Text.Encoding]::UTF8.GetBytes(($values | ConvertTo-Json))
            $stream.Write($bytes)
        } finally { $stream.Dispose() }
        Write-Host 'Created separate, git-ignored Kubernetes secrets. Compose .env was not read or changed.'
    }
    $values = Read-LocalSecrets
    if ($null -ne $existing) {
        Assert-SecretMatches $values $existing
        return
    }
    $data = @{}
    foreach ($key in $values.Keys) { $data[$key] = $values[$key] }
    $data['SCAFFOLD_DB_URL'] = 'jdbc:postgresql://scaffold-db:5432/scaffold'
    $data['SCAFFOLD_DB_USERNAME'] = 'scaffold'
    # Use stdin, not command arguments, tracked files, or a last-applied annotation.
    $payload = @{
        apiVersion = 'v1'; kind = 'Secret'; type = 'Opaque'
        metadata = @{ name = 'scaffold-runtime'; namespace = $namespace }
        stringData = $data
    } | ConvertTo-Json -Depth 5 -Compress
    $payload | & kubectl --context $clusterContext --namespace $namespace create -f -
    if ($LASTEXITCODE -ne 0) { throw 'Secret creation failed. Keep the local secret file and retry Prepare after diagnosis.' }
}

function Assert-Prepared {
    if (-not (Test-Path -LiteralPath $buildFile) -or -not (Test-Path -LiteralPath $jar)) { throw 'Run Kubernetes Prepare first.' }
    $record = Get-Content -LiteralPath $buildFile -Raw | ConvertFrom-Json
    if ($record.jarSha256 -ne (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -or
        $record.dockerfileSha256 -ne (Get-FileHash -LiteralPath (Join-Path $root 'Dockerfile') -Algorithm SHA256).Hash) {
        throw 'JAR or Dockerfile changed since Kubernetes Prepare. Stop the app, then run Prepare again.'
    }
    $actualImage = & docker image inspect --format '{{.Id}}' $image
    if ($LASTEXITCODE -ne 0 -or $actualImage -ne $record.imageId) { throw 'The prepared Kubernetes image is missing or changed. Run Prepare again.' }
    Assert-SecretMatches (Read-LocalSecrets) (Get-ClusterSecret)
}

function Assert-FreePort([int]$Port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $listener.Start() }
    catch { throw "Local port $Port is unavailable. Identify its owner; do not stop unrelated services." }
    finally { $listener.Stop() }
}

function Assert-NoComposeOrJar {
    # Switching execution modes is explicit. Do not stop or modify another environment here.
    $running = & docker ps --filter 'label=com.docker.compose.project=koiki-java-scaffold' --format '{{.Names}}'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Docker containers. Check Rancher Desktop before switching modes.' }
    if ($running) { throw 'JAR/Compose and Kubernetes must not run together. Stop the JAR and Compose app/DB first; no containers were stopped by this script.' }
    Assert-FreePort 8080
}

function Register-Admin {
    Assert-Prepared
    Invoke-Kube -Arguments @('rollout', 'status', 'statefulset/scaffold-db', '--timeout=180s')
    Assert-FreePort 15432
    $email = Read-Host 'Initial Kubernetes administrator email (separate database from Compose)'
    $password = Read-Host 'Password (15-128 characters, at most 72 UTF-8 bytes)' -AsSecureString
    $confirmation = Read-Host 'Confirm password' -AsSecureString
    $one = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($password)
    $two = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($confirmation)
    $forward = [Diagnostics.Process]::new()
    $java = [Diagnostics.Process]::new()
    $forwardStarted = $false
    $javaStarted = $false
    try {
        if ([Runtime.InteropServices.Marshal]::PtrToStringUni($one) -cne [Runtime.InteropServices.Marshal]::PtrToStringUni($two)) {
            throw 'Passwords do not match.'
        }
        $forward.StartInfo = [Diagnostics.ProcessStartInfo]::new('kubectl')
        $forward.StartInfo.UseShellExecute = $false
        $forward.StartInfo.CreateNoWindow = $true
        $forward.StartInfo.RedirectStandardOutput = $true
        $forward.StartInfo.RedirectStandardError = $true
        foreach ($argument in @('--context', $clusterContext, '--namespace', $namespace, 'port-forward',
            '--address=127.0.0.1', 'service/scaffold-db', '15432:5432', '--pod-running-timeout=30s')) {
            $forward.StartInfo.ArgumentList.Add($argument)
        }
        $null = $forward.Start()
        $forwardStarted = $true
        $errors = $forward.StandardError.ReadToEndAsync()
        $line = $forward.StandardOutput.ReadLineAsync()
        if (-not $line.Wait(30000) -or $line.Result -notmatch '^Forwarding from 127\.0\.0\.1:15432 ->' -or $forward.HasExited) {
            throw 'Could not establish the owned Kubernetes DB port-forward. Registration was not started.'
        }
        # Drain subsequent forwarding messages without writing them to a file.
        $forwardOutput = $forward.StandardOutput.ReadToEndAsync()
        $values = Read-LocalSecrets
        $java.StartInfo = [Diagnostics.ProcessStartInfo]::new('java')
        $java.StartInfo.UseShellExecute = $false
        $java.StartInfo.CreateNoWindow = $true
        $java.StartInfo.RedirectStandardInput = $true
        $java.StartInfo.RedirectStandardOutput = $true
        $java.StartInfo.RedirectStandardError = $true
        $java.StartInfo.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
        $java.StartInfo.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
        $java.StartInfo.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
        foreach ($key in $values.Keys) { $java.StartInfo.Environment[$key] = $values[$key] }
        $java.StartInfo.Environment['SPRING_DATASOURCE_PASSWORD'] = $values['SCAFFOLD_DB_PASSWORD']
        foreach ($argument in @('-jar', $jar, '--spring.main.web-application-type=none',
            '--spring.profiles.active=local-bootstrap', '--scaffold.bootstrap-admin.enabled=true',
            '--spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/scaffold', '--spring.datasource.username=scaffold')) {
            $java.StartInfo.ArgumentList.Add($argument)
        }
        $null = $java.Start()
        $javaStarted = $true
        # Explicitly capture both streams: do not depend on inherited Windows console handles.
        # Start both reads before waiting so neither output pipe can block the child.
        $javaOutput = $java.StandardOutput.ReadToEndAsync()
        $javaErrors = $java.StandardError.ReadToEndAsync()
        $java.StandardInput.WriteLine($email)
        $java.StandardInput.WriteLine([Runtime.InteropServices.Marshal]::PtrToStringUni($one))
        $java.StandardInput.Close()
        $java.WaitForExit()
        $outputText = $javaOutput.GetAwaiter().GetResult()
        $errorText = $javaErrors.GetAwaiter().GetResult()
        if (-not [string]::IsNullOrWhiteSpace($outputText)) { Write-Host $outputText.TrimEnd() }
        if (-not [string]::IsNullOrWhiteSpace($errorText)) { Write-Host $errorText.TrimEnd() }
        if ($java.ExitCode -ne 0) { throw 'Registration failed. Existing users/passwords were not replaced; inspect the Java error.' }
        if ($outputText -notmatch 'Initial local administrator created\.') {
            throw 'Java exited without the expected registration confirmation. Inspect DB state before retrying; do not assume registration succeeded.'
        }
        Write-Host 'Kubernetes administrator registration confirmed. Next: -Action Start.'
    } finally {
        if ($javaStarted -and -not $java.HasExited) { $java.Kill($true); $java.WaitForExit() }
        if ($forwardStarted -and -not $forward.HasExited) { $forward.Kill($true); $forward.WaitForExit() }
        $forward.Dispose()
        $java.Dispose()
        [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($one)
        [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($two)
        $password.Dispose()
        $confirmation.Dispose()
    }
}

Push-Location $root
try {
    Assert-LocalCluster
    if ($Action -notin @('Stop', 'Status')) { Assert-NoComposeOrJar }
    switch ($Action) {
        'Prepare' {
            $deployment = (Invoke-Kube -Arguments @('get', 'deployment', 'scaffold', '--ignore-not-found', '-o', 'json')) -join "`n"
            if ($deployment -and ($deployment | ConvertFrom-Json).spec.replicas -gt 0) {
                throw 'Stop the Kubernetes app before Prepare. The running app was not changed.'
            }
            $prepareArguments = @{}
            if ($LocalRepository) { $prepareArguments.LocalRepository = [IO.Path]::GetFullPath($LocalRepository) }
            if ($ConfigurationFile) { $prepareArguments.ConfigurationFile = [IO.Path]::GetFullPath($ConfigurationFile) }
            & (Join-Path $PSScriptRoot 'prepare-koiki-distribution.ps1') @prepareArguments
            $maven = if ($IsWindows) { './mvnw.cmd' } else { './mvnw' }
            $buildArguments = @('--batch-mode', '--no-transfer-progress')
            if ($LocalRepository) { $buildArguments += "-Dmaven.repo.local=$([IO.Path]::GetFullPath($LocalRepository))" }
            & $maven @buildArguments verify
            if ($LASTEXITCODE -ne 0) { throw 'Maven verify failed. Kubernetes resources were not applied.' }
            & docker build --tag $image .
            if ($LASTEXITCODE -ne 0) { throw 'Docker build failed. Kubernetes resources were not applied.' }
            $imageId = & docker image inspect --format '{{.Id}}' $image
            if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the built image.' }
            Invoke-Kube -Arguments @('apply', '-f', (Join-Path $overlay 'namespace.yaml'))
            Set-UpSecrets
            Invoke-Kube -Arguments @('apply', '-k', $overlay)
            Invoke-Kube -Arguments @('rollout', 'status', 'statefulset/scaffold-db', '--timeout=180s')
            @{
                jarSha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
                dockerfileSha256 = (Get-FileHash -LiteralPath (Join-Path $root 'Dockerfile') -Algorithm SHA256).Hash
                imageId = $imageId
            } | ConvertTo-Json | Set-Content -LiteralPath $buildFile -Encoding utf8
            Write-Host 'Kubernetes Prepare complete. First use: CreateAdmin, then Start, then Forward.'
        }
        'CreateAdmin' { Register-Admin }
        'Start' {
            Assert-Prepared
            Invoke-Kube -Arguments @('scale', 'statefulset/scaffold-db', '--replicas=1')
            Invoke-Kube -Arguments @('rollout', 'status', 'statefulset/scaffold-db', '--timeout=180s')
            Invoke-Kube -Arguments @('scale', 'deployment/scaffold', '--replicas=1')
            Invoke-Kube -Arguments @('rollout', 'status', 'deployment/scaffold', '--timeout=360s')
            Write-Host 'Kubernetes app ready. Run -Action Forward for http://127.0.0.1:18080/.'
        }
        'Forward' {
            Assert-FreePort 18080
            Write-Host 'Keep this terminal open: http://127.0.0.1:18080/ . Ctrl+C stops forwarding only.'
            Invoke-Kube -Arguments @('port-forward', '--address=127.0.0.1', 'service/scaffold', '18080:8080')
        }
        'Stop' {
            Invoke-Kube -Arguments @('scale', 'deployment/scaffold', '--replicas=0')
            Invoke-Kube -Arguments @('rollout', 'status', 'deployment/scaffold', '--timeout=90s')
            Invoke-Kube -Arguments @('scale', 'statefulset/scaffold-db', '--replicas=0')
            Invoke-Kube -Arguments @('rollout', 'status', 'statefulset/scaffold-db', '--timeout=90s')
            Write-Host 'Kubernetes app and DB stopped. PVC and Secret are retained. Close any Forward terminal with Ctrl+C.'
        }
        'Status' { Invoke-Kube -Arguments @('get', 'deployment,statefulset,pod,service,pvc') }
    }
} finally { Pop-Location }
