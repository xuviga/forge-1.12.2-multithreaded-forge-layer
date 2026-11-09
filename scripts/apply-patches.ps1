[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Target,

    [string]$Patch = "",

    [switch]$IgnoreWhitespace
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Patch) {
    $Patch = [IO.Path]::Combine($scriptDir, "..", "patches", "forge-1.12.2-multithreaded.patch")
}

if (-not (Test-Path -LiteralPath $Target)) {
    throw "Target source tree not found: $Target"
}
if (-not (Test-Path -LiteralPath $Patch)) {
    throw "Patch not found: $Patch"
}

$Target = (Resolve-Path -LiteralPath $Target).Path
$Patch = (Resolve-Path -LiteralPath $Patch).Path

Push-Location $Target
try {
    $useIgnoreWhitespace = [bool]$IgnoreWhitespace
    $applyArgs = @("apply", "--check", "--whitespace=nowarn")
    if ($useIgnoreWhitespace) { $applyArgs += "--ignore-whitespace" }
    $applyArgs += $Patch

    & git @applyArgs
    if ($LASTEXITCODE -ne 0) {
        if (-not $useIgnoreWhitespace) {
            Write-Warning "Strict patch check failed; retrying with --ignore-whitespace (CRLF baseline)."
            $useIgnoreWhitespace = $true
            $applyArgs = @("apply", "--check", "--whitespace=nowarn", "--ignore-whitespace", $Patch)
            & git @applyArgs
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Patch does not apply cleanly. Use a Forge 14.23.5.2860 MCP stable_39 baseline."
        }
    }

    $applyArgs = @("apply", "--whitespace=nowarn")
    if ($useIgnoreWhitespace) { $applyArgs += "--ignore-whitespace" }
    $applyArgs += $Patch

    & git @applyArgs
    if ($LASTEXITCODE -ne 0) {
        throw "git apply failed with exit code $LASTEXITCODE"
    }

    Write-Host "Applied patch: $Patch"
}
finally {
    Pop-Location
}
