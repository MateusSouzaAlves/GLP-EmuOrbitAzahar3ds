[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $AzaharSource,
    [Parameter(Mandatory = $true)]
    [string] $NdkRoot,
    [string] $OutputDirectory = (Join-Path $env:TEMP 'emuorbit-n3ds-core-probe')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sourceRoot = (Resolve-Path -LiteralPath $AzaharSource).Path
$ndkPath = (Resolve-Path -LiteralPath $NdkRoot).Path
$libretroInclude = Join-Path $sourceRoot `
    'externals\libretro-common\libretro-common\include'
$libretroHeader = Join-Path $libretroInclude 'libretro.h'
$clang = Join-Path $ndkPath `
    'toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
$source = Join-Path $projectRoot `
    'nintendo3dscore\harness\n3ds_core_probe.cpp'

if (-not (Test-Path -LiteralPath $libretroHeader -PathType Leaf)) {
    throw "Cabeçalho libretro do Azahar fixado não encontrado: $libretroHeader"
}
if (-not (Test-Path -LiteralPath $clang -PathType Leaf)) {
    throw "Compilador do Android NDK não encontrado: $clang"
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$output = Join-Path $OutputDirectory 'n3ds_core_probe'
$arguments = @(
    '--target=aarch64-linux-android21',
    '-std=c++20',
    '-O2',
    '-fPIE',
    '-pie',
    '-Wall',
    '-Wextra',
    '-Werror',
    '-Wl,-z,max-page-size=16384',
    '-Wl,-z,common-page-size=16384',
    "-I$libretroInclude",
    $source,
    '-ldl',
    '-lEGL',
    '-lGLESv3',
    '-o',
    $output
)

& $clang @arguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$readelf = Join-Path $ndkPath `
    'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
$programHeaders = (& $readelf -lW $output) -join "`n"
if ($LASTEXITCODE -ne 0 -or $programHeaders -notmatch 'LOAD\s+.*0x4000') {
    throw 'O probe ARM64 não foi gerado com alinhamento ELF de 16 KiB.'
}

$artifact = Get-Item -LiteralPath $output
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $output).Hash
Write-Host "N3DS_CORE_PROBE=$($artifact.FullName)"
Write-Host "N3DS_CORE_PROBE_SIZE=$($artifact.Length)"
Write-Host "N3DS_CORE_PROBE_SHA256=$hash"
