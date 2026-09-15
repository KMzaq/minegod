param([string[]]$JarPaths)

# Class names can differ while Java module packages still collide (split package).
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (!$JarPaths) {
    $JarPaths = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '../server/mods') -File -Filter '*.jar' | ForEach-Object FullName)
}
if ($JarPaths.Count -lt 2) { throw 'At least two mod JARs are required' }
$owners = [Collections.Generic.Dictionary[string,Collections.Generic.List[string]]]::new([StringComparer]::Ordinal)
foreach ($path in $JarPaths) {
    $resolved = (Resolve-Path -LiteralPath $path).Path
    $zip = [IO.Compression.ZipFile]::OpenRead($resolved)
    try {
        $packages = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($entry in $zip.Entries) {
            if (!$entry.FullName.EndsWith('.class')) { continue }
            $name = $entry.FullName -replace '^META-INF/versions/\d+/', ''
            if ($name.StartsWith('META-INF/') -or $name -eq 'module-info.class') { continue }
            $lastSlash = $name.LastIndexOf('/')
            if ($lastSlash -le 0) { continue }
            [void]$packages.Add($name.Substring(0, $lastSlash).Replace('/', '.'))
        }
        foreach ($package in $packages) {
            if (!$owners.ContainsKey($package)) { $owners[$package] = [Collections.Generic.List[string]]::new() }
            $owners[$package].Add($resolved)
        }
    } finally { $zip.Dispose() }
}
$collisions = @($owners.Keys | Where-Object { $owners[$_].Count -gt 1 } | Sort-Object)
if ($collisions.Count) {
    $details = @($collisions | ForEach-Object { $_ + ' => ' + ($owners[$_] -join ', ') })
    throw ('Split Java module packages detected (' + $collisions.Count + '):' + [Environment]::NewLine + ($details -join [Environment]::NewLine))
}
Write-Output "PASS Java module package isolation: $($JarPaths.Count) JARs, $($owners.Count) unique packages"
