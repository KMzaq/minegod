param()
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputRoot = Join-Path $taskRoot 'external-mods/combat-compat'
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
# Official Modrinth release IDs, pinned for repeatable development verification.
# Architectury 13.0.11 is already supplied by the project's FTB runtime.
$versionIds = @('nS0Yahr5', 'VhIOvcXP', 'HJZB6bmA', 'izKINKFg', 'Zb6dytvm', 'uG7oHgw6', 'uhJhCT7X')
$records = @()
foreach ($id in $versionIds) {
    $version = Invoke-RestMethod -Uri ('https://api.modrinth.com/v2/version/' + $id)
    $file = @($version.files | Where-Object primary)[0]
    if ($file.filename -ne [IO.Path]::GetFileName($file.filename) -or !$file.url.StartsWith('https://cdn.modrinth.com/')) {
        throw 'Unexpected official release file path'
    }
    $destination = Join-Path $outputRoot $file.filename
    if (!(Test-Path -LiteralPath $destination)) { Invoke-WebRequest -Uri $file.url -OutFile $destination }
    $actual = (Get-FileHash -LiteralPath $destination -Algorithm SHA512).Hash.ToLowerInvariant()
    if ($actual -ne $file.hashes.sha512) { throw ('Release hash mismatch: ' + $file.filename) }
    $records += [pscustomobject]@{ projectId=$version.project_id; versionId=$id; version=$version.version_number; filename=$file.filename; url=$file.url; sha512=$actual; dependencies=$version.dependencies }
    Write-Output ($file.filename + ' verified')
}
$records | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $outputRoot 'manifest.json') -Encoding UTF8
