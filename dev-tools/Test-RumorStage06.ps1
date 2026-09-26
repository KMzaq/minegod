param([string]$Baseline = 'C:\Users\ADMIN\Desktop\markmar\server\backups\before-memory-stage05-20260920-173428-369')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baselinePath = (Resolve-Path -LiteralPath $Baseline).Path
if (!$baselinePath.StartsWith((Join-Path $taskRoot 'server\backups\before-memory-stage05-'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected baseline' }
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $baselinePath
$rows = @(Import-Csv -LiteralPath (Join-Path $baselinePath 'file-manifest.csv'))
$before = @{}; $changes = [Collections.Generic.List[object]]::new(); $unchanged = 0
$gameFiles = @('build.gradle','gradle.properties','src\main\java\com\sande\mythictrpg\MythicTrpg.java',
    'src\main\java\com\sande\mythictrpg\rumor\RumorLedger.java','src\main\java\com\sande\mythictrpg\rumor\RumorSavedData.java',
    'src\main\java\com\sande\mythictrpg\rumor\CourierSettings.java','src\main\java\com\sande\mythictrpg\rumor\CourierProof.java',
    'src\main\java\com\sande\mythictrpg\rumor\CourierEngine.java','src\main\java\com\sande\mythictrpg\rumor\CourierRumorService.java',
    'src\test\java\com\sande\mythictrpg\rumor\RumorLedgerTest.java','src\test\java\com\sande\mythictrpg\rumor\CourierStageTest.java')
$aiFiles = @('build.gradle','gradle.properties','src\main\templates\META-INF\neoforge.mods.toml',
    'src\main\java\com\sande\mythai\response\memory\DialogueMemoryBridge.java',
    'src\main\java\com\sande\mythai\response\memory\ExperienceHistory.java',
    'src\main\java\com\sande\mythai\response\memory\MemoryRecallPolicy.java',
    'src\test\java\com\sande\mythai\response\memory\RumorDialogueTest.java')
$allowed = @($gameFiles | ForEach-Object { 'mythictrpg-main\' + $_ }) + @($aiFiles | ForEach-Object { 'mythai-ai-response\' + $_ }) + @(
    'docs\ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md','docs\ACTION_OBSERVATION_CONTRACT_20260915.md',
    'docs\LP_LONG_TERM_MEMORY_DESIGN.md','docs\LP_MEMORY_FOUNDATION_GUIDE.md','docs\RUMOR_STAGE06_PREFLIGHT_20260920.md',
    'docs\RUMOR_STAGE06_IMPLEMENTATION_20260920.md','docs\examples\rumor-courier.example.json',
    '인수인계\PROJECT_HANDOFF.md','dev-tools\Test-RumorStage06.ps1')
function Test-Allowed([string]$Path) { return $Path -in $allowed -or $Path -match '^mythictrpg-main\\logs\\(latest\.log|debug\.log|[0-9-]+[.]log[.]gz|debug-[0-9]+[.]log[.]gz)$' }
foreach ($row in $rows) {
    $before[$row.Path] = $row.SHA256
    $file = Join-Path $taskRoot $row.Path
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Baseline file removed: $($row.Path)" }
    $after = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
    if ($after -eq $row.SHA256) { $unchanged++; continue }
    if (!(Test-Allowed $row.Path)) { throw "Outside stage scope changed: $($row.Path)" }
    $changes.Add([pscustomobject]@{Status='MODIFIED';Path=$row.Path;Before=$row.SHA256;After=$after})
}
foreach ($relative in @('mythictrpg-main','mythai-ai-response','mythai-ai-content-registry','ftb-quests','docs','인수인계','dev-tools',
        'server/mods','server/client-required-mods','server/config','mine/mine/src','server/world/mythictrpg-ai-memory-lp-v1',
        'server/world/mythictrpg-ai-test-logs','server/world/datapacks')) {
    $folder = Join-Path $taskRoot $relative
    if (!(Test-Path -LiteralPath $folder)) { continue }
    foreach ($file in (Get-ChildItem -LiteralPath $folder -Recurse -File -Force |
        Where-Object { $_.FullName -notmatch '[\\/](\.git|\.gradle|\.idea|run|run-data|build|node_modules)[\\/]' })) {
        $path = $file.FullName.Substring($taskRoot.Length + 1)
        if ($before.ContainsKey($path) -or $path -eq 'docs\rumor-stage06-changes-20260920.csv') { continue }
        if (!(Test-Allowed $path)) { throw "Outside stage scope new file: $path" }
        $changes.Add([pscustomobject]@{Status='ADDED';Path=$path;Before='';After=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash})
    }
}
$game = Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.9.jar'
$ai = Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.11.jar'
$others = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'server/mods') -File -Filter '*.jar' |
    Where-Object { $_.Name -notlike 'mythictrpg-*' -and $_.Name -notlike 'mythai_ai_response-*' } | ForEach-Object FullName)
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths (@($game,$ai)+$others)
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($artifact in @($game,$ai)) {
    $zip = [IO.Compression.ZipFile]::OpenRead($artifact)
    try {
        if (@($zip.Entries | Group-Object FullName | Where-Object Count -gt 1).Count) { throw 'Duplicate JAR entries' }
        if (@($zip.Entries | Where-Object { $_.FullName -match '(CourierStageTest|RumorDialogueTest|MemoryModelBenchmark|MemoryModelConnectionTest|SchedulerFixture)' }).Count) { throw 'Offline test fixture packaged' }
        if ($artifact -eq $ai) {
            $reader = [IO.StreamReader]::new($zip.GetEntry('META-INF/neoforge.mods.toml').Open())
            try { $toml = $reader.ReadToEnd() } finally { $reader.Dispose() }
            if ($toml -notmatch 'version="0\.1\.11"' -or $toml -notmatch 'versionRange="\[1\.0\.9,\)"') { throw 'Incorrect AI/game dependency' }
        }
    } finally { $zip.Dispose() }
    $changes.Add([pscustomobject]@{Status='BUILT';Path=$artifact.Substring($taskRoot.Length+1);Before='';After=(Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash})
}
# Generate the mechanical audit artifact before checking links to it.
$changes | Sort-Object Path | Export-Csv -LiteralPath (Join-Path $taskRoot 'docs/rumor-stage06-changes-20260920.csv') -NoTypeInformation -Encoding UTF8
$example=Get-Content -LiteralPath (Join-Path $taskRoot 'docs/examples/rumor-courier.example.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($example.enabled -or $example.automaticSpawn -or $example.automaticRespawn -or $example.courierTypes.Count -or $example.rules.Count) { throw 'Example must be OFF and content-neutral' }
$links=0
foreach ($row in $changes | Where-Object Path -like '*.md') {
    $full=Join-Path $taskRoot $row.Path; $body=Get-Content -LiteralPath $full -Raw -Encoding UTF8
    if ([regex]::Matches($body,'(?m)^```').Count % 2) { throw "Unbalanced Markdown fence: $full" }
    foreach ($match in [regex]::Matches($body,'\[[^\]\r\n]*\]\(([^)\r\n]+)\)')) {
        $target=$match.Groups[1].Value.Trim('<','>') -replace '#.*$',''
        if (!$target -or $target -match '^([a-z]+:|/)' -or $target.Contains(' ')) { continue }
        $target=[Uri]::UnescapeDataString($target)
        if (!(Test-Path -LiteralPath (Join-Path (Split-Path -Parent $full) $target))) { throw "Missing local link in $($row.Path): $target" }
        $links++
    }
}
Write-Output "PASS: baseline $unchanged/$($rows.Count) unchanged; delta=$($changes.Count) including two new development JARs; local links=$links. LP, old JARs, server, FTB/content/legacy and unrelated sources preserved."
Get-FileHash -LiteralPath $game,$ai -Algorithm SHA256 | Format-List
