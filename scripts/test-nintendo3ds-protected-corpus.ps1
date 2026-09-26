[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $CoreFile,
    [Parameter(Mandatory = $true)]
    [string] $DeviceSerial,
    [string] $AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [string] $Python = 'py'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$corpusPath = Join-Path $projectRoot 'config\nintendo3ds-open-homebrew-corpus.json'
$corpusValidator = Join-Path $PSScriptRoot 'validate-nintendo3ds-open-homebrew-corpus.py'
$package = 'com.mateussouza.emuorbit.n3ds.core.test'
$runner = 'androidx.test.runner.AndroidJUnitRunner'
$testMethod = 'com.mateussouza.emuorbit.n3ds.core.' +
    'Nintendo3DsProtectedCoreInstrumentedTest#' +
    'packagedCoreRunsFiveContentsAcrossProductLifecycle'
$expectedBranch = 'codex/nintendo-3ds-analysis'
$baselineMain = '6babe3363abc851ccf11647b2cabf8db0c669819'
$expectedCoreBytes = 23157736L
$expectedCoreSha256 = '64221F5CA8E731846523669DAB3CA569F796CCEE57F5E4F78B29B4E0330A734C'
$buildSeed = 'n3ds12f-physical-v1'
$runId = [Guid]::NewGuid().ToString('N')
$stageRoot = Join-Path ([IO.Path]::GetTempPath()) "EmuOrbit-N3DS-12F3-$runId"
$remotePrefix = "/data/local/tmp/emuorbit-n3ds12f3-$runId"
$privateRoot = "/data/user/0/$package/files"
$remotePaths = New-Object 'System.Collections.Generic.List[string]'
$privatePaths = New-Object 'System.Collections.Generic.List[string]'
$packageInstalledByGate = $false
$mainPackage = 'com.mateussouza.emuorbit.advance'

function Invoke-AdbCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments,
        [switch] $AllowFailure
    )

    $output = @(& $script:adb -s $DeviceSerial @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        $operation = $Arguments[0]
        if ($Arguments.Count -gt 1 -and $Arguments[0] -eq 'shell') {
            $operation = "shell/$($Arguments[1])"
        }
        throw "Uma operação ADB falhou no gate protegido N3DS-12F3 (etapa=$operation)."
    }
    return [PSCustomObject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Test-PackageInstalled {
    param([string] $PackageName)

    $result = Invoke-AdbCapture -Arguments @(
        'shell', 'pm', 'list', 'packages', $PackageName)
    return ($result.Output -join "`n") -match [regex]::Escape("package:$PackageName")
}

function Get-Sha256 {
    param([string] $Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Utf8Sha256 {
    param([string] $Text)

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return [Convert]::ToHexString(
            $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))
    } finally {
        $sha.Dispose()
    }
}

function Assert-FileIdentity {
    param(
        [string] $Path,
        [int64] $Bytes,
        [string] $Sha256,
        [switch] $Require3dsxMagic
    )

    $item = Get-Item -LiteralPath $Path
    if ($item.Length -ne $Bytes -or (Get-Sha256 $Path) -ne $Sha256) {
        throw 'Um artefato homebrew não corresponde à identidade fixada.'
    }
    if ($Require3dsxMagic) {
        $stream = [IO.File]::OpenRead($Path)
        try {
            $header = New-Object byte[] 4
            if ($stream.Read($header, 0, 4) -ne 4 -or
                    [Text.Encoding]::ASCII.GetString($header) -ne '3DSX') {
                throw 'Um conteúdo fixado não possui cabeçalho 3DSX válido.'
            }
        } finally {
            $stream.Dispose()
        }
    }
}

function Expand-ValidatedContent {
    param(
        [string] $ArchivePath,
        [string] $ContentPath,
        [string] $Destination
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $wanted = $null
        foreach ($entry in $archive.Entries) {
            $normalized = $entry.FullName.Replace('\', '/')
            $segments = @($normalized.Split('/') | Where-Object { $_ -ne '' })
            if ([IO.Path]::IsPathRooted($normalized) -or $segments -contains '..') {
                throw 'O arquivo homebrew contém caminho inseguro.'
            }
            if ($normalized -ceq $ContentPath) {
                $wanted = $entry
            }
        }
        if ($null -eq $wanted -or $wanted.Length -le 0) {
            throw 'O conteúdo 3DSX fixado não foi encontrado no arquivo.'
        }
        $input = $wanted.Open()
        $output = [IO.File]::Create($Destination)
        try {
            $input.CopyTo($output)
        } finally {
            $output.Dispose()
            $input.Dispose()
        }
    } finally {
        $archive.Dispose()
    }
}

function Remove-ExactStage {
    if (-not (Test-Path -LiteralPath $script:stageRoot -PathType Container)) {
        return
    }
    $resolved = [IO.Path]::GetFullPath($script:stageRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Split-Path -Leaf $resolved).StartsWith(
                'EmuOrbit-N3DS-12F3-', [StringComparison]::Ordinal)) {
        throw "Diretório temporário recusado na limpeza: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw 'ADB indisponível para N3DS-12F3.'
}
$pythonCommand = (Get-Command $Python -ErrorAction Stop).Source
$core = (Resolve-Path -LiteralPath $CoreFile -ErrorAction Stop).Path
$coreItem = Get-Item -LiteralPath $core
if ($coreItem.Length -ne $expectedCoreBytes -or
        (Get-FileHash -LiteralPath $core -Algorithm SHA256).Hash -ne $expectedCoreSha256) {
    throw 'O core 3DS não corresponde ao candidato fixado.'
}
$branch = (& git -C $projectRoot branch --show-current).Trim()
$main = (& git -C $projectRoot rev-parse main).Trim()
$originMain = (& git -C $projectRoot rev-parse origin/main).Trim()
if ($branch -ne $expectedBranch -or $main -ne $baselineMain -or
        $originMain -ne $baselineMain) {
    throw "Fronteira Git recusada: branch=$branch main=$main origin/main=$originMain"
}
& $pythonCommand $corpusValidator | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'O contrato do corpus homebrew não foi aprovado.'
}
$state = Invoke-AdbCapture -Arguments @('get-state')
if (($state.Output -join "`n").Trim() -ne 'device') {
    throw 'O Galaxy solicitado não está pronto no ADB.'
}
$model = (Invoke-AdbCapture -Arguments @('shell', 'getprop', 'ro.product.model')).Output -join ''
$android = (Invoke-AdbCapture -Arguments @(
        'shell', 'getprop', 'ro.build.version.release')).Output -join ''
$mainInstalledBefore = Test-PackageInstalled $mainPackage
$logicalStageBytes = 0L

try {
    [void] (New-Item -ItemType Directory -Path $stageRoot)
    $corpus = Get-Content -Raw -LiteralPath $corpusPath | ConvertFrom-Json
    $entries = @($corpus.entries | Select-Object -First 5)
    if ($entries.Count -ne 5 -or
            @($entries.contentSha256 | Select-Object -Unique).Count -ne 5) {
        throw 'O recorte protegido exige cinco homebrews binariamente distintos.'
    }
    $localContents = New-Object 'System.Collections.Generic.List[string]'
    for ($index = 0; $index -lt $entries.Count; $index++) {
        $entry = $entries[$index]
        $assetExtension = if ($null -eq $entry.archiveContentPath) { '.3dsx' } else { '.zip' }
        $asset = Join-Path $stageRoot ("asset-{0}{1}" -f ($index + 1), $assetExtension)
        $content = Join-Path $stageRoot ("content-{0}.3dsx" -f ($index + 1))
        Invoke-WebRequest -UseBasicParsing -Uri $entry.assetUrl -OutFile $asset
        Assert-FileIdentity $asset $entry.assetBytes $entry.assetSha256
        if ($null -eq $entry.archiveContentPath) {
            Copy-Item -LiteralPath $asset -Destination $content
        } else {
            Expand-ValidatedContent $asset $entry.archiveContentPath $content
        }
        Assert-FileIdentity $content $entry.contentBytes $entry.contentSha256 -Require3dsxMagic
        [void] $localContents.Add($content)
    }

    Push-Location $projectRoot
    try {
        & .\gradlew.bat :nintendo3dscore:assembleDebugAndroidTest --no-daemon `
            "-PEMUORBIT_N3DS_CORE_FILE=$core" `
            "-PEMUORBIT_BUILD_SEED=$buildSeed"
        if ($LASTEXITCODE -ne 0) {
            throw 'A compilação do APK protegido de QA falhou.'
        }
    } finally {
        Pop-Location
    }
    $testOutput = Join-Path $projectRoot (
        'nintendo3dscore\build\outputs\apk\androidTest\debug')
    $testApks = @(Get-ChildItem -LiteralPath $testOutput -Filter '*.apk' -File)
    if ($testApks.Count -ne 1) {
        throw 'O APK autocontido de QA não foi resolvido de forma inequívoca.'
    }
    $testApk = $testApks[0].FullName
    $protectedAsset = 'q' + (
        Get-Utf8Sha256 "$buildSeed|n3ds-arm64-v8a|container-asset"
    ).Substring(0, 23).ToLowerInvariant()
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $apkArchive = [IO.Compression.ZipFile]::OpenRead($testApk)
    try {
        $apkEntries = @($apkArchive.Entries.FullName)
        if ("assets/$protectedAsset" -notin $apkEntries -or
                'lib/arm64-v8a/libemuorbit_n3ds_bootstrap.so' -notin $apkEntries -or
                @($apkEntries | Where-Object {
                    $_ -like '*libazahar_libretro.so' -or $_ -like '*.3dsx'
                }).Count -ne 0) {
            throw 'O APK de QA não preservou a fronteira protegida esperada.'
        }
    } finally {
        $apkArchive.Dispose()
    }

    $install = Invoke-AdbCapture -Arguments @('install', '-r', '-t', $testApk) -AllowFailure
    if ($install.ExitCode -ne 0 -or ($install.Output -join "`n") -notmatch 'Success') {
        throw 'A instalação do APK protegido de QA falhou.'
    }
    $packageInstalledByGate = $true
    Invoke-AdbCapture -Arguments @(
        'shell', 'run-as', $package, 'mkdir', '-p', $privateRoot) | Out-Null

    for ($index = 0; $index -lt $localContents.Count; $index++) {
        $remote = "$remotePrefix-$($index + 1).3dsx"
        $private = "$privateRoot/protected-content-$($index + 1).3dsx"
        [void] $remotePaths.Add($remote)
        [void] $privatePaths.Add($private)
        Invoke-AdbCapture -Arguments @('push', $localContents[$index], $remote) | Out-Null
        Invoke-AdbCapture -Arguments @(
            'shell', 'run-as', $package, 'cp', $remote, $private) | Out-Null
        Invoke-AdbCapture -Arguments @(
            'shell', 'run-as', $package, 'chmod', '400', $private) | Out-Null
        Invoke-AdbCapture -Arguments @('shell', 'rm', '-f', $remote) | Out-Null
    }

    Invoke-AdbCapture -Arguments @('shell', 'am', 'force-stop', $package) | Out-Null
    Invoke-AdbCapture -Arguments @('logcat', '-c') | Out-Null
    Invoke-AdbCapture -Arguments @('logcat', '-b', 'crash', '-c') | Out-Null
    $instrumentationArguments = @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', $testMethod)
    for ($index = 0; $index -lt $privatePaths.Count; $index++) {
        $instrumentationArguments += @(
            '-e', "n3dsContentPath$($index + 1)", $privatePaths[$index])
    }
    $instrumentationArguments += "$package/$runner"
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $instrumentation = Invoke-AdbCapture `
        -Arguments $instrumentationArguments `
        -AllowFailure
    $stopwatch.Stop()
    $logcat = Invoke-AdbCapture -Arguments @('logcat', '-d', '-v', 'brief') -AllowFailure
    $crash = Invoke-AdbCapture -Arguments @('logcat', '-b', 'crash', '-d') -AllowFailure
    $text = $instrumentation.Output -join "`n"
    $logs = $logcat.Output -join "`n"
    $crashText = $crash.Output -join "`n"
    $fatalPattern = 'Fatal signal|FATAL EXCEPTION|VK_ERROR_DEVICE_LOST|stack corruption detected'
    if ($instrumentation.ExitCode -ne 0 -or $text -notmatch 'OK \(1 test\)' -or
            $text -match 'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED' -or
            $logs -match $fatalPattern -or $crashText -match $fatalPattern) {
        throw "O gate físico protegido falhou.`n$text`n$crashText"
    }
    $evidence = ((@($instrumentation.Output) + @($logcat.Output)) | Where-Object {
        $_ -match 'N3DS_PROTECTED_FIVE_CONTENTS'
    } | Select-Object -Last 1) -join ''
    if ($evidence -notmatch 'status=PASS' -or $evidence -notmatch 'contents=5' -or
            $evidence -notmatch 'pauseResume=1' -or $evidence -notmatch 'recreation=1' -or
            $evidence -notmatch 'rotation=1' -or $evidence -notmatch 'closes=5' -or
            $evidence -notmatch 'plaintextResidual=0') {
        throw 'A evidência sanitizada do gate protegido está incompleta.'
    }
    $logicalStageBytes = [int64] (
        Get-ChildItem -LiteralPath $stageRoot -File -Recurse |
            Measure-Object -Property Length -Sum).Sum
    Write-Host (
        "N3DS_12F3 status=PASS contents=5 device=$model android=$android " +
        "elapsedMillis=$($stopwatch.ElapsedMilliseconds) fatalSignals=0 " +
        "plaintextResidual=0")
} finally {
    Invoke-AdbCapture -Arguments @(
        'shell', 'am', 'force-stop', $package) -AllowFailure | Out-Null
    foreach ($path in $privatePaths) {
        if ($path.StartsWith("$privateRoot/protected-content-", [StringComparison]::Ordinal) -and
                $path.EndsWith('.3dsx', [StringComparison]::Ordinal)) {
            Invoke-AdbCapture -Arguments @(
                'shell', 'run-as', $package, 'rm', '-f', $path) -AllowFailure | Out-Null
        }
    }
    foreach ($path in $remotePaths) {
        if ($path.StartsWith($remotePrefix, [StringComparison]::Ordinal)) {
            Invoke-AdbCapture -Arguments @(
                'shell', 'rm', '-f', $path) -AllowFailure | Out-Null
        }
    }
    if ($packageInstalledByGate -or (Test-PackageInstalled $package)) {
        Invoke-AdbCapture -Arguments @('uninstall', $package) -AllowFailure | Out-Null
    }
    Remove-ExactStage
}

if (Test-PackageInstalled $package) {
    throw 'O pacote de QA permaneceu instalado após a limpeza.'
}
if ((Test-PackageInstalled $mainPackage) -ne $mainInstalledBefore) {
    throw 'O estado de instalação do app principal mudou durante o gate.'
}
Write-Host (
    "N3DS_12F3_CLEANUP status=PASS pcStageBytes=$logicalStageBytes " +
    'devicePrivateContentsRemaining=0 qaPackageInstalled=0 mainAppStatePreserved=1')
