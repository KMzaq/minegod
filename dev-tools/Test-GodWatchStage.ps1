param([string]$Baseline = 'C:\Users\ADMIN\Desktop\markmar\server\backups\before-god-watch-stage03-20260915-210456-433')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baselinePath = (Resolve-Path -LiteralPath $Baseline).Path
if (!$baselinePath.StartsWith((Join-Path $taskRoot 'server\backups\before-god-watch-stage03-'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected baseline' }
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $baselinePath
$allowed = @('mythictrpg-main\build.gradle','mythictrpg-main\gradle.properties',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\ledger\AsyncActionLedger.java',
    'docs\ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md','docs\ACTION_OBSERVATION_CONTRACT_20260915.md',
    'docs\LP_MEMORY_FOUNDATION_GUIDE.md','인수인계\PROJECT_HANDOFF.md')
$newExact = @('docs\GOD_WATCH_STAGE03_20260915.md','dev-tools\Test-GodWatchStage.ps1')
$prefixes = @('mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\watch\',
    'mythictrpg-main\src\test\java\com\sande\mythictrpg\gameplay\watch\')
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
foreach ($relative in @('mythictrpg-main','mythai-ai-response','mythai-ai-content-registry','ftb-quests','docs','인수인계','dev-tools',
        'server/mods','server/client-required-mods','server/config','mine/mine/src','server/world/mythictrpg-ai-memory-lp-v1',
        'server/world/mythictrpg-ai-test-logs','server/world/datapacks')) {
    $folder = Join-Path $taskRoot $relative
    if (!(Test-Path -LiteralPath $folder)) { continue }
    foreach ($file in (Get-ChildItem -LiteralPath $folder -Recurse -File -Force | Where-Object { $_.FullName -notmatch '[\\/](\.git|\.gradle|\.idea|run|run-data|build|node_modules)[\\/]' })) {
        $path = $file.FullName.Substring($taskRoot.Length + 1)
        if ($before.ContainsKey($path) -or $path -eq 'docs\god-watch-stage03-changes-20260915.csv') { continue }
        if ($path -notin $newExact -and !@($prefixes | Where-Object { $path.StartsWith($_, [StringComparison]::OrdinalIgnoreCase) }).Count) { throw "Unexpected new file: $path" }
        $changes.Add([pscustomobject]@{Status='ADDED';Path=$path;Before='';After=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash})
    }
}
# Only the snapshot label helper was edited before taking the stage3 snapshot. Preserve its earlier hash too.
$helper = 'dev-tools\Backup-ActionLedgerStage.ps1'
$priorHelper = Join-Path $taskRoot ('server\backups\before-action-ledger-stage02-20260915-195314-636\workspace\' + $helper)
$changes.Add([pscustomobject]@{Status='MODIFIED';Path=$helper;Before=(Get-FileHash -LiteralPath $priorHelper -Algorithm SHA256).Hash;After=(Get-FileHash -LiteralPath (Join-Path $taskRoot $helper) -Algorithm SHA256).Hash})
$candidate = Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.4.jar'
$previous = Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.3.jar'
$others = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'server/mods') -File -Filter '*.jar' | Where-Object Name -notlike 'mythictrpg-*' | ForEach-Object FullName)
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths (@($candidate) + $others)
$devAi = Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.4.jar'
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths (@($candidate,$devAi) + @($others | Where-Object { [IO.Path]::GetFileName($_) -notlike 'mythai_ai_response-*' }))
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Entry-Hash($entry) {
    $stream = $entry.Open(); $sha = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha.ComputeHash($stream)) } finally { $stream.Dispose(); $sha.Dispose() }
}
$zip = [IO.Compression.ZipFile]::OpenRead($candidate); $old = [IO.Compression.ZipFile]::OpenRead($previous)
try {
    if (@($zip.Entries | Group-Object FullName | Where-Object Count -gt 1).Count) { throw 'Duplicate JAR entries' }
    $equalEntries = 0
    foreach ($entry in $old.Entries) {
        $next = $zip.GetEntry($entry.FullName)
        if ($null -eq $next) { throw "Missing old JAR entry: $($entry.FullName)" }
        if ((Entry-Hash $entry) -eq (Entry-Hash $next)) { $equalEntries++; continue }
        if ($entry.FullName -ne 'META-INF/neoforge.mods.toml' -and $entry.FullName -notlike 'com/sande/mythictrpg/gameplay/ledger/AsyncActionLedger*.class') {
            throw "Unexpected binary change: $($entry.FullName)"
        }
    }
    foreach ($entry in $zip.Entries) {
        if ($null -ne $old.GetEntry($entry.FullName)) { continue }
        if ($entry.FullName -notlike 'com/sande/mythictrpg/gameplay/watch/*' -and $entry.FullName -notlike 'com/sande/mythictrpg/gameplay/ledger/AsyncActionLedger*.class') {
            throw "Unexpected new JAR entry: $($entry.FullName)"
        }
    }
    foreach ($name in @('AsyncGodWatch','GameWatchGateway','WatchStore','WatchJournal','WatchContract')) {
        if ($null -eq $zip.GetEntry("com/sande/mythictrpg/gameplay/watch/$name.class")) { throw "Missing watch class: $name" }
    }
    $reader = [IO.StreamReader]::new($zip.GetEntry('META-INF/neoforge.mods.toml').Open())
    try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($metadata -notmatch 'version="1.0.4"' -or $metadata -notmatch 'config="mythictrpg.action-ledger.mixins.json"') { throw 'Version/mixin metadata mismatch' }
    Write-Output "PASS old JAR entries unchanged: $equalEntries/$($old.Entries.Count); only FIFO fence/new watch package/version changed"
} finally { $zip.Dispose(); $old.Dispose() }
$changes | Sort-Object Path | Export-Csv -LiteralPath (Join-Path $taskRoot 'docs/god-watch-stage03-changes-20260915.csv') -NoTypeInformation -Encoding UTF8
Write-Output "PASS protected baseline: $unchanged/$($rows.Count) unchanged; delta=$($changes.Count) including pre-snapshot helper. No deployment/server/model execution."
Get-FileHash -LiteralPath $candidate -Algorithm SHA256 | Format-List
