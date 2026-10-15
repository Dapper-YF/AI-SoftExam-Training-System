param(
    [string]$CredentialPath = $env:AIEXAM_DEV_DB_CREDENTIAL_PATH,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$bootstrap = Join-Path $PSScriptRoot 'bootstrap-dev.ps1'
$start = Join-Path $PSScriptRoot 'start-w0-local.ps1'

if ([string]::IsNullOrWhiteSpace($CredentialPath) -or
    -not (Test-Path -LiteralPath $CredentialPath -PathType Leaf)) {
    throw 'DEV_DB_CREDENTIAL_NOT_FOUND. Set AIEXAM_DEV_DB_CREDENTIAL_PATH to a local JSON credential file before starting services.'
}

& powershell.exe -ExecutionPolicy Bypass -File $bootstrap -CredentialPath $CredentialPath -SkipBuild:$SkipBuild
if ($LASTEXITCODE -ne 0) {
    throw "DEV_DATA_BOOTSTRAP_FAILED. ExitCode=$LASTEXITCODE"
}

& powershell.exe -ExecutionPolicy Bypass -File $start -CredentialPath $CredentialPath -SkipBuild:$SkipBuild
if ($LASTEXITCODE -ne 0) {
    throw "DEV_SERVICE_START_FAILED. ExitCode=$LASTEXITCODE"
}
