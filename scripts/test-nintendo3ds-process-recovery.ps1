[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $DeviceSerial,
    [string] $CoreDevicePath = '/data/user/0/com.mateussouza.emuorbit.n3ds.core.test/files/azahar_libretro.n3ds12b.so',
    [string] $ContentDevicePath = '/data/user/0/com.mateussouza.emuorbit.n3ds.core.test/files/gamepad-qa.3ds',
    [string] $AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$package = 'com.mateussouza.emuorbit.n3ds.core.test'
$runner = "$package/androidx.test.runner.AndroidJUnitRunner"
$testClass = 'com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest'

if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "ADB não encontrado: $adb"
}
& $adb -s $DeviceSerial get-state | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Dispositivo Android indisponível: $DeviceSerial"
}

function Invoke-RecoveryPhase([string] $Method) {
    $output = & $adb -s $DeviceSerial shell am instrument -w -r `
        -e class "$testClass#$Method" `
        -e n3dsCorePath $CoreDevicePath `
        -e n3dsContentPath $ContentDevicePath `
        $runner 2>&1
    $output | ForEach-Object { Write-Host $_ }
    $text = $output -join "`n"
    if ($LASTEXITCODE -ne 0 -or
            $text -notmatch 'OK \(1 test\)' -or
            $text -match 'FAILURES!!!|INSTRUMENTATION_FAILED') {
        throw "Gate de retomada 3DS falhou em $Method."
    }
}

Invoke-RecoveryPhase 'preparesTransientLifecycleStateForForcedProcessDeath'
& $adb -s $DeviceSerial shell am force-stop $package
if ($LASTEXITCODE -ne 0) {
    throw 'Não foi possível encerrar o pacote QA entre as fases.'
}
Invoke-RecoveryPhase 'restoresTransientLifecycleStateAfterForcedProcessDeath'
Write-Host 'N3DS_PROCESS_RECOVERY_GATE=PASS'
