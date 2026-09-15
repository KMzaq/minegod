param(
    [string]$RisuRoot = 'C:\Users\ADMIN\Desktop\marrisu\risuai\risuai'
)

$ErrorActionPreference = 'Stop'
$patchRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$workerDestination = Join-Path $RisuRoot 'src\ts\minecraft'

if (-not (Test-Path -LiteralPath (Join-Path $RisuRoot '.git'))) {
    throw "RisuAI repository not found: $RisuRoot"
}

$bootstrap = Join-Path $RisuRoot 'src\ts\bootstrap.ts'
$alreadyPatched = Select-String -LiteralPath $bootstrap -Quiet -SimpleMatch 'startMinecraftDialogueWorker'
if (-not $alreadyPatched) {
    & git -C $RisuRoot apply --check (Join-Path $patchRoot 'bootstrap-minecraft-worker.patch')
    if ($LASTEXITCODE -ne 0) {
        throw 'RisuAI bootstrap patch does not apply cleanly. Check the RisuAI revision before continuing.'
    }
}

New-Item -ItemType Directory -Path $workerDestination -Force | Out-Null
Copy-Item -Path (Join-Path $patchRoot 'src\ts\minecraft\*') -Destination $workerDestination -Recurse -Force
if (-not $alreadyPatched) {
    & git -C $RisuRoot apply (Join-Path $patchRoot 'bootstrap-minecraft-worker.patch')
    if ($LASTEXITCODE -ne 0) {
        throw 'RisuAI bootstrap patch could not be applied.'
    }
}

Write-Output "RisuAI Minecraft worker installed in $workerDestination"
