[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $NdkRoot,
    [string] $OutputDirectory = (Join-Path $env:TEMP 'emuorbit-n3ds-backend-probe')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ndkPath = (Resolve-Path -LiteralPath $NdkRoot).Path
$clang = Join-Path $ndkPath `
    'toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
$readelf = Join-Path $ndkPath `
    'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
$nativeRoot = Join-Path $projectRoot 'nintendo3dscore\src\main\cpp'
$harnessRoot = Join-Path $projectRoot 'nintendo3dscore\harness'

if (-not (Test-Path -LiteralPath $clang -PathType Leaf)) {
    throw "Compilador do Android NDK não encontrado: $clang"
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$mockCore = Join-Path $OutputDirectory 'azahar_mock_libretro.so'
$probe = Join-Path $OutputDirectory 'n3ds_backend_bootstrap_probe'
$commonArguments = @(
    '--target=aarch64-linux-android26',
    '-std=c++17',
    '-O2',
    '-Wall',
    '-Wextra',
    '-Werror',
    '-fvisibility=hidden',
    '-Wl,-z,max-page-size=16384',
    '-Wl,-z,common-page-size=16384',
    "-I$nativeRoot"
)

& $clang @commonArguments '-shared' '-fPIC' `
    (Join-Path $harnessRoot 'n3ds_mock_bootstrap_core.cpp') `
    '-o' $mockCore
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $clang @commonArguments '-fPIE' '-pie' '-pthread' `
    (Join-Path $nativeRoot 'core_bootstrap.cpp') `
    (Join-Path $nativeRoot 'core_session_registry.cpp') `
    (Join-Path $harnessRoot 'n3ds_backend_bootstrap_probe.cpp') `
    '-ldl' '-o' $probe
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

foreach ($artifact in @($mockCore, $probe)) {
    $programHeaders = (& $readelf -lW $artifact) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $programHeaders -notmatch 'LOAD\s+.*0x4000') {
        throw "Artefato sem alinhamento ELF de 16 KiB: $artifact"
    }
}

Write-Host "N3DS_BACKEND_PROBE=$probe"
Write-Host "N3DS_BACKEND_MOCK_CORE=$mockCore"
Write-Host "N3DS_BACKEND_PROBE_SHA256=$((Get-FileHash -Algorithm SHA256 -LiteralPath $probe).Hash)"
Write-Host "N3DS_BACKEND_MOCK_SHA256=$((Get-FileHash -Algorithm SHA256 -LiteralPath $mockCore).Hash)"
