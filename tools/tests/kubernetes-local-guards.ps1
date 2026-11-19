#!/usr/bin/env pwsh
<# Offline guard checks. Imports function definitions only; never runs kubectl, Docker or Java. #>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '../run-kubernetes-local.ps1'
$tokens = $null
$parseErrors = $null
$tree = [System.Management.Automation.Language.Parser]::ParseFile(
    [IO.Path]::GetFullPath($source), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) { throw 'PowerShell parse errors in the Kubernetes script.' }
foreach ($definition in $tree.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $false)) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
$clusterContext = 'rancher-desktop'
$namespace = 'koiki-scaffold-local'
$script:server = 'https://127.0.0.1:6443'
$script:version = 'v1.37.2+k3s1'
$script:clusterFailure = $false
$script:dockerFailure = $false
$script:runningCompose = $false
$script:checkedPort = 0
$script:passed = 0

function kubectl {
    $global:LASTEXITCODE = 0
    if ($args[0] -ne '--context' -or $args[1] -ne 'rancher-desktop' -or
        $args[2] -ne '--namespace' -or $args[3] -ne 'koiki-scaffold-local') {
        throw 'A kubectl call lost the explicit context or namespace.'
    }
    if ($script:clusterFailure) { $global:LASTEXITCODE = 1; return }
    switch ($args[4]) {
        'config' { return (@{ clusters = @(@{ cluster = @{ server = $script:server } }) } | ConvertTo-Json -Depth 5) }
        'version' { return (@{ serverVersion = @{ gitVersion = $script:version } } | ConvertTo-Json) }
        'wait' { return }
        default { throw 'Unexpected kubectl call in offline tests.' }
    }
}
function docker {
    $global:LASTEXITCODE = if ($script:dockerFailure) { 1 } else { 0 }
    if ($args[0] -ne 'ps' -or $args[2] -ne 'label=com.docker.compose.project=koiki-java-scaffold') {
        throw 'Unexpected Docker call in offline tests.'
    }
    if ($script:runningCompose) { 'compose-db-fixture' }
}
function Assert-FreePort([int]$Port) { $script:checkedPort = $Port }
function Check-Passes([string]$Name, [scriptblock]$Body) {
    & $Body
    $script:passed++
    Write-Host "PASS $Name"
}
function Check-Rejected([string]$Name, [scriptblock]$Body, [string]$Expected) {
    $rejected = $false
    try { & $Body } catch {
        if ($_.Exception.Message -notlike "*$Expected*") { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw "Expected rejection: $Name" }
    $script:passed++
    Write-Host "PASS $Name"
}

Check-Passes 'loopback K3s 1.37.2 accepted with explicit context/namespace' { Assert-LocalCluster }
$script:server = 'https://cluster.example.invalid:6443'
Check-Rejected 'remote API rejected' { Assert-LocalCluster } 'loopback API'
$script:server = 'https://127.0.0.1:6443'
$script:version = 'v1.36.6+k3s1'
Check-Rejected 'wrong server version rejected' { Assert-LocalCluster } 'targets Kubernetes'
$script:version = 'v1.37.2+k3s1'
$script:clusterFailure = $true
Check-Rejected 'cluster lookup failure rejected' { Assert-LocalCluster } 'kubectl failed'
$script:clusterFailure = $false

$script:runningCompose = $true
Check-Rejected 'running Compose rejected' { Assert-NoComposeOrJar } 'must not run together'
$script:runningCompose = $false
$script:dockerFailure = $true
Check-Rejected 'Docker inspection failure rejected' { Assert-NoComposeOrJar } 'Cannot inspect Docker'
$script:dockerFailure = $false
Check-Passes 'stopped Compose checks the JAR port' {
    Assert-NoComposeOrJar
    if ($script:checkedPort -ne 8080) { throw 'JAR port was not checked.' }
}

# Fixture values remain inside this offline test; no real secret files or cluster objects are used.
$values = @{ SCAFFOLD_DB_PASSWORD = 'fixture-only'; KOIKI_SOURCE_HMAC_KEY_ID = 'fixture-id'; KOIKI_SOURCE_HMAC_KEY = 'fixture-key' }
$data = @{}
foreach ($key in $values.Keys) { $data[$key] = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($values[$key])) }
$data['SCAFFOLD_DB_URL'] = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('jdbc:postgresql://scaffold-db:5432/scaffold'))
$data['SCAFFOLD_DB_USERNAME'] = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('scaffold'))
$existing = [pscustomobject]@{ data = [pscustomobject]$data }
Check-Passes 'matching credentials and DB target accepted' { Assert-SecretMatches $values $existing }
Check-Rejected 'missing Secret rejected' { Assert-SecretMatches $values $null } 'Secret is missing'
$existing.data.SCAFFOLD_DB_PASSWORD = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('different-fixture'))
Check-Rejected 'credential mismatch rejected' { Assert-SecretMatches $values $existing } 'differ'
$existing.data.SCAFFOLD_DB_PASSWORD = $data['SCAFFOLD_DB_PASSWORD']
$existing.data.SCAFFOLD_DB_URL = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('jdbc:postgresql://wrong-db:5432/scaffold'))
Check-Rejected 'DB target mismatch rejected' { Assert-SecretMatches $values $existing } 'differ'
Write-Host "Offline checks: $script:passed passed. No cluster or container changes were made."
