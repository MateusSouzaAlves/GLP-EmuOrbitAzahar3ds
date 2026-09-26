[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $AzaharSource,
    [Parameter(Mandatory = $true)]
    [string] $NdkRoot,
    [Parameter(Mandatory = $true)]
    [string] $CoreLibrary,
    [Parameter(Mandatory = $true)]
    [string] $DeviceSerial,
    [string] $RemoteContent,
    [ValidateRange(1, 600)]
    [int] $FrameCount = 1,
    [ValidateSet('Software', 'OpenGLES', 'Vulkan')]
    [string] $Renderer = 'Software',
    [switch] $MeasureState,
    [ValidateRange(0, 1073741824)]
    [long] $StateMaximumBytes = 0,
    [string] $AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$corePath = (Resolve-Path -LiteralPath $CoreLibrary).Path
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$outputDirectory = Join-Path $env:TEMP 'emuorbit-n3ds-core-probe'
$probe = Join-Path $outputDirectory 'n3ds_core_probe'
$remoteRoot = '/data/local/tmp/emuorbit-n3ds-core-probe'
$remoteCore = "$remoteRoot/azahar_libretro.so"
$remoteProbe = "$remoteRoot/n3ds_core_probe"
$remoteSystem = "$remoteRoot/runtime/system"
$remoteSaves = "$remoteRoot/runtime/saves"

if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "ADB não encontrado: $adb"
}

& (Join-Path $PSScriptRoot 'build-nintendo3ds-core-probe.ps1') `
    -AzaharSource $AzaharSource `
    -NdkRoot $NdkRoot `
    -OutputDirectory $outputDirectory
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $adb -s $DeviceSerial get-state | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Dispositivo Android indisponível: $DeviceSerial"
}
& $adb -s $DeviceSerial shell mkdir -p $remoteSystem $remoteSaves
& $adb -s $DeviceSerial push $probe $remoteProbe | Out-Host
& $adb -s $DeviceSerial push $corePath $remoteCore | Out-Host
& $adb -s $DeviceSerial shell chmod 700 $remoteProbe

$probeArguments = @($remoteProbe, $remoteCore, $remoteSystem, $remoteSaves)
if ($RemoteContent) {
    if ($RemoteContent -notmatch '^/[A-Za-z0-9._/-]+$') {
        throw 'O caminho remoto de conteúdo contém caracteres não suportados pelo probe.'
    }
    $probeArguments += @($RemoteContent, $FrameCount.ToString(), $Renderer.ToLowerInvariant())
    if ($MeasureState) {
        $probeArguments += $StateMaximumBytes.ToString()
    }
} elseif ($MeasureState) {
    throw 'A medição de state requer um conteúdo remoto.'
}
$probeOutput = & $adb -s $DeviceSerial shell @probeArguments 2>&1
$probeExitCode = $LASTEXITCODE
$probeOutput | ForEach-Object { Write-Host $_ }
if ($probeExitCode -ne 0) {
    throw "Probe Nintendo 3DS falhou no aparelho com código $probeExitCode."
}
if (($probeOutput -join "`n") -notmatch 'PROBE_RESULT=PASS') {
    throw 'Probe Nintendo 3DS não produziu a confirmação esperada.'
}
if ($MeasureState) {
    $probeText = $probeOutput -join "`n"
    if ($probeText -notmatch 'PROBE_STATE_SIZE=[1-9][0-9]*' -or
            $probeText -notmatch 'PROBE_STATE_WITHIN_LIMIT=1') {
        throw 'Probe Nintendo 3DS não mediu um state dentro do limite solicitado.'
    }
    if ($StateMaximumBytes -gt 0 -and
            ($probeText -notmatch 'PROBE_STATE_SERIALIZE_OK=1' -or
            $probeText -notmatch 'PROBE_STATE_UNSERIALIZE_OK=1' -or
            $probeText -notmatch 'PROBE_STATE_POST_RESTORE_FRAME=1')) {
        throw 'Probe Nintendo 3DS não concluiu o roundtrip de state.'
    }
}
