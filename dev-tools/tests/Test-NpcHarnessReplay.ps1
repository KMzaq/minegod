<# Offline safety/manifest checks only. No test passes -Execute. Fixtures stay in build. #>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$runner = Join-Path $taskRoot 'dev-tools\Compare-NpcHarness.ps1'
$fixtureRoot = Join-Path $taskRoot ('mythai-ai-response\build\npc-harness-comparison\offline-tests\' + [Guid]::NewGuid().ToString('N'))
$utf8 = [Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Path $fixtureRoot -ErrorAction Stop | Out-Null
$manifestPath = Join-Path $fixtureRoot 'cases.json'
$configPath = Join-Path $fixtureRoot 'config.json'
$script:checks = 0

function Assert-True([bool]$Condition, [string]$Message) {
    $script:checks++
    if (-not $Condition) { throw $Message }
}
function Save-Json([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, (ConvertTo-Json -InputObject $Value -Depth 100), $utf8)
}
function Assert-Rejected([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-True $rejected $Message
}
function New-Case([string]$Id) {
    $variants = foreach ($name in @('legacy', 'persona')) {
        [ordered]@{
            name = $name
            requestBody = [ordered]@{
                model = 'export-placeholder'; stream = $false; think = $false
                messages = @([ordered]@{ role = 'system'; content = 'SYNTHETIC FIXTURE, no live player data' },
                    [ordered]@{ role = 'user'; content = ([string][char]0xC548 + [char]0xB155) })
                format = [ordered]@{ type = 'object'; properties = @{ speech = @{ type = 'array' } }; required = @('speech') }
                options = [ordered]@{ temperature = 0.65; num_predict = 512 }
            }
        }
    }
    return [ordered]@{ id = $Id; description = 'Offline fixture'; rubric = @('Preserve context'); variants = @($variants) }
}
function New-Manifest([int]$Count = 2) {
    $cases = for ($i = 1; $i -le $Count; $i++) { New-Case ('case_' + $i) }
    return [ordered]@{ schemaVersion = 1; comparisonKind = 'frozen-context-generation'; cases = @($cases) }
}

$config = [ordered]@{ ollamaModel = 'fixture-only'; ollamaChatUrl = 'http://127.0.0.1:1/api/chat' }
Save-Json $configPath $config
Save-Json $manifestPath (New-Manifest)
$configHash = (Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash
$manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
$runsPath = Join-Path $taskRoot 'mythai-ai-response\build\npc-harness-comparison\runs'
$runsBefore = if (Test-Path -LiteralPath $runsPath) { @(Get-ChildItem -LiteralPath $runsPath -Force | ForEach-Object Name) } else { @() }
$plan = & $runner -ManifestPath $manifestPath -ConfigPath $configPath
Assert-True ($plan.Mode -eq 'DryRun' -and $plan.NetworkCalls -eq 0 -and $plan.FilesWritten -eq 0) 'Dry-run must not call HTTP or write artifacts.'
Assert-True ($plan.Plan.model -eq 'fixture-only' -and $plan.Plan.plannedCalls -eq 4) 'Use the current config model and count both variants.'
Assert-True ((Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash -eq $configHash) 'Configuration changed.'
Assert-True ((Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash -eq $manifestHash) 'Manifest changed.'
$plan = & $runner -ManifestPath $manifestPath -ConfigPath $configPath -CaseId case_2 -Repetitions 3
Assert-True ($plan.Plan.plannedCalls -eq 6 -and $plan.Plan.caseIds.Count -eq 1 -and $plan.Plan.caseIds[0] -eq 'case_2') 'Case filtering/repetitions failed.'
$plan = & $runner -ManifestPath $manifestPath -ConfigPath $configPath -OllamaUrl 'http://[::1]:11434/api/chat'
Assert-True ($plan.NetworkCalls -eq 0) 'IPv6 loopback should be accepted without HTTP.'
$plan = & $runner -ManifestPath $manifestPath -ConfigPath $configPath -OllamaUrl 'http://[0000:0000:0000:0000:0000:0000:0000:0001]:11434/api/chat'
Assert-True ($plan.NetworkCalls -eq 0) 'Expanded .NET Framework IPv6 loopback should be accepted without HTTP.'
$plan = & $runner -ManifestPath $manifestPath -ConfigPath $configPath -OllamaUrl 'http://localhost:11434/api/chat'
Assert-True ($plan.NetworkCalls -eq 0) 'Named loopback should be accepted without HTTP.'
foreach ($url in @('https://example.com/api/chat', 'http://127.0.0.1.evil.example/api/chat',
    'http://user:pass@127.0.0.1/api/chat', 'http://127.0.0.1/api/pull',
    'http://127.0.0.1/api/chat?model=other', 'http://127.0.0.1/api/chat#fragment',
    'http://[::2]:11434/api/chat', 'http://127.0.0.2:11434/api/chat',
    'file:///api/chat')) {
    Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath -OllamaUrl $url } ('Unsafe URL accepted: ' + $url)
}
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath -CaseId absent } 'Missing case accepted.'
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath -Repetitions 4 } 'Unbounded repetitions accepted.'
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath -MaxCases 13 } 'Unbounded cases accepted.'
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath -TimeoutSeconds 301 } 'Unbounded timeout accepted.'

$bad = New-Manifest
$bad.schemaVersion = 2
Save-Json $manifestPath $bad
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath } 'Unsupported schema accepted.'
$bad = New-Manifest
$bad.comparisonKind = 'live-session-rollout'
Save-Json $manifestPath $bad
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath } 'Mislabelled comparison accepted.'
$bad = New-Manifest
$bad.cases[1].id = $bad.cases[0].id
Save-Json $manifestPath $bad
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath } 'Duplicate case IDs accepted.'
$bad = New-Manifest
$bad.cases[0].id = '..\outside'
Save-Json $manifestPath $bad
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath } 'Unsafe path-like case ID accepted.'
$bad = New-Manifest
$bad.cases[0].variants[1].name = 'legacy'
Save-Json $manifestPath $bad
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath } 'Duplicate variant accepted.'
$bad = New-Manifest
$bad.cases[0].variants[0].requestBody.stream = $true
Save-Json $manifestPath $bad
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath } 'Streaming accepted.'
$bad = New-Manifest
$bad.cases[0].variants[0].requestBody.options.num_predict = -1
Save-Json $manifestPath $bad
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath } 'Unlimited token generation accepted.'
$bad = New-Manifest
$bad.cases[0].variants[0].requestBody.tools = @(@{ type = 'function' })
Save-Json $manifestPath $bad
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath } 'Tool use accepted.'

Save-Json $manifestPath (New-Manifest 12)
$plan = & $runner -ManifestPath $manifestPath -ConfigPath $configPath -MaxCases 12 -Repetitions 3
Assert-True ($plan.Plan.plannedCalls -eq 72) 'Maximum bounded plan should contain 72 calls.'
$plan = & $runner -ManifestPath $manifestPath -ConfigPath $configPath
Assert-True ($plan.Plan.plannedCalls -eq 12 -and $plan.Plan.caseIds.Count -eq 6) 'Default should cap at six cases.'
Assert-Rejected { & $runner -ManifestPath $manifestPath -ConfigPath $configPath -MaxCases 1 -CaseId case_1,case_2 } 'Explicit selected case list silently truncated.'
$runsAfter = if (Test-Path -LiteralPath $runsPath) { @(Get-ChildItem -LiteralPath $runsPath -Force | ForEach-Object Name) } else { @() }
Assert-True (($runsBefore -join '|') -ceq ($runsAfter -join '|')) 'Dry-run/validation created a run directory.'
[pscustomobject]@{ Checks = $script:checks; Status = 'PASS'; NetworkCalls = 0; Fixtures = $fixtureRoot; Scope = 'Offline manifest/safety checks only; no model-quality validation.' }
