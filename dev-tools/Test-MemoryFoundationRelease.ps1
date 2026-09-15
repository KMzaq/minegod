param([ValidateSet('LP', 'PERSONAL')][string]$ServerProfile = 'LP')

# Read-only release audit. Select the expected deployment; never auto-accept unknown changes.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$WorkspaceRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path

function Assert-Hash([string]$Path, [string]$Expected) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Expected) {
        throw "Hash mismatch: $Path"
    }
}

function Assert-Snapshot([string]$RelativePath) {
    $directory = Join-Path $WorkspaceRoot $RelativePath
    $rows = @(Import-Csv -LiteralPath (Join-Path $directory 'file-manifest.csv'))
    foreach ($row in $rows) { Assert-Hash (Join-Path $directory ('workspace/' + $row.Path)) $row.SHA256 }
    Write-Output "PASS snapshot: $RelativePath ($($rows.Count) files)"
}

function Get-JarIndex([string]$Path) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($entry in $zip.Entries) {
            if ($entry.Name -eq '') { continue }
            if (!$names.Add($entry.FullName)) { throw "Duplicate JAR entry: $Path : $($entry.FullName)" }
        }
        $metadataEntry = $zip.GetEntry('META-INF/neoforge.mods.toml')
        if (!$metadataEntry) { throw "No mod metadata: $Path" }
        $reader = [IO.StreamReader]::new($metadataEntry.Open())
        try { $metadata = $reader.ReadToEnd() } finally { $reader.Dispose() }
        return [pscustomobject]@{ Names=$names; Metadata=$metadata }
    } finally { $zip.Dispose() }
}

Assert-Snapshot 'server/backups/LP-source-20260912'
$baseline = 'server/backups/before-memory-rumor-foundation-20260913-223016-887'
Assert-Snapshot $baseline
$protected = @(Import-Csv -LiteralPath (Join-Path $WorkspaceRoot "$baseline/file-manifest.csv") | Where-Object {
    $_.Path -match '^server[\\/](mods|client-required-mods|config)[\\/]' -or
    $_.Path -match '^(mine[\\/]mine|mythai-ai-content-registry)[\\/]'
})
$replaced = @('server\mods\mythictrpg-1.0.0.jar', 'server\mods\mythai_ai_response-0.1.0.jar',
    'server\client-required-mods\mythictrpg-1.0.0.jar', 'server\client-required-mods\README.md')
