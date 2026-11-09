[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Target,

    [switch]$SkipPatch
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = [IO.Path]::Combine($scriptDir, "..")
$layer = [IO.Path]::Combine($repoRoot, "forge-layer")
$vanillaPatch = [IO.Path]::Combine($repoRoot, "patches", "vanilla-only.patch")

if (-not (Test-Path -LiteralPath $Target)) {
    throw "Target source tree not found: $Target"
}
if (-not (Test-Path -LiteralPath $layer)) {
    throw "Forge layer not found: $layer"
}

$Target = (Resolve-Path -LiteralPath $Target).Path

if (-not (Test-Path -LiteralPath ([IO.Path]::Combine($Target, "mcp")))) {
    Write-Warning "Target has no mcp/ package. Compilation may fail with 'package mcp does not exist'. Use a full official Forge source tree."
}

Write-Host "Copying Forge layer into: $Target"
$layerNet = [IO.Path]::Combine($layer, "net")
$targetNet = [IO.Path]::Combine($Target, "net")
New-Item -ItemType Directory -Path $targetNet -Force | Out-Null
Copy-Item -Path ([IO.Path]::Combine($layerNet, "*")) -Destination $targetNet -Recurse -Force

$layerPatches = [IO.Path]::Combine($layer, "_forge_patches")
if (Test-Path -LiteralPath $layerPatches) {
    $targetPatches = [IO.Path]::Combine($Target, "_forge_patches")
    New-Item -ItemType Directory -Path $targetPatches -Force | Out-Null
    Copy-Item -Path ([IO.Path]::Combine($layerPatches, "*")) -Destination $targetPatches -Recurse -Force
}

foreach ($name in @(
    "forge_at.cfg",
    "forge.srg",
    "forge.exc",
    "log4j2.xml",
    "log4j2_server.xml",
    "mcpmod.info"
)) {
    $src = [IO.Path]::Combine($layer, $name)
    if (Test-Path -LiteralPath $src) {
        Copy-Item -LiteralPath $src -Destination ([IO.Path]::Combine($Target, $name)) -Force
    }
}

if (-not $SkipPatch) {
    if (-not (Test-Path -LiteralPath $vanillaPatch)) {
        throw "Vanilla patch not found: $vanillaPatch"
    }

    Write-Host "Applying vanilla-only patch..."
    Push-Location $Target
    try {
        & git apply --check --whitespace=nowarn --ignore-whitespace $vanillaPatch
        if ($LASTEXITCODE -ne 0) {
            throw "vanilla-only.patch does not apply. Use a clean Forge 14.23.5.2860 MCP stable_39 source tree."
        }

        & git apply --whitespace=nowarn --ignore-whitespace $vanillaPatch
        if ($LASTEXITCODE -ne 0) {
            throw "git apply failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
    Write-Host "Applied vanilla-only patch."
}

Write-Host "Layer ready: $Target"
