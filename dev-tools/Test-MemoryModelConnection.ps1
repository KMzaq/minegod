param([string]$Baseline = 'C:\Users\ADMIN\Desktop\markmar\server\backups\before-memory-stage05-20260920-161949-064')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baselinePath = (Resolve-Path -LiteralPath $Baseline).Path
if (!$baselinePath.StartsWith((Join-Path $taskRoot 'server\backups\before-memory-stage05-'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected baseline' }
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $baselinePath
$rows = @(Import-Csv -LiteralPath (Join-Path $baselinePath 'file-manifest.csv'))
$before = @{}; $changes = [Collections.Generic.List[object]]::new(); $unchanged = 0
$allowedServer = @('server\config\mythictrpg\ai-memory-index.json','server\config\mythictrpg\ai-memory-recall.json')
function Test-Protected([string]$Path) {
    return (($Path -match '^(server\\|mine\\|ftb-quests\\|mythictrpg-main\\|mythai-ai-content-registry\\)' -and $Path -notin $allowedServer) -or
        $Path -match '\\build\\libs\\' -or $Path -eq 'AGENTS.md' -or $Path -like '*.docx')
}
foreach ($row in $rows) {
    $before[$row.Path] = $row.SHA256
    $file = Join-Path $taskRoot $row.Path
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Baseline file removed: $($row.Path)" }
    $after = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
    if ($after -eq $row.SHA256) { $unchanged++; continue }
    if (Test-Protected $row.Path) { throw "Protected runtime/other module/old artifact changed: $($row.Path)" }
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
        if ($before.ContainsKey($path) -or $path -eq 'docs\memory-model-changes-20260920.csv') { continue }
        if (Test-Protected $path) { throw "Protected area new file: $path" }
        $changes.Add([pscustomobject]@{Status='ADDED';Path=$path;Before='';After=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash})
    }
}
$index = Get-Content -LiteralPath (Join-Path $taskRoot $allowedServer[0]) -Raw -Encoding UTF8 | ConvertFrom-Json
$recall = Get-Content -LiteralPath (Join-Path $taskRoot $allowedServer[1]) -Raw -Encoding UTF8 | ConvertFrom-Json
if ($index.schemaVersion -ne 1 -or !$index.enabled -or !$index.consolidate -or $index.semanticMode -ne 'ON' -or
    $index.embeddingModel -ne 'bge-m3:latest' -or $index.extractionModel -ne 'gemma4:12b' -or
    $index.modelRevision -ne '7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab' -or
    $index.extractionRevision -ne '4eb23ef187e2c5462566d6a1d3bbbc2f1346d0b4327cbb66d58fffbcc9b2b05c' -or
    $index.endpoint -ne 'http://127.0.0.1:11434' -or $index.dimensions -ne 1024 -or $index.queryTimeoutMs -ne 350 -or
    !$index.execution.embeddingCpu -or $index.execution.extractionCpu -or !$index.execution.skipSemanticWhenFound -or
    $index.execution.embeddingThreads -ne 4 -or $index.execution.minimumSimilarity -ne 0.60 -or
    $index.maxStorageBytes -ne 268435456 -or $index.maxEntries -ne 12000 -or
    $recall.schemaVersion -ne 1 -or !$recall.recallV2 -or $recall.timeBasis -ne 'REAL_KST') { throw 'Prepared configuration mismatch' }
$game = Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.8.jar'
$ai = Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.10.jar'
$others = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'server/mods') -File -Filter '*.jar' |
    Where-Object { $_.Name -notlike 'mythictrpg-*' -and $_.Name -notlike 'mythai_ai_response-*' } | ForEach-Object FullName)
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths (@($game,$ai)+$others)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($ai)
try {
    if (@($zip.Entries | Group-Object FullName | Where-Object Count -gt 1).Count) { throw 'Duplicate JAR entries' }
    if (@($zip.Entries | Where-Object { $_.FullName -match '(MemoryModelBenchmark|MemoryModelConnectionTest|NaturalMemoryTest|SchedulerFixture)' }).Count) { throw 'Test fixture packaged' }
    $metadata = $zip.GetEntry('META-INF/neoforge.mods.toml')
    $reader = [IO.StreamReader]::new($metadata.Open())
    try { $toml = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if ($toml -notmatch 'version="0\.1\.10"' -or $toml -notmatch 'versionRange="\[1\.0\.8,\)"') { throw 'Incorrect AI/game version contract' }
} finally { $zip.Dispose() }
$changes | Sort-Object Path | Export-Csv -LiteralPath (Join-Path $taskRoot 'docs/memory-model-changes-20260920.csv') -NoTypeInformation -Encoding UTF8
$links = 0
foreach ($row in $changes | Where-Object Path -like '*.md') {
    $full = Join-Path $taskRoot $row.Path
    $body = Get-Content -LiteralPath $full -Raw -Encoding UTF8
    if ([regex]::Matches($body,'(?m)^```').Count % 2 -ne 0) { throw "Unbalanced Markdown fence: $full" }
    foreach ($match in [regex]::Matches($body,'\[[^\]\r\n]*\]\(([^)\r\n]+)\)')) {
        $target = $match.Groups[1].Value.Trim('<','>') -replace '#.*$',''
        if (!$target -or $target -match '^([a-z]+:|/)' -or $target.Contains(' ')) { continue }
        $target = [Uri]::UnescapeDataString($target)
        if (!(Test-Path -LiteralPath (Join-Path (Split-Path -Parent $full) $target))) { throw "Missing local link in $($row.Path): $target" }
        $links++
    }
}
Write-Output "PASS snapshot comparison: $unchanged/$($rows.Count) unchanged; changed/new=$($changes.Count); local links=$links. LP/game/FTB/content/legacy/old JARs/world preserved; only two new server memory configs allowed."
Get-FileHash -LiteralPath $game,$ai -Algorithm SHA256 | Format-List
