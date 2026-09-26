[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $CorePath,
    [string] $ContentPath,
    [string] $ContentLicensePath,
    [string] $OutputZip = 'artifacts/latest/EmuOrbit-N3DS-Adreno-Gate.zip'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$contractPath = Join-Path $projectRoot 'config/nintendo3ds-adreno-gate.json'
$validatorPath = Join-Path $PSScriptRoot 'validate-nintendo3ds-adreno-gate.py'
$localRunnerPath = Join-Path $PSScriptRoot 'run-nintendo3ds-adreno-gate.ps1'
$firebaseRunnerPath = Join-Path $PSScriptRoot 'run-firebase-adreno-gate.ps1'
$noticePath = Join-Path $projectRoot 'nintendo3dscore/compliance/THIRD_PARTY_NOTICES.txt'
$baselineMain = '6babe3363abc851ccf11647b2cabf8db0c669819'
$expectedBranch = 'codex/nintendo-3ds-analysis'
$qaBuildSeed = 'n3ds-adreno-private-qa-v1'
$stageRoot = Join-Path $projectRoot (
    'build/nintendo3ds-adreno-package-' + [Guid]::NewGuid().ToString('N'))

function Get-Sha256 {
    param([string] $Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-Identity {
    param(
        [string] $Path,
        [int64] $Bytes,
        [string] $Sha256,
        [string] $Label
    )
    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($item.Length -ne $Bytes -or (Get-Sha256 $Path) -ne $Sha256) {
        throw "$Label does not match its pinned identity."
    }
}

function Assert-WorkspaceOutput {
    param([string] $Path)
    $resolved = [IO.Path]::GetFullPath($Path)
    $prefix = $projectRoot.TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The portable Adreno output must remain inside the workspace.'
    }
    return $resolved
}

function Find-SingleApk {
    param([string] $Directory, [string] $Label)
    $candidates = @(Get-ChildItem -LiteralPath $Directory -Filter '*.apk' -File)
    if ($candidates.Count -ne 1) {
        throw "Expected exactly one $Label APK."
    }
    return $candidates[0].FullName
}

function Get-ApkEntries {
    param([string] $Path)
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        return @($archive.Entries.FullName)
    } finally {
        $archive.Dispose()
    }
}

foreach ($required in @(
        $contractPath, $validatorPath, $localRunnerPath, $firebaseRunnerPath, $noticePath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw 'A portable Adreno gate source file is unavailable.'
    }
}
$resolvedCore = (Resolve-Path -LiteralPath $CorePath -ErrorAction Stop).Path
if ([string]::IsNullOrWhiteSpace($ContentPath) `
        -xor [string]::IsNullOrWhiteSpace($ContentLicensePath)) {
    throw 'Cached content and its license must be supplied together.'
}
$resolvedCachedContent = if ([string]::IsNullOrWhiteSpace($ContentPath)) {
    $null
} else {
    (Resolve-Path -LiteralPath $ContentPath -ErrorAction Stop).Path
}
$resolvedCachedLicense = if ([string]::IsNullOrWhiteSpace($ContentLicensePath)) {
    $null
} else {
    (Resolve-Path -LiteralPath $ContentLicensePath -ErrorAction Stop).Path
}
$resolvedOutput = if ([IO.Path]::IsPathRooted($OutputZip)) {
    Assert-WorkspaceOutput $OutputZip
} else {
    Assert-WorkspaceOutput (Join-Path $projectRoot $OutputZip)
}
if ([IO.Path]::GetExtension($resolvedOutput) -ne '.zip') {
    throw 'The portable Adreno output must be a ZIP file.'
}

$contract = Get-Content -Raw -LiteralPath $contractPath | ConvertFrom-Json
Assert-Identity `
    -Path $resolvedCore `
    -Bytes ([int64] $contract.core.bytes) `
    -Sha256 ([string] $contract.core.sha256) `
    -Label 'The hardened Nintendo 3DS core'

try {
    [void] (New-Item -ItemType Directory -Path $stageRoot)
    $contentPath = Join-Path $stageRoot 'open-homebrew.3dsx'
    $licensePath = Join-Path $stageRoot 'MARS3DS_LICENSE.txt'
    if ($null -ne $resolvedCachedContent) {
        Copy-Item -LiteralPath $resolvedCachedContent -Destination $contentPath
        Copy-Item -LiteralPath $resolvedCachedLicense -Destination $licensePath
    } else {
        Invoke-WebRequest -UseBasicParsing -Uri $contract.content.assetUrl -OutFile $contentPath
        Invoke-WebRequest -UseBasicParsing -Uri $contract.content.licenseUrl -OutFile $licensePath
    }
    Assert-Identity `
        -Path $contentPath `
        -Bytes ([int64] $contract.content.bytes) `
        -Sha256 ([string] $contract.content.sha256) `
        -Label 'The redistributable Nintendo 3DS homebrew'
    Assert-Identity `
        -Path $licensePath `
        -Bytes ([int64] $contract.content.licenseBytes) `
        -Sha256 ([string] $contract.content.licenseSha256) `
        -Label 'The Nintendo 3DS homebrew license'

    Push-Location $projectRoot
    try {
        $branch = (& git branch --show-current).Trim()
        $mainRevision = (& git rev-parse main).Trim()
        $originMainRevision = (& git rev-parse origin/main).Trim()
        if ($branch -ne $expectedBranch) {
            throw 'The Adreno kit must be prepared from the isolated implementation branch.'
        }
        if ($mainRevision -ne $baselineMain -or $originMainRevision -ne $baselineMain) {
            throw 'The protected main branch invariant changed.'
        }
        & python $validatorPath | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw 'The portable Adreno gate contract is invalid.'
        }

        & .\gradlew.bat :nintendo3dscore:assembleDebugAndroidTest --no-daemon `
            "-PEMUORBIT_N3DS_CORE_FILE=$resolvedCore" `
            "-PEMUORBIT_BUILD_SEED=$qaBuildSeed"
        if ($LASTEXITCODE -ne 0) {
            throw 'The self-contained local Nintendo 3DS test APK did not compile.'
        }
        $testOutput = Join-Path $projectRoot (
            'nintendo3dscore/build/outputs/apk/androidTest/debug')
        $localTestApk = Find-SingleApk $testOutput 'local self-contained Nintendo 3DS test'
        Copy-Item -LiteralPath $localTestApk -Destination (
            Join-Path $stageRoot 'emuorbit-n3ds-adreno-test.apk')

        & .\gradlew.bat `
            :nintendo3dsadrenotarget:assembleDebug `
            :nintendo3dscore:assembleDebugAndroidTest `
            --no-daemon `
            "-PEMUORBIT_N3DS_CORE_FILE=$resolvedCore" `
            "-PEMUORBIT_N3DS_ADRENO_CONTENT_FILE=$contentPath" `
            "-PEMUORBIT_N3DS_ADRENO_CONTENT_LICENSE_FILE=$licensePath" `
            '-PEMUORBIT_N3DS_ADRENO_CLOUD_TEST=true' `
            "-PEMUORBIT_BUILD_SEED=$qaBuildSeed"
        if ($LASTEXITCODE -ne 0) {
            throw 'The Firebase Test Lab target/test APK pair did not compile.'
        }
        $cloudTestApk = Find-SingleApk $testOutput 'Firebase Nintendo 3DS test'
        $cloudTargetOutput = Join-Path $projectRoot (
            'nintendo3dscore/adreno-target/build/outputs/apk/debug')
        $cloudTargetApk = Find-SingleApk $cloudTargetOutput 'Firebase Nintendo 3DS target'
        Copy-Item -LiteralPath $cloudTestApk -Destination (
            Join-Path $stageRoot 'emuorbit-n3ds-firebase-test.apk')
        Copy-Item -LiteralPath $cloudTargetApk -Destination (
            Join-Path $stageRoot 'emuorbit-n3ds-firebase-target.apk')
    } finally {
        Pop-Location
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $localEntries = Get-ApkEntries (
        Join-Path $stageRoot 'emuorbit-n3ds-adreno-test.apk')
    $cloudTestEntries = Get-ApkEntries (
        Join-Path $stageRoot 'emuorbit-n3ds-firebase-test.apk')
    foreach ($entries in @($localEntries, $cloudTestEntries)) {
        if ('lib/arm64-v8a/libemuorbit_n3ds_bootstrap.so' -notin $entries) {
            throw 'A portable test APK lacks its ARM64 JNI bootstrap.'
        }
        if (@($entries | Where-Object {
                    $_ -like '*libazahar_libretro.so' -or $_ -like '*.3dsx'
                }).Count -ne 0) {
            throw 'A test APK must not embed the core or Nintendo 3DS content.'
        }
    }
    $cloudTargetEntries = Get-ApkEntries (
        Join-Path $stageRoot 'emuorbit-n3ds-firebase-target.apk')
    if ('lib/arm64-v8a/libemuorbit_n3ds_bootstrap.so' -notin $cloudTargetEntries) {
        throw 'The private Firebase target APK lacks its ARM64 JNI bootstrap.'
    }
    foreach ($requiredAsset in @(
            'assets/n3ds-adreno/azahar_libretro.so',
            'assets/n3ds-adreno/open-homebrew.3dsx',
            'assets/n3ds-adreno/MARS3DS_LICENSE.txt',
            'assets/n3ds-adreno/THIRD_PARTY_NOTICES.txt')) {
        if ($requiredAsset -notin $cloudTargetEntries) {
            throw 'The private Firebase target APK lacks a pinned licensed QA asset.'
        }
    }

    Copy-Item -LiteralPath $contractPath -Destination (Join-Path $stageRoot 'adreno-gate.json')
    Copy-Item -LiteralPath $resolvedCore -Destination (Join-Path $stageRoot 'azahar_libretro.so')
    Copy-Item -LiteralPath $localRunnerPath -Destination (
        Join-Path $stageRoot 'run-adreno-gate.ps1')
    Copy-Item -LiteralPath $firebaseRunnerPath -Destination (
        Join-Path $stageRoot 'run-firebase-adreno-gate.ps1')
    Copy-Item -LiteralPath $noticePath -Destination (
        Join-Path $stageRoot 'THIRD_PARTY_NOTICES.txt')

    $readme = @'
EmuOrbit Nintendo 3DS - physical Adreno acceptance kit

This is a private QA kit, not the production app. It contains two isolated test
paths: raw ADB for an owned physical device, and a separate target/test APK pair
for Firebase Test Lab. It contains the pinned hardened core, one redistributable
MIT homebrew and required notices. It contains no commercial game or user dump.

    Local ADB requirements: Windows PowerShell, Android platform-tools/adb, one
    unlocked physical ARM64 Android device, API 26 or newer. To run only the two
    short lifecycle gates used by N3DS-06 on the owned A37/A34/Poco matrix:
      .\run-adreno-gate.ps1 -Serial <adb-serial> -LifecycleOnly
    A34 and Poco are intentionally executed only once in the final consolidated
    local device pass. Firebase or emulators do not substitute for those runs.
    To run the complete Adreno acceptance from the extracted directory:
      .\run-adreno-gate.ps1 -Serial <adb-serial>

Firebase requirements: authenticated Google Cloud CLI, project emuorbitadvance
on the Spark plan with billing disabled, and the pinned physical Google Pixel 5
(redfin, Android 30, Snapdragon 765G/Adreno 620) from the current Test Lab
catalog. First validate the focused phase without cloud side effects:
  .\run-firebase-adreno-gate.ps1
Only after confirming the project still has billing disabled:
  .\run-firebase-adreno-gate.ps1 -Execute
After all focused checks pass, run the required 20-minute stability phase:
  .\run-firebase-adreno-gate.ps1 -Phase LongRun -Execute

The acceptance is complete only after the six focused checks and the separate
20-minute balanced driver run pass. This ordering preserves result quality while
avoiding a long physical-device run when a fast prerequisite already fails. The
Firebase launcher refuses execution when billing is enabled or ambiguous. The
target/test pair is private QA infrastructure and is not linked to the production
EmuOrbit application package.
'@
    [IO.File]::WriteAllText(
        (Join-Path $stageRoot 'README.txt'),
        $readme.TrimStart(),
        [Text.UTF8Encoding]::new($false))

    $hashedFiles = @(
        'adreno-gate.json',
        'azahar_libretro.so',
        'emuorbit-n3ds-adreno-test.apk',
        'emuorbit-n3ds-firebase-target.apk',
        'emuorbit-n3ds-firebase-test.apk',
        'MARS3DS_LICENSE.txt',
        'open-homebrew.3dsx',
        'README.txt',
        'run-adreno-gate.ps1',
        'run-firebase-adreno-gate.ps1',
        'THIRD_PARTY_NOTICES.txt'
    ) | Sort-Object
    $manifest = [ordered]@{
        schemaVersion = 1
        planItems = @('N3DS-06C', 'N3DS-11D', 'N3DS-13D')
        files = @($hashedFiles | ForEach-Object {
                $path = Join-Path $stageRoot $_
                [ordered]@{
                    path = $_
                    bytes = [int64] (Get-Item -LiteralPath $path).Length
                    sha256 = Get-Sha256 $path
                }
            })
    }
    [IO.File]::WriteAllText(
        (Join-Path $stageRoot 'bundle-manifest.json'),
        ($manifest | ConvertTo-Json -Depth 6),
        [Text.UTF8Encoding]::new($false))

    & python $validatorPath --bundle $stageRoot | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'The assembled portable Adreno kit failed its file-identity contract.'
    }

    [void] (New-Item -ItemType Directory -Path (Split-Path -Parent $resolvedOutput) -Force)
    if (Test-Path -LiteralPath $resolvedOutput) {
        [IO.File]::Delete($resolvedOutput)
    }
    [IO.Compression.ZipFile]::CreateFromDirectory(
        $stageRoot,
        $resolvedOutput,
        [IO.Compression.CompressionLevel]::Optimal,
        $false)
    Write-Host ((
        'N3DS_ADRENO_PACKAGE status=READY localAdb=true firebaseTestLab=true ' +
        'bytes={0} sha256={1} paidServiceUsed=false commercialContentUsed=false') -f `
        (Get-Item -LiteralPath $resolvedOutput).Length,
        (Get-Sha256 $resolvedOutput))
} finally {
    $resolvedStage = [IO.Path]::GetFullPath($stageRoot)
    $expectedPrefix = $projectRoot.TrimEnd('\') + '\build\nintendo3ds-adreno-package-'
    if (Test-Path -LiteralPath $resolvedStage) {
        if (-not $resolvedStage.StartsWith(
                $expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to clean an unexpected Adreno package stage.'
        }
        [IO.Directory]::Delete($resolvedStage, $true)
    }
}
