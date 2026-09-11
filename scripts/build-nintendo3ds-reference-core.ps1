[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $AzaharSource,
    [Parameter(Mandatory = $true)]
    [string] $NdkRoot,
    [Parameter(Mandatory = $true)]
    [string] $CMakeRoot,
    [Parameter(Mandatory = $true)]
    [string] $Ninja,
    [string] $BuildDirectory,
    [string] $OutputDirectory = (Join-Path $env:TEMP 'emuorbit-n3ds-reference-core'),
    [switch] $UpdateCompatibilityCandidate
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$manifestPath = Join-Path $projectRoot 'config\nintendo3ds-source-scope.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 |
    ConvertFrom-Json
$upstream = @($manifest.distributedUpstreams) |
    Where-Object { $_.id -eq 'azahar' } |
    Select-Object -First 1
if ($null -eq $upstream) {
    throw 'O manifesto não contém o upstream Azahar.'
}
$buildSpec = $manifest.referenceBuild
$sourceRoot = (Resolve-Path -LiteralPath $AzaharSource).Path
$ndkPath = (Resolve-Path -LiteralPath $NdkRoot).Path
$cmake = Join-Path (Resolve-Path -LiteralPath $CMakeRoot).Path 'bin\cmake.exe'
$ninjaPath = (Resolve-Path -LiteralPath $Ninja).Path
$expectedRevision = $upstream
if ($UpdateCompatibilityCandidate) {
    $expectedRevision = $manifest.n3ds09Decision.updateCompatibilityCandidate
    if ($null -eq $expectedRevision) {
        throw 'O manifesto não contém candidato de atualização do Azahar.'
    }
}
$expectedCommit = [string] $expectedRevision.commit
$expectedSubmoduleCount = [int] $expectedRevision.recursiveSubmoduleCount
$patches = @(
    (Join-Path $projectRoot `
        'nintendo3dscore\patches\azahar-build-worktree-hooks.patch'),
    (Join-Path $projectRoot `
        'nintendo3dscore\patches\azahar-2126.0-offline-no-legacy-tls.patch'),
    (Join-Path $projectRoot `
        'nintendo3dscore\patches\azahar-2126.0-libretro-software-android.patch'),
    (Join-Path $projectRoot `
        'nintendo3dscore\patches\azahar-2126.0-libretro-vfs-seek-semantics.patch')
)
$hardeningScript = (Resolve-Path -LiteralPath (Join-Path $projectRoot `
    'nintendo3dscore\src\main\cpp\azahar_hardening.cmake')).Path.Replace('\', '/')
$coreExportMap = (Resolve-Path -LiteralPath (Join-Path $projectRoot `
    'nintendo3dscore\src\main\cpp\azahar_libretro.exports.map')).Path.Replace('\', '/')

function Test-GitPatch {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Repository,
        [Parameter(Mandatory = $true)]
        [string] $Patch,
        [switch] $Reverse
    )

    $arguments = @('-C', $Repository, 'apply')
    if ($Reverse) {
        $arguments += '--reverse'
    }
    $arguments += @('--check', $Patch)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'SilentlyContinue'
        & git @arguments 2>$null
        return $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

if (-not $BuildDirectory) {
    $BuildDirectory = Join-Path $sourceRoot 'build\emuorbit-android-arm64-v8a'
}

$ndkProperties = Get-Content -LiteralPath (Join-Path $ndkPath 'source.properties') `
    -Raw -Encoding UTF8
if ($ndkProperties -notmatch "(?m)^Pkg\.Revision\s*=\s*$([regex]::Escape([string] $buildSpec.androidNdk))\s*$") {
    throw "Android NDK divergente; esperado $($buildSpec.androidNdk)."
}
$cmakeVersionOutput = @(& $cmake --version)
$cmakeVersionExitCode = $LASTEXITCODE
$cmakeVersionLine = $cmakeVersionOutput | Select-Object -First 1
if ($cmakeVersionExitCode -ne 0 -or
        $cmakeVersionLine -ne "cmake version $($buildSpec.cmake)") {
    throw "CMake divergente; esperado $($buildSpec.cmake)."
}
$ninjaVersion = (& $ninjaPath --version).Trim()
if ($LASTEXITCODE -ne 0 -or $ninjaVersion -ne [string] $buildSpec.ninja) {
    throw "Ninja divergente; esperado $($buildSpec.ninja)."
}

$actualCommit = (& git -C $sourceRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actualCommit -ne $expectedCommit) {
    throw "Checkout Azahar inesperado: $actualCommit"
}
$sourceDateEpoch = (& git -C $sourceRoot show -s --format=%ct $expectedCommit).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceDateEpoch -notmatch '^[0-9]+$') {
    throw 'Não foi possível fixar SOURCE_DATE_EPOCH pelo commit do Azahar.'
}
$submoduleStatus = & git -C $sourceRoot submodule status --recursive
if ($LASTEXITCODE -ne 0 -or @($submoduleStatus).Count -ne $expectedSubmoduleCount -or
        @($submoduleStatus | Where-Object { $_ -notmatch '^ [0-9a-f]{40} ' }).Count -ne 0) {
    throw "Os $expectedSubmoduleCount submódulos fixados do Azahar não estão inicializados e limpos."
}

foreach ($patch in $patches) {
    if (Test-GitPatch -Repository $sourceRoot -Patch $patch) {
        & git -C $sourceRoot apply $patch
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    } elseif (-not (Test-GitPatch -Repository $sourceRoot -Patch $patch -Reverse)) {
        throw "Patch não corresponde ao checkout Azahar fixado: $patch"
    }
}

$toolchain = Join-Path $ndkPath 'build\cmake\android.toolchain.cmake'
$configureArguments = @(
    '-S', $sourceRoot,
    '-B', $BuildDirectory,
    '-G', 'Ninja',
    '-DCMAKE_BUILD_TYPE=Release',
    '-DENABLE_LIBRETRO=ON',
    '-DENABLE_SOFTWARE_RENDERER=ON',
    '-DENABLE_WEB_SERVICE=OFF',
    '-DENABLE_HTTPS=OFF',
    '-DANDROID_ABI=arm64-v8a',
    '-DANDROID_PLATFORM=android-21',
    '-DANDROID_STL=c++_static',
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    "-DCMAKE_MAKE_PROGRAM=$ninjaPath",
    "-DCMAKE_PROJECT_INCLUDE=$hardeningScript",
    "-DEMUORBIT_N3DS_CORE_EXPORT_MAP=$coreExportMap"
)
if (Test-Path Env:SOURCE_DATE_EPOCH) {
    $previousSourceDateEpoch = $env:SOURCE_DATE_EPOCH
}
$env:SOURCE_DATE_EPOCH = $sourceDateEpoch
$python = (Get-Command python -ErrorAction Stop).Source
try {
    & $python (Join-Path $PSScriptRoot 'generate-nintendo3ds-compliance.py') --check
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    & $cmake @configureArguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    $auditArguments = @(
        (Join-Path $PSScriptRoot 'audit-nintendo3ds-upstream.py'),
        '--source-root', $sourceRoot,
        '--build-root', $BuildDirectory,
        '--ninja', $ninjaPath
    )
    if ($UpdateCompatibilityCandidate) {
        $auditArguments += '--candidate'
    }
    & $python @auditArguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    & $cmake --build $BuildDirectory --target azahar_libretro --parallel
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    if ($null -ne $previousSourceDateEpoch) {
        $env:SOURCE_DATE_EPOCH = $previousSourceDateEpoch
    } else {
        Remove-Item Env:SOURCE_DATE_EPOCH -ErrorAction SilentlyContinue
    }
}

$core = Join-Path $BuildDirectory 'bin\Release\azahar_libretro.so'
$llvmBin = Join-Path $ndkPath 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$readelf = Join-Path $llvmBin 'llvm-readelf.exe'
$strip = Join-Path $llvmBin 'llvm-strip.exe'
$programHeaders = (& $readelf -lW $core) -join "`n"
if ($LASTEXITCODE -ne 0 -or $programHeaders -notmatch 'LOAD\s+.*0x4000') {
    throw 'O core Azahar não possui alinhamento ELF de 16 KiB.'
}
$linkCommand = (& $ninjaPath -C $BuildDirectory -t commands azahar_libretro |
        Select-Object -Last 1)
if ($linkCommand -match 'libressl|libssl\.a|libcrypto\.a') {
    throw 'A baseline offline ainda contém a implementação TLS incompatível.'
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$strippedCore = Join-Path $OutputDirectory 'azahar_libretro.so'
Copy-Item -LiteralPath $core -Destination $strippedCore -Force
& $strip --strip-all --remove-section=.comment $strippedCore
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
$actualSize = (Get-Item -LiteralPath $strippedCore).Length
$actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $strippedCore).Hash.ToLowerInvariant()
if ($UpdateCompatibilityCandidate) {
    if ($actualSize -lt 1MB) {
        throw 'O candidato de atualização produziu um core inesperadamente pequeno.'
    }
    if ($expectedRevision.strippedBytes -and
            ($actualSize -ne [long] $expectedRevision.strippedBytes -or
            $actualSha256 -ne [string] $expectedRevision.strippedSha256)) {
        throw 'O candidato reproduzido diverge do tamanho ou SHA-256 fixado.'
    }
} elseif ($actualSize -ne [long] $buildSpec.strippedBytes -or
        $actualSha256 -ne [string] $buildSpec.strippedSha256) {
    throw 'O core reproduzido diverge do tamanho ou SHA-256 fixado no manifesto.'
}

Write-Host "N3DS_CORE=$strippedCore"
Write-Host "N3DS_CORE_REVISION=$expectedCommit"
Write-Host "N3DS_CORE_SIZE=$actualSize"
Write-Host "N3DS_CORE_SHA256=$actualSha256"
