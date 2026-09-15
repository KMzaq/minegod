param([switch]$VerifyOnly)
$ErrorActionPreference = 'Stop'
$releaseRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$serverDir = Join-Path $releaseRoot 'server'
$lpDir = Join-Path $serverDir 'backups/LP'
$expected = @{
    'mythai_ai_response-0.1.0.jar' = 'DBC7C095943817248E05CD86B58966DE8DF74075A54BB8474134AE7C68D8147F'
    'mythaiaicontent-0.1.0.jar' = '38B8461C13619569E477BB24AB85F9BFAD317998402FEA3E5ACF0019C6CC8CBA'
}
foreach ($name in $expected.Keys) {
    $source = Join-Path $lpDir "mods/$name"
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $expected[$name]) { throw "LP hash mismatch: $name" }
}
$coreHash = '51EF22FAF7F2149183CA58173FBCDBBB3A9C859A9E2198331542B13CFBC18EE8'
foreach ($relative in @('mods/mythictrpg-1.0.0.jar','client-required-mods/mythictrpg-1.0.0.jar')) {
    if ((Get-FileHash -LiteralPath (Join-Path $serverDir $relative)).Hash -ne $coreHash) {
        throw "Game JAR differs from tested LP runtime. Review compatibility before restoring: $relative"
    }
}
if ($VerifyOnly) { Write-Output 'LP hashes and runtime pair verified. No files changed.'; return }
$java = @(Get-Process -Name java,javaw -ErrorAction SilentlyContinue)
if ($java.Count -gt 0) {
    $processes = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    foreach ($process in $processes) {
        if (-not $process.CommandLine -or $process.CommandLine -match 'forgeserver|server_args|win_args|runServer') {
            throw 'A server may be running. Save and stop its console before restoring LP.'
        }
    }
}
$backup = Join-Path $serverDir ('backups/before-LP-restore-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backup | Out-Null
$configPath = Join-Path $serverDir 'config/mythictrpg/ai-dialogue.json'
# Validate configuration before any replacement.
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
Copy-Item -LiteralPath $configPath -Destination (Join-Path $backup 'ai-dialogue.json')
foreach ($name in $expected.Keys) {
    $target = Join-Path $serverDir "mods/$name"
    Copy-Item -LiteralPath $target -Destination (Join-Path $backup $name)
}
foreach ($name in $expected.Keys) {
    $target = Join-Path $serverDir "mods/$name"
    Copy-Item -LiteralPath (Join-Path $lpDir "mods/$name") -Destination $target -Force
    if ((Get-FileHash -LiteralPath $target).Hash -ne $expected[$name]) { throw "Copied hash mismatch: $target" }
}
$config.maxOutputTokens = 260
$config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $configPath -Encoding utf8
Write-Output "LP restored. Previous files preserved at $backup. World and memory files retained. Server remains stopped."
