$projectJavaHomes = @(
    'C:\Program Files\Java\jdk-21.0.11',
    'C:\Program Files\Android\Android Studio\jbr'
)
$projectJavaHome = $projectJavaHomes | Where-Object {
    Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')
} | Select-Object -First 1

if ($null -eq $projectJavaHome) {
    Write-Error "Java 21 JDK was not found. Checked: $($projectJavaHomes -join ', ')"
    exit 1
}

$originalJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
$originalPath = [Environment]::GetEnvironmentVariable('Path', 'Process')
$exitCode = 1

try {
    $env:JAVA_HOME = $projectJavaHome
    $env:Path = "$projectJavaHome\bin;$originalPath"

    & (Join-Path $PSScriptRoot 'gradlew.bat') @args
    $exitCode = $LASTEXITCODE
}
finally {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $originalJavaHome, 'Process')
    [Environment]::SetEnvironmentVariable('Path', $originalPath, 'Process')
}

exit $exitCode
