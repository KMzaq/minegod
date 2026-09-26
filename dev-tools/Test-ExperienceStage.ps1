param([string]$Baseline = 'C:\Users\ADMIN\Desktop\markmar\server\backups\before-experience-stage04-20260915-213244-306')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baselinePath = (Resolve-Path -LiteralPath $Baseline).Path
if (!$baselinePath.StartsWith((Join-Path $taskRoot 'server\backups\before-experience-stage04-'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected baseline' }
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $baselinePath
$allowed = @(
    'mythai-ai-response\build.gradle','mythai-ai-response\gradle.properties','mythai-ai-response\memory-foundation-overlay.gradle',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\DialogueMemoryBridge.java',
    'mythai-ai-response\src\main\templates\META-INF\neoforge.mods.toml',
    'mythictrpg-main\build.gradle','mythictrpg-main\gradle.properties',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\MythicTrpg.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\command\MythAdminCommands.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\command\ActionLedgerCommands.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\ledger\server\ActionLedgerCapture.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\ledger\server\ActionLedgerService.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\watch\AsyncGodWatch.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\watch\GameWatchGateway.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\watch\WatchStore.java',
    'mythictrpg-main\src\test\java\com\sande\mythictrpg\gameplay\watch\GodWatchTest.java',
    'mythictrpg-main\docs\AI_ACTION_INTEGRATION_GUIDE.md',
    'docs\ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md','docs\ACTION_OBSERVATION_CONTRACT_20260915.md',
    'docs\LP_MEMORY_FOUNDATION_GUIDE.md','인수인계\PROJECT_HANDOFF.md')
$newExact = @('dev-tools\Test-ExperienceStage.ps1','docs\EXPERIENCE_STAGE04_20260915.md','docs\examples\watch-trial.example.json',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\ExperienceMemory.java',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\ExperienceHistory.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\command\WatchTrialCommands.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\watch\GodWatchRuntime.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\watch\WatchTrialSettings.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\watch\WatchTrialRules.java')
$prefixes = @('mythictrpg-main\src\main\java\com\sande\mythictrpg\ai\experiencecontract\',
    'mythictrpg-main\src\test\java\com\sande\mythictrpg\ai\experiencecontract\',
    'mythai-ai-response\src\test\java\com\sande\mythictrpg\ai\experiencecontract\')
$rows = @(Import-Csv -LiteralPath (Join-Path $baselinePath 'file-manifest.csv'))
$before = @{}; $changes = [Collections.Generic.List[object]]::new(); $unchanged = 0
foreach ($row in $rows) {
    $before[$row.Path] = $row.SHA256; $file = Join-Path $taskRoot $row.Path
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
        if ($before.ContainsKey($path) -or $path -eq 'docs\experience-stage04-changes-20260915.csv') { continue }
        if ($path -notin $newExact -and !@($prefixes | Where-Object { $path.StartsWith($_, [StringComparison]::OrdinalIgnoreCase) }).Count) { throw "Unexpected new file: $path" }
        $changes.Add([pscustomobject]@{Status='ADDED';Path=$path;Before='';After=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash})
    }
}
# Helper was prepared before this snapshot; preserve the earlier stage3 version hash as well.
$helper = 'dev-tools\Backup-ActionLedgerStage.ps1'
$priorHelper = Join-Path $taskRoot ('server\backups\before-god-watch-stage03-20260915-210456-433\workspace\' + $helper)
$changes.Add([pscustomobject]@{Status='MODIFIED';Path=$helper;Before=(Get-FileHash -LiteralPath $priorHelper -Algorithm SHA256).Hash;After=(Get-FileHash -LiteralPath (Join-Path $taskRoot $helper) -Algorithm SHA256).Hash})
$game = Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.5.jar'
$ai = Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.5.jar'
$others = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'server/mods') -File -Filter '*.jar' | Where-Object Name -notlike 'mythictrpg-*' | ForEach-Object FullName)
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths (@($game) + $others)
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths (@($game,$ai) + @($others | Where-Object { [IO.Path]::GetFileName($_) -notlike 'mythai_ai_response-*' }))
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Entry-Hash($entry) {
    $stream = $entry.Open(); $sha = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha.ComputeHash($stream)) } finally { $stream.Dispose(); $sha.Dispose() }
}
function Compare-Jar($candidate, $previous, $allowedPatterns, $newPatterns, $version, $mustContain) {
    $zip = [IO.Compression.ZipFile]::OpenRead($candidate); $old = [IO.Compression.ZipFile]::OpenRead($previous)
    try {
        if (@($zip.Entries | Group-Object FullName | Where-Object Count -gt 1).Count) { throw 'Duplicate JAR entries' }
        $equal = 0
        foreach ($entry in $old.Entries) {
            $next = $zip.GetEntry($entry.FullName)
            if ($null -eq $next) { throw "Missing old entry: $($entry.FullName)" }
            if ((Entry-Hash $entry) -eq (Entry-Hash $next)) { $equal++; continue }
            if ($entry.FullName -eq 'META-INF/MANIFEST.MF' -and $version -eq '0.1.5') {
                $reader = [IO.StreamReader]::new($entry.Open()); try { $priorManifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
                $reader = [IO.StreamReader]::new($next.Open()); try { $nextManifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
                if ($priorManifest.Replace('Implementation-Version: 0.1.4','Implementation-Version: 0.1.5') -cne $nextManifest) { throw 'Unexpected manifest change' }
                continue
            }
            if ($entry.FullName -ne 'META-INF/neoforge.mods.toml' -and !@($allowedPatterns | Where-Object { $entry.FullName -like $_ }).Count) { throw "Unexpected binary change: $($entry.FullName)" }
        }
        foreach ($entry in $zip.Entries) {
            if ($null -ne $old.GetEntry($entry.FullName)) { continue }
            if (!@($newPatterns | Where-Object { $entry.FullName -like $_ }).Count) { throw "Unexpected new entry: $($entry.FullName)" }
        }
        foreach ($name in $mustContain) { if ($null -eq $zip.GetEntry($name)) { throw "Missing class: $name" } }
        $reader = [IO.StreamReader]::new($zip.GetEntry('META-INF/neoforge.mods.toml').Open())
        try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if (!$metadata.Contains('version="' + $version + '"')) { throw 'Version mismatch' }
        if ($version -eq '0.1.5' -and !$metadata.Contains('versionRange="[1.0.5,)"')) { throw 'AI minimum game dependency mismatch' }
        if ($version -eq '1.0.5' -and !$metadata.Contains('config="mythictrpg.action-ledger.mixins.json"')) { throw 'Mixin metadata missing' }
        Write-Output "PASS $version previous entries unchanged: $equal/$($old.Entries.Count); changes restricted to explicit attachment classes/metadata"
    } finally { $zip.Dispose(); $old.Dispose() }
}
$gamePatterns = @('com/sande/mythictrpg/MythicTrpg*.class','com/sande/mythictrpg/command/MythAdminCommands*.class',
    'com/sande/mythictrpg/command/ActionLedgerCommands*.class','com/sande/mythictrpg/gameplay/ledger/server/ActionLedgerCapture*.class',
    'com/sande/mythictrpg/gameplay/ledger/server/ActionLedgerService*.class','com/sande/mythictrpg/gameplay/watch/AsyncGodWatch*.class',
    'com/sande/mythictrpg/gameplay/watch/GameWatchGateway*.class','com/sande/mythictrpg/gameplay/watch/WatchStore*.class')
$gameNew = $gamePatterns + @('com/sande/mythictrpg/ai/experiencecontract/*','com/sande/mythictrpg/gameplay/watch/WatchTrial*.class',
    'com/sande/mythictrpg/gameplay/watch/GodWatchRuntime*.class','com/sande/mythictrpg/command/WatchTrialCommands*.class')
Compare-Jar $game (Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.4.jar') $gamePatterns $gameNew '1.0.5' @('com/sande/mythictrpg/ai/experiencecontract/ExperienceAccess.class')
$aiPatterns = @('com/sande/mythai/response/memory/DialogueMemoryBridge*.class','com/sande/mythictrpg/ai/AiTestDialogueAdapter*.class',
    'com/sande/mythictrpg/ai/DialogueTurnDirective*.class')
Compare-Jar $ai (Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.4.jar') $aiPatterns ($aiPatterns + @('com/sande/mythai/response/memory/Experience*.class')) '0.1.5' @('com/sande/mythai/response/memory/ExperienceMemory.class','com/sande/mythai/response/memory/ExperienceHistory.class')
$changes | Sort-Object Path | Export-Csv -LiteralPath (Join-Path $taskRoot 'docs/experience-stage04-changes-20260915.csv') -NoTypeInformation -Encoding UTF8
Write-Output "PASS protected baseline: $unchanged/$($rows.Count) unchanged; delta=$($changes.Count) including pre-snapshot helper. No deployment/server/model execution."
Get-FileHash -LiteralPath $game,$ai -Algorithm SHA256 | Format-List
