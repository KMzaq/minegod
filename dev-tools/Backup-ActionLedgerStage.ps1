param([switch]$VerifyOnly, [string]$Baseline = '',
    [ValidateSet('action-ledger-stage02', 'god-watch-stage03', 'experience-stage04', 'memory-stage05')][string]$SnapshotLabel = 'action-ledger-stage02')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
# Reuse the fixed LP hashes and manifest verifier without changing any prior backup.
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $Baseline
if ($VerifyOnly) { return }
$backupRoot = Join-Path $taskRoot ('server/backups/before-' + $SnapshotLabel + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
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
foreach ($row in $manifest) {
    if ((Get-FileHash -LiteralPath (Join-Path $taskRoot $row.Path) -Algorithm SHA256).Hash -ne $row.SHA256) { throw "Source changed during snapshot: $($row.Path)" }
}
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $backupRoot
