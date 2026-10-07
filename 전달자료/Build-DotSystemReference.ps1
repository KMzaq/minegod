# One-time, read-only source snapshot. Existing bundles are never overwritten.
[CmdletBinding()]
param([string]$OutputRoot = $PSScriptRoot)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $OutputRoot '..'))
$bundleName = 'dot-system-reference-20261001'
$bundle = Join-Path $OutputRoot $bundleName
$archivePath = Join-Path $OutputRoot ($bundleName + '.zip')
$archiveReportPath = $archivePath + '.validation.json'
$reference = Join-Path $bundle 'reference'
$utf8 = New-Object System.Text.UTF8Encoding($false)

foreach ($target in @($reference, $archivePath, $archiveReportPath, (Join-Path $bundle 'source-manifest.csv'), (Join-Path $bundle 'snapshot.json'), (Join-Path $bundle 'bundle-validation.json'))) {
    if (Test-Path -LiteralPath $target) { throw "Refusing to overwrite existing output: $target" }
}
$guides = @('00_READ_FIRST.md', '01_EXISTING_SYSTEMS.md', '02_SOURCE_NOTES.md')
foreach ($guide in $guides) {
    if (-not (Test-Path -LiteralPath (Join-Path $bundle $guide) -PathType Leaf)) { throw "Missing authored guide: $guide" }
}
$readFirst = Get-Content -LiteralPath (Join-Path $bundle $guides[0]) -Encoding UTF8 -Raw
$sources = @([regex]::Matches($readFirst, '\]\(reference/([^\)]+)\)') | ForEach-Object { $_.Groups[1].Value })
$sources += @('인수인계/START_HERE.md', 'docs/README.md', 'docs/AI_DIALOGUE_TEST_COMMAND.md')
$sources = @($sources | Sort-Object -Unique)
$rootPrefix = $workspace.TrimEnd('\') + '\'
foreach ($relative in $sources) {
    $resolved = [IO.Path]::GetFullPath((Join-Path $workspace $relative))
    if (-not $resolved.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Out-of-workspace source: $relative" }
    if ([IO.Path]::GetExtension($resolved) -ne '.md') { throw "Non-document source: $relative" }
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw "Missing source: $relative" }
}

$branch = (& git -C $workspace branch --show-current).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read Git branch' }
$commit = (& git -C $workspace rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read Git commit' }
$dirty = @(& git -C $workspace status --porcelain).Count -gt 0
if ($LASTEXITCODE -ne 0) { throw 'Cannot read Git status' }
$manifest = @()
foreach ($relative in $sources) {
    $source = Join-Path $workspace $relative
    $destination = Join-Path $reference $relative
    $hashBefore = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    $null = New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force
    Copy-Item -LiteralPath $source -Destination $destination
    $hashCopy = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    $hashAfter = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    if ($hashBefore -ne $hashCopy -or $hashBefore -ne $hashAfter) { throw "Source changed during snapshot: $relative" }
    $sourceInfo = Get-Item -LiteralPath $source
    $manifest += [pscustomobject]@{
        sourceRelativePath = $relative
        bundleRelativePath = 'reference/' + $relative
        bytes = $sourceInfo.Length
        sourceLastWriteTimeUtc = $sourceInfo.LastWriteTimeUtc.ToString('o')
        sha256 = $hashCopy
    }
}
$manifest | Export-Csv -LiteralPath (Join-Path $bundle 'source-manifest.csv') -NoTypeInformation -Encoding UTF8

$links = @()
foreach ($guide in $guides) {
    $content = Get-Content -LiteralPath (Join-Path $bundle $guide) -Encoding UTF8 -Raw
    foreach ($match in [regex]::Matches($content, '\]\(([^\)]+)\)')) {
        $target = $match.Groups[1].Value
        if ($target -match '^(https?://|#)') { continue }
        $path = [IO.Path]::GetFullPath((Join-Path $bundle (($target -split '#', 2)[0])))
        $inside = $path.StartsWith($bundle.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)
        $exists = $inside -and (Test-Path -LiteralPath $path -PathType Leaf)
        $links += [pscustomobject]@{ guide = $guide; target = $target; exists = [bool]$exists }
        if (-not $exists) { throw "Broken bundle navigation: $guide -> $target" }
    }
}
$snapshot = [ordered]@{
    createdAt = (Get-Date).ToString('o')
    referenceDateKst = '2026-10-01'
    purpose = 'Documentation only: existing systems reference for content planning'
    branch = $branch
    gitCommit = $commit
    workingTreeDirty = $dirty
    reproducibility = 'Copied working-tree documents, including uncommitted edits; not reproducible from commit alone.'
    sourceDocumentCount = $manifest.Count
    developmentVersions = @{ game = '1.0.23'; aiResponse = '0.1.26'; contentRegistry = '0.1.3'; networkProtocol = 10 }
    observedInstalledJarVersions = @{ game = '1.0.16'; aiResponse = '0.1.19'; contentRegistry = '0.1.2' }
    deploymentObservation = 'File inventory only, not boot verification'
    exclusions = @('Java source', 'JARs', 'world/player data', 'server configuration', 'logs', 'user mythology roster and story texts')
}
[IO.File]::WriteAllText((Join-Path $bundle 'snapshot.json'), ($snapshot | ConvertTo-Json -Depth 8), $utf8)
$validation = [ordered]@{
    checkedAt = (Get-Date).ToString('o')
    copiedSourceDocuments = $manifest.Count
    allCopiedHashesMatch = $true
    navigationLinkCount = $links.Count
    navigationLinks = $links
    copiedSourceOutboundLinks = 'Not exhaustively checked; source code and historical dependencies intentionally omitted.'
    archiveVerificationReport = $bundleName + '.zip.validation.json (adjacent to ZIP)'
    gameplayBuildOrTestsRun = $false
    serverStartedOrDeployed = $false
}
[IO.File]::WriteAllText((Join-Path $bundle 'bundle-validation.json'), ($validation | ConvertTo-Json -Depth 8), $utf8)

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($bundle, $archivePath, [IO.Compression.CompressionLevel]::Optimal, $false)
$localFiles = @(Get-ChildItem -LiteralPath $bundle -File -Recurse -Force)
$zip = [IO.Compression.ZipFile]::OpenRead($archivePath)
$entryChecks = @()
try {
    $entries = @($zip.Entries | Where-Object { -not $_.FullName.EndsWith('/') })
    if ($entries.Count -ne $localFiles.Count) { throw 'ZIP entry count mismatch' }
    foreach ($entry in $entries) {
        $localPath = [IO.Path]::GetFullPath((Join-Path $bundle $entry.FullName))
        if (-not $localPath.StartsWith($bundle.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe ZIP entry' }
        $expected = (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash
        $sha = [Security.Cryptography.SHA256]::Create()
        $stream = $entry.Open()
        try { $actual = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
        finally { $stream.Dispose(); $sha.Dispose() }
        if ($actual -ne $expected) { throw "ZIP content mismatch: $($entry.FullName)" }
        $entryChecks += [pscustomobject]@{ path = $entry.FullName; sha256 = $actual; matches = $true }
    }
} finally { $zip.Dispose() }
$report = [ordered]@{
    checkedAt = (Get-Date).ToString('o')
    archive = [IO.Path]::GetFileName($archivePath)
    archiveSha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
    sourceDocuments = $manifest.Count
    entryCount = $entryChecks.Count
    allArchiveContentsMatch = $true
    entries = $entryChecks
}
[IO.File]::WriteAllText($archiveReportPath, ($report | ConvertTo-Json -Depth 6), $utf8)
[pscustomobject]@{
    archive = $archivePath
    bytes = (Get-Item -LiteralPath $archivePath).Length
    sourceDocuments = $manifest.Count
    archiveEntries = $entryChecks.Count
    navigationLinksChecked = $links.Count
    allChecksPassed = $true
} | ConvertTo-Json
