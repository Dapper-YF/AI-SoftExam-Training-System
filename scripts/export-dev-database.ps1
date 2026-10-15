param(
  [string]$MysqlHost = "127.0.0.1",
  [int]$MysqlPort = 33307,
  [string]$MysqlUsername = "aiexam_app",
  [string]$Database = "ai_exam_platform",
  [string]$Output = "docker/mysql/init/001-ai_exam_platform-latest.sql"
)

$ErrorActionPreference = "Stop"

if (-not $env:MYSQL_PASSWORD) {
  $secure = Read-Host "MYSQL_PASSWORD" -AsSecureString
  $env:MYSQL_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  )
}

$mysqlDump = Get-Command mysqldump -ErrorAction SilentlyContinue
if (-not $mysqlDump) {
  throw "mysqldump.exe was not found in PATH. Add the MySQL bin directory first."
}

$outputPath = Join-Path (Get-Location) $Output
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null

& $mysqlDump.Source `
  --protocol=tcp `
  --host=$MysqlHost `
  --port=$MysqlPort `
  --user=$MysqlUsername `
  --password=$env:MYSQL_PASSWORD `
  --single-transaction `
  --routines `
  --events `
  --triggers `
  --set-gtid-purged=OFF `
  --databases $Database `
  --result-file=$outputPath

if ($LASTEXITCODE -ne 0) {
  throw "mysqldump failed with exit code $LASTEXITCODE"
}

Write-Host "Latest development database snapshot exported to $outputPath"
