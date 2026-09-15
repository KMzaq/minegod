param([switch]$VerifyOnly, [string]$Baseline = '')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function Verify-Manifest([string]$Folder) {
    $rows = @(Import-Csv -LiteralPath (Join-Path $Folder 'file-manifest.csv'))
    foreach ($row in $rows) {
        $file = [IO.Path]::GetFullPath((Join-Path (Join-Path $Folder 'workspace') $row.Path))
        if (!$file.StartsWith($Folder + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid manifest path' }
        if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $row.SHA256) { throw "Backup mismatch: $file" }
    }
    [pscustomobject]@{ Folder = $Folder; Files = $rows.Count; Verified = $true }
}
$audit = @()
foreach ($relative in @('server/backups/LP-source-20260912','server/backups/before-memory-rumor-foundation-20260913-223016-887')) {
    $audit += Verify-Manifest (Join-Path $taskRoot $relative)
}
$lpFiles = @{
    'server/backups/LP/mods/mythai_ai_response-0.1.0.jar' = 'DBC7C095943817248E05CD86B58966DE8DF74075A54BB8474134AE7C68D8147F'
    'server/backups/LP/mods/mythaiaicontent-0.1.0.jar' = '38B8461C13619569E477BB24AB85F9BFAD317998402FEA3E5ACF0019C6CC8CBA'
    'server/backups/LP/runtime-reference/mythictrpg-1.0.0.jar' = '51EF22FAF7F2149183CA58173FBCDBBB3A9C859A9E2198331542B13CFBC18EE8'
}
foreach ($relative in $lpFiles.Keys) {
    if ((Get-FileHash -LiteralPath (Join-Path $taskRoot $relative) -Algorithm SHA256).Hash -ne $lpFiles[$relative]) { throw "LP changed: $relative" }
}
$audit += [pscustomobject]@{ Folder = 'LP original JARs'; Files = 3; Verified = $true }
if ($Baseline) { $audit += Verify-Manifest ([IO.Path]::GetFullPath($Baseline)) }
if ($VerifyOnly) { $audit; return }
# Never overwrite LP or an earlier baseline. No restore/deploy/server commands in this script.
$backupRoot = Join-Path $taskRoot ('server/backups/before-recall-stage01-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backupRoot | Out-Null
$taskFiles = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($relative in @('mythictrpg-main','mythai-ai-response','mythai-ai-content-registry','ftb-quests','docs','인수인계','dev-tools')) {
    Get-ChildItem -LiteralPath (Join-Path $taskRoot $relative) -Recurse -File -Force |
        Where-Object { $_.FullName -notmatch '[\\/](\.git|\.gradle|\.idea|run|run-data|build|node_modules)[\\/]' } |
        ForEach-Object { [void]$taskFiles.Add($_.FullName) }
}
foreach ($relative in @('mine/mine/src','mine/mine/gradle','server/mods','server/client-required-mods','server/config',
        'server/world/mythictrpg-ai-memory-lp-v1','server/world/mythictrpg-ai-test-logs','server/world/datapacks',
        'mythictrpg-main/build/libs','mythai-ai-response/build/libs','mythai-ai-content-registry/build/libs','mine/mine/build/libs')) {
    $folder = Join-Path $taskRoot $relative
    if (Test-Path -LiteralPath $folder) { Get-ChildItem -LiteralPath $folder -Recurse -File | ForEach-Object { [void]$taskFiles.Add($_.FullName) } }
}
foreach ($relative in @('AGENTS.md','mine/mine/build.gradle','mine/mine/gradle.properties','mine/mine/settings.gradle',
        'mine/mine/gradlew','mine/mine/gradlew.bat','server/server.properties','server/start-neoforge-ai-server.bat')) {
    $file = Join-Path $taskRoot $relative
    if (Test-Path -LiteralPath $file -PathType Leaf) { [void]$taskFiles.Add($file) }
}
$manifest = foreach ($taskFile in ($taskFiles | Sort-Object)) {
    $relative = $taskFile.Substring($taskRoot.Length + 1)
    $destination = Join-Path (Join-Path $backupRoot 'workspace') $relative
    $sourceHash = (Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath $taskFile -Destination $destination
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sourceHash -or
        (Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash -ne $sourceHash) { throw "Unstable source/backup: $relative" }
    [pscustomobject]@{ Path = $relative; SHA256 = $sourceHash }
}
$manifest | Export-Csv -LiteralPath (Join-Path $backupRoot 'file-manifest.csv') -Encoding UTF8 -NoTypeInformation
$audit | Export-Csv -LiteralPath (Join-Path $backupRoot 'lp-audit.csv') -Encoding UTF8 -NoTypeInformation
# A second full comparison catches changes across the copying interval.
foreach ($row in $manifest) {
    if ((Get-FileHash -LiteralPath (Join-Path $taskRoot $row.Path) -Algorithm SHA256).Hash -ne $row.SHA256) { throw "Source changed during snapshot: $($row.Path)" }
}
$audit
Verify-Manifest $backupRoot
