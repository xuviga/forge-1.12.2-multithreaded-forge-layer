[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceRoot,

    [Parameter(Mandatory = $true)]
    [string]$Classes,

    [Parameter(Mandatory = $true)]
    [string]$OutJar,

    [string]$Jar = "jar",

    [string]$Stage = (Join-Path $env:TEMP "forge-multithreaded-stage")
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $SourceRoot)) {
    throw "Source root not found: $SourceRoot"
}
if (-not (Test-Path -LiteralPath $Classes)) {
    throw "Classes directory not found: $Classes"
}

if (Test-Path -LiteralPath $Stage) {
    Remove-Item -LiteralPath $Stage -Recurse -Force
}
New-Item -ItemType Directory -Path $Stage -Force | Out-Null

Write-Host "Copying compiled classes..."
Copy-Item -Path (Join-Path $Classes "*") -Destination $Stage -Recurse -Force

Write-Host "Copying runtime resources..."
foreach ($name in @("assets", "ibxm")) {
    $src = Join-Path $SourceRoot $name
    if (Test-Path -LiteralPath $src) {
        Copy-Item -LiteralPath $src -Destination (Join-Path $Stage $name) -Recurse -Force
    }
    else {
        Write-Warning "Missing resource directory: $src"
    }
}

foreach ($name in @(
    "log4j2.xml",
    "log4j2_server.xml",
    "forge_at.cfg",
    "forge_logo.png",
    "mcplogo.png",
    "pack.png",
    "url.png",
    "mcpmod.info",
    "yggdrasil_session_pubkey.der"
)) {
    $src = Join-Path $SourceRoot $name
    if (Test-Path -LiteralPath $src) {
        Copy-Item -LiteralPath $src -Destination (Join-Path $Stage $name) -Force
    }
    else {
        Write-Warning "Missing runtime file: $src"
    }
}

$OutJar = [IO.Path]::GetFullPath($OutJar)

Write-Host "Building jar: $OutJar"
Push-Location $Stage
try {
    if (Test-Path -LiteralPath $OutJar) {
        Remove-Item -LiteralPath $OutJar -Force
    }
    & $Jar cf $OutJar .
    if ($LASTEXITCODE -ne 0) {
        throw "jar failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$item = Get-Item -LiteralPath $OutJar
Write-Host ("Jar ready: {0} ({1:N0} bytes)" -f $item.FullName, $item.Length)
