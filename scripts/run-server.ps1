# Template launch script. Edit the parameter defaults for your machine.
[CmdletBinding()]
param(
    [string]$Java = "C:\Program Files\Java\jre1.8.0_301\bin\java.exe",
    [string]$Start = "C:\path\to\gradle\caches\minecraft\net\minecraftforge\forge\1.12.2-14.23.5.2847\start",
    [string]$ForgeJar = "C:\path\to\forge-1.12.2-14.23.5.2860-multithreaded.jar",
    [string]$Libs = "C:\path\to\dev_classpath.txt",
    [string]$GameDir = "C:\path\to\run-server",
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

New-Item -ItemType Directory -Path $GameDir -Force | Out-Null

$arguments = @(
    "-Xms$Xms",
    "-Xmx$Xmx",
    "-XX:+UseG1GC",
    "-XX:MaxGCPauseMillis=50",
    "-Dfml.ignoreInvalidMinecraftCertificates=true",
    "-cp", $cp,
    "GradleStartServer",
    "--gameDir", $GameDir,
    "--nogui"
)

if ($ExtraArgs) {
    $arguments += $ExtraArgs.Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
}

Write-Host "Launching server..."
Push-Location $GameDir
try {
    & $Java @arguments
    Write-Host "EXITCODE=$LASTEXITCODE"
}
finally {
    Pop-Location
}
