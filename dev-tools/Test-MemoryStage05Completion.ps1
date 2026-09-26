param([string]$Baseline = 'C:\Users\ADMIN\Desktop\markmar\server\backups\before-memory-stage05-20260917-053507-275')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baselinePath = (Resolve-Path -LiteralPath $Baseline).Path
if (!$baselinePath.StartsWith((Join-Path $taskRoot 'server\backups\before-memory-stage05-'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected baseline' }
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $baselinePath
$allowed = @('mythai-ai-response\build.gradle','mythai-ai-response\gradle.properties',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\DerivedMemory.java',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\DerivedService.java',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\DialogueMemoryBridge.java',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\MemoryRecallPolicy.java',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\structure\OllamaStructureVisionProvider.java',
    'docs\ACTION_OBSERVATION_CONTRACT_20260915.md','docs\ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md',
    'docs\EPISODIC_MEMORY_UPGRADE_DESIGN_20260915.md','docs\LP_MEMORY_FOUNDATION_GUIDE.md','인수인계\PROJECT_HANDOFF.md')
$newExact = @('dev-tools\Test-MemoryStage05Completion.ps1','docs\MEMORY_STAGE05_COMPLETION_20260917.md',
    'docs\examples\ai-memory-index.example.json',
    'mythai-ai-response\src\test\java\com\sande\mythai\response\memory\MemoryIndexRuntimeTest.java',
    'mythai-ai-response\src\test\java\com\sande\mythictrpg\ai\ModelAdmissionSchedulerFixture.java')
foreach ($name in @('ModelAdmission','MemoryIndexSettings','MemoryIndexRow','MemoryIndexStore','MemoryIndexRuntime','OllamaMemoryBackend')) {
    $newExact += 'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\' + $name + '.java'
}
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
        if ($before.ContainsKey($path) -or $path -eq 'docs\memory-stage05-completion-changes-20260917.csv') { continue }
        if ($path -notin $newExact) { throw "Unexpected new file: $path" }
        $changes.Add([pscustomobject]@{Status='ADDED';Path=$path;Before='';After=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash})
    }
}
# All prior development/deployed JARs and game/FTB/content sources are protected by the full manifest above.
$game = Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.6.jar'
$ai = Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.7.jar'
$previous = Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.6.jar'
$others = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'server/mods') -File -Filter '*.jar' | Where-Object Name -notlike 'mythictrpg-*' | ForEach-Object FullName)
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths (@($game,$ai) + @($others | Where-Object { [IO.Path]::GetFileName($_) -notlike 'mythai_ai_response-*' }))
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Entry-Bytes($entry) {
    $stream = $entry.Open(); $buffer = [IO.MemoryStream]::new()
    try { $stream.CopyTo($buffer); return ,$buffer.ToArray() } finally { $stream.Dispose(); $buffer.Dispose() }
}
function Entry-Hash($entry) {
    $stream = $entry.Open(); $sha = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha.ComputeHash($stream)) } finally { $stream.Dispose(); $sha.Dispose() }
}
$modifiedPatterns = @('com/sande/mythai/response/memory/DerivedMemory*.class','com/sande/mythai/response/memory/DerivedService*.class',
    'com/sande/mythai/response/memory/DialogueMemoryBridge*.class','com/sande/mythai/response/memory/MemoryRecallPolicy*.class',
    'com/sande/mythai/response/structure/OllamaStructureVisionProvider*.class','com/sande/mythictrpg/ai/LocalLlmRequestScheduler*.class')
$newPatterns = @('com/sande/mythai/response/memory/ModelAdmission*.class','com/sande/mythai/response/memory/MemoryIndex*.class',
    'com/sande/mythai/response/memory/OllamaMemoryBackend*.class')
$zip = [IO.Compression.ZipFile]::OpenRead($ai); $old = [IO.Compression.ZipFile]::OpenRead($previous)
try {
    if (@($zip.Entries | Group-Object FullName | Where-Object Count -gt 1).Count) { throw 'Duplicate JAR entries' }
    $equal = 0
    foreach ($entry in $old.Entries) {
        $next = $zip.GetEntry($entry.FullName)
        if ($null -eq $next) { throw "Missing old entry: $($entry.FullName)" }
        if ((Entry-Hash $entry) -eq (Entry-Hash $next)) { $equal++; continue }
        if ($entry.FullName -in @('META-INF/MANIFEST.MF','META-INF/neoforge.mods.toml')) {
            $oldText = [Text.Encoding]::UTF8.GetString((Entry-Bytes $entry))
            $newText = [Text.Encoding]::UTF8.GetString((Entry-Bytes $next))
            if ($oldText.Replace('0.1.6','0.1.7') -cne $newText) { throw 'Unexpected metadata change' }
            continue
        }
        if (!@($modifiedPatterns | Where-Object { $entry.FullName -like $_ }).Count) { throw "Unexpected binary change: $($entry.FullName)" }
    }
    foreach ($entry in $zip.Entries) {
        if ($null -ne $old.GetEntry($entry.FullName)) { continue }
        if (!@(($modifiedPatterns + $newPatterns) | Where-Object { $entry.FullName -like $_ }).Count) { throw "Unexpected new entry: $($entry.FullName)" }
    }
    foreach ($name in @('com/sande/mythictrpg/ai/LocalLlmRequestScheduler.class','com/sande/mythai/response/structure/OllamaStructureVisionProvider.class')) {
        if (![Text.Encoding]::ASCII.GetString((Entry-Bytes $zip.GetEntry($name))).Contains('com/sande/mythai/response/memory/ModelAdmission')) { throw "Common admission not packaged: $name" }
    }
    foreach ($name in @('MemoryIndexRuntime','MemoryIndexStore','OllamaMemoryBackend')) {
        if ($null -eq $zip.GetEntry('com/sande/mythai/response/memory/' + $name + '.class')) { throw "Missing runtime class: $name" }
    }
    if (@($zip.Entries | Where-Object { $_.FullName -match '(MemoryIndexRuntimeTest|SchedulerFixture|ObservedSummaryFixture)' }).Count) { throw 'Test authority shipped' }
    Write-Output "PASS AI 0.1.7: previous entries unchanged $equal/$($old.Entries.Count); admission connected in packaged dialogue and vision classes"
} finally { $zip.Dispose(); $old.Dispose() }
$changes | Sort-Object Path | Export-Csv -LiteralPath (Join-Path $taskRoot 'docs/memory-stage05-completion-changes-20260917.csv') -NoTypeInformation -Encoding UTF8
Write-Output "PASS protected baseline: $unchanged/$($rows.Count) unchanged; delta=$($changes.Count). LP/old JARs/game/FTB/content/server/config/logs unchanged."
Get-FileHash -LiteralPath $game,$ai -Algorithm SHA256 | Format-List
