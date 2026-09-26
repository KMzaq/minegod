param([string]$Baseline = 'C:\Users\ADMIN\Desktop\markmar\server\backups\before-action-ledger-stage02-20260915-195314-636')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baselinePath = (Resolve-Path -LiteralPath $Baseline).Path
if (!$baselinePath.StartsWith((Join-Path $taskRoot 'server\backups\before-action-ledger-stage02-'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected baseline' }
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $baselinePath
$allowed = @(
    'mythictrpg-main\build.gradle', 'mythictrpg-main\gradle.properties',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\MythicTrpg.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\command\MythAdminCommands.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\observation\GameplayObservationAdapters.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\observation\MatureCropHarvestClassifier.java',
    'mythictrpg-main\src\main\templates\META-INF\neoforge.mods.toml',
    'mythai-ai-response\build.gradle', 'docs\ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md',
    'docs\ACTION_OBSERVATION_CONTRACT_20260915.md', 'docs\LP_MEMORY_FOUNDATION_GUIDE.md', '인수인계\PROJECT_HANDOFF.md')
$rows = @(Import-Csv -LiteralPath (Join-Path $baselinePath 'file-manifest.csv'))
$before = @{}; $changes = [Collections.Generic.List[object]]::new(); $unchanged = 0
foreach ($row in $rows) {
    $before[$row.Path] = $row.SHA256
    $file = Join-Path $taskRoot $row.Path
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Protected file removed: $($row.Path)" }
    $after = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
    if ($after -eq $row.SHA256) { $unchanged++; continue }
    if ($row.Path -notin $allowed) { throw "Unexpected change: $($row.Path)" }
    $changes.Add([pscustomobject]@{Status='MODIFIED';Path=$row.Path;Before=$row.SHA256;After=$after})
}
$newExact = @('mythictrpg-main\src\main\java\com\sande\mythictrpg\command\ActionLedgerCommands.java',
    'mythictrpg-main\src\main\resources\mythictrpg.action-ledger.mixins.json',
    'docs\examples\action-ledger.example.json', 'docs\ACTION_LEDGER_STAGE02_20260915.md',
    'dev-tools\Test-ActionLedgerRelease.ps1')
$newPrefixes = @('mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\ledger\',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\mixin\',
    'mythictrpg-main\src\test\java\com\sande\mythictrpg\gameplay\ledger\')
foreach ($relative in @('mythictrpg-main','mythai-ai-response','mythai-ai-content-registry','ftb-quests','docs','인수인계','dev-tools',
        'server/mods','server/client-required-mods','server/config','mine/mine/src','server/world/mythictrpg-ai-memory-lp-v1',
        'server/world/mythictrpg-ai-test-logs','server/world/datapacks')) {
    $folder = Join-Path $taskRoot $relative
    if (!(Test-Path -LiteralPath $folder)) { continue }
    foreach ($file in (Get-ChildItem -LiteralPath $folder -Recurse -File -Force | Where-Object { $_.FullName -notmatch '[\\/](\.git|\.gradle|\.idea|run|run-data|build|node_modules)[\\/]' })) {
        $path = $file.FullName.Substring($taskRoot.Length + 1)
        if ($before.ContainsKey($path) -or $path -eq 'docs\action-ledger-stage02-changes-20260915.csv') { continue }
        if ($path -notin $newExact -and !@($newPrefixes | Where-Object { $path.StartsWith($_, [StringComparison]::OrdinalIgnoreCase) }).Count) { throw "Unexpected new file: $path" }
        $changes.Add([pscustomobject]@{Status='ADDED';Path=$path;Before='';After=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash})
    }
}
$candidate = Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.3.jar'
$others = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'server/mods') -File -Filter '*.jar' | Where-Object Name -notlike 'mythictrpg-*' | ForEach-Object FullName)
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths (@($candidate) + $others)
$devAi = Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.4.jar'
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths (@($candidate,$devAi) + @($others | Where-Object { [IO.Path]::GetFileName($_) -notlike 'mythai_ai_response-*' }))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($candidate)
try {
    if (@($zip.Entries | Group-Object FullName | Where-Object Count -gt 1).Count) { throw 'Duplicate JAR entries' }
    foreach ($entry in @('com/sande/mythictrpg/gameplay/ledger/ActionLedgerStore.class',
            'com/sande/mythictrpg/gameplay/ledger/AsyncActionLedger.class',
            'com/sande/mythictrpg/mixin/ServerPlayerGameModeLedgerMixin.class',
            'com/sande/mythictrpg/mixin/LivingEntityLedgerMixin.class',
            'com/sande/mythictrpg/command/ActionLedgerCommands.class','mythictrpg.action-ledger.mixins.json')) {
        if ($null -eq $zip.GetEntry($entry)) { throw "Missing production entry: $entry" }
    }
    $reader = [IO.StreamReader]::new($zip.GetEntry('META-INF/neoforge.mods.toml').Open())
    try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($metadata -notmatch 'version="1.0.3"' -or $metadata -notmatch 'config="mythictrpg.action-ledger.mixins.json"') { throw 'Version/mixin metadata mismatch' }
} finally { $zip.Dispose() }
$changes | Sort-Object Path | Export-Csv -LiteralPath (Join-Path $taskRoot 'docs/action-ledger-stage02-changes-20260915.csv') -NoTypeInformation -Encoding UTF8
Write-Output "PASS preserved baseline: $unchanged/$($rows.Count) unchanged; permitted source/docs delta=$($changes.Count). No deployment/server/model execution."
Get-FileHash -LiteralPath $candidate -Algorithm SHA256 | Format-List
