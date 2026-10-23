$ErrorActionPreference = 'Stop'
$contentWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$contentServer = [IO.Path]::GetFullPath((Join-Path $contentWorkspace 'server'))
$contentArchive = [IO.Path]::GetFullPath((Join-Path $contentWorkspace '구버전/20261022-테스트콘텐츠'))
$contentDeploymentManifest = Join-Path $contentArchive 'deployment-manifest.json'
if (Test-Path -LiteralPath $contentDeploymentManifest) { throw 'Deployment manifest exists; inspect it before repeating this one-time deployment.' }
function Confirm-ContentPath([string]$Path, [string]$Root) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($Root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escaped its intended directory: $full"
    }
    return $full
}
function Confirm-ContentServerStopped {
    $processes = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    if (@($processes | Where-Object { [string]$_.CommandLine -match 'win_args\.txt|net\.minecraft\.server\.Main|BootstrapLauncher.*launchTarget.*server' }).Count) {
        throw 'A Minecraft server is running; no JAR was replaced.'
    }
    if (@(Get-NetTCPConnection -State Listen | Where-Object LocalPort -eq 25565).Count) { throw 'Minecraft port is occupied.' }
    $lockPath = Join-Path $contentServer 'world/session.lock'
    if (Test-Path -LiteralPath $lockPath) {
        $lock = [IO.File]::Open($lockPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
        $lock.Dispose()
    }
}
Confirm-ContentServerStopped
Add-Type -AssemblyName System.IO.Compression.FileSystem
$contentModules = @(
    @{ folder='mythictrpg-main'; name='mythictrpg-1.0.36.jar'; prior='0F26620F338925BA9BD1232ACB5C625E4376B64207916A9CE23B166C5459FB14'; client=$true },
    @{ folder='mythai-ai-response'; name='mythai_ai_response-0.1.50.jar'; prior='9BA722021164888214081229A07DC0AD6D60995AE62235B81E4021E9091423A5'; client=$false },
    @{ folder='mythai-ai-content-registry'; name='mythaiaicontent-0.1.3.jar'; prior='FE759A1D98DDC230BFD9A952EE23D0F7ABD609B12D43E755CFC017E3C5429FF2'; client=$false }
)
$contentArchivedResources = (Get-Content -LiteralPath (Join-Path $contentArchive 'content-manifest.json') -Raw | ConvertFrom-Json).files
$contentTargets = @(foreach ($module in $contentModules) {
    $source = Confirm-ContentPath (Join-Path $contentWorkspace ($module.folder + '/build/libs/' + $module.name)) $contentWorkspace
    $hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    $jar = [IO.Compression.ZipFile]::OpenRead($source)
    try {
        foreach ($entry in $contentArchivedResources) {
            $prefix = $module.folder + '/src/main/resources/'
            if ($entry.action -eq 'MOVE' -and $entry.source.StartsWith($prefix)) {
                if ($null -ne $jar.GetEntry($entry.source.Substring($prefix.Length))) { throw "Archived content is still packaged: $($entry.source)" }
            }
        }
        if (@($jar.Entries | Where-Object { $_.FullName -match '(^|/)(구버전|gametest)/|mythictrpg-legacy-content-fixture' }).Count) {
            throw 'An archive or development fixture directory was included in a production JAR.'
        }
        if ($module.folder -eq 'mythai-ai-content-registry') {
            $common = @($jar.Entries | Where-Object FullName -match '^data/mythaiaicontent/mythai_ai/common_knowledge/.+\.json$').Count
            $classifier = @($jar.Entries | Where-Object FullName -match '^data/mythaiaicontent/mythai_ai/dialogue_examples/intent_classifier/.+\.json$').Count
            if ($common -ne 17 -or $classifier -ne 31) { throw 'Common knowledge or generic classifiers were lost.' }
        }
    } finally { $jar.Dispose() }
    foreach ($targetFolder in @('mods','client-required-mods')) {
        if ($targetFolder -eq 'client-required-mods' -and -not $module.client) { continue }
        $destination = Confirm-ContentPath (Join-Path $contentServer ($targetFolder + '/' + $module.name)) $contentServer
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $module.prior) {
            throw "Installed JAR changed since this work started: $destination"
        }
        $backup = Confirm-ContentPath (Join-Path $contentArchive ('배포전/server/' + $targetFolder + '/' + $module.name)) $contentArchive
        if (Test-Path -LiteralPath $backup) { throw 'Backup already exists.' }
        [pscustomobject]@{source=$source; destination=$destination; backup=$backup; beforeSha256=$module.prior; afterSha256=$hash}
    }
})
foreach ($target in $contentTargets) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $target.backup) -Force | Out-Null
    Copy-Item -LiteralPath $target.destination -Destination $target.backup
    if ((Get-FileHash -LiteralPath $target.backup -Algorithm SHA256).Hash -ne $target.beforeSha256) { throw 'Backup verification failed.' }
}
Confirm-ContentServerStopped
try {
    foreach ($target in $contentTargets) {
        Copy-Item -LiteralPath $target.source -Destination $target.destination -Force
        if ((Get-FileHash -LiteralPath $target.destination -Algorithm SHA256).Hash -ne $target.afterSha256) { throw 'Installed JAR verification failed.' }
    }
} catch {
    foreach ($target in $contentTargets) { Copy-Item -LiteralPath $target.backup -Destination $target.destination -Force }
    throw
}
foreach ($entry in Get-Content -LiteralPath (Join-Path $contentArchive 'preserved-state-manifest.json') -Raw | ConvertFrom-Json) {
    if ((Get-FileHash -LiteralPath (Join-Path $contentWorkspace $entry.source) -Algorithm SHA256).Hash -ne $entry.sha256) {
        throw "Existing player/world data changed: $($entry.source)"
    }
}
$result = [ordered]@{date='2026-10-22'; serverStarted=$false; actualLauncherChanged=$false; targets=$contentTargets}
[IO.File]::WriteAllText($contentDeploymentManifest, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
$contentTargets | Select-Object destination,afterSha256 | Format-List
