<#
.SYNOPSIS
Plans, or explicitly executes, a local frozen-context NPC prompt comparison.
.DESCRIPTION
Without -Execute this script makes no HTTP requests and writes no files. With
-Execute it calls only loopback Ollama /api/chat, sequentially, without retries.
It does not start servers, pull models, change configuration, or write game memory.
This compares generation prompts with fixed classification/history, not complete
two-stage routing or a live multi-turn rollout. Quality is reviewed by a human.
.EXAMPLE
powershell.exe -NoProfile -ExecutionPolicy Bypass -File dev-tools/Compare-NpcHarness.ps1
.EXAMPLE
powershell.exe -NoProfile -ExecutionPolicy Bypass -File dev-tools/Compare-NpcHarness.ps1 -CaseId memory_correction -Execute
#>
[CmdletBinding()]
param(
    [string]$ManifestPath,
    [string]$ConfigPath,
    [string]$OllamaUrl,
    [string[]]$CaseId = @(),
    [ValidateRange(1, 3)][int]$Repetitions = 1,
    [ValidateRange(1, 12)][int]$MaxCases = 6,
    [ValidateRange(180, 300)][int]$TimeoutSeconds = 180,
    [switch]$Execute
)

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$comparisonRoot = Join-Path $taskRoot 'mythai-ai-response\build\npc-harness-comparison'
if ([string]::IsNullOrWhiteSpace($ManifestPath)) { $ManifestPath = Join-Path $comparisonRoot 'cases.json' }
if ([string]::IsNullOrWhiteSpace($ConfigPath)) { $ConfigPath = Join-Path $taskRoot 'server\config\mythictrpg\ai-dialogue.json' }
$utf8 = [Text.UTF8Encoding]::new($false)

function Read-JsonFile([string]$Path) {
    $file = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($file.PSIsContainer -or $file.Length -gt 16777216) { throw "Expected a JSON file at most 16 MiB: $Path" }
    return (Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop)
}

function Assert-LocalChatUrl([string]$Url) {
    $uri = $null
    if (-not [Uri]::TryCreate($Url, [UriKind]::Absolute, [ref]$uri)) { throw 'Invalid Ollama URL.' }
    # .NET Framework may expand [::1] to eight groups; compare the address value.
    $literalAddress = $null
    $isIpv6Loopback = [Net.IPAddress]::TryParse($uri.Host.Trim('[', ']'), [ref]$literalAddress) -and
        $literalAddress.Equals([Net.IPAddress]::IPv6Loopback)
    if ($uri.Scheme -notin @('http', 'https') -or
        ($uri.Host -notin @('localhost', '127.0.0.1') -and -not $isIpv6Loopback) -or
        $uri.AbsolutePath -cne '/api/chat' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        throw 'Only http(s) loopback localhost, 127.0.0.1 or [::1], with exact /api/chat and no credentials/query/fragment, is allowed.'
    }
    return $uri
}

function Assert-ArtifactPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetFullPath($comparisonRoot)
    if (-not $full.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Artifacts must stay in the development comparison build directory.'
    }
    $cursor = $full
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing an artifact path through a symlink/junction: $cursor"
            }
        }
        $parent = [IO.Directory]::GetParent($cursor)
        if ($null -eq $parent) { break }
        $cursor = $parent.FullName
    }
}

function Write-Json([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, (ConvertTo-Json -InputObject $Value -Depth 100), $utf8)
}

function Get-ComparisonSeed([string]$Id, [int]$Repetition) {
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $hash.ComputeHash([Text.Encoding]::UTF8.GetBytes("npc-harness-v1/$Id/$Repetition"))
        return [int]([BitConverter]::ToUInt32($bytes, 0) -band 0x7fffffff)
    } finally { $hash.Dispose() }
}

function Markdown-Cell($Value) {
    return ([string]$Value).Replace('|', '\|').Replace("`r", ' ').Replace("`n", ' ')
}

