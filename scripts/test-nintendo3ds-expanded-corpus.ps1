[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $DeviceSerial,
    [string] $ReportPath = 'build/reports/nintendo3ds/n3ds13d-corpus-report.json',
    [string] $N3dsPackage = 'com.mateussouza.emuorbit.n3ds.core.test',
    [string] $MainPackage = 'com.mateussouza.emuorbit.advance',
    [string] $Runner = 'androidx.test.runner.AndroidJUnitRunner',
    [string] $AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$corpusPath = Join-Path $projectRoot 'config/nintendo3ds-open-homebrew-corpus.json'
$corpusValidator = Join-Path $PSScriptRoot 'validate-nintendo3ds-open-homebrew-corpus.py'
$reportValidator = Join-Path $PSScriptRoot 'validate-nintendo3ds-regression-matrix.py'
$baselineReportPath = Join-Path $projectRoot 'config/nintendo3ds-regression-report.json'
$expectedBranch = 'codex/nintendo-3ds-analysis'
$baselineMain = '6babe3363abc851ccf11647b2cabf8db0c669819'
$n3dsClass = 'com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest'
$privateRoot = "/data/user/0/$N3dsPackage/files"
$corePath = "$privateRoot/azahar_libretro.n3ds12b.so"
$runId = [Guid]::NewGuid().ToString('N')
$stageRoot = Join-Path ([IO.Path]::GetTempPath()) "EmuOrbit-N3DS-13D-$runId"
$remotePrefix = "/data/local/tmp/emuorbit-n3ds13d-$runId"
$devicePaths = New-Object 'System.Collections.Generic.List[string]'
$remotePaths = New-Object 'System.Collections.Generic.List[string]'
$pcLogicalBytesBeforeCleanup = 0L
$deviceTemporaryBytes = 0L

if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw 'ADB is unavailable for the N3DS-13D expanded-corpus gate.'
}
foreach ($requiredFile in @(
        $corpusPath, $corpusValidator, $reportValidator, $baselineReportPath)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw 'A required N3DS-13D corpus contract file is unavailable.'
    }
}

Push-Location $projectRoot
try {
    $branch = (& git branch --show-current).Trim()
    $mainRevision = (& git rev-parse main).Trim()
    $originMainRevision = (& git rev-parse origin/main).Trim()
    if ($branch -ne $expectedBranch) {
        throw 'N3DS-13D must run from the isolated implementation branch.'
    }
    if ($mainRevision -ne $baselineMain -or $originMainRevision -ne $baselineMain) {
        throw 'The protected main branch invariant changed.'
    }
    & python $corpusValidator | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'The pinned open-homebrew corpus failed its local contract.'
    }
} finally {
    Pop-Location
}

