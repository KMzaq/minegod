$projectJavaHome = 'C:\Program Files\Java\jdk-21.0.11'
$projectJava = Join-Path $projectJavaHome 'bin\java.exe'

if (-not (Test-Path -LiteralPath $projectJava)) {
    Write-Error "Project JDK was not found at $projectJavaHome"
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
