param([string]$Baseline = 'C:\Users\ADMIN\Desktop\markmar\server\backups\before-memory-stage05-20260916-223454-266')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baselinePath = (Resolve-Path -LiteralPath $Baseline).Path
if (!$baselinePath.StartsWith((Join-Path $taskRoot 'server\backups\before-memory-stage05-'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected baseline' }
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $baselinePath
$allowed = @(
    'mythai-ai-response\build.gradle','mythai-ai-response\gradle.properties',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\DialogueMemoryBridge.java',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\RecallSearch.java',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\MythAiResponseMod.java',
    'mythai-ai-response\src\main\templates\META-INF\neoforge.mods.toml',
    'mythictrpg-main\build.gradle','mythictrpg-main\gradle.properties',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\MythicTrpg.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\command\ActionLedgerCommands.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\ledger\ActionRecord.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\ledger\server\ActionLedgerService.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\mixin\LivingEntityLedgerMixin.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\mixin\ServerPlayerGameModeLedgerMixin.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\quest\QuestEvaluationGateway.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\quest\QuestRuntimeService.java',
    'mythictrpg-main\src\main\resources\mythictrpg.action-ledger.mixins.json',
    'mythictrpg-main\docs\AI_ACTION_INTEGRATION_GUIDE.md',
    'docs\ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md','docs\ACTION_OBSERVATION_CONTRACT_20260915.md',
    'docs\EPISODIC_MEMORY_UPGRADE_DESIGN_20260915.md','docs\LP_MEMORY_FOUNDATION_GUIDE.md','인수인계\PROJECT_HANDOFF.md')
$newExact = @('dev-tools\Test-MemoryStage05.ps1','docs\MEMORY_STAGE05_20260916.md',
    'docs\examples\action-detail.example.json','docs\examples\ai-derived-memory.example.json',
    'mythai-ai-response\src\test\java\com\sande\mythai\response\memory\DerivedMemoryTest.java',
    'mythai-ai-response\src\test\java\com\sande\mythictrpg\ai\experiencecontract\ObservedSummaryFixture.java')
foreach ($name in @('DerivedMemory','DerivedSettings','DerivedStore','DerivedService','HybridRetrieval','SemanticIndex','ObservedExperienceSummary')) {
    $newExact += 'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\' + $name + '.java'
}
foreach ($name in @('PlayerDetailMixin','ServerPlayerDetailMixin','CommonHooksDetailMixin')) {
    $newExact += 'mythictrpg-main\src\main\java\com\sande\mythictrpg\mixin\' + $name + '.java'
}
$prefixes = @('mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\ledger\detail\',
    'mythictrpg-main\src\test\java\com\sande\mythictrpg\gameplay\ledger\detail\')
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
        if ($before.ContainsKey($path) -or $path -eq 'docs\memory-stage05-changes-20260916.csv') { continue }
        if ($path -notin $newExact -and !@($prefixes | Where-Object { $path.StartsWith($_, [StringComparison]::OrdinalIgnoreCase) }).Count) { throw "Unexpected new file: $path" }
        $changes.Add([pscustomobject]@{Status='ADDED';Path=$path;Before='';After=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash})
    }
}
# The helper label was added immediately before this snapshot; its true prior hash is recorded by stage4.
$helper = 'dev-tools\Backup-ActionLedgerStage.ps1'
$priorHelper = @(Import-Csv -LiteralPath (Join-Path $taskRoot 'docs/experience-stage04-changes-20260915.csv') | Where-Object Path -eq $helper)
if ($priorHelper.Count -ne 1) { throw 'Missing prior helper hash' }
$changes.Add([pscustomobject]@{Status='MODIFIED';Path=$helper;Before=$priorHelper[0].After;After=(Get-FileHash -LiteralPath (Join-Path $taskRoot $helper) -Algorithm SHA256).Hash})
# Verify the authoritative quest/FTB/reward source is byte-for-byte unchanged after removing only the new read hooks.
foreach ($name in @('QuestRuntimeService','QuestEvaluationGateway')) {
    $relative = 'mythictrpg-main\src\main\java\com\sande\mythictrpg\quest\' + $name + '.java'
    $oldText = [IO.File]::ReadAllText((Join-Path (Join-Path $baselinePath 'workspace') $relative)).Replace("`r`n","`n")
    $newText = [IO.File]::ReadAllText((Join-Path $taskRoot $relative)).Replace("`r`n","`n")
    if ($name -eq 'QuestRuntimeService') {
        $newText = $newText.Replace("        com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents.quest(player, committed.orElseThrow());`n",'')
    } else {
        $newText = $newText.Replace("            com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents.evaluation(player,questId,evaluatorNpcId,score,false,`"NOT_ELIGIBLE`");`n",'')
        $newText = $newText.Replace("        com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents.evaluation(player,questId,evaluatorNpcId,score,true,`n                !issued.succeeded()?`"ISSUE_FAILED`":resolvedReward.choices().isEmpty()?`"ISSUE_ACCEPTED`":`"ISSUE_ACCEPTED_CHOICE_PENDING`");`n",'')
    }
    if ($oldText -cne $newText) { throw "Unexpected quest execution change: $name" }
}
Write-Output 'PASS quest/FTB/reward source unchanged except exact read-only detail attachments'
$game = Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.6.jar'
$ai = Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.6.jar'
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
            if ($entry.FullName -eq 'META-INF/MANIFEST.MF' -and $version -eq '0.1.6') {
                $reader = [IO.StreamReader]::new($entry.Open()); try { $priorManifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
                $reader = [IO.StreamReader]::new($next.Open()); try { $nextManifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
                if ($priorManifest.Replace('Implementation-Version: 0.1.5','Implementation-Version: 0.1.6') -cne $nextManifest) { throw 'Unexpected manifest change' }
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
        if ($version -eq '0.1.6' -and !$metadata.Contains('versionRange="[1.0.6,)"')) { throw 'AI minimum game mismatch' }
        if ($version -eq '1.0.6' -and !$metadata.Contains('config="mythictrpg.action-ledger.mixins.json"')) { throw 'Mixin metadata missing' }
        Write-Output "PASS $version previous entries unchanged: $equal/$($old.Entries.Count); changes restricted to explicit attachment classes/metadata"
    } finally { $zip.Dispose(); $old.Dispose() }
}
$gamePatterns = @('com/sande/mythictrpg/MythicTrpg*.class','com/sande/mythictrpg/command/ActionLedgerCommands*.class',
    'com/sande/mythictrpg/gameplay/ledger/ActionRecord*.class','com/sande/mythictrpg/gameplay/ledger/server/ActionLedgerService*.class',
    'com/sande/mythictrpg/mixin/*LedgerMixin*.class','com/sande/mythictrpg/quest/QuestRuntimeService*.class',
    'com/sande/mythictrpg/quest/QuestEvaluationGateway*.class','mythictrpg.action-ledger.mixins.json')
$gameNew = $gamePatterns + @('com/sande/mythictrpg/gameplay/ledger/detail/*','com/sande/mythictrpg/mixin/*DetailMixin*.class')
Compare-Jar $game (Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.5.jar') $gamePatterns $gameNew '1.0.6' @('com/sande/mythictrpg/gameplay/ledger/detail/DetailCapture.class')
$aiPatterns = @('com/sande/mythai/response/memory/DialogueMemoryBridge*.class','com/sande/mythai/response/memory/RecallSearch*.class','com/sande/mythai/response/MythAiResponseMod*.class')
$aiNew = $aiPatterns + @('com/sande/mythai/response/memory/Derived*.class','com/sande/mythai/response/memory/HybridRetrieval*.class',
    'com/sande/mythai/response/memory/SemanticIndex*.class','com/sande/mythai/response/memory/ObservedExperienceSummary*.class')
Compare-Jar $ai (Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.5.jar') $aiPatterns $aiNew '0.1.6' @('com/sande/mythai/response/memory/HybridRetrieval.class','com/sande/mythai/response/memory/DerivedStore.class')
$changes | Sort-Object Path | Export-Csv -LiteralPath (Join-Path $taskRoot 'docs/memory-stage05-changes-20260916.csv') -NoTypeInformation -Encoding UTF8
Write-Output "PASS protected baseline: $unchanged/$($rows.Count) unchanged; delta=$($changes.Count) including pre-snapshot helper. No deployment/server/model execution."
Get-FileHash -LiteralPath $game,$ai -Algorithm SHA256 | Format-List
