param([string]$Baseline = 'C:\Users\ADMIN\Desktop\markmar\server\backups\before-memory-stage05-20260920-143804-077')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baselinePath = (Resolve-Path -LiteralPath $Baseline).Path
if (!$baselinePath.StartsWith((Join-Path $taskRoot 'server\backups\before-memory-stage05-'), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected baseline' }
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $baselinePath
$rows = @(Import-Csv -LiteralPath (Join-Path $baselinePath 'file-manifest.csv'))
$before = @{}; $changes = [Collections.Generic.List[object]]::new(); $unchanged = 0
foreach ($row in $rows) {
    $before[$row.Path] = $row.SHA256
    $file = Join-Path $taskRoot $row.Path
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Baseline file removed: $($row.Path)" }
    $after = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
    if ($after -eq $row.SHA256) { $unchanged++; continue }
    if ($row.Path -match '^(server\\|mine\\|ftb-quests\\|mythai-ai-content-registry\\)' -or
        $row.Path -match '\\build\\libs\\' -or $row.Path -eq 'AGENTS.md' -or $row.Path -like '*.docx') {
        throw "Protected runtime/other module/old artifact changed: $($row.Path)"
    }
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
        if ($before.ContainsKey($path) -or $path -eq 'docs\memory-policy-changes-20260920.csv') { continue }
        if ($path -match '^(server\\|mine\\|ftb-quests\\|mythai-ai-content-registry\\)') { throw "Protected area new file: $path" }
        $changes.Add([pscustomobject]@{Status='ADDED';Path=$path;Before='';After=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash})
    }
}
$game=Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.8.jar'
$ai=Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.9.jar'
$others=@(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'server/mods') -File -Filter '*.jar' |
    Where-Object { $_.Name -notlike 'mythictrpg-*' -and $_.Name -notlike 'mythai_ai_response-*' } | ForEach-Object FullName)
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths (@($game,$ai)+$others)
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($path in @($game,$ai)) {
    $zip=[IO.Compression.ZipFile]::OpenRead($path)
    try {
        if (@($zip.Entries | Group-Object FullName | Where-Object Count -gt 1).Count) { throw "Duplicate entries: $path" }
        if (@($zip.Entries | Where-Object { $_.FullName -match '(NaturalMemoryTest|LatestWatchPolicyTest|MiningPolicyTest|WatchRewardTest|SchedulerFixture)' }).Count) { throw 'Offline test fixture packaged' }
    } finally { $zip.Dispose() }
}
$links=0
foreach ($row in $changes | Where-Object Path -like '*.md') {
    $full=Join-Path $taskRoot $row.Path
    $body=Get-Content -LiteralPath $full -Raw -Encoding UTF8
    if ([regex]::Matches($body,'(?m)^```').Count % 2 -ne 0) { throw "Unbalanced Markdown fence: $full" }
    foreach ($match in [regex]::Matches($body,'\[[^\]\r\n]*\]\(([^)\r\n]+)\)')) {
        $target=$match.Groups[1].Value.Trim('<','>') -replace '#.*$',''
        if (!$target -or $target -match '^([a-z]+:|/)' -or $target.Contains(' ')) { continue }
        $target=[Uri]::UnescapeDataString($target)
        if (!(Test-Path -LiteralPath (Join-Path (Split-Path -Parent $full) $target))) { throw "Missing local link in $($row.Path): $target" }
        $links++
    }
}
$changes | Sort-Object Path | Export-Csv -LiteralPath (Join-Path $taskRoot 'docs/memory-policy-changes-20260920.csv') -NoTypeInformation -Encoding UTF8
Write-Output "PASS snapshot comparison: $unchanged/$($rows.Count) unchanged; changed/new=$($changes.Count); local links=$links. LP/old JARs/FTB/content/legacy/server protected."
Get-FileHash -LiteralPath $game,$ai -Algorithm SHA256 | Format-List
