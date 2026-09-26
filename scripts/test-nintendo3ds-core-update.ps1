[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $DeviceSerial,
    [Parameter(Mandatory = $true)]
    [string] $BaseCorePath,
    [Parameter(Mandatory = $true)]
    [string] $UpdateCorePath,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $UpdateCoreRevision,
    [Parameter(Mandatory = $true)]
    [ValidateCount(3, 5)]
    [string[]] $RemoteContent,
    [ValidateRange(2, 1800)]
    [int] $FramesPerContent = 120,
    [string] $PackageName = 'com.mateussouza.emuorbit.n3ds.core.test',
    [string] $Runner = 'androidx.test.runner.AndroidJUnitRunner',
    [string] $AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$testClass = 'com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest'
$testMethod = 'preservesExactSnapshotsAcrossAzaharCoreUpdateAndRollback'

if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "ADB não encontrado: $adb"
}
foreach ($path in @($BaseCorePath, $UpdateCorePath) + $RemoteContent) {
    if ($path -notmatch '^/[A-Za-z0-9._/-]+$') {
        throw "Caminho remoto não suportado pelo gate 3DS: $path"
    }
}

& $adb -s $DeviceSerial get-state | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Dispositivo Android indisponível: $DeviceSerial"
}

# Uma atualização instalada reinicia o processo do app. O gate começa em um PID
# novo e só então compara as duas revisões sequencialmente, sem herdar globais
# C/C++ de dezenas de sessões de outros testes.
& $adb -s $DeviceSerial shell am force-stop $PackageName
& $adb -s $DeviceSerial logcat -c

$instrumentationArguments = @(
    '-s', $DeviceSerial,
    'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'class', "$testClass#$testMethod",
    '-e', 'n3dsCorePath', $BaseCorePath,
    '-e', 'n3dsUpdateCorePath', $UpdateCorePath,
    '-e', 'n3dsUpdateCoreRevision', $UpdateCoreRevision,
    '-e', 'n3dsExpectedContentCount', $RemoteContent.Count.ToString(),
    '-e', 'n3dsUpdateFrames', $FramesPerContent.ToString()
)
for ($index = 0; $index -lt $RemoteContent.Count; $index++) {
    $key = if ($index -eq 0) { 'n3dsContentPath' } else { "n3dsContentPath$($index + 1)" }
    $instrumentationArguments += @('-e', $key, $RemoteContent[$index])
}
$instrumentationArguments += "$PackageName/$Runner"

$instrumentationOutput = @(& $adb @instrumentationArguments 2>&1)
$instrumentationExitCode = $LASTEXITCODE
$instrumentationOutput | ForEach-Object { Write-Host $_ }
$instrumentationText = $instrumentationOutput -join "`n"
if ($instrumentationExitCode -ne 0 `
        -or $instrumentationText -notmatch 'OK \(1 test\)' `
        -or $instrumentationText -match 'Process crashed|FAILURES!!!') {
    throw 'Gate físico de atualização do core Nintendo 3DS falhou.'
}

$logOutput = @(& $adb -s $DeviceSerial logcat -d -v brief 2>&1)
$evidence = @($logOutput | Select-String -Pattern 'N3DS_CORE_UPDATE')
$fatal = @($logOutput | Select-String -Pattern 'FATAL EXCEPTION|Fatal signal|VK_ERROR_DEVICE_LOST')
if ($evidence.Count -ne 1) {
    throw 'Gate físico não produziu exatamente uma evidência N3DS_CORE_UPDATE.'
}
if ($fatal.Count -ne 0) {
    $fatal | ForEach-Object { Write-Host $_ }
    throw 'Gate físico registrou crash ou perda do dispositivo Vulkan.'
}

$evidence | ForEach-Object { Write-Host $_ }
Write-Host 'N3DS_CORE_UPDATE_GATE=PASS'
