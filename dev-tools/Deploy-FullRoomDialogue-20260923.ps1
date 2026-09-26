param()
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$serverRoot = [IO.Path]::GetFullPath((Join-Path $workspace 'server'))
$backupRoot = Join-Path $serverRoot 'backups/full-room-dialogue-20260923'

function Assert-ServerStopped {
    $java = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    if ($java.Count -gt 0) { throw 'Java is running. Inspect the exact server and stop it gracefully before deployment.' }
    $listeners = @(Get-NetTCPConnection -State Listen)
    if (@($listeners | Where-Object LocalPort -eq 25565).Count -gt 0) { throw 'Server port 25565 is listening.' }
}
function Assert-WithinServer([string]$path) {
    $absolute = [IO.Path]::GetFullPath($path)
    if (-not $absolute.StartsWith($serverRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Target is outside the named server directory: $absolute"
    }
    return $absolute
}

$artifacts = @(
    @{ Source='mythictrpg-main/build/libs/mythictrpg-1.0.16.jar'; Name='mythictrpg-1.0.16.jar'; Hash='8F1187B3E9C6C2BC264512BFAC5A449F8EA9441DD4E9762E45C415C5F00EF5FD'; Old='mythictrpg-1.0.15.jar'; Client=$true },
    @{ Source='mythai-ai-response/build/libs/mythai_ai_response-0.1.17.jar'; Name='mythai_ai_response-0.1.17.jar'; Hash='EECDB96AD76AB0AB4623314AB89657B1CA2A09AA029EA53E0B752AE49AE0F568'; Old='mythai_ai_response-0.1.16.jar'; Client=$false },
    @{ Source='mythai-ai-content-registry/build/libs/mythaiaicontent-0.1.1.jar'; Name='mythaiaicontent-0.1.1.jar'; Hash='72096C4FFA1D103508013BCDBFBCD45242394114A58E60978DA630C8FB593E61'; Old='mythaiaicontent-0.1.0.jar'; Client=$false }
)
Assert-ServerStopped
if (Test-Path -LiteralPath $backupRoot) { throw 'Backup directory already exists. Do not overwrite or rerun this release blindly.' }
$targets = @()
foreach ($artifact in $artifacts) {
    $source = (Resolve-Path -LiteralPath (Join-Path $workspace $artifact.Source)).Path
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $artifact.Hash) { throw "Unverified build: $source" }
    $folders = @('mods')
    if ($artifact.Client) { $folders += 'client-required-mods' }
    foreach ($folder in $folders) {
        $old = Assert-WithinServer (Join-Path $serverRoot "$folder/$($artifact.Old)")
        $destination = Assert-WithinServer (Join-Path $serverRoot "$folder/$($artifact.Name)")
        if (-not (Test-Path -LiteralPath $old -PathType Leaf) -or (Test-Path -LiteralPath $destination)) {
            throw "Unexpected release target state: $destination"
        }
        $targets += [PSCustomObject]@{Source=$source; Destination=$destination; Old=$old; Folder=$folder; Hash=$artifact.Hash}
    }
}

New-Item -ItemType Directory -Path $backupRoot | Out-Null
$backupSets = @('mods','client-required-mods','world','config','defaultconfigs','mythictrpg-ai-data','mythictrpg-dialogue-logs')
$backupFiles = @()
foreach ($folder in $backupSets) {
    $sourceFolder = Assert-WithinServer (Join-Path $serverRoot $folder)
    $copyFolder = Assert-WithinServer (Join-Path $backupRoot $folder)
    New-Item -ItemType Directory -Path $copyFolder | Out-Null
    $backupFiles += @(Get-ChildItem -LiteralPath $sourceFolder -File -Recurse -Force)
}
foreach ($name in @('server.properties','README.md')) { $backupFiles += Get-Item -LiteralPath (Join-Path $serverRoot $name) }
$manifest = foreach ($file in $backupFiles) {
    $relative = [IO.Path]::GetRelativePath($serverRoot,$file.FullName)
    $destination = Assert-WithinServer (Join-Path $backupRoot $relative)
    New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $destination
    $originalHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $originalHash) { throw "Backup mismatch: $relative" }
    [PSCustomObject]@{RelativePath=$relative; Bytes=$file.Length; SHA256=$originalHash}
}
$manifest | Export-Csv -LiteralPath (Join-Path $backupRoot 'backup-manifest.csv') -NoTypeInformation -Encoding utf8
Assert-ServerStopped

$moved = @()
$installed = @()
try {
    foreach ($target in $targets) {
        $retired = Assert-WithinServer (Join-Path $backupRoot ("retired-"+$target.Folder+"/"+[IO.Path]::GetFileName($target.Old)))
        New-Item -ItemType Directory -Path (Split-Path $retired) -Force | Out-Null
        Move-Item -LiteralPath $target.Old -Destination $retired
        $moved += [PSCustomObject]@{Original=$target.Old; Retired=$retired}
        $installed += $target
        Copy-Item -LiteralPath $target.Source -Destination $target.Destination
        if ((Get-FileHash -LiteralPath $target.Destination -Algorithm SHA256).Hash -ne $target.Hash) {
            throw "Installed hash mismatch: $($target.Destination)"
        }
    }
} catch {
    $releaseFailure = $_
    foreach ($target in $installed) {
        if (-not (Test-Path -LiteralPath $target.Destination -PathType Leaf)) { continue }
        $failedCopy = Assert-WithinServer (Join-Path $backupRoot ("failed-install/"+$target.Folder+"/"+[IO.Path]::GetFileName($target.Destination)))
        New-Item -ItemType Directory -Path (Split-Path $failedCopy) -Force | Out-Null
        Move-Item -LiteralPath $target.Destination -Destination $failedCopy
    }
    foreach ($target in $moved) { Copy-Item -LiteralPath $target.Retired -Destination $target.Original }
    throw $releaseFailure
}
$targets | Select-Object Destination,Hash | Format-List
[PSCustomObject]@{Backup=$backupRoot; Files=$manifest.Count; Bytes=($manifest | Measure-Object Bytes -Sum).Sum; Installed=$installed.Count; ServerStarted=$false} | Format-List
