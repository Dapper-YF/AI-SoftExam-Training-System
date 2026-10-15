param(
    [string]$DatasetVersion = 'dev-question-bank-20260717-w2',
    [string]$CredentialPath = $env:AIEXAM_DEV_DB_CREDENTIAL_PATH,
    [string]$DatabaseName = $env:AIEXAM_DEV_DB_NAME,
    [switch]$SkipBuild,
    [switch]$VerifyRandomSchema,
    [switch]$KeepRandomSchema
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path -LiteralPath "$PSScriptRoot\..").Path
$backendRoot = "$projectRoot\backend"
$datasetRoot = "$projectRoot\dev-data\question-bank\$DatasetVersion"
$runtimeImportRoot = "$projectRoot\.runtime\dev-data-import\$DatasetVersion"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

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

function Set-DatabaseNameInJdbcUrl {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Name
    )
    if ($Name -notmatch '^[a-zA-Z0-9_]+$') {
        throw "DEV_DB_NAME_INVALID. Only letters, numbers and underscore are allowed: $Name"
    }
    $updated = $Url -replace '(jdbc:mysql://[^/?]+/)([^?]+)', "`$1$Name"
    if ($updated -notmatch 'createDatabaseIfNotExist=') {
        $separator = '?'
        if ($updated.Contains('?')) {
            $separator = '&'
        }
        $updated = "$updated${separator}createDatabaseIfNotExist=true"
    }
    return $updated
}

function Invoke-DevDataBootstrap {
    param([Parameter(Mandatory = $true)][string]$JdbcUrl)

    $credential = Read-DevCredential
    $env:MYSQL_URL = $JdbcUrl
    $env:MYSQL_USERNAME = [string]$credential.username
    $env:MYSQL_PASSWORD = [string]$credential.password
    try {
        Push-Location -LiteralPath $backendRoot
        try {
            if (-not $SkipBuild) {
                & mvn.cmd -pl platform-application -am -DskipTests package org.springframework.boot:spring-boot-maven-plugin:3.3.5:repackage
                if ($LASTEXITCODE -ne 0) {
                    throw "BACKEND_PACKAGE_FAILED. ExitCode=$LASTEXITCODE"
                }
            }
        }
        finally {
            Pop-Location
        }

        $backendTarget = "$backendRoot\platform-application\target"
        $backendJar = $null
        foreach ($candidate in (Get-ChildItem -LiteralPath $backendTarget -Filter 'platform-application-*.jar' -File)) {
            if ($candidate.Name -like '*.original') {
                continue
            }
            if ($null -eq $backendJar -or $candidate.LastWriteTime -gt $backendJar.LastWriteTime) {
                $backendJar = $candidate
            }
        }
        if ($null -eq $backendJar) {
            throw 'BACKEND_EXECUTABLE_JAR_NOT_FOUND. Retry without -SkipBuild.'
        }
        $manifestPath = "$datasetRoot\manifest.json"
        $packagePath = "$datasetRoot\canonical-questions.jsonl.gz"
        $manifestExists = Test-Path -LiteralPath $manifestPath -PathType Leaf
        $packageExists = Test-Path -LiteralPath $packagePath -PathType Leaf
        if (-not $manifestExists -or -not $packageExists) {
            throw "DEV_DATA_PACKAGE_INCOMPLETE: $datasetRoot"
        }
        $null = New-Item -ItemType Directory -Force -Path $runtimeImportRoot
        & java.exe -jar $backendJar.FullName --spring.main.web-application-type=none --spring.profiles.active=dev,mysql --spring.flyway.enabled=false --aiexam.auth.persistence=jdbc --aiexam.auth.password-adapter=argon2id --aiexam.auth.registration.verification-required=false --aiexam.auth.challenge.dev-delivery.enabled=true --aiexam.auth.dev-accounts.enabled=true --aiexam.auth.dev-seed-username-login.enabled=true --aiexam.dev-data.enabled=true --aiexam.dev-data.command=bootstrap "--aiexam.dev-data.dataset-version=$DatasetVersion" "--aiexam.dev-data.input-dir=$datasetRoot" "--aiexam.dev-data.import-root=$runtimeImportRoot" "--aiexam.question-import.allowed-root=$runtimeImportRoot"
        if ($LASTEXITCODE -ne 0) {
            throw "DEV_DATA_BOOTSTRAP_FAILED. ExitCode=$LASTEXITCODE"
        }
    }
    finally {
        Remove-Item Env:\MYSQL_PASSWORD -ErrorAction SilentlyContinue
    }
}

$credential = Read-DevCredential
$jdbcUrl = [string]$credential.url
if (-not [string]::IsNullOrWhiteSpace($DatabaseName)) {
    $jdbcUrl = Set-DatabaseNameInJdbcUrl -Url $jdbcUrl -Name $DatabaseName
}

if ($VerifyRandomSchema) {
    $timestamp = Get-Date -Format 'yyyyMMddHHmmss'
    $randomSchema = "ai_exam_platform_devdata_${timestamp}_test"
    $testUrl = Set-DatabaseNameInJdbcUrl -Url $jdbcUrl -Name $randomSchema
    Write-Output "DEV_DATA_RANDOM_SCHEMA=$randomSchema"
    Invoke-DevDataBootstrap -JdbcUrl $testUrl
    Invoke-DevDataBootstrap -JdbcUrl $testUrl
    if (-not $KeepRandomSchema) {
        Write-Output "DEV_DATA_RANDOM_SCHEMA_READY_FOR_MANUAL_DROP=$randomSchema"
        Write-Output 'DEV_DATA_RANDOM_SCHEMA_MANUAL_DROP_REQUIRED'
    }
} else {
    Invoke-DevDataBootstrap -JdbcUrl $jdbcUrl
}

Write-Output "DEV_DATA_BOOTSTRAP_OK=$DatasetVersion"
Write-Output 'DEV_ACCOUNTS=student/admin/question_reviewer'
