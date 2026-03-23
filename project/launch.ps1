param(
    [Parameter(Mandatory)][int]$c,
    [Parameter(Mandatory)][int]$r
)

function Show-Usage {
    Write-Host @"
Usage: .\launch.ps1 -c <num_clients> -r <num_replicas>

Options:
  -c    Number of clients to start (starting at client id 1)
  -r    Number of replicas to start (starting at node id 1)
"@
}

if ($c -le 0 -or $r -le 0) {
    Write-Error "Both -c and -r must be positive integers."
    Show-Usage
    exit 1
}

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ConfigDir  = Join-Path $ProjectDir "config"

if (-not (Test-Path $ConfigDir -PathType Container)) {
    Write-Error "Config directory not found at: $ConfigDir"
    exit 1
}

$MaxClients  = (Get-ChildItem "$ConfigDir\client*.priv" -File -ErrorAction SilentlyContinue).Count
$MaxReplicas = (Get-ChildItem "$ConfigDir\node*.priv"   -File -ErrorAction SilentlyContinue).Count

if ($c -gt $MaxClients) {
    Write-Error "Requested $c clients, but only $MaxClients are configured in config/."
    exit 1
}

if ($r -gt $MaxReplicas) {
    Write-Error "Requested $r replicas, but only $MaxReplicas are configured in config/."
    exit 1
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "Maven (mvn) was not found in PATH."
    exit 1
}

function Start-Window {
    param([string]$Title, [string]$Command)

    Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$ProjectDir'; $Command" `
        -WindowStyle Normal
}

Write-Host "Starting $r replicas and $c clients..."

for ($i = 1; $i -le $r; $i++) {
    Start-Window `
        -Title "Replica $i" `
        -Command "cd '$ProjectDir\service'; mvn exec:java '-Dexec.mainClass=pt.depchain.service.Node' '-Dexec.args=$i'"
}

Start-Sleep -Seconds 1

for ($i = 1; $i -le $c; $i++) {
    Start-Window `
        -Title "Client $i" `
        -Command "cd '$ProjectDir\client'; mvn exec:java '-Dexec.mainClass=pt.depchain.client.ClientMain' '-Dexec.args=$i'"
}

Write-Host "All processes launched."