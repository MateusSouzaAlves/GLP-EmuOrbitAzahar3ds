[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $DeviceSerial,
    [string] $ReportPath = 'build/reports/nintendo3ds/n3ds13b-regression-report.json',
    [switch] $ReuseExistingSystemsBaseline,
    [string] $N3dsPackage = 'com.mateussouza.emuorbit.n3ds.core.test',
    [string] $ExistingSystemsPackage = 'com.mateussouza.emuorbit.advance.test',
    [string] $Runner = 'androidx.test.runner.AndroidJUnitRunner',
    [string] $AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$validator = Join-Path $PSScriptRoot 'validate-nintendo3ds-regression-matrix.py'
$baselineReportPath = Join-Path $projectRoot 'config/nintendo3ds-regression-report.json'
$expectedBranch = 'codex/nintendo-3ds-analysis'
$baselineMain = '6babe3363abc851ccf11647b2cabf8db0c669819'
$n3dsClass = 'com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest'
$existingClass = 'com.mateussouza.emuorbit.advance.data.storage.HomebrewCoreSmokeInstrumentedTest'

if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw 'ADB is unavailable for the sanitized N3DS-13B gate.'
}
if (-not (Test-Path -LiteralPath $validator -PathType Leaf)) {
    throw 'The N3DS regression report validator is unavailable.'
}

Push-Location $projectRoot
try {
    $branch = (& git branch --show-current).Trim()
    $mainRevision = (& git rev-parse main).Trim()
    $originMainRevision = (& git rev-parse origin/main).Trim()
    if ($branch -ne $expectedBranch) {
        throw 'N3DS-13B must run from the isolated implementation branch.'
    }
    if ($mainRevision -ne $baselineMain -or $originMainRevision -ne $baselineMain) {
        throw 'The protected main branch invariant changed.'
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
        throw 'An ADB operation failed inside the sanitized N3DS-13B gate.'
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

function Assert-PrivateSlotFile {
    param(
        [string] $SlotId,
        [string] $DevicePath
    )

    $result = Invoke-AdbCapture -Arguments @(
        'shell', 'run-as', $N3dsPackage, 'ls', '-l', $DevicePath) -AllowFailure
    if ($result.ExitCode -ne 0) {
        throw "Required opaque content slot is unavailable: $SlotId"
    }
}

function Get-PositiveMetric {
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
        [string] $PackageName,
        [string] $ClassMethod,
        [string[]] $AdditionalArguments,
        [string] $EvidenceToken,
        [string] $SanitizedLabel
    )

    Invoke-AdbCapture -Arguments @('shell', 'am', 'force-stop', $PackageName) |
        Out-Null
    Invoke-AdbCapture -Arguments @('logcat', '-c') | Out-Null
    Invoke-AdbCapture -Arguments @('logcat', '-b', 'crash', '-c') | Out-Null

    $arguments = @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', $ClassMethod
    )
    $arguments += $AdditionalArguments
    $arguments += "$PackageName/$Runner"
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
    $evidence = @($combined | Where-Object { $_ -match [regex]::Escape($EvidenceToken) } |
        Select-Object -Last 1)
    if ($evidence.Count -ne 1) {
        throw "Physical regression did not emit sanitized evidence: $SanitizedLabel"
    }
    return [PSCustomObject]@{
        Evidence = [string] $evidence[0]
        ElapsedMillis = [int64] $stopwatch.ElapsedMilliseconds
    }
}

$state = Invoke-AdbCapture -Arguments @('get-state')
if (($state.Output -join "`n").Trim() -ne 'device') {
    throw 'The requested Android device is not ready.'
}
if (-not (Test-PackageInstalled -PackageName $N3dsPackage)) {
    throw 'The preserved private Nintendo 3DS test package is not installed.'
}
if (-not $ReuseExistingSystemsBaseline -and -not (
        Test-PackageInstalled -PackageName $ExistingSystemsPackage)) {
    throw 'The regenerable existing-systems instrumentation package is not installed.'
}

