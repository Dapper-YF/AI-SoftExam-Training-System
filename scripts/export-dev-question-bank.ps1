param(
    [string]$DatasetVersion = 'dev-question-bank-20260717-w2',
    [string]$CredentialPath = $env:AIEXAM_DEV_DB_CREDENTIAL_PATH,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $projectRoot 'backend'
$outputRoot = Join-Path $projectRoot "dev-data\question-bank\$DatasetVersion"

function Read-DevCredential {
    if ($env:MYSQL_URL -and $env:MYSQL_USERNAME -and $env:MYSQL_PASSWORD) {
        return [pscustomobject]@{
            url = [string]$env:MYSQL_URL
            username = [string]$env:MYSQL_USERNAME
            password = [string]$env:MYSQL_PASSWORD
        }
    }
    if ([string]::IsNullOrWhiteSpace($CredentialPath) -or
        -not (Test-Path -LiteralPath $CredentialPath -PathType Leaf)) {
        throw 'DEV_DB_CREDENTIAL_NOT_FOUND. Set MYSQL_URL/MYSQL_USERNAME/MYSQL_PASSWORD or AIEXAM_DEV_DB_CREDENTIAL_PATH.'
    }
    return Get-Content -Raw -LiteralPath $CredentialPath | ConvertFrom-Json
}

$credential = Read-DevCredential
$env:MYSQL_URL = [string]$credential.url
$env:MYSQL_USERNAME = [string]$credential.username
$env:MYSQL_PASSWORD = [string]$credential.password
try {
    Push-Location -LiteralPath $backendRoot
    try {
        if (-not $SkipBuild) {
            & mvn.cmd -pl platform-application -am -DskipTests compile
            if ($LASTEXITCODE -ne 0) {
                throw "BACKEND_COMPILE_FAILED. ExitCode=$LASTEXITCODE"
            }
        }
        & mvn.cmd -pl platform-application -am spring-boot:run `
            "-Dspring-boot.run.arguments=--spring.main.web-application-type=none --spring.profiles.active=dev,mysql --spring.flyway.enabled=false --aiexam.auth.persistence=jdbc --aiexam.auth.password-adapter=argon2id --aiexam.auth.registration.verification-required=false --aiexam.auth.challenge.dev-delivery.enabled=true --aiexam.auth.dev-accounts.enabled=false --aiexam.dev-data.enabled=true --aiexam.dev-data.command=export --aiexam.dev-data.dataset-version=$DatasetVersion --aiexam.dev-data.output-dir=$outputRoot --aiexam.runtime.commit=$((& git.exe -C $projectRoot rev-parse HEAD).Trim())"
        if ($LASTEXITCODE -ne 0) {
            throw "DEV_QUESTION_BANK_EXPORT_FAILED. ExitCode=$LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    Remove-Item Env:\MYSQL_PASSWORD -ErrorAction SilentlyContinue
}

Write-Output "DEV_DATA_EXPORT_OK=$outputRoot"
