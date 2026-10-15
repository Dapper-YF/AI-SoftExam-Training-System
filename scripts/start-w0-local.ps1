param(
    [string]$CredentialPath = 'C:\tmp\AIExam-auth-mysql-33307\app-credentials.json',
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $projectRoot 'backend'
$frontendRoot = Join-Path $projectRoot 'frontend'
$runtimeDirectory = Join-Path $projectRoot '.runtime'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$sourceCommit = (& git.exe -C $projectRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch '^[0-9a-f]{40}$') {
    throw 'Unable to resolve the source Git commit for the runtime.'
}

function Test-ListeningPort {
    param([Parameter(Mandatory = $true)][int]$Port)
    return @(
        Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    ).Count -gt 0
}

function Wait-HttpReady {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [int]$Attempts = 60
    )

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        if ($Process.HasExited) {
            throw "Process $($Process.Id) exited before $Url became ready."
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3
            if ($response.StatusCode -eq 200) {
                return
            }
        }
        catch {
            if ($attempt -eq $Attempts) {
                throw "Timed out waiting for $Url. Last error: $($_.Exception.Message)"
            }
        }
        Start-Sleep -Seconds 1
    }
}

function Restore-EnvironmentValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowNull()][string]$Value
    )
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Stop-VerifiedFrontendProcessTree {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$RootProcess,
        [Parameter(Mandatory = $true)][string]$ExpectedFrontendRoot
    )

    try {
        $allProcesses = @(Get-CimInstance Win32_Process -ErrorAction Stop)
        $descendantIds = New-Object System.Collections.Generic.List[int]
        $frontier = @($RootProcess.Id)
        while ($frontier.Count -gt 0) {
            $children = @($allProcesses | Where-Object { $_.ParentProcessId -in $frontier })
            $frontier = @($children | Select-Object -ExpandProperty ProcessId)
            foreach ($childId in $frontier) {
                if (-not $descendantIds.Contains([int]$childId)) {
                    $descendantIds.Add([int]$childId)
                }
            }
        }

        foreach ($processId in @($descendantIds | Sort-Object -Descending)) {
            $process = $allProcesses | Where-Object ProcessId -eq $processId | Select-Object -First 1
            $commandLine = [string]$process.CommandLine
            if (
                $commandLine.IndexOf($ExpectedFrontendRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
                ($commandLine -match '(?i)node|vite|esbuild|npm')
            ) {
                Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
            }
        }
    }
    catch {
        # The exact wrapper below is still safe to stop because this script created it.
    }

    if (-not $RootProcess.HasExited) {
        Stop-Process -Id $RootProcess.Id -Force -ErrorAction SilentlyContinue
    }
}

if (-not (Test-Path -LiteralPath $CredentialPath -PathType Leaf)) {
    throw "Application credential file is missing: $CredentialPath"
}
if (-not (Test-ListeningPort -Port 33307)) {
    throw 'MySQL is not listening on the fixed local port 33307.'
}
foreach ($port in @(5173, 8000)) {
    if (Test-ListeningPort -Port $port) {
        throw "Port $port is already occupied. Inspect and stop only the verified stale project process before retrying."
    }
}

$credential = Get-Content -Raw -LiteralPath $CredentialPath | ConvertFrom-Json
if ($credential.url -notmatch '^jdbc:mysql://127\.0\.0\.1:33307/ai_exam_platform\?' -or
    [string]::IsNullOrWhiteSpace([string]$credential.username) -or
    [string]::IsNullOrWhiteSpace([string]$credential.password)) {
    throw 'Credential file is not scoped to ai_exam_platform on local MySQL 33307.'
}

if (-not $SkipBuild) {
    $mavenRepository = Join-Path $backendRoot '.m2\repository'
    Push-Location -LiteralPath $backendRoot
    try {
        & mvn.cmd -o -pl platform-application -am "-Dmaven.repo.local=$mavenRepository" `
            -DskipTests package org.springframework.boot:spring-boot-maven-plugin:3.3.5:repackage
        if ($LASTEXITCODE -ne 0) {
            throw "Backend package build failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

$backendJar = Get-ChildItem -LiteralPath (Join-Path $backendRoot 'platform-application\target') `
    -Filter 'platform-application-*.jar' -File |
    Where-Object { $_.Name -notlike '*.original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $backendJar) {
    throw 'Backend executable jar is missing. Run without -SkipBuild first.'
}
if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'node_modules\.bin\vite.cmd') -PathType Leaf)) {
    throw 'Frontend dependencies are missing. Run npm ci in frontend before starting.'
}

[void](New-Item -ItemType Directory -Force -Path $runtimeDirectory)
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backendOut = Join-Path $runtimeDirectory "backend-8000-$timestamp.out.log"
$backendErr = Join-Path $runtimeDirectory "backend-8000-$timestamp.err.log"
$frontendOut = Join-Path $runtimeDirectory "frontend-5173-$timestamp.out.log"
$frontendErr = Join-Path $runtimeDirectory "frontend-5173-$timestamp.err.log"
$statePath = Join-Path $runtimeDirectory 'w0-local-state.json'

