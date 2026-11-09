[CmdletBinding()]
param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $RepoRoot) {
    $RepoRoot = [IO.Path]::Combine($scriptDir, "..")
}

$layer = [IO.Path]::Combine($RepoRoot, "forge-layer")
$forgeJava = [IO.Path]::Combine($layer, "net", "minecraftforge")
$forgePatches = [IO.Path]::Combine($layer, "_forge_patches")
$fullPatch = [IO.Path]::Combine($RepoRoot, "patches", "forge-1.12.2-multithreaded.patch")
$vanillaPatch = [IO.Path]::Combine($RepoRoot, "patches", "vanilla-only.patch")

foreach ($p in @($layer, $forgeJava, $forgePatches, $fullPatch, $vanillaPatch)) {
    if (-not (Test-Path -LiteralPath $p)) {
        throw "Missing required path: $p"
    }
}

$javaCount = (Get-ChildItem -LiteralPath $forgeJava -Recurse -Filter "*.java" | Measure-Object).Count
if ($javaCount -lt 700) {
    throw "Expected at least 700 Forge Java files, found $javaCount"
}

$patchCount = (Get-ChildItem -LiteralPath $forgePatches -Recurse -Filter "*.patch" | Measure-Object).Count
if ($patchCount -lt 500) {
    throw "Expected at least 500 Forge patches, found $patchCount"
}

$threadingDir = [IO.Path]::Combine($forgeJava, "common", "threading")
$memoryDir = [IO.Path]::Combine($forgeJava, "common", "memory")
if (-not (Test-Path -LiteralPath $threadingDir)) {
    throw "Missing threading package: $threadingDir"
}
if (-not (Test-Path -LiteralPath $memoryDir)) {
    throw "Missing memory package: $memoryDir"
}

# Ensure no vanilla Minecraft tree leaked into the layer.
$vanillaDir = [IO.Path]::Combine($layer, "net", "minecraft")
if (Test-Path -LiteralPath $vanillaDir) {
    throw "Forbidden vanilla tree present: $vanillaDir"
}

$fullText = Get-Content -LiteralPath $fullPatch -Raw
foreach ($needle in @(
    "net/minecraftforge/common/threading/RegionManager.java",
    "net/minecraftforge/common/memory/MemoryLeakGuard.java",
    "net/minecraft/client/Minecraft.java"
)) {
    if ($fullText.IndexOf($needle, [System.StringComparison]::Ordinal) -lt 0) {
        throw "Full patch is missing expected path: $needle"
    }
}

$vanillaText = Get-Content -LiteralPath $vanillaPatch -Raw
if ($vanillaText.IndexOf("net/minecraft/client/Minecraft.java", [System.StringComparison]::Ordinal) -lt 0) {
    throw "vanilla-only.patch is missing net/minecraft/client/Minecraft.java"
}
if ($vanillaText.IndexOf("net/minecraftforge/", [System.StringComparison]::Ordinal) -ge 0) {
    # The patch may mention Forge names in code/imports, but must not contain a
    # diff header for a Forge path.
    $forgeDiff = [regex]::IsMatch($vanillaText, "(?m)^diff --git a/net/minecraftforge/")
    if ($forgeDiff) {
        throw "vanilla-only.patch contains a net/minecraftforge diff header"
    }
}

Write-Host "Layer verification OK."
Write-Host "Forge Java files: $javaCount"
Write-Host "Forge patches:    $patchCount"
Write-Host "Full patch:       $fullPatch"
Write-Host "Vanilla patch:    $vanillaPatch"