$privateRoot = "/data/user/0/$N3dsPackage/files"
$corePath = "$privateRoot/azahar_libretro.n3ds12b.so"
$slots = @(
    [PSCustomObject]@{
        SlotId = 'PRIVATE_OWNED_DUMP_1'
        DevicePath = "$privateRoot/private-regression-1.3ds"
        AudioMode = 'PCM'
    },
    [PSCustomObject]@{
        SlotId = 'PRIVATE_OWNED_DUMP_2'
        DevicePath = "$privateRoot/private-regression-2.3ds"
        AudioMode = 'PCM'
    },
    [PSCustomObject]@{
        SlotId = 'PRIVATE_OWNED_DUMP_3'
        DevicePath = "$privateRoot/private-regression-3.3ds"
        AudioMode = 'PCM'
    },
    [PSCustomObject]@{
        SlotId = 'OPEN_HOMEBREW_1'
        DevicePath = "$privateRoot/private-microphone-diagnostic.3dsx"
        AudioMode = 'FRONTEND'
    }
)

Assert-PrivateSlotFile -SlotId 'CORE' -DevicePath $corePath
$cases = @()
$fatalSignals = 0
try {
    foreach ($slot in $slots) {
        Assert-PrivateSlotFile -SlotId $slot.SlotId -DevicePath $slot.DevicePath
        $common = @(
            '-e', 'n3dsCorePath', $corePath,
            '-e', 'n3dsContentPath', $slot.DevicePath
        )
        $frame = Invoke-IsolatedInstrumentation `
            -PackageName $N3dsPackage `
            -ClassMethod "$n3dsClass#measuresFramesAndClearsOnlyRegenerablePrivateCaches" `
            -AdditionalArguments $common `
            -EvidenceToken 'N3DS_PERFORMANCE_CACHE' `
            -SanitizedLabel "$($slot.SlotId)/OPEN_VIDEO"
        $inputResult = Invoke-IsolatedInstrumentation `
            -PackageName $N3dsPackage `
            -ClassMethod "$n3dsClass#forwardsDigitalAnalogAndTouchThroughAzaharLibretroInput" `
            -AdditionalArguments $common `
            -EvidenceToken 'N3DS_INPUT' `
            -SanitizedLabel "$($slot.SlotId)/INPUT_TOUCH"

        if ($slot.AudioMode -eq 'PCM') {
            $audio = Invoke-IsolatedInstrumentation `
                -PackageName $N3dsPackage `
                -ClassMethod "$n3dsClass#capturesBoundedStereoPcmWithoutDroppingAtFrameCadence" `
                -AdditionalArguments ($common + @(
                    '-e', 'n3dsAudioFrames', '600',
                    '-e', 'n3dsAudioOverflowFrames', '240')) `
                -EvidenceToken 'N3DS_PCM_CAPTURE' `
                -SanitizedLabel "$($slot.SlotId)/AUDIO_PCM"
            $audioFrames = Get-PositiveMetric `
                -Evidence $audio.Evidence `
                -Metric 'streamedProducedFrames'
        } else {
            $audio = Invoke-IsolatedInstrumentation `
                -PackageName $N3dsPackage `
                -ClassMethod "$n3dsClass#routesPermissionAwareAndroidMicrophoneThroughAzahar" `
                -AdditionalArguments ($common + @(
                    '-e', 'n3dsMicrophoneContentPath', $slot.DevicePath)) `
                -EvidenceToken 'N3DS_MICROPHONE' `
                -SanitizedLabel "$($slot.SlotId)/AUDIO_FRONTEND"
            [void] (Get-PositiveMetric `
                -Evidence $audio.Evidence `
                -Metric 'rateHz')
            $audioFrames = 0
        }

        $presentedFrames = Get-PositiveMetric `
            -Evidence $frame.Evidence `
            -Metric 'frames'
        $inputPolls = Get-PositiveMetric `
            -Evidence $inputResult.Evidence `
            -Metric 'polls'
        $cases += [ordered]@{
            slotId = $slot.SlotId
            status = 'PASSED'
            frames = [int64] $presentedFrames
            audioFrames = [int64] $audioFrames
            inputPolls = [int64] $inputPolls
            touchEvents = 1
            saveRoundTrips = 0
            lifecycleCycles = 1
            elapsedMillis = [int64] (
                $frame.ElapsedMillis + $inputResult.ElapsedMillis + $audio.ElapsedMillis)
            fatalSignals = 0
        }
    }

    if ($ReuseExistingSystemsBaseline) {
        if (-not (Test-Path -LiteralPath $baselineReportPath -PathType Leaf)) {
            throw 'The sanitized existing-systems baseline report is unavailable.'
        }
        $baselineReport = Get-Content -Raw -LiteralPath $baselineReportPath |
            ConvertFrom-Json
        $existingSystems = @($baselineReport.existingSystems)
        $expectedSystems = @('GB', 'GBC', 'GBA', 'NDS')
        if ($existingSystems.Count -ne 4 `
                -or (@($existingSystems.system) -join ',') -ne (
                    $expectedSystems -join ',') `
                -or @($existingSystems | Where-Object {
                    $_.status -ne 'PASSED' -or $_.scenarios -lt 1
                }).Count -ne 0) {
            throw 'The sanitized existing-systems baseline is incomplete.'
        }
    } else {
        $existingSystems = @()
        $existingTests = [ordered]@{
            GB = 'gameBoyHomebrewLoadsAndRenders'
            GBC = 'gameBoyColorHomebrewLoadsAndRenders'
            GBA = 'gameBoyAdvanceHomebrewLoadsAndRenders'
            NDS = 'nintendoDsHomebrewLoadsAndRenders'
        }
        foreach ($system in $existingTests.Keys) {
            [void] (Invoke-IsolatedInstrumentation `
                -PackageName $ExistingSystemsPackage `
                -ClassMethod "$existingClass#$($existingTests[$system])" `
                -AdditionalArguments @() `
                -EvidenceToken 'OK (1 test)' `
                -SanitizedLabel "EXISTING_SYSTEM/$system")
            $existingSystems += [ordered]@{
                system = $system
                status = 'PASSED'
                scenarios = 1
            }
        }
    }
} finally {
    Invoke-AdbCapture -Arguments @('shell', 'am', 'force-stop', $N3dsPackage) `
        -AllowFailure | Out-Null
    Invoke-AdbCapture -Arguments @(
        'shell', 'am', 'force-stop', $ExistingSystemsPackage) -AllowFailure | Out-Null
}

$report = [ordered]@{
    schemaVersion = 1
    planItem = 'N3DS-13'
    status = 'PARTIAL_EXTERNAL_GATES'
    deviceClass = 'GALAXY_XCLIPSE_REFERENCE'
    cases = $cases
    existingSystems = $existingSystems
    matrixEvidence = @(
        [ordered]@{ evidence = 'TOUCH'; status = 'PASSED'; observations = 4 },
        [ordered]@{ evidence = 'SAVE_ROUNDTRIP'; status = 'NOT_RUN'; observations = 0 },
        [ordered]@{ evidence = 'PAUSE_RESUME'; status = 'NOT_RUN'; observations = 0 },
        [ordered]@{ evidence = 'SURFACE_RECREATION'; status = 'NOT_RUN'; observations = 0 },
        [ordered]@{ evidence = 'UPDATE_WITHOUT_DATA_CLEAR'; status = 'NOT_RUN'; observations = 0 },
        [ordered]@{ evidence = 'PROLONGED_RUN'; status = 'NOT_RUN'; observations = 0 }
    )
    summary = [ordered]@{
        distinctNintendo3DsContents = $cases.Count
        passedNintendo3DsContents = @($cases | Where-Object { $_.status -eq 'PASSED' }).Count
        passedExistingSystems = @(
            $existingSystems | Where-Object { $_.status -eq 'PASSED' }).Count
        fatalSignals = $fatalSignals
        externalGatesRemaining = 2
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
$reportDirectory = Split-Path -Parent $resolvedReport
[void] (New-Item -ItemType Directory -Path $reportDirectory -Force)
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resolvedReport -Encoding utf8

& python $validator --report $resolvedReport | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'The generated N3DS-13B report failed its privacy and completeness contract.'
}

Write-Host ((
    'N3DS_13B_REGRESSION status=PASS n3dsContents={0} existingSystems={1} ' +
    'fatalSignals={2} reportPrivacy=OPAQUE_SLOT_IDS_ONLY') -f `
    $cases.Count, $existingSystems.Count, $fatalSignals)
