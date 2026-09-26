param([string]$Baseline = 'C:\Users\ADMIN\Desktop\markmar\server\backups\before-memory-stage05-20260920-182320-030')
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baselinePath=(Resolve-Path -LiteralPath $Baseline).Path
if (!$baselinePath.StartsWith((Join-Path $taskRoot 'server\backups\before-memory-stage05-'),[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected baseline' }
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $baselinePath
$allowed=@('mythictrpg-main\build.gradle','mythictrpg-main\gradle.properties',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\MythicTrpg.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\rumor\RumorLedger.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\rumor\CourierEngine.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\rumor\CourierRumorService.java',
    'mythictrpg-main\src\test\java\com\sande\mythictrpg\rumor\ReputationStageTest.java',
    'docs\REPUTATION_STAGE07_20260920.md','docs\examples\reputation-judgement.example.json',
    'docs\ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md','docs\ACTION_OBSERVATION_CONTRACT_20260915.md',
    'docs\LP_LONG_TERM_MEMORY_DESIGN.md','인수인계\PROJECT_HANDOFF.md','dev-tools\Test-ReputationStage07.ps1')
foreach ($name in @('ReputationSettings','ReputationLedger','ReputationEngine','ReputationSavedData','ReputationService','DialogueRecovery')) {
    $allowed+='mythictrpg-main\src\main\java\com\sande\mythictrpg\rumor\'+$name+'.java'
}
function Test-Allowed([string]$Path) { return $Path -in $allowed -or $Path -match '^mythictrpg-main\\logs\\(latest\.log|debug\.log|[0-9-]+[.]log[.]gz|debug-[0-9]+[.]log[.]gz)$' }
$rows=@(Import-Csv -LiteralPath (Join-Path $baselinePath 'file-manifest.csv'))
$before=@{};$changes=[Collections.Generic.List[object]]::new();$unchanged=0
foreach ($row in $rows) {
    $before[$row.Path]=$row.SHA256;$file=Join-Path $taskRoot $row.Path
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) {
        if ($row.Path -match '^mythictrpg-main\\logs\\([0-9-]+[.]log[.]gz|debug-[0-9]+[.]log[.]gz)$') {
            $changes.Add([pscustomobject]@{Status='ROTATED_OUT';Path=$row.Path;Before=$row.SHA256;After=''});continue
        }
        throw "Baseline file removed: $($row.Path)"
    }
    $after=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
    if ($after -eq $row.SHA256) { $unchanged++;continue }
    if (!(Test-Allowed $row.Path)) { throw "Unrelated file changed: $($row.Path)" }
    $changes.Add([pscustomobject]@{Status='MODIFIED';Path=$row.Path;Before=$row.SHA256;After=$after})
}
foreach ($relative in @('mythictrpg-main','mythai-ai-response','mythai-ai-content-registry','ftb-quests','docs','인수인계','dev-tools',
    'server/mods','server/client-required-mods','server/config','mine/mine/src','server/world/mythictrpg-ai-memory-lp-v1','server/world/mythictrpg-ai-test-logs','server/world/datapacks')) {
    $folder=Join-Path $taskRoot $relative;if (!(Test-Path -LiteralPath $folder)) { continue }
    foreach ($file in (Get-ChildItem -LiteralPath $folder -Recurse -File -Force | Where-Object { $_.FullName -notmatch '[\\/](\.git|\.gradle|\.idea|run|run-data|build|node_modules)[\\/]' })) {
        $path=$file.FullName.Substring($taskRoot.Length+1)
        if ($before.ContainsKey($path) -or $path -eq 'docs\reputation-stage07-changes-20260920.csv') { continue }
        if (!(Test-Allowed $path)) { throw "Unrelated new file: $path" }
        $changes.Add([pscustomobject]@{Status='ADDED';Path=$path;Before='';After=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash})
    }
}
$game=Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.10.jar'
$ai=Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.11.jar'
$others=@(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'server/mods') -File -Filter '*.jar' | Where-Object { $_.Name -notlike 'mythictrpg-*' -and $_.Name -notlike 'mythai_ai_response-*' } | ForEach-Object FullName)
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths (@($game,$ai)+$others)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($game)
try {
    if (@($zip.Entries | Group-Object FullName | Where-Object Count -gt 1).Count) { throw 'Duplicate entries' }
    if ($zip.GetEntry('com/sande/mythictrpg/rumor/ReputationStageTest.class')) { throw 'Offline test in release JAR' }
    if (!$zip.GetEntry('com/sande/mythictrpg/rumor/ReputationService.class')) { throw 'Missing implementation' }
    $reader=[IO.StreamReader]::new($zip.GetEntry('META-INF/neoforge.mods.toml').Open())
    try { $metadata=$reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($metadata -notmatch 'version="1\.0\.10"') { throw 'Game version mismatch' }
} finally { $zip.Dispose() }
$changes.Add([pscustomobject]@{Status='BUILT';Path=$game.Substring($taskRoot.Length+1);Before='';After=(Get-FileHash -LiteralPath $game -Algorithm SHA256).Hash})
$changes | Sort-Object Path | Export-Csv -LiteralPath (Join-Path $taskRoot 'docs/reputation-stage07-changes-20260920.csv') -NoTypeInformation -Encoding UTF8
$example=Get-Content -LiteralPath (Join-Path $taskRoot 'docs/examples/reputation-judgement.example.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($example.enabled -or $example.negativeCap -or $example.positiveCap -or $example.rules.Count) { throw 'Operational values must remain OFF/unassigned' }
$links=0
foreach ($row in $changes | Where-Object Path -like '*.md') {
    $full=Join-Path $taskRoot $row.Path;$body=Get-Content -LiteralPath $full -Raw -Encoding UTF8
    if ([regex]::Matches($body,'(?m)^```').Count % 2) { throw "Markdown fence: $full" }
    foreach ($match in [regex]::Matches($body,'\[[^\]\r\n]*\]\(([^)\r\n]+)\)')) {
        $target=$match.Groups[1].Value.Trim('<','>') -replace '#.*$',''
        if (!$target -or $target -match '^([a-z]+:|/)' -or $target.Contains(' ')) { continue }
        if (!(Test-Path -LiteralPath (Join-Path (Split-Path -Parent $full) ([Uri]::UnescapeDataString($target))))) { throw "Missing link: $target in $full" }
        $links++
    }
}
Write-Output "PASS: $unchanged/$($rows.Count) unchanged; delta=$($changes.Count) including one new game development JAR; links=$links. LP/AI source+JAR/server/FTB/content/legacy and unrelated gameplay preserved."
Get-FileHash -LiteralPath $game,$ai -Algorithm SHA256 | Format-List
