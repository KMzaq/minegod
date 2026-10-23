param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$RunId,
    [string[]]$Only = @(),
    [switch]$SkipBuild,
    [switch]$CombatMods,
    [switch]$ListOnly
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$gameRoot = Join-Path $taskRoot 'mythictrpg-main'
$buildRoot = [IO.Path]::GetFullPath((Join-Path $gameRoot 'build'))
$runRoot = [IO.Path]::GetFullPath((Join-Path $buildRoot $RunId))
if (!$runRoot.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Fixture output must remain under the game build directory'
}

# Discover actual template namespaces, not GameTestHolder labels (which can differ).
# Count every annotation and fail closed if a new fixture needs a different discovery mechanism.
function Read-TestInventory([string]$Module) {
    $inventory = @{}
    $files = & rg --files (Join-Path $taskRoot "$Module/src/main/java") -g '*.java'
    if ($LASTEXITCODE -ne 0) { throw "Cannot enumerate $Module test sources" }
    foreach ($file in $files) {
        $source = [IO.File]::ReadAllText($file)
        if ($source -match '@GameTestGenerator\b') { throw "Add generated-test discovery for $file" }
        foreach ($annotation in [regex]::Matches($source, '@GameTest\(([^)]*)\)')) {
            $namespace = [regex]::Match($annotation.Groups[1].Value, 'templateNamespace\s*=\s*"([^"]+)"')
            if (!$namespace.Success) { throw "Nonliteral test namespace in $file" }
            $key = $namespace.Groups[1].Value
            $inventory[$key] = 1 + [int]$inventory[$key]
        }
    }
    return $inventory
}
function Read-Property([string]$Module, [string]$Key) {
    $line = Get-Content -LiteralPath (Join-Path $taskRoot "$Module/gradle.properties") |
        Where-Object { $_ -match "^$Key=" }
    if (@($line).Count -ne 1) { throw "Ambiguous property $Module/$Key" }
    return ($line -split '=', 2)[1].Trim()
}
$game = Read-TestInventory 'mythictrpg-main'
$ai = Read-TestInventory 'mythai-ai-response'
$cases = @(
    foreach ($namespace in ($game.Keys | Sort-Object)) {
        [pscustomobject]@{ Name = $namespace; Namespace = $namespace; Expected = $game[$namespace]; Ai = $false; Shadow = $false }
    }
    foreach ($namespace in ($ai.Keys | Sort-Object)) {
        [pscustomobject]@{ Name = $namespace; Namespace = $namespace; Expected = $ai[$namespace]; Ai = $true; Shadow = ($namespace -eq 'mythai_native_content_evidence') }
    }
    if ($ai.ContainsKey('mythai_room_full_path')) {
        [pscustomobject]@{ Name = 'mythai_room_full_path_shadow'; Namespace = 'mythai_room_full_path'; Expected = $ai['mythai_room_full_path']; Ai = $true; Shadow = $true }
    }
)
foreach ($name in $Only) {
    if ($name -notin $cases.Name) { throw "Unknown case: $name" }
}
if ($Only.Count) { $cases = @($cases | Where-Object { $_.Name -in $Only }) }
if ($ListOnly) { $cases | Format-Table; exit 0 }
if (Test-Path -LiteralPath $runRoot) { throw 'Use a fresh RunId; existing results/worlds are preserved' }
New-Item -ItemType Directory -Path $runRoot | Out-Null
$cases | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runRoot 'inventory.json') -Encoding UTF8
$results = [Collections.Generic.List[object]]::new()
$wrapper = Join-Path $PSScriptRoot 'run-mythictrpg-gradle.cmd'

function Invoke-LoggedGradle([string]$Name, [string[]]$Arguments) {
    $log = Join-Path $runRoot "$Name.gradle.log"
    Write-Output "START $Name"
    # Native stderr may contain JVM warnings; the process exit code remains authoritative.
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $wrapper @Arguments --offline --no-daemon --max-workers=1 *> $log; $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $savedPreference }
    return [pscustomobject]@{ Exit = $code; Log = $log }
}
function Save-Results {
    $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runRoot 'results.json') -Encoding UTF8
}

