param(
    [string]$WorkspaceRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ResponseJar = '',
    [string]$ContentJar = ''
)

# Read-only verification. Compare every uncompressed file, not ZIP timestamps.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-JarEntries([string]$Path) {
    $zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Path).Path)
    try {
        $entries = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
        foreach ($entry in $zip.Entries) {
            if ($entry.Name -eq '') { continue }
            if ($entries.ContainsKey($entry.FullName)) { throw "Duplicate JAR entry: $($entry.FullName)" }
            $stream = $entry.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $entries.Add($entry.FullName, [BitConverter]::ToString($sha.ComputeHash($stream))) }
            finally { $sha.Dispose(); $stream.Dispose() }
        }
        return ,$entries
    } finally { $zip.Dispose() }
}

function Get-CurrentBuildJar([string]$Module, [string]$ArchiveName) {
    $properties = Get-Content -LiteralPath (Join-Path $WorkspaceRoot "$Module/gradle.properties") -Raw
    $versionMatch = [regex]::Match($properties, '(?m)^mod_version=([A-Za-z0-9._+-]+)\s*$')
    if (!$versionMatch.Success) { throw "Missing/invalid build version in $Module" }
    return Join-Path $WorkspaceRoot ("$Module/build/libs/$ArchiveName-" + $versionMatch.Groups[1].Value + '.jar')
}
if (!$ResponseJar) { $ResponseJar = Get-CurrentBuildJar 'mythai-ai-response' 'mythai_ai_response' }
if (!$ContentJar) { $ContentJar = Get-CurrentBuildJar 'mythai-ai-content-registry' 'mythaiaicontent' }
$targets = @(
    @{ Name='response'; File='mythai_ai_response-0.1.0.jar'; Candidate=$ResponseJar;
       Hash='DBC7C095943817248E05CD86B58966DE8DF74075A54BB8474134AE7C68D8147F' },
    @{ Name='content'; File='mythaiaicontent-0.1.0.jar'; Candidate=$ContentJar;
       Hash='38B8461C13619569E477BB24AB85F9BFAD317998402FEA3E5ACF0019C6CC8CBA' }
)
$results = foreach ($target in $targets) {
    $baselinePath = Join-Path $WorkspaceRoot ('server/backups/LP/mods/' + $target.File)
    if ((Get-FileHash -LiteralPath $baselinePath -Algorithm SHA256).Hash -ne $target.Hash) {
        throw "LP baseline has changed: $baselinePath"
    }
    $baseline = Get-JarEntries $baselinePath
    $candidate = Get-JarEntries $target.Candidate
    $differences = @()
    foreach ($name in $baseline.Keys) {
        if (!$candidate.ContainsKey($name)) { $differences += "MISSING $name" }
        elseif ($baseline[$name] -cne $candidate[$name]) { $differences += "CHANGED $name" }
    }
    foreach ($name in $candidate.Keys) {
        if (!$baseline.ContainsKey($name)) { $differences += "ADDED $name" }
    }
    if ($differences.Count) {
        throw ($target.Name + ': NOT LP (' + $differences.Count + ' differences)' + [Environment]::NewLine + ($differences -join [Environment]::NewLine))
    }
    [pscustomobject]@{
        Module = $target.Name
        Result = 'PASS: every class and resource is byte-identical to LP'
        FileEntries = $candidate.Count
        Candidate = (Resolve-Path -LiteralPath $target.Candidate).Path
        CandidateJarSHA256 = (Get-FileHash -LiteralPath $target.Candidate -Algorithm SHA256).Hash
        BaselineJarSHA256 = $target.Hash
    }
}
$results
