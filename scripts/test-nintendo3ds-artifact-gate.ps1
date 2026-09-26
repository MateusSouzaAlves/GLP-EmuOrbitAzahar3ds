[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $CoreFile,
    [Parameter(Mandatory = $true)]
    [string] $BundletoolJar,
    [string] $NdkRoot = "$env:LOCALAPPDATA\Android\Sdk\ndk\29.0.14206865",
    [string] $Python = 'python',
    [string] $BuildSeed = 'n3ds-artifact-gate-v1',
    [switch] $ReuseExistingBundle
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$expectedBranch = 'codex/nintendo-3ds-analysis'
$baselineMain = '6babe3363abc851ccf11647b2cabf8db0c669819'
$publicSourceCommit = '85ff5ce78e439e5d9fae84dd45165f061a9f18ed'
$expectedCoreBytes = 23157736L
$expectedCoreSha256 = '64221F5CA8E731846523669DAB3CA569F796CCEE57F5E4F78B29B4E0330A734C'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'emuorbit-n3ds-artifact-gate-' + [guid]::NewGuid().ToString('N'))

function Resolve-Leaf {
    param([string] $Path, [string] $Label)
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "$Label inválido: $resolved"
    }
    return $resolved
}

function Invoke-Checked {
    param([string] $Executable, [string[]] $Arguments, [string] $Label)
    $output = @(& $Executable @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "$Label falhou.`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

$core = Resolve-Leaf $CoreFile 'Core Nintendo 3DS'
$bundletool = Resolve-Leaf $BundletoolJar 'Bundletool'
$ndk = (Resolve-Path -LiteralPath $NdkRoot -ErrorAction Stop).Path
$java = (Get-Command java.exe -ErrorAction Stop).Source
$pythonCommand = (Get-Command $Python -ErrorAction Stop).Source
$branch = (& git -C $projectRoot branch --show-current).Trim()
$main = (& git -C $projectRoot rev-parse main).Trim()
$originMain = (& git -C $projectRoot rev-parse origin/main).Trim()
if ($branch -ne $expectedBranch -or $main -ne $baselineMain -or $originMain -ne $baselineMain) {
    throw "Fronteira Git recusada: branch=$branch main=$main origin/main=$originMain"
}
if ($BuildSeed -notmatch '^[A-Za-z0-9._-]{8,128}$') {
    throw "Seed de auditoria 3DS inválida."
}
$coreItem = Get-Item -LiteralPath $core
$coreHash = (Get-FileHash -LiteralPath $core -Algorithm SHA256).Hash
if ($coreItem.Length -ne $expectedCoreBytes -or $coreHash -ne $expectedCoreSha256) {
    throw "Core Nintendo 3DS divergente: $($coreItem.Length) bytes/$coreHash"
}
$bundletoolVersion = (Invoke-Checked $java @('-jar', $bundletool, 'version') 'Bundletool') -join ''
if (-not $bundletoolVersion.Contains('1.18.3')) {
    throw "Bundletool 1.18.3 obrigatório; observado: $bundletoolVersion"
}
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    if (-not $ReuseExistingBundle) {
        Push-Location $projectRoot
        try {
            & .\gradlew.bat :app:bundleDebug `
                "-PEMUORBIT_N3DS_CORE_FILE=$core" `
                "-PEMUORBIT_BUILD_SEED=$BuildSeed" `
                --no-daemon
            if ($LASTEXITCODE -ne 0) {
                throw 'A geração do AAB debug normal falhou.'
            }
        } finally {
            Pop-Location
        }
    }

    $bundle = Resolve-Leaf (
        Join-Path $projectRoot 'app\build\outputs\bundle\debug\app-debug.aab') 'AAB'
    $apks = Join-Path $temporaryRoot 'candidate-universal.apks'
    Invoke-Checked $java @(
        '-jar', $bundletool, 'build-apks',
        "--bundle=$bundle", "--output=$apks", '--mode=universal', '--overwrite'
    ) 'APK universal' | Out-Null

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $outer = [IO.Compression.ZipFile]::OpenRead($apks)
    try {
        $entry = $outer.GetEntry('universal.apk')
        if ($null -eq $entry) {
            throw 'universal.apk não foi gerado pelo bundletool.'
        }
        $universalApk = Join-Path $temporaryRoot 'universal.apk'
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $universalApk, $true)
    } finally {
        $outer.Dispose()
    }

    $featureManifest = Join-Path $temporaryRoot 'nintendo3dscore-manifest.xml'
    $featureManifestOutput = Invoke-Checked $java @(
        '-jar', $bundletool, 'dump', 'manifest',
        "--bundle=$bundle", '--module=nintendo3dscore'
    ) 'Manifesto derivado do AAB'
    [IO.File]::WriteAllLines(
        $featureManifest,
        $featureManifestOutput,
        [Text.UTF8Encoding]::new($false))

    $boundaryJson = (Invoke-Checked $pythonCommand @(
        (Join-Path $PSScriptRoot 'audit-nintendo3ds-bundle.py'),
        '--bundle', $bundle,
        '--feature-manifest', $featureManifest,
        '--seed', $BuildSeed
    ) 'Auditoria da fronteira AAB') -join "`n"
    $artifactJson = (Invoke-Checked $pythonCommand @(
        (Join-Path $PSScriptRoot 'audit-nintendo3ds-packaged-artifacts.py'),
        '--bundle', $bundle,
        '--universal-apk', $universalApk,
        '--ndk-root', $ndk,
        '--seed', $BuildSeed,
        '--forbid-path', $projectRoot,
        '--forbid-path', $temporaryRoot
    ) 'Auditoria artifact-only') -join "`n"

    $buildToolsRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools'
    $apksigner = Get-ChildItem -LiteralPath $buildToolsRoot -Filter 'apksigner.bat' `
        -File -Recurse | Sort-Object FullName -Descending | Select-Object -First 1
    if ($null -eq $apksigner) {
        throw "apksigner não encontrado abaixo de $buildToolsRoot"
    }
    $signatureOutput = Invoke-Checked $apksigner.FullName @(
        'verify', '--verbose', '--print-certs', $universalApk
    ) 'Assinatura do APK universal'

    $result = [ordered]@{
        schemaVersion = 1
        status = 'PASSED'
        branch = $branch
        appRevision = (& git -C $projectRoot rev-parse HEAD).Trim()
        bundletool = $bundletoolVersion.Trim()
        publicSourceCommit = $publicSourceCommit
        boundary = $boundaryJson | ConvertFrom-Json
        artifacts = $artifactJson | ConvertFrom-Json
        apkSignatureVerified = $true
        apkSigner = $apksigner.FullName
        apkSignatureSummary = @($signatureOutput | Where-Object {
                $_ -match '^Verified using|^Signer #1 certificate SHA-256 digest:'
            })
    }
    $result | ConvertTo-Json -Depth 8
} finally {
    if (Test-Path -LiteralPath $temporaryRoot -PathType Container) {
        $resolvedTemporary = (Resolve-Path -LiteralPath $temporaryRoot).Path
        $expectedPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        $insideSystemTemp = $resolvedTemporary.StartsWith(
            $expectedPrefix,
            [StringComparison]::OrdinalIgnoreCase)
        $hasExpectedName = (Split-Path -Leaf $resolvedTemporary).StartsWith(
            'emuorbit-n3ds-artifact-gate-',
            [StringComparison]::Ordinal)
        if (-not $insideSystemTemp -or -not $hasExpectedName) {
            throw "Diretório temporário recusado na limpeza: $resolvedTemporary"
        }
        Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
    }
}