Push-Location $taskRoot
try {
    if (!$SkipBuild) {
        foreach ($module in @('mythictrpg-main', 'mythai-ai-response', 'mythai-ai-content-registry')) {
            $name = "build-$module"
            $outcome = @(Invoke-LoggedGradle $name @('-p', "../$module", 'build'))[-1]
            $results.Add([pscustomobject]@{ Name = $name; Passed = ($outcome.Exit -eq 0); Exit = $outcome.Exit; Log = $outcome.Log })
            Save-Results
            Write-Output "$name exit=$($outcome.Exit)"
            if ($outcome.Exit -ne 0) { throw "Build failed: $module; no stale combined JAR will be tested" }
        }
    }
    $aiJar = "../mythai-ai-response/build/libs/$(Read-Property 'mythai-ai-response' 'mod_id')-$(Read-Property 'mythai-ai-response' 'mod_version').jar"
    $contentJar = "../mythai-ai-content-registry/build/libs/$(Read-Property 'mythai-ai-content-registry' 'mod_id')-$(Read-Property 'mythai-ai-content-registry' 'mod_version').jar"
    foreach ($case in $cases) {
        $namespace = $case.Namespace
        $fixture = Join-Path $runRoot $case.Name
        $enabled = if ($namespace -eq 'mythictrpg_raid') { "$namespace,mythictrpg_raid_trial" } else { $namespace }
        $arguments = @('runRoomIntegrationGameTestServer', "-ProomTestDirectory=build/$RunId/$($case.Name)", "-ProomTestNamespaces=$enabled")
        if ($CombatMods -or $namespace -eq 'mythictrpg_combat_compat') { $arguments += '-PcombatMods=true' }
        if ($case.Ai) { $arguments += @('-ProomAiIntegration=true', "-ProomAiResponseJar=$aiJar", "-ProomAiContentJar=$contentJar") }
        if ($case.Shadow -or $namespace -in @('mythictrpg_native_evidence', 'mythictrpg_native_interpretation')) {
            $arguments += '-ProomNativeRetrievalShadow=true'
        }
        if ($namespace -eq 'mythai_recording_policy') { $arguments += '-ProomNativeRetrievalMode=NEW' }
        if ($namespace -eq 'mythai_native_dialogue') { $arguments += @('-ProomNativeRetrievalMode=NEW', '-ProomSupportDialogue=true', '-ProomSupportStatistics=true') }
        if ($namespace -in @('mythictrpg_recording_channels', 'mythictrpg_recording_rumor')) {
            $config = Join-Path $fixture 'config/mythictrpg'
            New-Item -ItemType Directory -Path $config -Force | Out-Null
            $memoryMode = if ($namespace -eq 'mythictrpg_recording_rumor') { 'RUMOR_TEST' } else { 'PERSONAL' }
            @{ schemaVersion = 1; mode = $memoryMode } | ConvertTo-Json |
                Set-Content -LiteralPath (Join-Path $config 'ai-memory-foundation.json') -Encoding UTF8
            @{ schemaVersion = 2; archiveMode = 'RECORD_ONLY'; worldRecordingLimitBytes = 2147483648L;
                maintenanceHeadroomBytes = 16777216L; warningRatio = 0.90; deferBackgroundRatio = 0.95;
                overflowPolicy = 'STOP_NEW_RECORDS'; automaticRawDeletion = $false; importLegacyTestData = $false } |
                ConvertTo-Json | Set-Content -LiteralPath (Join-Path $config 'recording-v2.json') -Encoding UTF8
        }
        $outcome = @(Invoke-LoggedGradle $case.Name $arguments)[-1]
        $logText = [IO.File]::ReadAllText($outcome.Log)
        $complete = [regex]::Matches($logText, '(\d+) GAME TESTS COMPLETE')
        $executed = if ($complete.Count -eq 1) { [int]$complete[0].Groups[1].Value } else { 0 }
        $passed = $outcome.Exit -eq 0 -and $executed -eq $case.Expected -and $logText -match 'All \d+ required tests passed'
        $results.Add([pscustomobject]@{ Name = $case.Name; Passed = $passed; Exit = $outcome.Exit;
            Expected = $case.Expected; Executed = $executed; Log = $outcome.Log; Arguments = $arguments })
        Save-Results
        Write-Output "$($case.Name) passed=$passed executed=$executed/$($case.Expected) exit=$($outcome.Exit)"
    }
} finally { Pop-Location }
$failures = @($results | Where-Object { !$_.Passed })
Write-Output "MATRIX completed=$($results.Count) failed=$($failures.Count) results=$runRoot/results.json"
if ($failures.Count) { exit 1 }