$unchanged = @($protected | Where-Object { $ServerProfile -eq 'LP' -or $_.Path -notin $replaced })
foreach ($row in $unchanged) { Assert-Hash (Join-Path $WorkspaceRoot $row.Path) $row.SHA256 }
# Also detect additions, not only changed baseline files, in the deployed JAR/config directories.
$baselinePaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($row in $protected) { [void]$baselinePaths.Add($row.Path.Replace('/', '\')) }
if ($ServerProfile -eq 'PERSONAL') {
    foreach ($name in @('server\mods\mythictrpg-1.0.2.jar', 'server\mods\mythai_ai_response-0.1.3.jar',
            'server\client-required-mods\mythictrpg-1.0.2.jar', 'server\config\mythictrpg\ai-memory-foundation.json')) {
        [void]$baselinePaths.Add($name)
    }
    foreach ($name in $replaced | Where-Object { $_.EndsWith('.jar') }) {
        if (Test-Path -LiteralPath (Join-Path $WorkspaceRoot $name)) { throw "Old active JAR remains: $name" }
    }
}
foreach ($relative in @('server/mods','server/client-required-mods','server/config/mythictrpg')) {
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $WorkspaceRoot $relative) -File -Recurse) {
        $name = $file.FullName.Substring($WorkspaceRoot.Length + 1)
        if (!$baselinePaths.Contains($name)) { throw "Unreviewed server file added: $name" }
    }
}
Write-Output "PASS unchanged runtime, content and legacy build inputs ($($unchanged.Count) baseline files; expected profile=$ServerProfile)"
if ($ServerProfile -eq 'LP') {
    & (Join-Path $PSScriptRoot 'Restore-LP.ps1') -VerifyOnly
    & (Join-Path $PSScriptRoot 'Test-LPBuild.ps1') -WorkspaceRoot $WorkspaceRoot `
        -ResponseJar (Join-Path $WorkspaceRoot 'server/mods/mythai_ai_response-0.1.0.jar') `
        -ContentJar (Join-Path $WorkspaceRoot 'server/mods/mythaiaicontent-0.1.0.jar') | Format-Table Module,Result,FileEntries
} else {
    Assert-Snapshot 'server/backups/before-personal-memory-deploy-20260914-194200-226'
    Assert-Snapshot 'server/backups/before-memory-package-fix-20260914-203705-251'
    Assert-Snapshot 'server/backups/before-memory-recall-tuning-20260914-212335-935'
    Assert-Hash (Join-Path $WorkspaceRoot 'server/backups/LP/mods/mythai_ai_response-0.1.0.jar') 'DBC7C095943817248E05CD86B58966DE8DF74075A54BB8474134AE7C68D8147F'
    Assert-Hash (Join-Path $WorkspaceRoot 'server/backups/LP/mods/mythaiaicontent-0.1.0.jar') '38B8461C13619569E477BB24AB85F9BFAD317998402FEA3E5ACF0019C6CC8CBA'
    Assert-Hash (Join-Path $WorkspaceRoot 'server/backups/LP/runtime-reference/mythictrpg-1.0.0.jar') '51EF22FAF7F2149183CA58173FBCDBBB3A9C859A9E2198331542B13CFBC18EE8'
    Write-Output 'PASS preserved LP runtime originals (not the active deployment)'
}

$gamePath = Join-Path $WorkspaceRoot 'mythictrpg-main/build/libs/mythictrpg-1.0.2.jar'
$aiPath = Join-Path $WorkspaceRoot 'mythai-ai-response/build/libs/mythai_ai_response-0.1.3.jar'
$game = Get-JarIndex $gamePath
$ai = Get-JarIndex $aiPath
foreach ($required in @('ai/memorycontract/ConversationMemoryContext','ai/memorycontract/MemoryFoundationSettings','rumor/RumorSavedData')) {
    if (!$game.Names.Contains("com/sande/mythictrpg/$required.class")) { throw "Missing game contract: $required" }
}
foreach ($required in @('MemoryJournal','DialogueMemoryBridge','MemoryCommands','MemoryRecallPolicy')) {
    if (!$ai.Names.Contains("com/sande/mythai/response/memory/$required.class")) { throw "Missing AI foundation: $required" }
}
$overlap = @($ai.Names | Where-Object { $_.EndsWith('.class') -and $game.Names.Contains($_) })
if ($overlap.Count) { throw "Duplicate game/AI classes: $($overlap -join ', ')" }
if ($game.Metadata -notmatch 'version\s*=\s*"1\.0\.2"' -or
    $ai.Metadata -notmatch 'version\s*=\s*"0\.1\.3"' -or
    $ai.Metadata -notmatch 'versionRange\s*=\s*"\[1\.0\.2,\)"') { throw 'Wrong release versions or game dependency' }
Write-Output 'PASS versioned JAR contracts, metadata, no duplicate game/AI classes'
& (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths @($gamePath, $aiPath)
# Regression: distinct class names in the old 1.0.1/0.1.1 pair still form a split package.
$brokenPair = @(
    (Join-Path $WorkspaceRoot 'server/backups/before-memory-package-fix-20260914-203705-251/workspace/server/mods/mythictrpg-1.0.1.jar'),
    (Join-Path $WorkspaceRoot 'server/backups/before-memory-package-fix-20260914-203705-251/workspace/server/mods/mythai_ai_response-0.1.1.jar')
)
$caughtSplitPackage = $false
try { & (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1') -JarPaths $brokenPair }
catch {
    if ($_.Exception.Message -notmatch '^Split Java module packages detected') { throw }
    $caughtSplitPackage = $true
}
if (!$caughtSplitPackage) { throw 'Old split-package regression was not detected' }
Write-Output 'PASS split-package regression rejects the broken 1.0.1/0.1.1 pair'
if ($ServerProfile -eq 'PERSONAL') {
    $gameHash = (Get-FileHash -LiteralPath $gamePath -Algorithm SHA256).Hash
    $aiHash = (Get-FileHash -LiteralPath $aiPath -Algorithm SHA256).Hash
    foreach ($relative in @('server/mods/mythictrpg-1.0.2.jar', 'server/client-required-mods/mythictrpg-1.0.2.jar')) {
        Assert-Hash (Join-Path $WorkspaceRoot $relative) $gameHash
    }
    Assert-Hash (Join-Path $WorkspaceRoot 'server/mods/mythai_ai_response-0.1.3.jar') $aiHash
    & (Join-Path $PSScriptRoot 'Test-ModPackageIsolation.ps1')
    $configPath = Join-Path $WorkspaceRoot 'server/config/mythictrpg/ai-memory-foundation.json'
    if ((Get-Item -LiteralPath $configPath).Length -gt 4096) { throw 'Memory configuration too large' }
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    if ($config.schemaVersion -ne 1 -or $config.mode -cne 'PERSONAL') { throw 'Expected PERSONAL memory configuration' }
    Write-Output 'PASS deployed PERSONAL configuration, server/client game pair and AI JAR equal current build'
}
$rejected = $false
try { & (Join-Path $PSScriptRoot 'Test-LPBuild.ps1') -WorkspaceRoot $WorkspaceRoot | Out-Null }
catch {
    if ($_.Exception.Message -notmatch '^response: NOT LP') { throw }
    $rejected = $true
}
if (!$rejected) { throw 'Current-version build was incorrectly reported as LP' }
Write-Output 'PASS LP checker rejects the current 0.1.3 development build'
Get-FileHash -LiteralPath $gamePath,$aiPath -Algorithm SHA256 | Format-List Path,Hash
