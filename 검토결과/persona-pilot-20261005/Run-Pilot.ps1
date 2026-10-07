[CmdletBinding()]
param(
    [string[]]$CaseId = @(),
    [int]$Seed = 1729,
    [string]$RunLabel = 'baseline',
    [int]$MaxNewCases = 100,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$archivePath = 'C:\Users\ADMIN\Downloads\rpg_persona_pilot_C01_C06_v0.1.zip'
$archivePrefix = 'rpg_persona_pilot_C01_C06_v0.1/'
$endpoint = 'http://127.0.0.1:11434'
$model = 'gemma4:12b'
if ($RunLabel -cnotmatch '^[a-z0-9_-]+$') { throw 'Unsafe run label' }
$utf8 = [Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
function Read-Entry([string]$Name) {
    $entry = $archive.GetEntry($archivePrefix + $Name)
    if ($null -eq $entry) { throw "Missing archive entry: $Name" }
    $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
function Save-Json([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, (ConvertTo-Json -InputObject $Value -Depth 100), $utf8)
}
try {
    $roleText = @{ goddess = (Read-Entry 'roles/original_independent_goddess.json') }
    $roleEntries = @{
        farmer = 'roles/경작자_역할카드.md'
        guide = 'roles/숲안내인_역할카드.md'
        apprentice = 'roles/공방견습_역할카드.md'
    }
    foreach ($role in $roleEntries.Keys) {
        $match = [regex]::Match((Read-Entry $roleEntries[$role]), '(?s)## 모델 기본 입력 시작\s*(.*?)\s*## 모델 기본 입력 끝')
        if (-not $match.Success) { throw "Missing model-input boundaries: $role" }
        $roleText[$role] = $match.Groups[1].Value.Trim()
    }
    $testDocument = Read-Entry '02_대화_검증_질문.md'
    $commonBlock = [regex]::Match($testDocument, '(?s)### 공통 역할 정보\s*(.*?)\s*T01–T06은').Groups[1].Value
    $commonRoles = @{}
    $roleLabels = @{goddess='여신';farmer='경작자';guide='안내인';apprentice='견습'}
    foreach ($role in $roleLabels.Keys) {
        $match = [regex]::Match($commonBlock, '(?m)^- \*\*' + $roleLabels[$role] + ':\*\* (.+)$')
        if (-not $match.Success) { throw "Missing common scene: $role" }
        $commonRoles[$role] = $match.Groups[1].Value.Trim()
    }
} finally { $archive.Dispose() }
$allCases = @()
foreach ($caseFile in @('cases-01-14.json','cases-15-28.json')) {
    $path = Join-Path $PSScriptRoot $caseFile
    if (Test-Path -LiteralPath $path) {
        $allCases += @(Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json)
    }
}
if ($allCases.Count -eq 0) { throw 'No case manifests available' }
if (@($allCases.id | Sort-Object -Unique).Count -ne $allCases.Count) { throw 'Duplicate case IDs' }
foreach ($id in $CaseId) { if ($id -cnotin $allCases.id) { throw "Unknown case: $id" } }
$selected = @($allCases | Where-Object { $CaseId.Count -eq 0 -or $_.id -cin $CaseId } | Select-Object -First $MaxNewCases)
foreach ($case in $selected) {
    if ($case.id -cnotmatch '^T\d\d-[a-zA-Z0-9-]+$' -or -not $roleText.ContainsKey($case.role)) { throw 'Invalid case identity' }
    if (@($case.turns).Count -eq 0 -or [string]::IsNullOrWhiteSpace($case.context)) { throw "Empty case: $($case.id)" }
}
if ($DryRun) {
    [pscustomobject]@{cases=$selected.Count;calls=($selected | ForEach-Object { @($_.turns).Count } | Measure-Object -Sum).Sum;ids=$selected.id} | ConvertTo-Json -Depth 5
    exit 0
}
$runPath = Join-Path $PSScriptRoot $RunLabel
if (-not (Test-Path -LiteralPath $runPath)) { $null = New-Item -ItemType Directory -Path $runPath }
$version = Invoke-RestMethod -Uri "$endpoint/api/version" -TimeoutSec 10
$tags = Invoke-RestMethod -Uri "$endpoint/api/tags" -TimeoutSec 10
$installed = @($tags.models | Where-Object { $_.name -ceq $model })
if ($installed.Count -ne 1) { throw 'Exact model must already be installed; no pull permitted' }
$show = Invoke-RestMethod -Uri "$endpoint/api/show" -Method Post -ContentType 'application/json' -Body (@{model=$model} | ConvertTo-Json) -TimeoutSec 10
$options = @{temperature=0.7;top_p=0.9;top_k=64;num_ctx=8192;num_predict=512;seed=$Seed}
$metadataPath = Join-Path $runPath 'metadata.json'
if (-not (Test-Path -LiteralPath $metadataPath)) {
    Save-Json $metadataPath ([ordered]@{
        createdAt=[DateTimeOffset]::Now.ToString('o');kind='direct-ollama-persona-pilot';model=$model
        installedModel=$installed[0];ollamaVersion=$version.version;modelDefaults=$show.parameters
        sourceZip=$archivePath;sourceZipSha256=(Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
        think=$false;options=$options;responseFormat='plain NPC dialogue; no game JSON contract'
        serverStartedByThisTaskPid=29976;serverConfigChanged=$false;worldWrites=$false
        limitations=@('Not the Minecraft two-stage engine','Synthetic supplied scene and history','No persistent memory retrieval or game action execution','Rubrics excluded from model inputs')
    })
}
$completed = 0
$consecutiveErrors = 0
foreach ($case in $selected) {
    $casePath = Join-Path $runPath ($case.id + '.json')
    if (Test-Path -LiteralPath $casePath) { Write-Output "SKIP existing $($case.id)"; continue }
    $scene = [string]$case.context
    if ($case.scenario -cin @('T01','T02','T03','T04','T05','T06','T13')) { $scene = $commonRoles[$case.role] + "`n" + $scene }
    $baseSystem = "제공된 한 역할로 한국어 대화를 한다. 실제 NPC가 상대에게 하는 대사만 출력한다. 작성 지침을 해설하거나 시험 답안을 설명하지 않는다.`n`n[역할 기본 입력]`n" + $roleText[$case.role]
    $dialogue = [Collections.Generic.List[object]]::new()
    foreach ($message in @($case.history)) {
        if ($null -eq $message) { continue }
        if ($message.role -cnotin @('user','assistant') -or $message.content -isnot [string]) { throw 'Invalid supplied history' }
        $dialogue.Add(@{role=$message.role;content=$message.content})
    }
    $records = [Collections.Generic.List[object]]::new()
    $turnNumber = 0
    $caseError = $false
    foreach ($turn in @($case.turns)) {
        $turnNumber++
        if ($turn.contextUpdate) { $scene += "`n[이후 새로 전달된 확인 결과]`n" + $turn.contextUpdate }
        $dialogue.Add(@{role='user';content=[string]$turn.user})
        $messages = @(@{role='system';content=($baseSystem + "`n`n[현재 화자에게 제공되는 허용된 상황과 이력]`n" + $scene)}) + $dialogue.ToArray()
        $body = @{model=$model;messages=$messages;stream=$false;think=$false;keep_alive='5m';options=$options}
        $record = [ordered]@{turn=$turnNumber;startedAt=[DateTimeOffset]::Now.ToString('o');request=$body}
        $stopwatch = [Diagnostics.Stopwatch]::StartNew()
        try {
            $requestBytes = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $body -Depth 30 -Compress))
            $response = Invoke-RestMethod -Uri "$endpoint/api/chat" -Method Post -ContentType 'application/json; charset=utf-8' -Body $requestBytes -TimeoutSec 240
            $stopwatch.Stop()
            $record.response = $response
            $record.wallSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds,3)
            if ([string]::IsNullOrWhiteSpace([string]$response.message.content)) { throw 'Empty assistant content' }
            $dialogue.Add(@{role='assistant';content=[string]$response.message.content})
            Write-Output ("DONE {0} turn={1} seconds={2} tokens={3} finish={4}" -f $case.id,$turnNumber,$record.wallSeconds,$response.eval_count,$response.done_reason)
            $consecutiveErrors = 0
        } catch {
            $stopwatch.Stop()
            $record.error = $_.Exception.Message
            $record.wallSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds,3)
            $consecutiveErrors++
            $caseError = $true
            Write-Output "ERROR $($case.id) turn=$turnNumber $($_.Exception.Message)"
        }
        $records.Add($record)
        Save-Json $casePath ([ordered]@{id=$case.id;scenario=$case.scenario;role=$case.role;fixture=$case;records=$records.ToArray();complete=($turnNumber -eq @($case.turns).Count -and -not $caseError);reviewStatus='unreviewed'})
        if ($caseError) { break }
    }
    $completed++
    if ($consecutiveErrors -ge 2) { throw 'Two consecutive request failures; stopping without retries' }
}
Write-Output "FINISHED cases=$completed run=$RunLabel"
