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

$keysWereGenerated = $false

function Get-PublicKeyHashHex {
    param([string]$PubPath)

    $pem = Get-Content $PubPath -Raw
    $base64 = $pem -replace '-----BEGIN PUBLIC KEY-----', '' -replace '-----END PUBLIC KEY-----', '' -replace '\s', ''
    $bytes = [Convert]::FromBase64String($base64)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    return (($sha256.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Normalize-AddressHex {
    param([string]$Raw)
    $hex = if ($Raw.StartsWith('0x')) { $Raw.Substring(2) } else { $Raw }
    if ($hex.Length -eq 64) { return $hex.Substring(0, 40) }
    return $hex
}

function Pad-Address {
    param([string]$Raw)
    $hex = Normalize-AddressHex $Raw
    return ('0' * 24) + $hex
}

function Convert-IntegerToHex256Bit {
    param([int]$Number)
    return ('{0:x64}' -f $Number)
}

function Write-GenesisJson {
    param(
        [string]$GenesisPath,
        [string]$ConfigDir,
        [int]$ReplicaCount,
        [int]$ClientCount
    )

    if (-not (Test-Path $GenesisPath -PathType Leaf)) {
        Write-Error "Genesis file not found at: $GenesisPath"
        return
    }

    $genesis = Get-Content $GenesisPath -Raw | ConvertFrom-Json
    
    # Helper to ensure addresses are exactly 40 chars (20 bytes)
    # This trims prefixes like '0x' and truncates longer hashes
    filter Set-AddressFormat {
        param([string]$addr)
        $clean = $addr.Replace("0x", "")
        if ($clean.Length -gt 40) {
            return $clean.Substring(0, 40)
        }
        return $clean
    }

    $newState = [ordered]@{}
    $rootAddr = "1234567891234567891234567891234567891234"
    $newState[$rootAddr] = @{ balance = "100000"; nonce = 0 }

    # Process Clients
    $clientHashes = @()
    for ($i = 1; $i -le $ClientCount; $i++) {
        $rawHash = Get-PublicKeyHashHex (Join-Path $ConfigDir "client$i.pub")
        $hash = Set-AddressFormat $rawHash
        $clientHashes += $hash
        $newState[$hash] = @{ balance = "10000"; nonce = 0 }
    }

    # Process Nodes/Replicas
    for ($i = 1; $i -le $ReplicaCount; $i++) {
        $rawHash = Get-PublicKeyHashHex (Join-Path $ConfigDir "node$i.pub")
        $hash = Set-AddressFormat $rawHash
        $newState[$hash] = @{ balance = "0"; nonce = 0 }
    }

    $genesis.state = $newState

    # Update Transactions
    if ($genesis.transactions -and $genesis.transactions.Count -ge 1) {
        $genesis.transactions[0].from = $rootAddr
    }

    if ($genesis.transactions -and $genesis.transactions.Count -ge 2 -and $clientHashes.Count -ge 1) {
        $client1Addr = $clientHashes[0]
        $genesis.transactions[1].from = $rootAddr
        $genesis.transactions[1].to = $client1Addr
        
        # Ensure the data field uses the 40-char version for the ABI encoding
        $genesis.transactions[1].data = '0xa9059cbb' + (Pad-Address $client1Addr) + (Convert-IntegerToHex256Bit 1000)
    }

    # Save with specific depth to prevent truncation of nested objects
    $genesis | ConvertTo-Json -Depth 32 | Set-Content -Path $GenesisPath
    Write-Host "genesis.json regenerated. All addresses forced to 40 characters."
}

Write-Host "Checking RSA keys..."

for ($i = 1; $i -le $r; $i++) {
    $priv = "$ConfigDir\node$i.priv"
    $pub  = "$ConfigDir\node$i.pub"
    if (-not (Test-Path $priv) -or -not (Test-Path $pub)) {
        New-KeyPair -PrivPath $priv -PubPath $pub
        $keysWereGenerated = $true
    }
}

for ($i = 1; $i -le $c; $i++) {
    $priv = "$ConfigDir\client$i.priv"
    $pub  = "$ConfigDir\client$i.pub"
    if (-not (Test-Path $priv) -or -not (Test-Path $pub)) {
        New-KeyPair -PrivPath $priv -PubPath $pub
        $keysWereGenerated = $true
    }
}

Write-Host "All RSA keys ready."

$GenesisPath = Join-Path $ProjectDir "blocks\genesis.json"
if ($keysWereGenerated) {
    Write-Host "Updating genesis.json to match newly generated keys..."
    Write-GenesisJson -GenesisPath $GenesisPath -ConfigDir $ConfigDir -ReplicaCount $r -ClientCount $c
}

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