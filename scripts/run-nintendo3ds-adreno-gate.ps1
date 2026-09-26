[CmdletBinding()]
param(
    [string] $BundleDirectory = $PSScriptRoot,
    [string] $AdbPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
    [string] $Serial,
    [switch] $LifecycleOnly,
    [switch] $CalibrationOnly,
    [switch] $SkipLongRun
)

$ErrorActionPreference = 'Stop'
if ($SkipLongRun -and -not $CalibrationOnly) {
    throw '-SkipLongRun is allowed only for an explicitly ineligible calibration run.'
}
if ($LifecycleOnly -and ($CalibrationOnly -or $SkipLongRun)) {
    throw '-LifecycleOnly cannot be combined with Adreno calibration or long-run options.'
}
$bundleRoot = (Resolve-Path -LiteralPath $BundleDirectory -ErrorAction Stop).Path
$contractPath = Join-Path $bundleRoot 'adreno-gate.json'
$manifestPath = Join-Path $bundleRoot 'bundle-manifest.json'
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw 'ADB is unavailable for the physical Adreno gate.'
}
if (-not (Test-Path -LiteralPath $contractPath -PathType Leaf) `
        -or -not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw 'The portable Adreno kit is incomplete.'
}
$contract = Get-Content -Raw -LiteralPath $contractPath | ConvertFrom-Json
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
foreach ($resultName in @('adreno-gate-result.json', 'lifecycle-gate-result.json')) {
    $priorResult = Join-Path $bundleRoot $resultName
    if (Test-Path -LiteralPath $priorResult -PathType Leaf) {
        [IO.File]::Delete($priorResult)
    }
}
$observedFiles = @(Get-ChildItem -LiteralPath $bundleRoot -File | ForEach-Object Name | Sort-Object)
$expectedFiles = @($contract.requiredBundleFiles | Sort-Object)
if (($observedFiles -join "`n") -ne ($expectedFiles -join "`n")) {
    throw 'The portable Adreno kit contains missing or unexpected files.'
}
foreach ($entry in $manifest.files) {
    $path = Join-Path $bundleRoot $entry.path
    $item = Get-Item -LiteralPath $path -ErrorAction Stop
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($item.Length -ne [int64] $entry.bytes -or $hash -ne [string] $entry.sha256) {
        throw 'A portable Adreno kit file failed its pinned identity.'
    }
}

$deviceArguments = if ($Serial) { @('-s', $Serial) } else { @() }
function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments,
        [switch] $AllowFailure
    )
    $output = @(& $script:AdbPath @script:deviceArguments @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        $commandText = (@($script:deviceArguments) + $Arguments) -join ' '
        $outputText = ($output -join "`n").Trim()
        throw "An ADB operation failed inside the physical Nintendo 3DS gate: adb $commandText`n$outputText"
    }
    return [PSCustomObject]@{ ExitCode = $exitCode; Output = $output }
}

function Test-PackageInstalled {
    param([string] $PackageName)
    $result = Invoke-Adb -Arguments @('shell', 'pm', 'list', 'packages', $PackageName)
    return ($result.Output -join "`n") -match [regex]::Escape("package:$PackageName")
}

function Invoke-IsolatedTest {
    param(
        [string] $ClassMethod,
        [string] $EvidenceToken,
        [string[]] $Arguments
    )
    Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $script:testPackage) | Out-Null
    Invoke-Adb -Arguments @('logcat', '-c') | Out-Null
    Invoke-Adb -Arguments @('logcat', '-b', 'crash', '-c') | Out-Null
    $command = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $ClassMethod)
    $command += $Arguments
    $command += "$script:testPackage/$script:runner"
    $instrumentation = Invoke-Adb -Arguments $command -AllowFailure
    $text = $instrumentation.Output -join "`n"
    $logcat = Invoke-Adb -Arguments @('logcat', '-d', '-v', 'brief') -AllowFailure
    $crash = Invoke-Adb -Arguments @('logcat', '-b', 'crash', '-d') -AllowFailure
    $fatalPattern = 'Fatal signal|FATAL EXCEPTION|VK_ERROR_DEVICE_LOST|stack corruption detected'
    if ($instrumentation.ExitCode -ne 0 `
            -or $text -notmatch 'OK \(1 test\)' `
            -or $text -match 'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED' `
            -or ($logcat.Output -join "`n") -match $fatalPattern `
            -or ($crash.Output -join "`n") -match $fatalPattern) {
        throw 'A physical Nintendo 3DS instrumentation failed closed.'
    }
    if ([string]::IsNullOrEmpty($EvidenceToken)) {
        return ''
    }
    $evidence = @($instrumentation.Output + $logcat.Output | Where-Object {
            $_ -match [regex]::Escape($EvidenceToken)
        } | Select-Object -Last 1)
    if ($evidence.Count -ne 1) {
        throw 'A physical Nintendo 3DS instrumentation omitted its sanitized evidence.'
    }
    return [string] $evidence[0]
}

