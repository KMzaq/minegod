# HanesTest experimental release only; no server start, model invocation, or game/client replacement.
param()
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$serverRoot = [IO.Path]::GetFullPath((Join-Path $workspace 'server'))
$backupRoot = Join-Path $serverRoot 'backups/hanestest-common-knowledge-20260927'

function Assert-WithinServer([string]$path) {
    $absolute = [IO.Path]::GetFullPath($path)
    if (-not $absolute.StartsWith($serverRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Target is outside the named server directory: $absolute"
    }
    return $absolute
}
function Assert-ServerStopped {
    $java = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    if ($java.Count -gt 0) { throw 'Java is running. Inspect and gracefully stop the exact server before deployment.' }
    $listeners = @(Get-NetTCPConnection -State Listen)
    if (@($listeners | Where-Object LocalPort -eq 25565).Count -gt 0) { throw 'Server port 25565 is listening.' }
}
function Assert-Hash([string]$path, [string]$hash) {
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $hash) { throw "Hash mismatch: $path" }
}

$artifacts = @(
    @{ Source='mythai-ai-response/build/libs/mythai_ai_response-0.1.19.jar'; Name='mythai_ai_response-0.1.19.jar'; Hash='E3BA2CF5E2CF1B53F0C75861F8FAF48D2391C214E5A22E0C4BE3A1FB7502BB7A'; Old='mythai_ai_response-0.1.17.jar'; OldHash='EECDB96AD76AB0AB4623314AB89657B1CA2A09AA029EA53E0B752AE49AE0F568' },
    @{ Source='mythai-ai-content-registry/build/libs/mythaiaicontent-0.1.2.jar'; Name='mythaiaicontent-0.1.2.jar'; Hash='CA81C37988392EB2DC6AA03B2EE1E3E377F985E2B10FE3C51068803F2760CD95'; Old='mythaiaicontent-0.1.1.jar'; OldHash='72096C4FFA1D103508013BCDBFBCD45242394114A58E60978DA630C8FB593E61' }
)
Assert-ServerStopped
$null = Assert-WithinServer $backupRoot
if (Test-Path -LiteralPath $backupRoot) { throw 'Backup already exists; do not overwrite or rerun this release blindly.' }
$targets = @()
foreach ($artifact in $artifacts) {
    $source = (Resolve-Path -LiteralPath (Join-Path $workspace $artifact.Source)).Path
    Assert-Hash $source $artifact.Hash
    $old = Assert-WithinServer (Join-Path $serverRoot ('mods/' + $artifact.Old))
    $destination = Assert-WithinServer (Join-Path $serverRoot ('mods/' + $artifact.Name))
    if ((Test-Path -LiteralPath $destination) -or -not (Test-Path -LiteralPath $old -PathType Leaf)) {
        throw "Unexpected target state: $destination"
    }
    Assert-Hash $old $artifact.OldHash
    $targets += [PSCustomObject]@{Source=$source; Destination=$destination; Old=$old; Hash=$artifact.Hash}
}

New-Item -ItemType Directory -Path $backupRoot | Out-Null
$backupFiles = @()
foreach ($folder in @('mods','client-required-mods','world','config','defaultconfigs','mythictrpg-ai-data','mythictrpg-dialogue-logs')) {
    $sourceFolder = Assert-WithinServer (Join-Path $serverRoot $folder)
    if (-not (Test-Path -LiteralPath $sourceFolder -PathType Container)) { throw "Missing expected folder: $sourceFolder" }
    $backupFiles += @(Get-ChildItem -LiteralPath $sourceFolder -File -Recurse -Force)
}
foreach ($name in @('server.properties','README.md','eula.txt','ops.json','whitelist.json','banned-ips.json','banned-players.json','usercache.json','usernamecache.json','user_jvm_args.txt','run.bat','start-neoforge-ai-server.bat')) {
    $path = Assert-WithinServer (Join-Path $serverRoot $name)
    if (Test-Path -LiteralPath $path -PathType Leaf) { $backupFiles += Get-Item -LiteralPath $path }
}
$manifest = @(foreach ($file in $backupFiles) {
    $relative = [IO.Path]::GetRelativePath($serverRoot, $file.FullName)
    $destination = Assert-WithinServer (Join-Path $backupRoot $relative)
    New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    Copy-Item -LiteralPath $file.FullName -Destination $destination
    Assert-Hash $destination $hash
    [PSCustomObject]@{RelativePath=$relative; Bytes=$file.Length; SHA256=$hash}
})
$manifest | Export-Csv -LiteralPath (Join-Path $backupRoot 'backup-manifest.csv') -NoTypeInformation -Encoding utf8
Assert-ServerStopped

$moved = @()
$installed = @()
try {
    foreach ($target in $targets) {
        $retired = Assert-WithinServer (Join-Path $backupRoot ('retired-mods/' + [IO.Path]::GetFileName($target.Old)))
        New-Item -ItemType Directory -Path (Split-Path $retired) -Force | Out-Null
        Move-Item -LiteralPath $target.Old -Destination $retired
        $moved += [PSCustomObject]@{Original=$target.Old; Retired=$retired}
        $installed += $target
        Copy-Item -LiteralPath $target.Source -Destination $target.Destination
        Assert-Hash $target.Destination $target.Hash
    }
    $protected = @($manifest | Where-Object { (Join-Path $serverRoot $_.RelativePath) -notin $targets.Old })
    foreach ($entry in $protected) { Assert-Hash (Join-Path $serverRoot $entry.RelativePath) $entry.SHA256 }
} catch {
    $releaseFailure = $_
    foreach ($target in $installed) {
        if (-not (Test-Path -LiteralPath $target.Destination -PathType Leaf)) { continue }
        $failed = Assert-WithinServer (Join-Path $backupRoot ('failed-install/' + [IO.Path]::GetFileName($target.Destination)))
        New-Item -ItemType Directory -Path (Split-Path $failed) -Force | Out-Null
        Move-Item -LiteralPath $target.Destination -Destination $failed
    }
    foreach ($target in $moved) { Copy-Item -LiteralPath $target.Retired -Destination $target.Original }
    throw $releaseFailure
}
$targets | Select-Object Destination,Hash | Format-List
[PSCustomObject]@{Backup=$backupRoot; Files=$manifest.Count; Bytes=($manifest | Measure-Object Bytes -Sum).Sum; ProtectedFilesVerified=$protected.Count; Installed=$installed.Count; ServerStarted=$false} | Format-List
