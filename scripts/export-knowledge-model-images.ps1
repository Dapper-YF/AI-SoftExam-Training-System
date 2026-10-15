param(
  [Parameter(Mandatory = $true)][string]$EmbeddingImage,
  [Parameter(Mandatory = $true)][string]$OcrImage,
  [string]$RuntimeRoot = 'F:\AIExamRuntime',
  [string]$Release = 'knowledge-models'
)

$output = Join-Path $RuntimeRoot 'images'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$tar = Join-Path $output "$Release.tar"
docker save --output $tar $EmbeddingImage $OcrImage
$hash = (Get-FileHash -LiteralPath $tar -Algorithm SHA256).Hash.ToLowerInvariant()
[pscustomobject]@{ release = $Release; file = $tar; sha256 = $hash; images = @($EmbeddingImage, $OcrImage) } |
  ConvertTo-Json | Set-Content -LiteralPath "$tar.manifest.json" -Encoding UTF8
Write-Output "Exported $tar"
