[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceRoot,

    [Parameter(Mandatory = $true)]
    [string]$Libs,

    [string]$Out = "",

    [string]$Javac = "javac",

    [string]$Release = "8",

    [int]$MaxErrors = 2000,

    [string[]]$ExtraClasspath = @(),

    [switch]$NoRelease
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Out) {
    $Out = [IO.Path]::Combine($scriptDir, "..", "compile_out")
}

if (-not (Test-Path -LiteralPath $SourceRoot)) {
    throw "Source root not found: $SourceRoot"
}
if (-not (Test-Path -LiteralPath $Libs)) {
    throw "Libs directory not found: $Libs"
}

New-Item -ItemType Directory -Path $Out -Force | Out-Null

$exclude = @(
    "forge-1.12.2-14.23.5.2860-mci.jar",
    "forge-1.12.2-14.23.5.2860-userdev3.jar",
    "minecraft_merged-1.12.2.jar"
)

$jars = Get-ChildItem -LiteralPath $Libs -Filter "*.jar" |
    Where-Object { $exclude -notcontains $_.Name } |
    ForEach-Object { $_.FullName }

if ($jars.Count -eq 0) {
    throw "No dependency jars found in: $Libs"
}

$cpParts = @($jars)
foreach ($extra in $ExtraClasspath) {
    if (-not (Test-Path -LiteralPath $extra)) {
        throw "Extra classpath not found: $extra"
    }
    $cpParts += [IO.Path]::GetFullPath($extra)
}
$cp = $cpParts -join ";"

Write-Host "Collecting sources..."
$sources = Get-ChildItem -LiteralPath $SourceRoot -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }

if ($sources.Count -eq 0) {
    throw "No .java files found under: $SourceRoot"
}

$sourceList = Join-Path $env:TEMP "forge-multithreaded-sources.txt"
$sources | Set-Content -LiteralPath $sourceList -Encoding ASCII

Write-Host "Sources: $($sources.Count)"
Write-Host "Output:  $Out"
Write-Host "Javac:   $Javac"

$javacArgs = @(
    "-d", $Out,
    "-cp", $cp,
    "-encoding", "UTF-8",
    "-proc:none",
    "-nowarn",
    "-Xmaxerrs", "$MaxErrors"
)

if (-not $NoRelease -and $Release) {
    $javacArgs += @("--release", $Release)
}

$javacArgs += "@$sourceList"

& $Javac @javacArgs
$exit = $LASTEXITCODE
Write-Host "JAVAC_EXIT=$exit"

if ($exit -ne 0) {
    throw "javac failed with exit code $exit"
}

Write-Host "Compile OK."