$manifest = Read-JsonFile $ManifestPath
$config = Read-JsonFile $ConfigPath
if ($manifest.schemaVersion -ne 1 -or $manifest.comparisonKind -cne 'frozen-context-generation') {
    throw 'Expected schemaVersion 1 and comparisonKind frozen-context-generation.'
}
if ([string]::IsNullOrWhiteSpace([string]$config.ollamaModel) -or ([string]$config.ollamaModel).Length -gt 200) {
    throw 'Configuration must contain a nonempty ollamaModel of at most 200 characters.'
}
if ([string]::IsNullOrWhiteSpace($OllamaUrl)) { $OllamaUrl = [string]$config.ollamaChatUrl }
$endpoint = Assert-LocalChatUrl $OllamaUrl
$allCases = @($manifest.cases)
if ($allCases.Count -eq 0 -or $allCases.Count -gt 100) { throw 'Manifest must contain 1 to 100 cases.' }
$seenIds = @{}
foreach ($case in $allCases) {
    if ([string]$case.id -cnotmatch '^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$' -or $seenIds.ContainsKey([string]$case.id)) {
        throw 'Case IDs must be unique, safe ASCII identifiers of at most 64 characters.'
    }
    $seenIds[[string]$case.id] = $true
}
foreach ($id in $CaseId) {
    if (-not $seenIds.ContainsKey($id)) { throw "Unknown case ID: $id" }
}
$selected = @($allCases | Where-Object { $CaseId.Count -eq 0 -or $_.id -in $CaseId })
if ($CaseId.Count -gt 0 -and $selected.Count -gt $MaxCases) { throw 'Selected cases exceed MaxCases; select fewer or explicitly increase MaxCases (maximum 12).' }
$selected = @($selected | Select-Object -First $MaxCases)
if ($selected.Count -eq 0) { throw 'No cases selected.' }

$calls = [Collections.Generic.List[object]]::new()
foreach ($case in $selected) {
    if (@($case.rubric).Count -eq 0) { throw "Case $($case.id) needs a human-review rubric." }
    $variants = @($case.variants)
    if ($variants.Count -ne 2 -or @($variants | Where-Object { $_.name -ceq 'legacy' }).Count -ne 1 -or
        @($variants | Where-Object { $_.name -ceq 'persona' }).Count -ne 1) {
        throw "Case $($case.id) requires exactly one legacy and one persona variant."
    }
    foreach ($variant in $variants) {
        $body = $variant.requestBody
        if ($null -eq $body -or @($body.messages).Count -eq 0 -or $body.stream -isnot [bool] -or $body.stream -ne $false -or
            $body.think -isnot [bool] -or $body.think -ne $false -or $null -eq $body.format -or $null -eq $body.options) {
            throw "Case $($case.id)/$($variant.name) requires messages, production format/options, stream:false and think:false."
        }
        if ($body.format -is [string] -or $body.format.type -cne 'object') { throw 'Use the exported production JSON object schema, not a free-form format.' }
        $tokenCap = 0
        if (-not [int]::TryParse([string]$body.options.num_predict, [ref]$tokenCap) -or $tokenCap -lt 1 -or $tokenCap -gt 8192) {
            throw 'Exported options.num_predict must be an explicit bound between 1 and 8192; it is never replaced by this runner.'
        }
        foreach ($message in @($body.messages)) {
            if ($message.role -cnotin @('system', 'user', 'assistant') -or $message.content -isnot [string] -or
                $null -ne $message.images -or $null -ne $message.tool_calls) {
                throw 'Only text system/user/assistant messages are supported.'
            }
        }
        if ($null -ne $body.tools) { throw 'Tool definitions are not supported in the isolated comparison.' }
        if ((ConvertTo-Json -InputObject $body -Depth 100 -Compress).Length -gt 2097152) { throw 'A request body exceeds 2 MiB of characters.' }
    }
    for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
        $seed = Get-ComparisonSeed ([string]$case.id) $repetition
        # Alternate pair order across repetitions, while preserving a shared seed.
        $ordered = @($variants | Sort-Object name)
        if (($repetition % 2) -eq 0) { [array]::Reverse($ordered) }
        foreach ($variant in $ordered) {
            $body = ConvertTo-Json -InputObject $variant.requestBody -Depth 100 | ConvertFrom-Json
            $body | Add-Member -NotePropertyName model -NotePropertyValue ([string]$config.ollamaModel) -Force
            $body.options | Add-Member -NotePropertyName seed -NotePropertyValue $seed -Force
            $calls.Add([pscustomobject]@{
                caseId = [string]$case.id; variant = [string]$variant.name; repetition = $repetition; seed = $seed; requestBody = $body
            })
        }
    }
}
if ($calls.Count -gt 72) { throw 'At most 72 total generation calls are allowed.' }
$plan = [ordered]@{
    schemaVersion = 1; comparisonKind = 'frozen-context-generation'; execute = [bool]$Execute
    model = [string]$config.ollamaModel; endpoint = $endpoint.AbsoluteUri; timeoutSeconds = $TimeoutSeconds
    caseIds = @($selected | ForEach-Object { $_.id }); repetitions = $Repetitions; plannedCalls = $calls.Count
    limitation = 'Fixed classification/history and supplied context; not an end-to-end classifier evaluation, live conversation rollout, or game action validation. No automatic naturalness scores.'
    manifestSha256 = (Get-FileHash -LiteralPath $ManifestPath -Algorithm SHA256).Hash
}
if (-not $Execute) {
    [pscustomobject]@{ Mode = 'DryRun'; NetworkCalls = 0; FilesWritten = 0; Plan = [pscustomobject]$plan }
    return
}

