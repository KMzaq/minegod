$ErrorActionPreference = 'Stop'
$archiveWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$archiveRoot = [IO.Path]::GetFullPath((Join-Path $archiveWorkspace '구버전/20261022-테스트콘텐츠'))
$archiveManifest = Join-Path $archiveRoot 'content-manifest.json'
$archiveUtf8 = [Text.UTF8Encoding]::new($false)
if (Test-Path -LiteralPath $archiveManifest) { throw 'This one-time archival already has a manifest; do not run it twice.' }

function Assert-ArchiveWorkspacePath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($archiveWorkspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escaped the workspace: $full"
    }
    return $full
}
function Write-ArchiveJson([string]$Path, $Value) {
    $full = Assert-ArchiveWorkspacePath $Path
    New-Item -ItemType Directory -Path (Split-Path -Parent $full) -Force | Out-Null
    [IO.File]::WriteAllText($full, ($Value | ConvertTo-Json -Depth 30), $archiveUtf8)
}

# Recheck local server access without starting, stopping or changing a world.
$archiveLockPath = Assert-ArchiveWorkspacePath (Join-Path $archiveWorkspace 'server/world/session.lock')
if (Test-Path -LiteralPath $archiveLockPath) {
    $archiveLock = [IO.File]::Open($archiveLockPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
    $archiveLock.Dispose()
}

$archivePlan = [Collections.Generic.List[object]]::new()
function Add-ArchiveFile([string]$Relative, [string]$Action = 'MOVE') {
    $source = Assert-ArchiveWorkspacePath (Join-Path $archiveWorkspace $Relative)
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing archival source: $Relative" }
    $destination = Assert-ArchiveWorkspacePath (Join-Path $archiveRoot ('원본/' + $Relative))
    if (-not $destination.StartsWith($archiveRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Archive destination escaped its folder.'
    }
    if (Test-Path -LiteralPath $destination) { throw "Archive destination already exists: $Relative" }
    $archivePlan.Add([pscustomobject]@{
        source = $Relative.Replace('\', '/'); action = $Action
        archived = ('원본/' + $Relative).Replace('\', '/')
        bytes = (Get-Item -LiteralPath $source).Length
        sha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    })
}
function Add-ArchiveTree([string]$Relative) {
    $source = Assert-ArchiveWorkspacePath (Join-Path $archiveWorkspace $Relative)
    foreach ($file in Get-ChildItem -LiteralPath $source -File -Recurse) {
        Add-ArchiveFile $file.FullName.Substring($archiveWorkspace.Length + 1)
    }
}

$archiveGameBase = 'mythictrpg-main/src/main/resources/data/mythictrpg/mythictrpg/'
foreach ($folder in @('gods','ai_actions','ftb_quests','generated_quest_templates','reward_tables',
        'interaction_rules','gameplay_promotions','structure_evaluation_policies','story_actors',
        'story_cover_stories','story_events','story_facts','story_hooks','story_locations','story_presentations')) {
    Add-ArchiveTree ($archiveGameBase + $folder)
}
Add-ArchiveFile ($archiveGameBase + 'story_disclosure_policies/lubras_cover.json')
Add-ArchiveFile 'mythictrpg-main/src/main/resources/data/mythictrpg/tags/block/fortuna_modern_materials.json'

$archiveRegistryBase = 'mythai-ai-content-registry/src/main/resources/data/'
foreach ($folder in @('mythaiaicontent/mythai_ai/god_profiles','mythaiaicontent/mythai_ai/lore',
        'mythictrpg/mythai_ai/quest_lists')) { Add-ArchiveTree ($archiveRegistryBase + $folder) }
foreach ($name in @('lubras_information','test_common_dialogue','test_directional_relation_dialogue','test_relation_dialogue')) {
    Add-ArchiveFile ($archiveRegistryBase + 'mythaiaicontent/mythai_ai/dialogue_examples/' + $name + '.json')
}
Add-ArchiveTree 'example_dialogue_dictionary'
Add-ArchiveTree 'server/world/datapacks/mythictrpg-greek-ai-test'
Add-ArchiveTree 'server/world/datapacks/mythai-dialogue-dictionary'

$archiveEmptyCatalogs = [ordered]@{
    'ai-personas.json' = @{ schemaVersion = 1; gods = @{} }
    'npc-agents.json' = @{ schemaVersion = 1; agents = @{} }
    'npc-character-tags.json' = @{ schemaVersion = 1; npcs = @{} }
    'ai-knowledge.json' = @{ schemaVersion = 1; entries = @() }
    'voice-styles.json' = @{ schemaVersion = 1; styles = @() }
    'dialogue-examples.json' = @{ schemaVersion = 1; examples = @() }
}
foreach ($name in $archiveEmptyCatalogs.Keys) { Add-ArchiveFile ('server/config/mythictrpg/' + $name) 'SNAPSHOT_AND_EMPTY' }
foreach ($locale in @('ko_kr','en_us')) {
    Add-ArchiveFile ('mythictrpg-main/src/main/resources/assets/mythictrpg/lang/' + $locale + '.json') 'SNAPSHOT_AND_FILTER_LOCALE'
}
foreach ($pack in @('mythictrpg-main/ftbquests-pack/config','server/config')) {
    Add-ArchiveFile ($pack + '/ftbquests/quests/chapters/mythic_story.snbt')
    Add-ArchiveFile ($pack + '/ftbquests/quests/chapters/mythic_internal.snbt') 'SNAPSHOT_AND_EMPTY_CHAPTER'
    foreach ($locale in @('ko_kr','en_us')) {
        Add-ArchiveFile ($pack + '/ftbquests/quests/lang/' + $locale + '.snbt') 'SNAPSHOT_AND_FILTER_FTB_LOCALE'
    }
}

# Copy tasks can leave removed input resources behind. Preserve those exact stale outputs too.
$archiveStaleOutputs = @()
foreach ($entry in @($archivePlan)) {
    if ($entry.action -eq 'MOVE' -and $entry.source -match '^(mythictrpg-main|mythai-ai-content-registry)/src/main/resources/(.+)$') {
        $stale = $Matches[1] + '/build/resources/main/' + $Matches[2]
        if (Test-Path -LiteralPath (Join-Path $archiveWorkspace $stale) -PathType Leaf) { $archiveStaleOutputs += $stale }
    }
}
foreach ($relative in $archiveStaleOutputs) { Add-ArchiveFile $relative }

# Record untouched persistent player/world data; no progress, memories or logs are reset.
$archivePreserved = @(foreach ($relative in @('server/world','server/mythictrpg-ai-data')) {
    $path = Assert-ArchiveWorkspacePath (Join-Path $archiveWorkspace $relative)
    if (Test-Path -LiteralPath $path) {
        foreach ($file in Get-ChildItem -LiteralPath $path -Recurse -File) {
            if ($file.FullName.StartsWith((Join-Path $archiveWorkspace 'server/world/datapacks') + [IO.Path]::DirectorySeparatorChar,
                    [StringComparison]::OrdinalIgnoreCase)) { continue }
            [pscustomobject]@{ source = $file.FullName.Substring($archiveWorkspace.Length + 1).Replace('\','/');
                sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
        }
    }
})
Write-ArchiveJson (Join-Path $archiveRoot 'preserved-state-manifest.json') $archivePreserved
Write-ArchiveJson $archiveManifest ([ordered]@{ date = '2026-10-22'; status = 'PLANNED'; files = @($archivePlan.ToArray()) })

foreach ($entry in $archivePlan) {
    $source = Assert-ArchiveWorkspacePath (Join-Path $archiveWorkspace $entry.source)
    $destination = Assert-ArchiveWorkspacePath (Join-Path $archiveRoot $entry.archived)
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $entry.sha256) { throw "Source changed: $($entry.source)" }
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    if ($entry.action -eq 'MOVE') { Move-Item -LiteralPath $source -Destination $destination }
    else { Copy-Item -LiteralPath $source -Destination $destination }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $entry.sha256) { throw "Archive hash mismatch: $($entry.source)" }
}
foreach ($name in $archiveEmptyCatalogs.Keys) {
    Write-ArchiveJson (Join-Path $archiveWorkspace ('server/config/mythictrpg/' + $name)) $archiveEmptyCatalogs[$name]
}
foreach ($locale in @('ko_kr','en_us')) {
    $path = Join-Path $archiveWorkspace ('mythictrpg-main/src/main/resources/assets/mythictrpg/lang/' + $locale + '.json')
    $json = [IO.File]::ReadAllText($path) | ConvertFrom-Json
    foreach ($name in @($json.PSObject.Properties.Name)) {
        if ($name.StartsWith('god.') -or $name.StartsWith('story.')) { $json.PSObject.Properties.Remove($name) }
    }
    Write-ArchiveJson $path $json
}
foreach ($pack in @('mythictrpg-main/ftbquests-pack/config','server/config')) {
    $internal = Join-Path $archiveWorkspace ($pack + '/ftbquests/quests/chapters/mythic_internal.snbt')
    $text = [IO.File]::ReadAllText($internal)
    $text = [regex]::Replace($text, '(?s)quests:\s*\[\{.*?\}\]', 'quests: [ ]')
    [IO.File]::WriteAllText($internal, $text, $archiveUtf8)
    foreach ($locale in @('ko_kr','en_us')) {
        $title = if ($locale -eq 'ko_kr') { '즉석 의뢰' } else { 'Generated Side Quests' }
        $path = Join-Path $archiveWorkspace ($pack + '/ftbquests/quests/lang/' + $locale + '.snbt')
        [IO.File]::WriteAllText($path, "{`n`tchapter.1D1A4D1C00000001.title: `"$title`"`n}`n", $archiveUtf8)
    }
}
foreach ($entry in $archivePreserved) {
    if ((Get-FileHash -LiteralPath (Join-Path $archiveWorkspace $entry.source) -Algorithm SHA256).Hash -ne $entry.sha256) {
        throw "Preserved world state changed: $($entry.source)"
    }
}
Write-ArchiveJson $archiveManifest ([ordered]@{ date = '2026-10-22'; status = 'ARCHIVED'; files = @($archivePlan.ToArray()) })
[pscustomobject]@{ archivedFiles = $archivePlan.Count; originalMoves = @($archivePlan | Where-Object action -eq 'MOVE').Count;
    preservedStateFiles = $archivePreserved.Count; directory = $archiveRoot }
