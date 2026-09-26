[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $DeviceSerial,
    [string] $ReportPath = 'build/reports/nintendo3ds/n3ds13c-regression-report.json',
    [string] $Package = 'com.mateussouza.emuorbit.n3ds.core.test',
    [string] $Runner = 'androidx.test.runner.AndroidJUnitRunner',
    [string] $AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
$validator = Join-Path $PSScriptRoot 'validate-nintendo3ds-regression-matrix.py'
$baselineReportPath = Join-Path $projectRoot 'config/nintendo3ds-regression-report.json'
$sourceScopePath = Join-Path $projectRoot 'config/nintendo3ds-source-scope.json'
$expectedBranch = 'codex/nintendo-3ds-analysis'
$baselineMain = '6babe3363abc851ccf11647b2cabf8db0c669819'
$sessionClass = 'com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest'
$lifecycleClass = 'com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreLifecycleInstrumentedTest'
$updateRevision = '26e608f6fa292b27cda0ae8c84e148d17600a5e6'

if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw 'ADB is unavailable for the sanitized N3DS-13C gate.'
}
foreach ($requiredFile in @($validator, $baselineReportPath, $sourceScopePath)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw 'A required sanitized N3DS-13C input is unavailable.'
    }
}

Push-Location $projectRoot
try {
    $branch = (& git branch --show-current).Trim()
    $mainRevision = (& git rev-parse main).Trim()
    $originMainRevision = (& git rev-parse origin/main).Trim()
    if ($branch -ne $expectedBranch) {
        throw 'N3DS-13C must run from the isolated implementation branch.'
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
        throw 'An ADB operation failed inside the sanitized N3DS-13C gate.'
    }
    return [PSCustomObject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Test-PackageInstalled {
    $result = Invoke-AdbCapture -Arguments @(
        'shell', 'pm', 'list', 'packages', $Package)
    return ($result.Output -join "`n") -match [regex]::Escape("package:$Package")
}

function Assert-PrivateSlotFile {
    param(
        [string] $SlotId,
        [string] $DevicePath
    )

    $result = Invoke-AdbCapture -Arguments @(
        'shell', 'run-as', $Package, 'ls', '-l', $DevicePath) -AllowFailure
    if ($result.ExitCode -ne 0) {
        throw "Required opaque state-regression slot is unavailable: $SlotId"
    }
}

function Get-IntegerMetric {
    param(
        [string] $Evidence,
        [string] $Metric,
        [switch] $AllowZero
    )

    $match = [regex]::Match(
        $Evidence,
        '(?:^|\s)' + [regex]::Escape($Metric) + '=(\d+)(?:\s|$)')
    if (-not $match.Success) {
        throw "Sanitized state evidence lacks metric: $Metric"
    }
    $value = [int64] $match.Groups[1].Value
    if ((-not $AllowZero -and $value -le 0) -or ($AllowZero -and $value -lt 0)) {
        throw "Sanitized state evidence contains an invalid metric: $Metric"
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

    Invoke-AdbCapture -Arguments @('shell', 'am', 'force-stop', $Package) |
        Out-Null
    Invoke-AdbCapture -Arguments @('logcat', '-c') | Out-Null
    Invoke-AdbCapture -Arguments @('logcat', '-b', 'crash', '-c') | Out-Null
    $arguments = @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', $ClassMethod
    )
    $arguments += $AdditionalArguments
    $arguments += "$Package/$Runner"
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
        throw "Physical state regression failed at sanitized gate: $SanitizedLabel"
    }

    $combined = @($instrumentation.Output) + @($logcat.Output)
    $evidence = @($combined | Where-Object { $_ -match [regex]::Escape($EvidenceToken) } |
        Select-Object -Last 1)
    if ($evidence.Count -ne 1) {
        throw "Physical state regression lacks sanitized evidence: $SanitizedLabel"
    }
    return [PSCustomObject]@{
        Evidence = [string] $evidence[0]
        ElapsedMillis = [int64] $stopwatch.ElapsedMilliseconds
    }
}

function Get-ScratchDirectoryNames {
    param([string] $Root)

    $result = Invoke-AdbCapture -Arguments @(
        'shell', 'run-as', $Package, 'ls', '-1', $Root) -AllowFailure
    if ($result.ExitCode -ne 0) {
        return @()
    }
    return @($result.Output | Where-Object {
        $_ -match '^n3ds-(?:storage-matrix|core-update|long-run-balanced)-[0-9]+$'
    })
}

function Remove-NewScratchDirectories {
    param(
        [string] $Root,
        [string[]] $Before
    )

    $after = @(Get-ScratchDirectoryNames -Root $Root)
    foreach ($name in @($after | Where-Object { $_ -notin $Before })) {
        if ($name -notmatch '^n3ds-(?:storage-matrix|core-update|long-run-balanced)-[0-9]+$') {
            throw 'A device cleanup target escaped the exact N3DS-13C scratch policy.'
        }
        Invoke-AdbCapture -Arguments @(
            'shell', 'run-as', $Package, 'rm', '-rf', '--', "$Root/$name") |
            Out-Null
    }
}

function Get-ContentArguments {
    param([object[]] $SelectedSlots)

    $arguments = @()
    for ($index = 0; $index -lt $SelectedSlots.Count; $index++) {
        $key = if ($index -eq 0) {
            'n3dsContentPath'
        } else {
            "n3dsContentPath$($index + 1)"
        }
        $arguments += @('-e', $key, $SelectedSlots[$index].DevicePath)
    }
    return $arguments
}

function Assert-ProlongedRunBaseline {
    $scope = Get-Content -Raw -LiteralPath $sourceScopePath | ConvertFrom-Json
    $validation = $scope.n3ds11Decision.validation
    $accepted = $validation.n3ds11cAcceptedLongRuns
    $runs = @($accepted.CONSERVATIVE, $accepted.BALANCED, $accepted.PERFORMANCE)
    if ($validation.n3ds11cLongRunDurationMinutesPerProfile -ne 20 `
            -or $validation.n3ds11cEffectiveSpeedFloorPercent -ne 95.0 `
            -or $validation.n3ds11cLongRunTemporaryRootsRemaining -ne 0 `
            -or $validation.n3ds11cProtectedPackagePreserved -ne $true `
            -or $runs.Count -ne 3 `
            -or @($runs | Where-Object {
                $_.elapsedMillis -lt 1200000 `
                    -or $_.measuredFrames -lt 70000 `
                    -or $_.effectiveSpeedPercent -lt 95.0 `
                    -or $_.audioDroppedFrames -ne 0 `
                    -or $_.audioOutputFailures -ne 0
            }).Count -ne 0) {
        throw 'The reusable three-profile prolonged-run baseline is incomplete.'
    }

    $candidateReport = Get-Content -Raw -LiteralPath $baselineReportPath |
        ConvertFrom-Json
    if ($candidateReport.cases.Count -ne 4 `
            -or @($candidateReport.cases | Where-Object {
                $_.status -ne 'PASSED' `
                    -or $_.frames -lt 60 `
                    -or $_.inputPolls -le 0 `
                    -or $_.fatalSignals -ne 0
            }).Count -ne 0 `
            -or $candidateReport.summary.fatalSignals -ne 0) {
        throw 'The exact hardened-candidate sentinel baseline is incomplete.'
    }
}

$state = Invoke-AdbCapture -Arguments @('get-state')
if (($state.Output -join "`n").Trim() -ne 'device') {
    throw 'The requested Android device is not ready.'
}
if (-not (Test-PackageInstalled)) {
    throw 'The preserved private Nintendo 3DS test package is not installed.'
}
Assert-ProlongedRunBaseline

$privateRoot = "/data/user/0/$Package/files"
$baseCorePath = "$privateRoot/azahar_libretro.n3ds12b.so"
$updateCorePath = "$privateRoot/azahar_libretro_2126_1.so"
$slots = @(
    [PSCustomObject]@{
        SlotId = 'PRIVATE_OWNED_DUMP_1'
        DevicePath = "$privateRoot/private-regression-1.3ds"
        SaveCapable = $true
    },
    [PSCustomObject]@{
        SlotId = 'PRIVATE_OWNED_DUMP_2'
        DevicePath = "$privateRoot/private-regression-2.3ds"
        SaveCapable = $true
    },
    [PSCustomObject]@{
        SlotId = 'PRIVATE_OWNED_DUMP_3'
        DevicePath = "$privateRoot/private-regression-3.3ds"
        SaveCapable = $true
    },
    [PSCustomObject]@{
        SlotId = 'OPEN_HOMEBREW_1'
        DevicePath = "$privateRoot/private-microphone-diagnostic.3dsx"
        SaveCapable = $false
    }
)

Assert-PrivateSlotFile -SlotId 'HARDENED_BASE_CORE' -DevicePath $baseCorePath
Assert-PrivateSlotFile -SlotId 'PINNED_UPDATE_CORE' -DevicePath $updateCorePath
foreach ($slot in $slots) {
    Assert-PrivateSlotFile -SlotId $slot.SlotId -DevicePath $slot.DevicePath
}

$beforeFilesScratch = @(Get-ScratchDirectoryNames -Root 'files')
$beforeCacheScratch = @(Get-ScratchDirectoryNames -Root 'cache')
$saveSlots = @($slots | Where-Object { $_.SaveCapable })
$baseArguments = @('-e', 'n3dsCorePath', $baseCorePath)
$saveContentArguments = @(Get-ContentArguments -SelectedSlots $saveSlots)
$lifecycleResults = @()
try {
    $persistence = Invoke-IsolatedInstrumentation `
        -ClassMethod "$sessionClass#preservesNativeTitleDataAcrossPrivateContentMatrix" `
        -AdditionalArguments ($baseArguments + $saveContentArguments + @(
            '-e', 'n3dsExpectedContentCount', '3',
            '-e', 'n3dsPersistenceFrames', '120',
            '-e', 'n3dsExpectedTitleSaveCount', '2')) `
        -EvidenceToken 'N3DS_PERSISTENCE_MATRIX' `
        -SanitizedLabel 'SAVE_ROUNDTRIP'

    $update = Invoke-IsolatedInstrumentation `
        -ClassMethod "$sessionClass#preservesExactSnapshotsAcrossAzaharCoreUpdateAndRollback" `
        -AdditionalArguments ($baseArguments + @(
            '-e', 'n3dsUpdateCorePath', $updateCorePath,
            '-e', 'n3dsUpdateCoreRevision', $updateRevision,
            '-e', 'n3dsExpectedContentCount', '3',
            '-e', 'n3dsUpdateFrames', '120') + $saveContentArguments) `
        -EvidenceToken 'N3DS_CORE_UPDATE' `
        -SanitizedLabel 'UPDATE_WITHOUT_DATA_CLEAR'

    foreach ($slot in $slots) {
        $lifecycleResults += Invoke-IsolatedInstrumentation `
            -ClassMethod "$sessionClass#recoversFromRejectedContentAndRecreatesSurfaceSessions" `
            -AdditionalArguments ($baseArguments + @(
                '-e', 'n3dsContentPath', $slot.DevicePath,
                '-e', 'n3dsLifecycleCycles', '3',
                '-e', 'n3dsLifecycleFrames', '2',
                '-e', 'n3dsMaxPssGrowthKb', '524288')) `
            -EvidenceToken 'N3DS_LIFECYCLE' `
            -SanitizedLabel "$($slot.SlotId)/SURFACE_RECREATION"
    }

    $androidLifecycle = Invoke-IsolatedInstrumentation `
        -ClassMethod "$lifecycleClass#closesAndRecreatesOwnerThreadSessionAcrossAndroidLifecycle" `
        -AdditionalArguments ($baseArguments + @(
            '-e', 'n3dsContentPath', $slots[0].DevicePath,
            '-e', 'n3dsLifecycleHostFrames', '2')) `
        -EvidenceToken 'N3DS_ANDROID_LIFECYCLE' `
        -SanitizedLabel 'PAUSE_RESUME_AND_ROTATION'

} finally {
    Invoke-AdbCapture -Arguments @('shell', 'am', 'force-stop', $Package) `
        -AllowFailure | Out-Null
    Remove-NewScratchDirectories -Root 'files' -Before $beforeFilesScratch
    Remove-NewScratchDirectories -Root 'cache' -Before $beforeCacheScratch
}

$saveContents = Get-IntegerMetric -Evidence $persistence.Evidence -Metric 'contents'
$saveRestoredBytes = Get-IntegerMetric `
    -Evidence $persistence.Evidence `
    -Metric 'restoredBytes'
$updateContents = Get-IntegerMetric -Evidence $update.Evidence -Metric 'contents'
$updateRestoredBytes = Get-IntegerMetric `
    -Evidence $update.Evidence `
    -Metric 'restoredUpdateBytes'
$lifecycleGrowth = @($lifecycleResults | ForEach-Object {
    Get-IntegerMetric -Evidence $_.Evidence -Metric 'growthKb' -AllowZero
})
$report = Get-Content -Raw -LiteralPath $baselineReportPath | ConvertFrom-Json
foreach ($case in $report.cases) {
    $case.lifecycleCycles = 4
    $case.saveRoundTrips = if ($case.slotId -like 'PRIVATE_OWNED_DUMP_*') { 1 } else { 0 }
}
$matrixEvidence = @{}
foreach ($item in $report.matrixEvidence) {
    $matrixEvidence[$item.evidence] = $item
}
$matrixEvidence['SAVE_ROUNDTRIP'].status = 'PASSED'
$matrixEvidence['SAVE_ROUNDTRIP'].observations = [int] $saveContents
$matrixEvidence['PAUSE_RESUME'].status = 'PASSED'
$matrixEvidence['PAUSE_RESUME'].observations = 3
$matrixEvidence['SURFACE_RECREATION'].status = 'PASSED'
$matrixEvidence['SURFACE_RECREATION'].observations = $lifecycleResults.Count
$matrixEvidence['UPDATE_WITHOUT_DATA_CLEAR'].status = 'PASSED'
$matrixEvidence['UPDATE_WITHOUT_DATA_CLEAR'].observations = [int] $updateContents
$matrixEvidence['PROLONGED_RUN'].status = 'PASSED'
$matrixEvidence['PROLONGED_RUN'].observations = 3

$resolvedReport = if ([IO.Path]::IsPathRooted($ReportPath)) {
    [IO.Path]::GetFullPath($ReportPath)
} else {
    [IO.Path]::GetFullPath((Join-Path $projectRoot $ReportPath))
}
if (-not $resolvedReport.StartsWith(
        $projectRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The sanitized N3DS-13C report must remain inside the workspace.'
}
[void] (New-Item -ItemType Directory -Path (Split-Path -Parent $resolvedReport) -Force)
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resolvedReport -Encoding utf8
& python $validator --report $resolvedReport | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'The generated N3DS-13C report failed its privacy contract.'
}

Write-Host ((
    'N3DS_13C_STATE status=PASS saveContents={0} saveRestoredBytes={1} ' +
    'updateContents={2} updateRestoredBytes={3} lifecycleContents={4} ' +
    'lifecyclePeakGrowthKb={5} androidLifecycle=PASS longRunMinutes={6} ' +
    'longRunBaselineRuns={7} hardenedCandidateSentinel=PASS ' +
    'fatalSignals=0 scratchCleanup=PASS') -f `
    $saveContents,
    $saveRestoredBytes,
    $updateContents,
    $updateRestoredBytes,
    $lifecycleResults.Count,
    ($lifecycleGrowth | Measure-Object -Maximum).Maximum,
    20,
    3)