$state = Invoke-Adb -Arguments @('get-state')
if (($state.Output -join "`n").Trim() -ne 'device') {
    throw 'The requested physical Android device is not ready.'
}
$isEmulator = ((Invoke-Adb -Arguments @('shell', 'getprop', 'ro.kernel.qemu')).Output -join '').Trim()
if ($isEmulator -eq '1') {
    throw 'An emulator cannot satisfy the physical Adreno acceptance gate.'
}
$sdkText = ((Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.version.sdk')).Output -join '').Trim()
$abiText = ((Invoke-Adb -Arguments @('shell', 'getprop', 'ro.product.cpu.abilist')).Output -join '').Trim()
if ($sdkText -notmatch '^\d+$' -or [int] $sdkText -lt [int] $contract.minimumSdk) {
    throw 'The physical Android device is below the required API floor.'
}
if ($abiText -notmatch '(^|,)arm64-v8a(,|$)') {
    throw 'The physical Android device lacks the required ARM64 ABI.'
}

$script:testPackage = [string] $contract.testPackage
$script:runner = [string] $contract.instrumentationRunner
$installedBefore = Test-PackageInstalled $testPackage
$runId = [Guid]::NewGuid().ToString('N')
$remoteCore = "/data/local/tmp/emuorbit-adreno-core-$runId.so"
$remoteContent = "/data/local/tmp/emuorbit-adreno-content-$runId.3dsx"
$privateRoot = "/data/user/0/$testPackage/files"
$privateCore = "$privateRoot/adreno-gate-core-$runId.so"
$privateContent = "$privateRoot/adreno-gate-content-$runId.3dsx"
$focalPassed = 0
$lifecyclePassed = 0
$gpu = ''
$longRunPassed = $false
$cleanupComplete = $false
$failure = $null
$started = [Diagnostics.Stopwatch]::StartNew()

try {
    $apkPath = Join-Path $bundleRoot 'emuorbit-n3ds-adreno-test.apk'
    Invoke-Adb -Arguments @('install', '-r', '-t', $apkPath) | Out-Null
    Invoke-Adb -Arguments @(
        'shell', 'run-as', $testPackage, 'mkdir', '-p', $privateRoot) | Out-Null
    Invoke-Adb -Arguments @(
        'push', (Join-Path $bundleRoot 'azahar_libretro.so'), $remoteCore) | Out-Null
    Invoke-Adb -Arguments @(
        'push', (Join-Path $bundleRoot 'open-homebrew.3dsx'), $remoteContent) | Out-Null
    Invoke-Adb -Arguments @(
        'shell', 'run-as', $testPackage, 'cp', $remoteCore, $privateCore) | Out-Null
    Invoke-Adb -Arguments @(
        'shell', 'run-as', $testPackage, 'cp', $remoteContent, $privateContent) | Out-Null
    Invoke-Adb -Arguments @(
        'shell', 'run-as', $testPackage, 'chmod', '500', $privateCore) | Out-Null
    Invoke-Adb -Arguments @(
        'shell', 'run-as', $testPackage, 'chmod', '400', $privateContent) | Out-Null

    $common = @(
        '-e', 'n3dsCorePath', $privateCore,
        '-e', 'n3dsContentPath', $privateContent,
        '-e', 'n3dsFrameCount', '60',
        '-e', 'n3dsCorpusFrameCount', '60'
    )
    if ($LifecycleOnly) {
        $lifecycle = $contract.localLifecycleGate
        $lifecycleArguments = $common + @(
            '-e', 'n3dsLifecycleHostFrames', ([string] $lifecycle.framesPerTransition),
            '-e', 'n3dsExternalLifecycleCycles', ([string] $lifecycle.externalLifecycleCycles)
        )
        for ($index = 0; $index -lt @($lifecycle.classMethods).Count; $index++) {
            [void] (Invoke-IsolatedTest `
                -ClassMethod $lifecycle.classMethods[$index] `
                -EvidenceToken $lifecycle.evidenceTokens[$index] `
                -Arguments $lifecycleArguments)
            $lifecyclePassed++
        }
    } else {
        foreach ($test in $contract.focalTests) {
            $evidence = Invoke-IsolatedTest `
                -ClassMethod $test.classMethod `
                -EvidenceToken $test.evidenceToken `
                -Arguments $common
            $focalPassed++
            if ($test.id -eq 'VULKAN_SWAPCHAIN') {
                $match = [regex]::Match($evidence, 'device=(.+?)\s+api=')
                if (-not $match.Success) {
                    throw 'The Vulkan probe did not expose a physical-device name.'
                }
                $gpu = $match.Groups[1].Value.Trim()
                $isAdreno = $gpu -match [string] $contract.acceptanceGpuRegex
                if (-not $CalibrationOnly -and -not $isAdreno) {
                    throw 'The connected physical device is not an Adreno acceptance target.'
                }
            }
        }

        if (-not $SkipLongRun) {
            $longRun = $contract.longRun
            $longArguments = $common + @(
                '-e', 'n3dsLongRunEnabled', 'true',
                '-e', 'n3dsLongRunMinutes', ([string] $longRun.minutes),
                '-e', 'n3dsLongRunMaxPssGrowthKb', ([string] $longRun.maximumPssGrowthKilobytes),
                '-e', 'n3dsPerformanceProfile', ([string] $longRun.profile)
            )
            [void] (Invoke-IsolatedTest `
                -ClassMethod $longRun.classMethod `
                -EvidenceToken $longRun.evidenceToken `
                -Arguments $longArguments)
            $longRunPassed = $true
        }
    }
} catch {
    $failure = $_
} finally {
    $started.Stop()
    Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $testPackage) -AllowFailure |
        Out-Null
    foreach ($path in @($privateCore, $privateContent)) {
        Invoke-Adb -Arguments @(
            'shell', 'run-as', $testPackage, 'rm', '-f', $path) -AllowFailure |
            Out-Null
    }
    foreach ($leaf in @(
            'n3ds-system', 'n3ds-saves',
            'n3ds-profile-system', 'n3ds-profile-saves',
            'n3ds-input-system', 'n3ds-input-saves')) {
        Invoke-Adb -Arguments @(
            'shell', 'run-as', $testPackage, 'rm', '-rf', "$privateRoot/$leaf") `
            -AllowFailure | Out-Null
    }
    foreach ($path in @($remoteCore, $remoteContent)) {
        Invoke-Adb -Arguments @('shell', 'rm', '-f', $path) -AllowFailure | Out-Null
    }
    if (-not $installedBefore -and (Test-PackageInstalled $testPackage)) {
        Invoke-Adb -Arguments @('uninstall', $testPackage) -AllowFailure | Out-Null
    }
    $packagePresentAfterCleanup = Test-PackageInstalled $testPackage
    $remoteRemaining = @(@($remoteCore, $remoteContent) | Where-Object {
            (Invoke-Adb -Arguments @('shell', 'ls', $_) -AllowFailure).ExitCode -eq 0
        }).Count
    $privateRemaining = if ($packagePresentAfterCleanup) {
        @(@($privateCore, $privateContent) | Where-Object {
                (Invoke-Adb -Arguments @(
                        'shell', 'run-as', $testPackage, 'ls', $_) -AllowFailure).ExitCode -eq 0
            }).Count
    } else {
        0
    }
    $packagePolicyPreserved = if ($installedBefore) {
        $packagePresentAfterCleanup
    } else {
        -not $packagePresentAfterCleanup
    }
    $cleanupComplete = $remoteRemaining -eq 0 `
        -and $privateRemaining -eq 0 `
        -and $packagePolicyPreserved
    if (-not $cleanupComplete -and $null -eq $failure) {
        $failure = [InvalidOperationException]::new(
            'The physical Adreno gate could not prove exact stage cleanup.')
    }
}

$isAdrenoResult = $gpu -match [string] $contract.acceptanceGpuRegex
$lifecycleTotal = @($contract.localLifecycleGate.classMethods).Count
$lifecycleEligible = $null -eq $failure `
    -and $LifecycleOnly `
    -and $lifecyclePassed -eq $lifecycleTotal
$acceptanceEligible = $null -eq $failure `
    -and -not $LifecycleOnly `
    -and -not $CalibrationOnly `
    -and $isAdrenoResult `
    -and $longRunPassed
$status = if ($null -ne $failure) {
    'FAILED'
} elseif ($lifecycleEligible) {
    'PASSED_LIFECYCLE'
} elseif ($LifecycleOnly) {
    'FAILED'
} elseif ($CalibrationOnly) {
    'CALIBRATION_ONLY_INELIGIBLE'
} elseif ($acceptanceEligible) {
    'PASSED_ADRENO'
} else {
    'FAILED'
}
$result = [ordered]@{
    schemaVersion = 1
    planItems = @('N3DS-06C', 'N3DS-11D', 'N3DS-13D')
    status = $status
    acceptanceEligible = $acceptanceEligible
    lifecycleEligible = $lifecycleEligible
    physicalDevice = $true
    gpu = $gpu
    sdk = [int] $sdkText
    abi = 'arm64-v8a'
    focalTestsPassed = $focalPassed
    focalTestsTotal = @($contract.focalTests).Count
    lifecycleTestsPassed = $lifecyclePassed
    lifecycleTestsTotal = $lifecycleTotal
    longRunRequired = [bool] $contract.longRun.requiredForAcceptance
    longRunPassed = $longRunPassed
    elapsedMillis = [int64] $started.ElapsedMilliseconds
    fatalSignals = 0
    paidServiceUsed = $false
    commercialContentUsed = $false
    exactStageCleanupComplete = $cleanupComplete
    privateTestPackagePreservedWhenPreexisting = $installedBefore -and $packagePresentAfterCleanup
    privateTestPackageRemovedWhenNew = -not $installedBefore -and -not $packagePresentAfterCleanup
}
$resultName = if ($LifecycleOnly) {
    'lifecycle-gate-result.json'
} else {
    'adreno-gate-result.json'
}
$resultPath = Join-Path $bundleRoot $resultName
[IO.File]::WriteAllText(
    $resultPath,
    ($result | ConvertTo-Json -Depth 5),
    [Text.UTF8Encoding]::new($false))
if ($LifecycleOnly) {
    Write-Host ((
        'N3DS_LIFECYCLE_GATE status={0} eligible={1} tests={2}/{3} ' +
        'fatalSignals=0 cleanup={4} paidServiceUsed=false') -f `
        $status, $lifecycleEligible.ToString().ToLowerInvariant(),
        $lifecyclePassed, $lifecycleTotal,
        $cleanupComplete.ToString().ToLowerInvariant())
} else {
    Write-Host ((
        'N3DS_ADRENO_GATE status={0} eligible={1} gpu="{2}" focal={3}/{4} ' +
        'longRun={5} fatalSignals=0 cleanup={6} paidServiceUsed=false') -f `
        $status, $acceptanceEligible.ToString().ToLowerInvariant(), $gpu,
        $focalPassed, @($contract.focalTests).Count,
        $longRunPassed.ToString().ToLowerInvariant(),
        $cleanupComplete.ToString().ToLowerInvariant())
}
if ($null -ne $failure) {
    throw $failure
}
if ($LifecycleOnly -and -not $lifecycleEligible) {
    throw 'The physical Nintendo 3DS lifecycle contract was not satisfied.'
}
if (-not $LifecycleOnly -and -not $CalibrationOnly -and -not $acceptanceEligible) {
    throw 'The physical Adreno acceptance contract was not satisfied.'
}
