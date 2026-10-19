# Deploy only the verified first-raid game artifact. No server boot or launcher writes.
$ErrorActionPreference = 'Stop'
$raidWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$raidServer = (Resolve-Path -LiteralPath (Join-Path $raidWorkspace 'server')).Path
$raidHash = 'F7F75C034FA152D996407BCAF0653B0E5823CA831F7B17F4FDE2DAE929AFBAD2'
$raidSource = (Resolve-Path -LiteralPath (Join-Path $raidWorkspace 'mythictrpg-main/build/libs/mythictrpg-1.0.28.jar')).Path
$raidBackup = Join-Path $raidServer ('backups/raid-trial-20261013-' + (Get-Date -Format 'HHmmss-fff'))

function Confirm-RaidPath([string]$path) {
    $full = [IO.Path]::GetFullPath($path)
    if (-not $full.StartsWith($raidServer + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escaped the named server: $full"
    }
    return $full
}
function Confirm-RaidHash([string]$path, [string]$expected) {
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $expected) { throw "Hash mismatch: $path" }
}
function Confirm-RaidServerStopped {
    # Conservative preflight; never stop unrelated Java processes automatically.
    $running = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    if ($running.Count) { throw 'Java is running; inspect the exact process before deploying.' }
    $listeners = @(Get-NetTCPConnection -State Listen)
    if (@($listeners | Where-Object LocalPort -eq 25565).Count) { throw 'Minecraft port 25565 is occupied.' }
}

Confirm-RaidHash $raidSource $raidHash
Confirm-RaidServerStopped
$null = Confirm-RaidPath $raidBackup
if (Test-Path -LiteralPath $raidBackup) { throw 'Unique backup path already exists.' }
$raidTargets = @(foreach ($folder in @('mods', 'client-required-mods')) {
    $directory = Confirm-RaidPath (Join-Path $raidServer $folder)
    $old = @(Get-ChildItem -LiteralPath $directory -File -Filter 'mythictrpg-*.jar')
    if ($old.Count -ne 1 -or $old[0].Name -ne 'mythictrpg-1.0.16.jar') {
        throw "Unexpected game JAR state in $directory; no automatic replacement."
    }
    $destination = Confirm-RaidPath (Join-Path $directory 'mythictrpg-1.0.28.jar')
    if (Test-Path -LiteralPath $destination) { throw "Destination already exists: $destination" }
    [PSCustomObject]@{ Folder=$folder; Old=(Confirm-RaidPath $old[0].FullName); Destination=$destination }
})

New-Item -ItemType Directory -Path $raidBackup | Out-Null
$raidFiles = @(foreach ($folder in @('mods','client-required-mods','world','config','defaultconfigs',
        'mythictrpg-ai-data','mythictrpg-dialogue-logs')) {
    $path = Confirm-RaidPath (Join-Path $raidServer $folder)
    if (Test-Path -LiteralPath $path -PathType Container) { Get-ChildItem -LiteralPath $path -File -Recurse -Force }
})
$raidFiles += @(foreach ($name in @('server.properties','README.md','eula.txt','ops.json','whitelist.json',
        'banned-ips.json','banned-players.json','usercache.json','usernamecache.json','user_jvm_args.txt',
        'run.bat','start-neoforge-ai-server.bat')) {
    $path = Confirm-RaidPath (Join-Path $raidServer $name)
    if (Test-Path -LiteralPath $path -PathType Leaf) { Get-Item -LiteralPath $path }
})
$raidManifest = @(foreach ($file in $raidFiles) {
    $relative = [IO.Path]::GetRelativePath($raidServer, $file.FullName)
    $copy = Confirm-RaidPath (Join-Path $raidBackup $relative)
    New-Item -ItemType Directory -Path (Split-Path $copy) -Force | Out-Null
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    Copy-Item -LiteralPath $file.FullName -Destination $copy
    Confirm-RaidHash $copy $hash
    [PSCustomObject]@{ RelativePath=$relative; Bytes=$file.Length; SHA256=$hash }
})
$raidManifest | Export-Csv -LiteralPath (Join-Path $raidBackup 'backup-manifest.csv') -NoTypeInformation -Encoding utf8
Confirm-RaidServerStopped

$raidMoved = @()
$raidInstalled = @()
try {
    foreach ($target in $raidTargets) {
        $retired = Confirm-RaidPath (Join-Path $raidBackup ('retired/' + $target.Folder + '/mythictrpg-1.0.16.jar'))
        New-Item -ItemType Directory -Path (Split-Path $retired) -Force | Out-Null
        Move-Item -LiteralPath $target.Old -Destination $retired
        $raidMoved += [PSCustomObject]@{ Old=$target.Old; Retired=$retired }
        $raidInstalled += $target.Destination
        Copy-Item -LiteralPath $raidSource -Destination $target.Destination
        Confirm-RaidHash $target.Destination $raidHash
    }
    # Everything except the two deliberately retired game files must remain byte-identical.
    foreach ($entry in $raidManifest) {
        $path = Confirm-RaidPath (Join-Path $raidServer $entry.RelativePath)
        if ($path -notin $raidTargets.Old) { Confirm-RaidHash $path $entry.SHA256 }
    }
} catch {
    $raidFailure = $_
    foreach ($path in $raidInstalled) {
        if (Test-Path -LiteralPath $path) {
            $failed = Confirm-RaidPath (Join-Path $raidBackup ('failed/' + [IO.Path]::GetRelativePath($raidServer, $path)))
            New-Item -ItemType Directory -Path (Split-Path $failed) -Force | Out-Null
            Move-Item -LiteralPath $path -Destination $failed
        }
    }
    foreach ($moved in $raidMoved) { Copy-Item -LiteralPath $moved.Retired -Destination $moved.Old }
    throw $raidFailure
}
$raidResult = [PSCustomObject]@{
    GameVersion='1.0.28'; SHA256=$raidHash; Backup=$raidBackup
    BackupFiles=$raidManifest.Count; BackupBytes=($raidManifest | Measure-Object Bytes -Sum).Sum
    Installed=$raidInstalled; ServerStarted=$false; LauncherChanged=$false
}
$raidResult | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $raidBackup 'deployment.json') -Encoding utf8
$raidResult | ConvertTo-Json -Depth 4
