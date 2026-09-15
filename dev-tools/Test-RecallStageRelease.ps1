param([string]$Baseline = 'server/backups/before-recall-stage01-20260915-183337-145')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baselineRoot = [IO.Path]::GetFullPath((Join-Path $taskRoot $Baseline))
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $baselineRoot
$rows = @(Import-Csv -LiteralPath (Join-Path $baselineRoot 'file-manifest.csv'))
$allowed = @('mythai-ai-response\build.gradle','mythai-ai-response\gradle.properties',
    'mythai-ai-response\memory-foundation-overlay.gradle',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\MemoryJournal.java',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\MemoryRecallPolicy.java',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\memory\DialogueMemoryBridge.java',
    'docs\ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md','docs\EPISODIC_MEMORY_UPGRADE_DESIGN_20260915.md',
    'docs\LP_MEMORY_FOUNDATION_GUIDE.md','인수인계\PROJECT_HANDOFF.md')
$changes = [Collections.Generic.List[object]]::new()
$protectedCount = 0
foreach ($row in $rows) {
    $hash = (Get-FileHash -LiteralPath (Join-Path $taskRoot $row.Path) -Algorithm SHA256).Hash
    if ($hash -ne $row.SHA256) {
        if ($row.Path -notin $allowed) { throw "Unexpected baseline change: $($row.Path)" }
        $changes.Add([pscustomobject]@{ Status='MODIFIED'; Path=$row.Path; Before=$row.SHA256; After=$hash })
    } else { $protectedCount++ }
}
# Detect unauthorized additions too in the source/runtime trees that this stage must not change.
$known = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($row in $rows) { [void]$known.Add($row.Path) }
foreach ($relative in @('mythictrpg-main/src','mythai-ai-content-registry/src','ftb-quests/common/src',
        'ftb-quests/neoforge/src','mine/mine/src','server/mods','server/client-required-mods','server/config')) {
    $folder = Join-Path $taskRoot $relative
    if (!(Test-Path -LiteralPath $folder)) { continue }
    foreach ($file in Get-ChildItem -LiteralPath $folder -Recurse -File) {
        $name = $file.FullName.Substring($taskRoot.Length + 1)
        if (!$known.Contains($name)) { throw "Unexpected protected addition: $name" }
    }
}
foreach ($relative in @('mythai-ai-response/src','docs','dev-tools')) {
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $taskRoot $relative) -Recurse -File) {
        $name = $file.FullName.Substring($taskRoot.Length + 1)
        if (!$known.Contains($name) -and $name -ne 'docs\recall-stage01-changes-20260915.csv') {
            $changes.Add([pscustomobject]@{ Status='ADDED'; Path=$name; Before=''; After=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash })
        }
    }
}
$ai = Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.4.jar'
$jars = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'server/mods') -Filter '*.jar' |
    Where-Object { $_.Name -notlike 'mythai_ai_response-*' } | ForEach-Object FullName) + @($ai)
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths $jars
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($ai)
try {
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $zip.Entries) {
        if ($entry.Name -and !$names.Add($entry.FullName)) { throw "Duplicate JAR entry: $($entry.FullName)" }
    }
    foreach ($name in @('RecallQuery','RecallSearch','RecallSettings','MemoryJournal','DialogueMemoryBridge')) {
        if (!$zip.GetEntry("com/sande/mythai/response/memory/$name.class")) { throw "Missing new class: $name" }
    }
    $stream = [IO.StreamReader]::new($zip.GetEntry('META-INF/neoforge.mods.toml').Open())
    try { $metadata = $stream.ReadToEnd() } finally { $stream.Dispose() }
    if ($metadata -notmatch 'version\s*=\s*"0\.1\.4"' -or $metadata -notmatch 'versionRange\s*=\s*"\[1\.0\.2,\)"') { throw 'Invalid mod version/dependency' }
} finally { $zip.Dispose() }
$changes | Sort-Object Path | Export-Csv -LiteralPath (Join-Path $taskRoot 'docs/recall-stage01-changes-20260915.csv') -NoTypeInformation -Encoding UTF8
Write-Output "PASS baseline protected=$protectedCount; reviewed changes=$($changes.Count); no deploy/server/model execution"
Get-FileHash -LiteralPath $ai -Algorithm SHA256