$environmentNames = @(
    'MYSQL_URL',
    'MYSQL_USERNAME',
    'MYSQL_PASSWORD',
    'VITE_RUNTIME_COMMIT',
    'VITE_API_BASE_URL',
    'VITE_PLATFORM_MOCK',
    'VITE_PRACTICE_MOCK',
    'VITE_ADMIN_QUESTION_MOCK',
    'VITE_EXAM_SIMULATOR_MOCK'
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$backendProcess = $null
$frontendProcess = $null
try {
    $env:MYSQL_URL = [string]$credential.url
    $env:MYSQL_USERNAME = [string]$credential.username
    $env:MYSQL_PASSWORD = [string]$credential.password

    $backendProcess = Start-Process -FilePath 'java.exe' -ArgumentList @(
        '-jar',
        $backendJar.FullName,
        '--server.port=8000',
        '--server.address=127.0.0.1',
        '--spring.profiles.active=dev,mysql',
        '--spring.flyway.enabled=false',
        '--aiexam.auth.persistence=jdbc',
        '--aiexam.auth.password-adapter=argon2id',
        '--aiexam.auth.registration.verification-required=false',
        '--aiexam.auth.challenge.dev-delivery.enabled=true',
        '--aiexam.auth.dev-accounts.enabled=true',
        '--aiexam.auth.dev-seed-username-login.enabled=true',
        "--aiexam.runtime.commit=$sourceCommit"
    ) -WorkingDirectory $backendRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr

    Wait-HttpReady -Url 'http://127.0.0.1:8000/api/v1/health' -Process $backendProcess

    $env:VITE_API_BASE_URL = '/api/v1'
    $env:VITE_RUNTIME_COMMIT = $sourceCommit
    $env:VITE_PLATFORM_MOCK = 'false'
    $env:VITE_PRACTICE_MOCK = 'false'
    $env:VITE_ADMIN_QUESTION_MOCK = 'false'
    $env:VITE_EXAM_SIMULATOR_MOCK = 'false'

    $frontendProcess = Start-Process -FilePath 'npm.cmd' -ArgumentList @(
        'run',
        'dev',
        '--',
        '--host',
        '127.0.0.1',
        '--port',
        '5173',
        '--strictPort'
    ) -WorkingDirectory $frontendRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $frontendOut -RedirectStandardError $frontendErr

    Wait-HttpReady -Url 'http://127.0.0.1:5173/' -Process $frontendProcess
    $proxyCapabilities = Invoke-WebRequest -UseBasicParsing `
        -Uri 'http://127.0.0.1:5173/api/v1/auth/capabilities' -TimeoutSec 5
    if ($proxyCapabilities.StatusCode -ne 200) {
        throw "Frontend proxy capability check returned HTTP $($proxyCapabilities.StatusCode)."
    }
    $frontendCommit = Invoke-RestMethod -Uri 'http://127.0.0.1:5173/__runtime/commit' -TimeoutSec 5
    $backendCommit = Invoke-RestMethod -Uri 'http://127.0.0.1:8000/api/v1/runtime/commit' -TimeoutSec 5
    if ([string]$frontendCommit.commit -ne $sourceCommit -or
        [string]$backendCommit.data.commit -ne $sourceCommit) {
        throw 'Runtime commit verification failed.'
    }

    $backendListenerPid = (
        Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort 8000 -ErrorAction Stop |
        Select-Object -First 1 -ExpandProperty OwningProcess
    )
    $frontendListenerPid = (
        Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction Stop |
        Select-Object -First 1 -ExpandProperty OwningProcess
    )
    $mysqlListenerPid = (
        Get-NetTCPConnection -State Listen -LocalPort 33307 -ErrorAction Stop |
        Select-Object -First 1 -ExpandProperty OwningProcess
    )

    $state = [ordered]@{
        status = 'RUNNING'
        startedAt = (Get-Date).ToString('o')
        projectRoot = $projectRoot
        backend = [ordered]@{
            url = 'http://127.0.0.1:8000'
            commit = $sourceCommit
            processPid = $backendProcess.Id
            listenerPid = $backendListenerPid
            stdout = $backendOut
            stderr = $backendErr
        }
        frontend = [ordered]@{
            url = 'http://127.0.0.1:5173'
            commit = $sourceCommit
            processPid = $frontendProcess.Id
            listenerPid = $frontendListenerPid
            stdout = $frontendOut
            stderr = $frontendErr
        }
        mysql = [ordered]@{
            port = 33307
            listenerPid = $mysqlListenerPid
            schema = 'ai_exam_platform'
            principal = [string]$credential.username
        }
        developmentAccounts = @(
            'student / 123456',
            'admin / 123456',
            'question_reviewer / 123456'
        )
    }
    [System.IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json -Depth 6), $utf8NoBom)

    Write-Output "W0_LOCAL_RUNNING=$statePath"
    Write-Output "BACKEND_LISTENER_PID=$backendListenerPid"
    Write-Output "FRONTEND_LISTENER_PID=$frontendListenerPid"
    Write-Output "MYSQL_LISTENER_PID=$mysqlListenerPid"
    Write-Output 'DEV_ACCOUNTS=student/admin/question_reviewer password 123456'
}
catch {
    if ($null -ne $frontendProcess) {
        Stop-VerifiedFrontendProcessTree -RootProcess $frontendProcess `
            -ExpectedFrontendRoot $frontendRoot
    }
    if ($null -ne $backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
    }
    throw
}
finally {
    foreach ($name in $environmentNames) {
        Restore-EnvironmentValue -Name $name -Value $previousEnvironment[$name]
    }
}
