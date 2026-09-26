param([string]$Baseline='C:\Users\ADMIN\Desktop\markmar\server\backups\before-memory-stage05-20260920-192438-700')
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$baselinePath=(Resolve-Path -LiteralPath $Baseline).Path
if (!$baselinePath.StartsWith((Join-Path $taskRoot 'server\backups\before-memory-stage05-'),[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected baseline' }
& (Join-Path $PSScriptRoot 'Backup-RecallStageBaseline.ps1') -VerifyOnly -Baseline $baselinePath
$allowed=@('mythictrpg-main\build.gradle','mythictrpg-main\gradle.properties',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\MythicTrpg.java',
    'mythictrpg-main\src\main\java\com\sande\mythictrpg\gameplay\ledger\detail\ImportantEvents.java',
    'mythictrpg-main\src\test\java\com\sande\mythictrpg\rumor\SocialPipelineTest.java',
    'mythai-ai-response\build.gradle','mythai-ai-response\gradle.properties','mythai-ai-response\memory-foundation-overlay.gradle',
    'mythai-ai-response\src\main\templates\META-INF\neoforge.mods.toml',
    'mythai-ai-response\src\main\java\com\sande\mythai\response\MythAiResponseMod.java',
    'mythai-ai-response\src\main\java\com\sande\mythictrpg\ai\SocialPersona.java',
    'mythai-ai-response\src\test\java\com\sande\mythictrpg\ai\QuestParticipationDialogueTest.java',
    'mythai-ai-response\src\test\java\com\sande\mythai\response\memory\SocialReviewTest.java',
    'docs\SOCIAL_PIPELINE_COMPLETION_20260920.md','docs\examples\social-rumor.example.json','docs\examples\ai-social-review.example.json',
    'docs\ACTION_OBSERVATION_MEMORY_ROADMAP_20260915.md','docs\ACTION_OBSERVATION_CONTRACT_20260915.md','docs\LP_LONG_TERM_MEMORY_DESIGN.md',
    '인수인계\PROJECT_HANDOFF.md','dev-tools\Test-SocialPipeline.ps1')
foreach($name in @('SocialReview','SocialSettings','SocialRuntime','RumorLedger','RumorSavedData','CourierEngine','CourierRumorService','ReputationLedger','ReputationSettings','ReputationService')) {
    $allowed+='mythictrpg-main\src\main\java\com\sande\mythictrpg\rumor\'+$name+'.java'
}
foreach($name in @('DialogueMemoryBridge','MemoryRecallPolicy','ModelAdmission','OllamaSocialReview','SocialReviewProvider')) {
    $allowed+='mythai-ai-response\src\main\java\com\sande\mythai\response\memory\'+$name+'.java'
}
function Test-DevelopmentLog([string]$Path) { return $Path -match '^(mythictrpg-main|mythai-ai-response)\\logs\\(latest\.log|debug\.log|[0-9-]+[.]log[.]gz|debug-[0-9]+[.]log[.]gz)$' }
function Test-Allowed([string]$Path) { return $Path -in $allowed -or (Test-DevelopmentLog $Path) }
$rows=@(Import-Csv -LiteralPath (Join-Path $baselinePath 'file-manifest.csv'))
$before=@{};$changes=[Collections.Generic.List[object]]::new();$unchanged=0
foreach($row in $rows) {
    $before[$row.Path]=$row.SHA256;$file=Join-Path $taskRoot $row.Path
    if(!(Test-Path -LiteralPath $file -PathType Leaf)) {
        if((Test-DevelopmentLog $row.Path) -and $row.Path.EndsWith('.log.gz')) {
            $changes.Add([pscustomobject]@{Status='ROTATED_OUT';Path=$row.Path;Before=$row.SHA256;After=''});continue
        }
        throw "Baseline file removed: $($row.Path)"
    }
    $after=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
    if($after -eq $row.SHA256){$unchanged++;continue}
    if(!(Test-Allowed $row.Path)){throw "Unrelated file changed: $($row.Path)"}
    $changes.Add([pscustomobject]@{Status='MODIFIED';Path=$row.Path;Before=$row.SHA256;After=$after})
}
foreach($relative in @('mythictrpg-main','mythai-ai-response','mythai-ai-content-registry','ftb-quests','docs','인수인계','dev-tools',
        'server/mods','server/client-required-mods','server/config','mine/mine/src','server/world/mythictrpg-ai-memory-lp-v1','server/world/mythictrpg-ai-test-logs','server/world/datapacks')) {
    $folder=Join-Path $taskRoot $relative;if(!(Test-Path -LiteralPath $folder)){continue}
    foreach($file in (Get-ChildItem -LiteralPath $folder -Recurse -File -Force | Where-Object { $_.FullName -notmatch '[\\/](\.git|\.gradle|\.idea|run|run-data|build|node_modules)[\\/]' })) {
        $path=$file.FullName.Substring($taskRoot.Length+1)
        if($before.ContainsKey($path) -or $path -eq 'docs\social-pipeline-changes-20260920.csv'){continue}
        if(!(Test-Allowed $path)){throw "Unrelated new file: $path"}
        $changes.Add([pscustomobject]@{Status='ADDED';Path=$path;Before='';After=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash})
    }
}
$game=Join-Path $taskRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.11.jar'
$ai=Join-Path $taskRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.12.jar'
$others=@(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'server/mods') -File -Filter '*.jar' | Where-Object { $_.Name -notlike 'mythictrpg-*' -and $_.Name -notlike 'mythai_ai_response-*' } | ForEach-Object FullName)
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths (@($game,$ai)+$others)
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach($jar in @($game,$ai)) {
    $zip=[IO.Compression.ZipFile]::OpenRead($jar)
    try {
        if(@($zip.Entries | Group-Object FullName | Where-Object Count -gt 1).Count){throw 'Duplicate JAR entries'}
        if($zip.Entries.FullName -match '(SocialPipelineTest|SocialReviewTest)[.]class$'){throw 'Offline fixture in release JAR'}
        $expected=if($jar -eq $game){'com/sande/mythictrpg/rumor/SocialRuntime.class'}else{'com/sande/mythai/response/memory/SocialReviewProvider.class'}
        if(!$zip.GetEntry($expected)){throw "Missing implementation: $expected"}
        $reader=[IO.StreamReader]::new($zip.GetEntry('META-INF/neoforge.mods.toml').Open())
        try{$metadata=$reader.ReadToEnd()}finally{$reader.Dispose()}
        $version=if($jar -eq $game){'1.0.11'}else{'0.1.12'}
        if(!$metadata.Contains('version="'+$version+'"')){throw 'Version mismatch'}
        if($jar -eq $ai -and !$metadata.Contains('versionRange="[1.0.11,)"')){throw 'AI requires new game API'}
    }finally{$zip.Dispose()}
    $changes.Add([pscustomobject]@{Status='BUILT';Path=$jar.Substring($taskRoot.Length+1);Before='';After=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash})
}
$changes | Sort-Object Path | Export-Csv -LiteralPath (Join-Path $taskRoot 'docs/social-pipeline-changes-20260920.csv') -NoTypeInformation -Encoding UTF8
foreach($name in @('social-rumor','ai-social-review')) {
    $example=Get-Content -LiteralPath (Join-Path $taskRoot "docs/examples/$name.example.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    if($example.enabled){throw 'Example must remain OFF'}
}
$links=0
foreach($row in $changes | Where-Object Path -like '*.md') {
    $full=Join-Path $taskRoot $row.Path;$body=Get-Content -LiteralPath $full -Raw -Encoding UTF8
    if([regex]::Matches($body,'(?m)^```').Count % 2){throw "Markdown fence: $full"}
    foreach($match in [regex]::Matches($body,'\[[^\]\r\n]*\]\(([^)\r\n]+)\)')) {
        $target=$match.Groups[1].Value.Trim('<','>') -replace '#.*$',''
        if(!$target -or $target -match '^([a-z]+:|/)' -or $target.Contains(' ')){continue}
        if(!(Test-Path -LiteralPath (Join-Path (Split-Path -Parent $full) ([Uri]::UnescapeDataString($target))))){throw "Missing link: $target"}
        $links++
    }
}
Write-Output "PASS: $unchanged/$($rows.Count) unchanged; delta=$($changes.Count) includes two new development JARs; links=$links. LP/old JARs/server/FTB/content/legacy and unrelated changes preserved."
Get-FileHash -LiteralPath $game,$ai -Algorithm SHA256 | Format-List
