$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
& (Join-Path $PSScriptRoot 'Test-LPBuild.ps1') -ResponseJar (Join-Path $taskRoot 'server/mods/mythai_ai_response-0.1.0.jar') -ContentJar (Join-Path $taskRoot 'server/mods/mythaiaicontent-0.1.0.jar')
& (Join-Path $PSScriptRoot 'Restore-LP.ps1') -VerifyOnly
$backupRoot = Join-Path $taskRoot ('server/backups/before-memory-rumor-foundation-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backupRoot | Out-Null
$taskFiles = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($relative in @('mythictrpg-main','mythai-ai-response','mythai-ai-content-registry','docs','인수인계','dev-tools')) {
    Get-ChildItem -LiteralPath (Join-Path $taskRoot $relative) -Recurse -File -Force |
        Where-Object { $_.FullName -notmatch '[\\/](\.git|\.gradle|\.idea|run|run-data|build)[\\/]' } |
        ForEach-Object { [void]$taskFiles.Add($_.FullName) }
}
foreach ($relative in @('mine/mine/src/main/java/com/sande/mythictrpg/ai','server/mods','server/client-required-mods','server/config/mythictrpg','mythictrpg-main/build/libs','mythai-ai-response/build/libs','mythai-ai-content-registry/build/libs','mine/mine/build/libs')) {
    Get-ChildItem -LiteralPath (Join-Path $taskRoot $relative) -Recurse -File |
        ForEach-Object { [void]$taskFiles.Add($_.FullName) }
}
$manifest = foreach ($taskFile in ($taskFiles | Sort-Object)) {
    $relative = $taskFile.Substring($taskRoot.Length + 1)
    $destination = Join-Path (Join-Path $backupRoot 'workspace') $relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath $taskFile -Destination $destination
    $sourceHash = (Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sourceHash) { throw "Backup verification failed: $relative" }
    [pscustomobject]@{ Path = $relative; SHA256 = $sourceHash }
}
$manifest | Export-Csv -LiteralPath (Join-Path $backupRoot 'file-manifest.csv') -Encoding UTF8 -NoTypeInformation
[pscustomobject]@{ Backup = $backupRoot; Files = $manifest.Count; Verified = $true }