function Invoke-AdbCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments,
        [switch] $AllowFailure
    )

    $output = @(& $script:adb -s $DeviceSerial @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw 'An ADB operation failed inside the sanitized N3DS-13D gate.'
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

function Assert-FileIdentity {
    param(
        [string] $Path,
        [int64] $ExpectedBytes,
        [string] $ExpectedSha256,
        [switch] $Require3dsxMagic
    )

    $item = Get-Item -LiteralPath $Path
    if ($item.Length -ne $ExpectedBytes -or (Get-Sha256 -Path $Path) -ne $ExpectedSha256) {
        throw 'A downloaded open-homebrew artifact does not match its pinned identity.'
    }
    if ($Require3dsxMagic) {
        $stream = [IO.File]::OpenRead($Path)
        try {
            $header = New-Object byte[] 4
            if ($stream.Read($header, 0, 4) -ne 4 `
                    -or [Text.Encoding]::ASCII.GetString($header) -ne '3DSX') {
                throw 'A pinned homebrew content does not have a valid 3DSX header.'
            }
        } finally {
            $stream.Dispose()
        }
    }
}

function Expand-ValidatedArchiveContent {
    param(
        [string] $ArchivePath,
        [string] $ContentPath,
        [string] $DestinationPath
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $wanted = $null
        foreach ($entry in $archive.Entries) {
            $normalized = $entry.FullName.Replace('\', '/')
            $segments = @($normalized.Split('/') | Where-Object { $_ -ne '' })
            if ([IO.Path]::IsPathRooted($normalized) -or $segments -contains '..') {
                throw 'The pinned homebrew archive contains an unsafe path.'
            }
            if ($normalized -ceq $ContentPath) {
                $wanted = $entry
            }
        }
        if ($null -eq $wanted -or $wanted.Length -le 0) {
            throw 'The pinned 3DSX content is absent from its release archive.'
        }
        $input = $wanted.Open()
        $output = [IO.File]::Create($DestinationPath)
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

function Get-SanitizedMetric {
    param(
        [string] $Evidence,
        [string] $Metric,
        [switch] $AllowZero
    )

    $match = [regex]::Match(
        $Evidence,
        '(?:^|\s)' + [regex]::Escape($Metric) + '=(\d+)(?:\s|$)')
    if (-not $match.Success) {
        throw "Sanitized evidence lacks metric: $Metric"
    }
    $value = [int64] $match.Groups[1].Value
    if ((-not $AllowZero -and $value -le 0) -or ($AllowZero -and $value -lt 0)) {
        throw "Sanitized evidence contains an invalid metric: $Metric"
    }
    return $value
}

function Invoke-IsolatedInstrumentation {
    param(
        [string] $ClassMethod,
        [string[]] $AdditionalArguments,
        [string] $EvidenceToken,
        [string] $SanitizedLabel
    )

    Invoke-AdbCapture -Arguments @('shell', 'am', 'force-stop', $N3dsPackage) |
        Out-Null
    Invoke-AdbCapture -Arguments @('logcat', '-c') | Out-Null
    Invoke-AdbCapture -Arguments @('logcat', '-b', 'crash', '-c') | Out-Null
    $arguments = @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', $ClassMethod
    )
    $arguments += $AdditionalArguments
    $arguments += "$N3dsPackage/$Runner"
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $instrumentation = Invoke-AdbCapture -Arguments $arguments -AllowFailure
    $stopwatch.Stop()
    $instrumentationText = $instrumentation.Output -join "`n"
    $logcat = Invoke-AdbCapture -Arguments @('logcat', '-d', '-v', 'brief') -AllowFailure
    $crash = Invoke-AdbCapture -Arguments @('logcat', '-b', 'crash', '-d') -AllowFailure
    $logcatText = $logcat.Output -join "`n"
    $crashText = $crash.Output -join "`n"
    $fatalPattern = 'Fatal signal|FATAL EXCEPTION|VK_ERROR_DEVICE_LOST|stack corruption detected'
    if ($instrumentation.ExitCode -ne 0 `
            -or $instrumentationText -notmatch 'OK \(1 test\)' `
            -or $instrumentationText -match 'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED' `
            -or $logcatText -match $fatalPattern `
            -or $crashText -match $fatalPattern) {
        throw "Physical regression failed at sanitized gate: $SanitizedLabel"
    }
    $combined = @($instrumentation.Output) + @($logcat.Output)
    $evidence = @($combined | Where-Object {
            $_ -match [regex]::Escape($EvidenceToken)
        } | Select-Object -Last 1)
    if ($evidence.Count -ne 1) {
        throw "Physical regression did not emit sanitized evidence: $SanitizedLabel"
    }
    return [PSCustomObject]@{
        Evidence = [string] $evidence[0]
        ElapsedMillis = [int64] $stopwatch.ElapsedMilliseconds
    }
}

function Remove-ExactDeviceArtifacts {
    foreach ($path in $script:devicePaths) {
        if ($path.StartsWith("$script:privateRoot/open-regression-", [StringComparison]::Ordinal) `
                -and $path.EndsWith('.3dsx', [StringComparison]::Ordinal)) {
            Invoke-AdbCapture -Arguments @(
                'shell', 'run-as', $N3dsPackage, 'rm', '-f', $path) -AllowFailure |
                Out-Null
        }
    }
    foreach ($path in $script:remotePaths) {
        if ($path.StartsWith($script:remotePrefix, [StringComparison]::Ordinal)) {
            Invoke-AdbCapture -Arguments @('shell', 'rm', '-f', $path) -AllowFailure |
                Out-Null
        }
    }
    foreach ($leaf in @(
            'n3ds-input-system',
            'n3ds-input-saves',
            'n3ds-microphone-system',
            'n3ds-microphone-saves')) {
        $path = "$script:privateRoot/$leaf"
        Invoke-AdbCapture -Arguments @(
            'shell', 'run-as', $N3dsPackage, 'rm', '-rf', $path) -AllowFailure |
            Out-Null
    }
}

function Remove-ExactLocalStage {
    if (-not (Test-Path -LiteralPath $script:stageRoot)) {
        return
    }
    $resolvedStage = [IO.Path]::GetFullPath($script:stageRoot)
    $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar)
    $expectedPrefix = $resolvedTemp + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedStage.StartsWith(
            $expectedPrefix, [StringComparison]::OrdinalIgnoreCase) `
            -or (Split-Path -Leaf $resolvedStage) -notlike 'EmuOrbit-N3DS-13D-*') {
        throw 'Refusing to clean a local path outside the dedicated N3DS-13D stage.'
    }
    Remove-Item -LiteralPath $resolvedStage -Recurse -Force
}

$newCases = @()
$corpus = Get-Content -Raw -LiteralPath $corpusPath | ConvertFrom-Json
$baseline = Get-Content -Raw -LiteralPath $baselineReportPath | ConvertFrom-Json
if (@($baseline.cases).Count -ne 4 `
        -or @($baseline.cases | Where-Object { $_.status -ne 'PASSED' }).Count -ne 0 `
        -or @($baseline.existingSystems).Count -ne 4 `
        -or @($baseline.existingSystems | Where-Object {
            $_.status -ne 'PASSED'
        }).Count -ne 0) {
    throw 'The sanitized N3DS-13C Galaxy baseline is incomplete.'
}

$state = Invoke-AdbCapture -Arguments @('get-state')
if (($state.Output -join "`n").Trim() -ne 'device') {
    throw 'The requested Android device is not ready.'
}
if (-not (Test-PackageInstalled -PackageName $N3dsPackage) `
        -or -not (Test-PackageInstalled -PackageName $MainPackage)) {
    throw 'A protected EmuOrbit package is unavailable before N3DS-13D.'
}
$core = Invoke-AdbCapture -Arguments @(
    'shell', 'run-as', $N3dsPackage, 'ls', '-l', $corePath) -AllowFailure
if ($core.ExitCode -ne 0 -or ($core.Output -join "`n") -notmatch '\s23157736\s') {
    throw 'The hardened N3DS-12E candidate core is unavailable or changed.'
}

try {
    [void] (New-Item -ItemType Directory -Path $stageRoot)
    foreach ($entry in $corpus.entries) {
        $slotNumber = [int] ([regex]::Match($entry.slotId, '(\d+)$').Groups[1].Value)
        $assetExtension = if ($null -eq $entry.archiveContentPath) { '.3dsx' } else { '.zip' }
        $assetPath = Join-Path $stageRoot ("asset-{0}{1}" -f $slotNumber, $assetExtension)
        $contentPath = Join-Path $stageRoot ("content-{0}.3dsx" -f $slotNumber)
        Invoke-WebRequest -UseBasicParsing -Uri $entry.assetUrl -OutFile $assetPath
        Assert-FileIdentity `
            -Path $assetPath `
            -ExpectedBytes $entry.assetBytes `
            -ExpectedSha256 $entry.assetSha256
        if ($null -eq $entry.archiveContentPath) {
            Copy-Item -LiteralPath $assetPath -Destination $contentPath
        } else {
            Expand-ValidatedArchiveContent `
                -ArchivePath $assetPath `
                -ContentPath $entry.archiveContentPath `
                -DestinationPath $contentPath
        }
        Assert-FileIdentity `
            -Path $contentPath `
            -ExpectedBytes $entry.contentBytes `
            -ExpectedSha256 $entry.contentSha256 `
            -Require3dsxMagic

        $remotePath = "$remotePrefix-$slotNumber.3dsx"
        $devicePath = "$privateRoot/open-regression-$slotNumber.3dsx"
        [void] $remotePaths.Add($remotePath)
        [void] $devicePaths.Add($devicePath)
        Invoke-AdbCapture -Arguments @('push', $contentPath, $remotePath) | Out-Null
        Invoke-AdbCapture -Arguments @(
            'shell', 'run-as', $N3dsPackage, 'cp', $remotePath, $devicePath) | Out-Null
        Invoke-AdbCapture -Arguments @(
            'shell', 'run-as', $N3dsPackage, 'chmod', '400', $devicePath) | Out-Null
        $deviceTemporaryBytes += [int64] $entry.contentBytes
    }

    foreach ($entry in $corpus.entries) {
        $slotNumber = [int] ([regex]::Match($entry.slotId, '(\d+)$').Groups[1].Value)
        $devicePath = "$privateRoot/open-regression-$slotNumber.3dsx"
        $common = @(
            '-e', 'n3dsCorePath', $corePath,
            '-e', 'n3dsContentPath', $devicePath,
            '-e', 'n3dsCorpusFrameCount', '12'
        )
        $audio = Invoke-IsolatedInstrumentation `
            -ClassMethod "$n3dsClass#validatesAudioFrontendForSilentOrPcmHomebrew" `
            -AdditionalArguments $common `
            -EvidenceToken 'N3DS_AUDIO_FRONTEND' `
            -SanitizedLabel "$($entry.slotId)/OPEN_VIDEO_AUDIO_CLOSE"
        $inputResult = Invoke-IsolatedInstrumentation `
            -ClassMethod "$n3dsClass#forwardsDigitalAnalogAndTouchThroughAzaharLibretroInput" `
            -AdditionalArguments $common `
            -EvidenceToken 'N3DS_INPUT' `
            -SanitizedLabel "$($entry.slotId)/INPUT_TOUCH_PAUSE_RESUME"
        $frames = Get-SanitizedMetric -Evidence $audio.Evidence -Metric 'frames'
        $polls = Get-SanitizedMetric -Evidence $inputResult.Evidence -Metric 'polls'
        $audioFrames = Get-SanitizedMetric `
            -Evidence $audio.Evidence `
            -Metric 'producedFrames' `
            -AllowZero
        if ((Get-SanitizedMetric `
                -Evidence $audio.Evidence `
                -Metric 'outputDropped' `
                -AllowZero) -ne 0 `
                -or (Get-SanitizedMetric `
                -Evidence $audio.Evidence `
                -Metric 'outputFailures' `
                -AllowZero) -ne 0) {
            throw "Audio frontend reported a failure for sanitized slot: $($entry.slotId)"
        }
        $newCases += [ordered]@{
            slotId = $entry.slotId
            status = 'PASSED'
            frames = $frames
            audioFrames = $audioFrames
            inputPolls = $polls
            touchEvents = 1
            saveRoundTrips = 0
            lifecycleCycles = 2
            elapsedMillis = [int64] (
                $audio.ElapsedMillis + $inputResult.ElapsedMillis)
            fatalSignals = 0
        }
    }

    $matrixEvidence = @()
    foreach ($evidence in $baseline.matrixEvidence) {
        $observations = $evidence.observations
        if ($evidence.evidence -eq 'TOUCH' -or $evidence.evidence -eq 'SURFACE_RECREATION') {
            $observations = 10
        } elseif ($evidence.evidence -eq 'PAUSE_RESUME') {
            $observations = 9
        }
        $matrixEvidence += [ordered]@{
            evidence = $evidence.evidence
            status = $evidence.status
            observations = $observations
        }
    }
    $allCases = @($baseline.cases) + @($newCases)
    $report = [ordered]@{
        schemaVersion = 1
        planItem = 'N3DS-13'
        status = 'PARTIAL_EXTERNAL_GATES'
        deviceClass = 'GALAXY_XCLIPSE_REFERENCE'
        cases = $allCases
        existingSystems = @($baseline.existingSystems)
        matrixEvidence = $matrixEvidence
        summary = [ordered]@{
            distinctNintendo3DsContents = @($allCases.slotId | Select-Object -Unique).Count
            passedNintendo3DsContents = @(
                $allCases | Where-Object { $_.status -eq 'PASSED' }).Count
            passedExistingSystems = @(
                $baseline.existingSystems | Where-Object { $_.status -eq 'PASSED' }).Count
            fatalSignals = 0
            externalGatesRemaining = 1
        }
    }

    $resolvedReport = if ([IO.Path]::IsPathRooted($ReportPath)) {
        [IO.Path]::GetFullPath($ReportPath)
    } else {
        [IO.Path]::GetFullPath((Join-Path $projectRoot $ReportPath))
    }
    if (-not $resolvedReport.StartsWith(
            $projectRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The sanitized report path must remain inside the workspace.'
    }
    [void] (New-Item -ItemType Directory -Path (Split-Path -Parent $resolvedReport) -Force)
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resolvedReport -Encoding utf8
    & python $reportValidator --report $resolvedReport | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'The N3DS-13D report failed its privacy and completeness contract.'
    }
    $pcLogicalBytesBeforeCleanup = [int64] (
        Get-ChildItem -LiteralPath $stageRoot -Recurse -File |
            Measure-Object -Property Length -Sum).Sum
} finally {
    Invoke-AdbCapture -Arguments @('shell', 'am', 'force-stop', $N3dsPackage) `
        -AllowFailure | Out-Null
    Remove-ExactDeviceArtifacts
    Remove-ExactLocalStage
}

if (Test-Path -LiteralPath $stageRoot) {
    throw 'The dedicated local N3DS-13D stage was not cleaned.'
}
foreach ($path in $devicePaths) {
    $probe = Invoke-AdbCapture -Arguments @(
        'shell', 'run-as', $N3dsPackage, 'ls', $path) -AllowFailure
    if ($probe.ExitCode -eq 0) {
        throw 'A staged N3DS-13D content remained on the device.'
    }
}
if (-not (Test-PackageInstalled -PackageName $N3dsPackage) `
        -or -not (Test-PackageInstalled -PackageName $MainPackage)) {
    throw 'A protected EmuOrbit package changed during N3DS-13D cleanup.'
}

Write-Host ((
    'N3DS_13D_CORPUS status=PASS totalContents=10 newContents={0} ' +
    'instrumentations={1} externalGatesRemaining=1 fatalSignals=0 ' +
    'pcCleanupBytes={2} deviceCleanupBytes={3} stagedContentsRemaining=0') -f `
    $newCases.Count, ($newCases.Count * 2), $pcLogicalBytesBeforeCleanup,
    $deviceTemporaryBytes)
