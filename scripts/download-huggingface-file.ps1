param(
  [Parameter(Mandatory = $true)][string]$Url,
  [Parameter(Mandatory = $true)][string]$Destination
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$target = [System.IO.Path]::GetFullPath($Destination)
$parent = [System.IO.Path]::GetDirectoryName($target)
[System.IO.Directory]::CreateDirectory($parent) | Out-Null
$existing = if (Test-Path -LiteralPath $target) { (Get-Item -LiteralPath $target).Length } else { 0 }

$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $true
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromMinutes(3)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('AIExamKnowledgeBase/1.0')
$chunkSize = 16MB
$buffer = New-Object byte[] (1024 * 1024)
while ($true) {
  $start = if (Test-Path -LiteralPath $target) { (Get-Item -LiteralPath $target).Length } else { 0 }
  $end = $start + $chunkSize - 1
  $completed = $false
  for ($attempt = 1; $attempt -le 5 -and -not $completed; $attempt++) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $Url)
    $request.Headers.Range = [System.Net.Http.Headers.RangeHeaderValue]::new($start, $end)
    try {
      $response = $client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
      $response.EnsureSuccessStatusCode()
      $input = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
      $output = [System.IO.File]::Open($target, [System.IO.FileMode]::Append, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
      try { while (($read = $input.Read($buffer, 0, $buffer.Length)) -gt 0) { $output.Write($buffer, 0, $read) } }
      finally { $output.Dispose(); $input.Dispose(); $response.Dispose() }
      $completed = $true
      Write-Output "Downloaded through $((Get-Item -LiteralPath $target).Length) bytes"
    } catch { if ($attempt -eq 5) { throw }; Start-Sleep -Seconds $attempt }
  }
  if (-not $completed) { throw 'Unable to download range' }
  if ($response.Headers.Contains('Content-Range')) {
    $range = $response.Headers.GetValues('Content-Range') | Select-Object -First 1
    if ($range -match '/(\d+)$' -and (Get-Item -LiteralPath $target).Length -ge [int64]$Matches[1]) { break }
  } elseif ((Get-Item -LiteralPath $target).Length -lt $end + 1) { break }
}
$client.Dispose(); $handler.Dispose()
Write-Output "Downloaded $target ($((Get-Item -LiteralPath $target).Length) bytes)"
