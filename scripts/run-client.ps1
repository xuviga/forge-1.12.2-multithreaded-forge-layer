# Template launch script. Edit the parameter defaults for your machine.
[CmdletBinding()]
param(
    [string]$Java = "C:\Program Files\Java\jre1.8.0_301\bin\java.exe",
    [string]$Start = "C:\path\to\gradle\caches\minecraft\net\minecraftforge\forge\1.12.2-14.23.5.2847\start",
    [string]$ForgeJar = "C:\path\to\forge-1.12.2-14.23.5.2860-multithreaded.jar",
    [string]$Libs = "C:\path\to\dev_classpath.txt",
    [string]$GameDir = "C:\path\to\run-forge",
    [string]$Assets = "C:\path\to\assets",
    [string]$Natives = "C:\path\to\natives",
    [string]$Username = "Dev",
    [string]$Xms = "2G",
    [string]$Xmx = "6G",
    [string]$ExtraArgs = ""
)

$ErrorActionPreference = "Stop"

foreach ($p in @($Java, $ForgeJar, $Start)) {
    if (-not (Test-Path -LiteralPath $p)) {
        throw "Missing path: $p"
    }
}

$libsValue = ""
if (Test-Path -LiteralPath $Libs) {
    if ((Get-Item -LiteralPath $Libs).PSIsContainer) {
        $libsValue = (Get-ChildItem -LiteralPath $Libs -Filter "*.jar" |
            ForEach-Object { $_.FullName }) -join ";"
    }
    else {
        $libsValue = (Get-Content -LiteralPath $Libs -Raw).Trim()
    }
}

$cp = "$Start;$ForgeJar"
if ($libsValue) { $cp += ";$libsValue" }

$arguments = @(
    "-Xms$Xms",
    "-Xmx$Xmx",
    "-XX:+UseG1GC",
    "-XX:MaxGCPauseMillis=50",
    "-Djava.library.path=$Natives",
    "-Dfml.ignoreInvalidMinecraftCertificates=true",
    "-cp", $cp,
    "GradleStart",
    "--username", $Username,
    "--gameDir", $GameDir,
    "--assetsDir", $Assets,
    "--assetIndex", "1.12",
    "--version", "1.12.2"
)

if ($ExtraArgs) {
    $arguments += $ExtraArgs.Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
}

Write-Host "Launching client..."
& $Java @arguments
Write-Host "EXITCODE=$LASTEXITCODE"
