param(
  [string]$RuntimeRoot = 'F:\AIExamRuntime',
  [string]$QwenModel = 'F:\AIExamRuntime\models\qwen3-embedding-0.6b',
  [string]$PaddleCache = 'C:\Users\Dapper\.paddlex'
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$embedding = Join-Path $RuntimeRoot 'build-context\embedding'
$ocr = Join-Path $RuntimeRoot 'build-context\ocr'
Remove-Item $embedding,$ocr -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $embedding,$ocr,(Join-Path $embedding 'model'),(Join-Path $ocr 'model-cache') | Out-Null

Copy-Item (Join-Path $project 'docker\embedding\Dockerfile') (Join-Path $embedding 'Dockerfile')
Copy-Item (Join-Path $project 'docker\embedding\requirements.txt') (Join-Path $embedding 'requirements.txt')
Copy-Item (Join-Path $project 'docker\embedding\app.py') (Join-Path $embedding 'app.py')
Get-ChildItem $QwenModel -Force | Where-Object { $_.Name -ne '.cache' } | Copy-Item -Destination (Join-Path $embedding 'model') -Recurse -Force

Copy-Item (Join-Path $project 'docker\ocr\Dockerfile') (Join-Path $ocr 'Dockerfile')
Copy-Item (Join-Path $project 'docker\ocr\requirements.txt') (Join-Path $ocr 'requirements.txt')
Copy-Item (Join-Path $project 'scripts\ocr\paddle_ocr_server.py') (Join-Path $ocr 'paddle_ocr_server.py')
Copy-Item (Join-Path $PaddleCache 'official_models') (Join-Path $ocr 'model-cache') -Recurse -Force

Write-Output "Prepared offline model contexts under $RuntimeRoot\build-context"
