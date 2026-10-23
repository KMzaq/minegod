param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$GameSha256,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$AiSha256,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ContentSha256
)

# Explicitly authorized experimental deployment; no server boot or launcher changes.
$ErrorActionPreference = 'Stop'
$deployWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$deployServer = (Resolve-Path -LiteralPath (Join-Path $deployWorkspace 'server')).Path
$deployStamp = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(9)).ToString('yyyyMMdd-HHmmss-fff')
$deployBackup = Join-Path $deployServer ('backups/test-latest-' + $deployStamp)

function Confirm-ServerPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($deployServer + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escaped the named server: $full"
    }
    return $full
}
function Confirm-Hash([string]$Path, [string]$Expected) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Expected) { throw "Hash mismatch: $Path" }
}
function Confirm-ServerStopped {
    $running = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    $servers = @($running | Where-Object {
        [string]$_.CommandLine -match 'win_args\.txt|net\.minecraft\.server\.Main|BootstrapLauncher.*launchTarget.*server'
    })
    if ($servers.Count) { throw 'A Minecraft server is running; stop it normally before deployment.' }
    if (@(Get-NetTCPConnection -State Listen | Where-Object LocalPort -eq 25565).Count) {
        throw 'Minecraft port 25565 is occupied.'
    }
    $lockPath = Confirm-ServerPath (Join-Path $deployServer 'world/session.lock')
    if (Test-Path -LiteralPath $lockPath) {
        $lock = [IO.File]::Open($lockPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $lock.Dispose()
    }
}

$deployModules = @(
    [PSCustomObject]@{ Id='mythictrpg'; Folder='mythictrpg-main'; Old='mythictrpg-1.0.28.jar'; New='mythictrpg-1.0.36.jar'; Hash=$GameSha256; Client=$true },
    [PSCustomObject]@{ Id='mythai_ai_response'; Folder='mythai-ai-response'; Old='mythai_ai_response-0.1.19.jar'; New='mythai_ai_response-0.1.50.jar'; Hash=$AiSha256; Client=$false },
    [PSCustomObject]@{ Id='mythaiaicontent'; Folder='mythai-ai-content-registry'; Old='mythaiaicontent-0.1.2.jar'; New='mythaiaicontent-0.1.3.jar'; Hash=$ContentSha256; Client=$false }
)
Confirm-ServerStopped
$null = Confirm-ServerPath $deployBackup
if (Test-Path -LiteralPath $deployBackup) { throw 'Unique backup directory already exists.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$deployTargets = @(foreach ($module in $deployModules) {
    $source = (Resolve-Path -LiteralPath (Join-Path $deployWorkspace ($module.Folder + '/build/libs/' + $module.New))).Path
    Confirm-Hash $source $module.Hash
    $archive = [IO.Compression.ZipFile]::OpenRead($source)
    try {
        $metadata = $archive.GetEntry('META-INF/neoforge.mods.toml')
        if ($null -eq $metadata) { throw "Missing mod metadata: $source" }
        $reader = [IO.StreamReader]::new($metadata.Open())
        try { $toml = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $id = [regex]::Match($toml, '(?m)^modId\s*=\s*"([^"]+)"').Groups[1].Value
        if ($id -ne $module.Id) { throw "Wrong mod ID in $source" }
    } finally { $archive.Dispose() }
    foreach ($folder in @('mods', 'client-required-mods')) {
        if ($folder -eq 'client-required-mods' -and -not $module.Client) { continue }
        $directory = Confirm-ServerPath (Join-Path $deployServer $folder)
        $existing = @(Get-ChildItem -LiteralPath $directory -File -Filter ($module.Id + '-*.jar'))
        if ($existing.Count -ne 1 -or $existing[0].Name -ne $module.Old) {
            throw "Unexpected $($module.Id) JAR state in $directory; nothing replaced."
        }
        $destination = Confirm-ServerPath (Join-Path $directory $module.New)
        if (Test-Path -LiteralPath $destination) { throw "Destination exists: $destination" }
        [PSCustomObject]@{ Folder=$folder; Old=(Confirm-ServerPath $existing[0].FullName); Destination=$destination; Source=$source; Hash=$module.Hash }
    }
})

New-Item -ItemType Directory -Path $deployBackup | Out-Null
$deployFiles = @(foreach ($folder in @('mods','client-required-mods','world','config','defaultconfigs',
        'mythictrpg-ai-data','mythictrpg-dialogue-logs')) {
    $path = Confirm-ServerPath (Join-Path $deployServer $folder)
    if (Test-Path -LiteralPath $path -PathType Container) { Get-ChildItem -LiteralPath $path -Recurse -File -Force }
})
$deployFiles += @(foreach ($name in @('server.properties','README.md','eula.txt','ops.json','whitelist.json',
        'banned-ips.json','banned-players.json','usercache.json','usernamecache.json','user_jvm_args.txt',
        'run.bat','start-neoforge-ai-server.bat')) {
    $path = Confirm-ServerPath (Join-Path $deployServer $name)
    if (Test-Path -LiteralPath $path -PathType Leaf) { Get-Item -LiteralPath $path }
})
$deployManifest = @(foreach ($file in $deployFiles) {
    $relative = $file.FullName.Substring($deployServer.Length + 1)
    $copy = Confirm-ServerPath (Join-Path $deployBackup $relative)
    New-Item -ItemType Directory -Path (Split-Path $copy) -Force | Out-Null
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    Copy-Item -LiteralPath $file.FullName -Destination $copy
    Confirm-Hash $copy $hash
    [PSCustomObject]@{ RelativePath=$relative; Bytes=$file.Length; SHA256=$hash }
})
# Generated backup inventory, not an authored configuration file.
$deployManifest | Export-Csv -LiteralPath (Join-Path $deployBackup 'backup-manifest.csv') -NoTypeInformation -Encoding UTF8
Confirm-ServerStopped

$deployMoved = @()
$deployInstalled = @()
try {
    foreach ($target in $deployTargets) {
        Confirm-Hash $target.Source $target.Hash
        $retired = Confirm-ServerPath (Join-Path $deployBackup ('retired/' + $target.Folder + '/' + (Split-Path $target.Old -Leaf)))
        New-Item -ItemType Directory -Path (Split-Path $retired) -Force | Out-Null
        Move-Item -LiteralPath $target.Old -Destination $retired
        $deployMoved += [PSCustomObject]@{ Old=$target.Old; Retired=$retired }
        $deployInstalled += $target.Destination
        Copy-Item -LiteralPath $target.Source -Destination $target.Destination
        Confirm-Hash $target.Destination $target.Hash
    }
    # World, configuration, memory, FTB dependencies and all other preserved files stay identical.
    foreach ($entry in $deployManifest) {
        $path = Confirm-ServerPath (Join-Path $deployServer $entry.RelativePath)
        if ($path -notin $deployTargets.Old) { Confirm-Hash $path $entry.SHA256 }
    }
} catch {
    $deployFailure = $_
    foreach ($path in $deployInstalled) {
        if (Test-Path -LiteralPath $path) {
            $relative = $path.Substring($deployServer.Length + 1)
            $failed = Confirm-ServerPath (Join-Path $deployBackup ('failed/' + $relative))
            New-Item -ItemType Directory -Path (Split-Path $failed) -Force | Out-Null
            Move-Item -LiteralPath $path -Destination $failed
        }
    }
    foreach ($moved in $deployMoved) { Copy-Item -LiteralPath $moved.Retired -Destination $moved.Old }
    throw $deployFailure
}
[PSCustomObject]@{
    GameVersion='1.0.36'; AiVersion='0.1.50'; ContentVersion='0.1.3'
    Backup=$deployBackup; BackupFiles=$deployManifest.Count
    BackupBytes=($deployManifest | Measure-Object Bytes -Sum).Sum
    Installed=$deployInstalled; Hashes=@($deployModules | Select-Object Id,New,Hash)
    ServerStarted=$false; LauncherChanged=$false; KnownDialogueQualityIssuesAccepted=$true
} | ConvertTo-Json -Depth 5