$runDirectory = Join-Path $comparisonRoot ('runs\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N'))
Assert-ArtifactPath $runDirectory
if (Test-Path -LiteralPath $runDirectory) { throw 'Refusing to reuse an existing run directory.' }
New-Item -ItemType Directory -Path $runDirectory -ErrorAction Stop | Out-Null
Write-Json (Join-Path $runDirectory 'plan.json') $plan
# Keep the selected synthetic fixture, not a copy of operational configuration.
Write-Json (Join-Path $runDirectory 'selected-cases.json') ([ordered]@{
    schemaVersion = 1; comparisonKind = $manifest.comparisonKind; cases = $selected
})
$results = [Collections.Generic.List[object]]::new()
foreach ($call in $calls) {
    $stem = '{0}-{1}-r{2}' -f $call.caseId, $call.variant, $call.repetition
    $requestJson = ConvertTo-Json -InputObject $call.requestBody -Depth 100
    [IO.File]::WriteAllText((Join-Path $runDirectory ($stem + '.request.json')), $requestJson, $utf8)
    $result = [ordered]@{
        caseId = $call.caseId; variant = $call.variant; repetition = $call.repetition; seed = $call.seed
        startedUtc = [DateTime]::UtcNow.ToString('o'); elapsedMilliseconds = 0; httpStatus = $null
        requestError = $null; responseParseError = $null; dialogueParseError = $null
        done = $null; doneReason = $null; evalCount = $null; promptEvalCount = $null
        totalDurationNanoseconds = $null; loadDurationNanoseconds = $null
        promptEvalDurationNanoseconds = $null; evalDurationNanoseconds = $null
        parsedDialogue = $null; parsedSpeech = @(); gameValidated = $false
    }
    $rawResponse = ''
    $webResponse = $null
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $webRequest = [Net.HttpWebRequest]::Create($endpoint)
        $webRequest.Method = 'POST'
        $webRequest.ContentType = 'application/json; charset=utf-8'
        $webRequest.Accept = 'application/json'
        $webRequest.AllowAutoRedirect = $false
        $webRequest.Proxy = $null
        $webRequest.Timeout = $TimeoutSeconds * 1000
        $webRequest.ReadWriteTimeout = $TimeoutSeconds * 1000
        $payload = [Text.Encoding]::UTF8.GetBytes($requestJson)
        $webRequest.ContentLength = $payload.Length
        $stream = $webRequest.GetRequestStream()
        try { $stream.Write($payload, 0, $payload.Length) } finally { $stream.Dispose() }
        $webResponse = $webRequest.GetResponse()
        $result.httpStatus = [int]$webResponse.StatusCode
        $reader = [IO.StreamReader]::new($webResponse.GetResponseStream(), [Text.Encoding]::UTF8)
        try { $rawResponse = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($result.httpStatus -lt 200 -or $result.httpStatus -ge 300) { throw "HTTP $($result.httpStatus); redirects are never followed." }
    } catch {
        $result.requestError = $_.Exception.Message
        if ($null -ne $_.Exception.Response) {
            $errorResponse = $_.Exception.Response
            try {
                $result.httpStatus = [int]$errorResponse.StatusCode
                $errorReader = [IO.StreamReader]::new($errorResponse.GetResponseStream(), [Text.Encoding]::UTF8)
                try { $rawResponse = $errorReader.ReadToEnd() } finally { $errorReader.Dispose() }
            } catch { $result.requestError += '; error response body unavailable' }
            finally { $errorResponse.Close() }
        }
    } finally {
        $stopwatch.Stop()
        $result.elapsedMilliseconds = $stopwatch.ElapsedMilliseconds
        if ($null -ne $webResponse) { $webResponse.Close() }
    }
    [IO.File]::WriteAllText((Join-Path $runDirectory ($stem + '.response.txt')), $rawResponse, $utf8)
    if ($rawResponse) {
        try {
            $response = $rawResponse | ConvertFrom-Json -ErrorAction Stop
            $result.done = $response.done
            $result.doneReason = $response.done_reason
            $result.evalCount = $response.eval_count
            $result.promptEvalCount = $response.prompt_eval_count
            $result.totalDurationNanoseconds = $response.total_duration
            $result.loadDurationNanoseconds = $response.load_duration
            $result.promptEvalDurationNanoseconds = $response.prompt_eval_duration
            $result.evalDurationNanoseconds = $response.eval_duration
            try {
                if ([string]::IsNullOrWhiteSpace([string]$response.message.content)) { throw 'No message.content returned.' }
                $dialogue = [string]$response.message.content | ConvertFrom-Json -ErrorAction Stop
                if ($null -eq $dialogue.PSObject.Properties['speech']) { throw 'JSON has no speech field.' }
                $result.parsedDialogue = $dialogue
                $result.parsedSpeech = @($dialogue.speech)
            } catch { $result.dialogueParseError = $_.Exception.Message }
        } catch { $result.responseParseError = $_.Exception.Message }
    }
    $results.Add([pscustomobject]$result)
    Write-Json (Join-Path $runDirectory ($stem + '.result.json')) $result
    Write-Host ("{0}/{1} r{2}: {3} ms, HTTP {4}, done_reason={5}" -f $call.caseId, $call.variant, $call.repetition,
        $result.elapsedMilliseconds, $result.httpStatus, $result.doneReason)
}
Write-Json (Join-Path $runDirectory 'results.json') @($results.ToArray())
$report = [Collections.Generic.List[string]]::new()
$report.Add('# NPC harness generation comparison')
$report.Add('')
$report.Add('Synthetic, frozen-context generation only. Classification and prior history are fixed; this is not a full two-stage or live multi-turn test. No output is applied to a game. JSON parsing does not imply game validation or naturalness.')
$report.Add('')
$report.Add('Model: ' + (Markdown-Cell $config.ollamaModel) + '. Each pair uses the same seed and exported token settings. Identical seeds do not guarantee identical sampling behavior. Order alternates across repetitions; model loading can affect timing.')
$report.Add('')
$report.Add('| Case | Variant | Repetition | Wall ms | HTTP | Done reason | JSON parsed |')
$report.Add('| --- | --- | --- | ---: | --- | --- | --- |')
foreach ($result in $results) {
    $parsed = $null -ne $result.parsedDialogue
    $report.Add('| ' + ((@($result.caseId, $result.variant, $result.repetition, $result.elapsedMilliseconds,
        $result.httpStatus, $result.doneReason, $parsed) | ForEach-Object { Markdown-Cell $_ }) -join ' | ') + ' |')
}
foreach ($case in $selected) {
    $report.Add('')
    $report.Add('## ' + [string]$case.id)
    $report.Add('')
    $report.Add([string]$case.description)
    $report.Add('')
    $report.Add('| Human review criterion | Legacy | Persona | Notes / counterexamples |')
    $report.Add('| --- | --- | --- | --- |')
    foreach ($criterion in @($case.rubric)) { $report.Add('| ' + (Markdown-Cell $criterion) + ' | Not scored | Not scored | |') }
    foreach ($result in @($results | Where-Object { $_.caseId -eq $case.id })) {
        $report.Add('')
        $report.Add('### ' + $result.variant + ', repetition ' + $result.repetition)
        $report.Add('')
        foreach ($errorMessage in @($result.requestError, $result.responseParseError, $result.dialogueParseError)) {
            if ($errorMessage) { $report.Add('Error: ' + (Markdown-Cell $errorMessage)); $report.Add('') }
        }
        foreach ($speech in @($result.parsedSpeech)) {
            $speechText = if ($speech -is [string]) { $speech } else { [string]$speech.text }
            $report.Add('> ' + $speechText.Replace("`r", '').Replace("`n", "`n> "))
            $report.Add('')
        }
        $report.Add('See the matching .request.json, .response.txt and .result.json files for exact prompts, raw model output, proposals, parse errors, token counts and durations.')
    }
}
[IO.File]::WriteAllLines((Join-Path $runDirectory 'REVIEW.md'), $report.ToArray(), $utf8)
[pscustomobject]@{ Mode = 'Executed'; RunDirectory = $runDirectory; GenerationAttempts = $results.Count; AutomaticQualityScores = $false }
