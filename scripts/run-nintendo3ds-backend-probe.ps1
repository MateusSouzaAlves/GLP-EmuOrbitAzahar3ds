[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $NdkRoot,
    [Parameter(Mandatory = $true)]
    [string] $DeviceSerial,
    [string] $CoreLibrary,
    [string] $ExpectedCoreSha256,
    [string] $AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$outputDirectory = Join-Path $env:TEMP 'emuorbit-n3ds-backend-probe'
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$probe = Join-Path $outputDirectory 'n3ds_backend_bootstrap_probe'
$mockCore = Join-Path $outputDirectory 'azahar_mock_libretro.so'
$remoteRoot = '/data/local/tmp/emuorbit-n3ds-backend-probe'
$remoteProbe = "$remoteRoot/n3ds_backend_bootstrap_probe"
$remoteMock = "$remoteRoot/azahar_mock_libretro.so"
$remoteCore = "$remoteRoot/azahar_libretro.so"
$remoteSentinel = "$remoteRoot/load-game-called"

if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "ADB não encontrado: $adb"
}
if ($DeviceSerial -notmatch '^[A-Za-z0-9._:-]+$') {
    throw 'O serial ADB contém caracteres não suportados.'
}

& (Join-Path $PSScriptRoot 'build-nintendo3ds-backend-probe.ps1') `
    -NdkRoot $NdkRoot `
    -OutputDirectory $outputDirectory
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $adb -s $DeviceSerial get-state | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Dispositivo Android indisponível: $DeviceSerial"
}
& $adb -s $DeviceSerial shell mkdir -p $remoteRoot
& $adb -s $DeviceSerial push $probe $remoteProbe | Out-Host
& $adb -s $DeviceSerial push $mockCore $remoteMock | Out-Host
& $adb -s $DeviceSerial shell chmod 700 $remoteProbe

function Invoke-BackendProbe([string] $RemoteLibrary, [string] $Label) {
    & $adb -s $DeviceSerial shell rm -f $remoteSentinel
    $command = "N3DS_LOAD_GAME_SENTINEL=$remoteSentinel " +
        "$remoteProbe $RemoteLibrary $remoteSentinel"
    $probeOutput = & $adb -s $DeviceSerial shell $command 2>&1
    $probeExitCode = $LASTEXITCODE
    $probeOutput | ForEach-Object { Write-Host $_ }
    $joined = $probeOutput -join "`n"
    if ($probeExitCode -ne 0 `
            -or $joined -notmatch 'BOOTSTRAP_RESULT=PASS' `
            -or $joined -notmatch 'BOOTSTRAP_LOAD_GAME_CALLS=0') {
        throw "Bootstrap Nintendo 3DS falhou para $Label."
    }
}

Invoke-BackendProbe -RemoteLibrary $remoteMock -Label 'mock controlado'

if ($CoreLibrary) {
    $corePath = (Resolve-Path -LiteralPath $CoreLibrary).Path
    if ($ExpectedCoreSha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw 'Informe o SHA-256 esperado do núcleo Azahar fixado.'
    }
    $observedCoreSha256 =
        (Get-FileHash -Algorithm SHA256 -LiteralPath $corePath).Hash
    if ($observedCoreSha256 -ne $ExpectedCoreSha256.ToUpperInvariant()) {
        throw "SHA-256 inesperado para o núcleo Azahar: $observedCoreSha256"
    }
    & $adb -s $DeviceSerial push $corePath $remoteCore | Out-Host
    Invoke-BackendProbe -RemoteLibrary $remoteCore -Label 'Azahar fixado'
}
