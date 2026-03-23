param(
    [Parameter(Mandatory)][int]$c,
    [Parameter(Mandatory)][int]$r
)

function Show-Usage {
    Write-Host @"
Usage: .\launch.ps1 -c <num_clients> -r <num_replicas>

Options:
  -c    Number of clients to start (starting at client id 1)
  -r    Number of replicas (total nodes)
"@
}

if ($c -le 0 -or $r -le 0) {
    Write-Error "Both -c and -r must be positive integers."
    Show-Usage
    exit 1
}

$f = [math]::Floor(($r - 1) / 3)
$k = $r - $f

Write-Host "HotStuff params: r=$r, f=$f, k=$k"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ConfigDir  = Join-Path $ProjectDir "config"

if (-not (Test-Path $ConfigDir -PathType Container)) {
    Write-Error "Config directory not found at: $ConfigDir"
    exit 1
}

if (-not (Get-Command openssl -ErrorAction SilentlyContinue)) {
    Write-Error "openssl was not found in PATH."
    exit 1
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "Maven (mvn) was not found in PATH."
    exit 1
}

function New-KeyPair {
    param([string]$PrivPath, [string]$PubPath)
    Write-Host "  Generating keys: $PrivPath ..."
    openssl genpkey -algorithm RSA -out $PrivPath -pkeyopt rsa_keygen_bits:2048 2>$null
    openssl rsa -pubout -in $PrivPath -out $PubPath 2>$null
}

Write-Host "Checking RSA keys..."

for ($i = 1; $i -le $r; $i++) {
    $priv = "$ConfigDir\node$i.priv"
    $pub  = "$ConfigDir\node$i.pub"
    if (-not (Test-Path $priv) -or -not (Test-Path $pub)) {
        New-KeyPair -PrivPath $priv -PubPath $pub
    }
}

for ($i = 1; $i -le $c; $i++) {
    $priv = "$ConfigDir\client$i.priv"
    $pub  = "$ConfigDir\client$i.pub"
    if (-not (Test-Path $priv) -or -not (Test-Path $pub)) {
        New-KeyPair -PrivPath $priv -PubPath $pub
    }
}

Write-Host "All RSA keys ready."

$groupKeyPath = "$ConfigDir\groupKey.json"
$sharesExist  = $true
for ($i = 1; $i -le $r; $i++) {
    if (-not (Test-Path "$ConfigDir\node$i.share.json")) {
        $sharesExist = $false
        break
    }
}

if (-not (Test-Path $groupKeyPath) -or -not $sharesExist) {
    Write-Host "Generating threshold keys (k=$k, l=$r)..."
    Push-Location "$ProjectDir\crypto"
    mvn exec:java "-Dexec.mainClass=pt.depchain.crypto.ThresholdKeyGenerator" "-Dexec.args=$k $r"
    Pop-Location
    Write-Host "Threshold keys ready."
} else {
    Write-Host "Threshold keys already exist, skipping."
}

# --- Generate membership.json ---
function Test-Port {
    param([int]$Port)
    $conn = [System.Net.Sockets.TcpClient]::new()
    try {
        $conn.Connect("localhost", $Port)
        $conn.Close()
        return $true  # port is in use
    } catch {
        return $false  # port is free
    }
}

function Get-FreePort {
    param([int]$StartPort)
    $port = $StartPort
    while (Test-Port -Port $port) {
        Write-Host "  Port $port is in use, trying $($port + 1)..."
        $port++
    }
    return $port
}

Write-Host "Allocating ports for membership.json..."

$nodeBasePort   = 9001
$clientBasePort = 4001

$nodesJson   = @()
$clientsJson = @()

$nextNodePort = $nodeBasePort
for ($i = 1; $i -le $r; $i++) {
    $port = Get-FreePort -StartPort $nextNodePort
    $nodesJson += @{ id = "$i"; host = "localhost"; port = $port; pub = "node$i.pub" }
    $nextNodePort = $port + 1
}

$nextClientPort = $clientBasePort
for ($i = 1; $i -le $c; $i++) {
    $port = Get-FreePort -StartPort $nextClientPort
    $clientsJson += @{ id = "client$i"; host = "localhost"; port = $port; pub = "client$i.pub" }
    $nextClientPort = $port + 1
}

$membershipObj = [ordered]@{ clients = $clientsJson; nodes = $nodesJson }
$membershipJson = "[" + ($membershipObj | ConvertTo-Json -Depth 4) + "]"
Set-Content -Path "$ConfigDir\membership.json" -Value $membershipJson

Write-Host "membership.json generated."

function Start-Window {
    param([string]$Title, [string]$Command)

    Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$ProjectDir'; $Command" `
        -WindowStyle Normal
}

Write-Host "Starting $r replicas and $c clients..."

for ($i = 1; $i -le $r; $i++) {
    $PrivKey = "../config/node$i.priv"
    $PubKey  = "../config/node$i.pub"
    Start-Window `
        -Title "Replica $i" `
        -Command "cd '$ProjectDir\service'; mvn exec:java '-Dexec.mainClass=pt.depchain.service.Node' '-Dexec.args=$i $PrivKey $PubKey'"
}

Start-Sleep -Seconds 1

for ($i = 1; $i -le $c; $i++) {
    $PrivKey = "../config/client$i.priv"
    $PubKey  = "../config/client$i.pub"
    Start-Window `
        -Title "Client $i" `
        -Command "cd '$ProjectDir\client'; mvn exec:java '-Dexec.mainClass=pt.depchain.client.ClientMain' '-Dexec.args=client$i $PrivKey $PubKey'"
}

Write-Host "All processes launched."