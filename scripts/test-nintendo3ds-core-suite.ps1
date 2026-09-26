[CmdletBinding(DefaultParameterSetName = "Suite")]
param(
    [string] $AdbPath = (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
    [string] $Serial = "",
    [string] $Package = "com.mateussouza.emuorbit.n3ds.core.test",
    [string] $Runner = "androidx.test.runner.AndroidJUnitRunner",
    [Parameter(Mandatory = $true)]
    [string] $CorePath,
    [Parameter(Mandatory = $true)]
    [string] $ContentPath,
    [Parameter(Mandatory = $true, ParameterSetName = "Suite")]
    [string] $ContentPath2,
    [Parameter(Mandatory = $true, ParameterSetName = "Suite")]
    [string] $ContentPath3,
    [Parameter(Mandatory = $true, ParameterSetName = "Suite")]
    [string] $MicrophoneContentPath,
    [Parameter(Mandatory = $true, ParameterSetName = "Interactive")]
    [switch] $Interactive,
    [Parameter(ParameterSetName = "Interactive")]
    [ValidateRange(30, 1800)]
    [int] $InteractiveSeconds = 180,
    [Parameter(ParameterSetName = "Interactive")]
    [ValidateLength(0, 16384)]
    [string] $InteractiveInputPlan = "",
    [Parameter(ParameterSetName = "Interactive")]
    [switch] $InteractiveSmokeOnly
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "adb não encontrado em $AdbPath"
}
if ($InteractiveInputPlan -and
        -not [regex]::IsMatch($InteractiveInputPlan, '\A[A-Za-z0-9_.:+,\-]+\z')) {
    throw "O plano interativo contém caracteres não permitidos para transporte ADB."
}

$deviceArguments = @()
if ($Serial) {
    $deviceArguments = @("-s", $Serial)
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments,
        [switch] $AllowFailure
    )

    $output = @(& $AdbPath @deviceArguments @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "adb falhou ($exitCode): $($Arguments -join ' ')`n$($output -join "`n")"
    }
    [PSCustomObject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Assert-PrivateDeviceFile {
    param([string] $Path)

    $result = Invoke-Adb -Arguments @(
        "shell", "run-as", $Package, "ls", "-l", $Path
    ) -AllowFailure
    if ($result.ExitCode -ne 0) {
        throw "Arquivo privado obrigatório indisponível no aparelho: $Path"
    }
}

$tests = @(
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMiiDataManagerInstrumentedTest#exposesOpenDocumentContractAndPublishesInsideAndroidPrivateStorage",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMiiImportWorkflowInstrumentedTest#serializesSafImportPreservesPriorDataAndSuppressesClosedCallbacks",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsMiiRecoveryCoordinatorInstrumentedTest#repreparesRequiredMiiReusesPausedOptionalControllerAndRetries",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsActivityResultHostInstrumentedTest#restoresDialogAfterCancelThenImportsAndRepreparesFromActivityResult",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsExperienceSettingsInstrumentedTest#persistsAppliesAndRendersAccessibleLocalizedSettings",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsProductActivityInstrumentedTest#launchesAppliesSettingsStylesDialogsAndResumesFrames",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsLaunchReadinessInstrumentedTest#acceptsMeasuredGalaxyCapabilitiesAndExplainsMiiRequirement",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsReadinessLocalizationInstrumentedTest#resolvesEveryReadinessMessageAndActionInTenLocalesIncludingRtl",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsReadinessDialogInstrumentedTest#rendersEveryActionPathAndSkipsReady",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsExperimentalHostInstrumentedTest#allowsAzaharFallbackAndClosesNativeSessionBeforeMiiImport",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsHardwareRenderHostInstrumentedTest#createsPresentsAndDestroysRealAndroidSwapchain",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsHardwareRenderHostInstrumentedTest#repeatsSurfaceLifecycleWithoutLeakingNativeOwnership",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreLifecycleInstrumentedTest#closesAndRecreatesOwnerThreadSessionAcrossAndroidLifecycle",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreLifecycleInstrumentedTest#survivesRealLauncherSwitchesAndScreenOffOn",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#restoresTransientLifecycleStateAcrossFreshController",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#loadsAzaharNegotiatesVulkanAndPresentsRealFrames",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#forwardsDigitalAnalogAndTouchThroughAzaharLibretroInput",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#routesMultipleLayoutsTouchAndSyntheticAndroidGamepadThroughAzahar",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#forwardsLifecycleBoundAndroidMotionThroughAzaharSensors",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#routesPermissionAwareAndroidMicrophoneThroughAzahar",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#recoversFromRejectedContentAndRecreatesSurfaceSessions",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#capturesBoundedStereoPcmWithoutDroppingAtFrameCadence",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#pacesAudioTrackAndReleasesItAcrossPauseResume",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#interruptsInFlightAudioWriteBeforePauseReturns",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#keepsFastForwardAudioNonBlockingAndAccountsForEveryFrame",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#checkpointsPrivateAzaharTreesOnlyAfterOwnerThreadShutdown",
    "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#preservesNativeTitleDataAcrossPrivateContentMatrix"
)
if ($Interactive) {
    $tests = @(
        "com.mateussouza.emuorbit.n3ds.core.Nintendo3DsCoreSessionInstrumentedTest#recordsInteractiveSessionSaveMutation"
    )
}

$state = Invoke-Adb -Arguments @("get-state")
if (($state.Output -join "`n").Trim() -ne "device") {
    throw "Nenhum aparelho adb pronto para a suíte 3DS."
}

$requiredPrivatePaths = @($CorePath, $ContentPath)
if (-not $Interactive) {
    $requiredPrivatePaths += @($ContentPath2, $ContentPath3, $MicrophoneContentPath)
}
$requiredPrivatePaths | ForEach-Object { Assert-PrivateDeviceFile -Path $_ }

$commonArguments = @(
    "-e", "n3dsCorePath", $CorePath,
    "-e", "n3dsContentPath", $ContentPath
)
if ($Interactive) {
    $strictInteractive = -not $InteractiveSmokeOnly.IsPresent
    $strictValue = $strictInteractive.ToString().ToLowerInvariant()
    $commonArguments += @(
        "-e", "n3dsInteractiveValidation", "true",
        "-e", "n3dsInteractiveSeconds", $InteractiveSeconds.ToString(),
        "-e", "n3dsInteractiveRequireSave", $strictValue,
        "-e", "n3dsInteractiveRequireExistingTitleRoot", $strictValue
    )
    if ($InteractiveInputPlan) {
        $commonArguments += @(
            "-e", "n3dsInteractiveInputPlan", $InteractiveInputPlan
        )
    }
} else {
    $commonArguments += @(
        "-e", "n3dsContentPath2", $ContentPath2,
        "-e", "n3dsContentPath3", $ContentPath3,
        "-e", "n3dsMicrophoneContentPath", $MicrophoneContentPath
    )
}
$component = "$Package/$Runner"
$passed = 0
$retries = 0
$suiteStopwatch = [System.Diagnostics.Stopwatch]::StartNew()

try {
    foreach ($test in $tests) {
        # AudioTrack scheduling can transiently miss its strict one-buffer performance gate.
        # Repeat that unchanged assertion once in a new PID; all correctness/crash gates fail once.
        $maximumAttempts = if ($test -like "*#keepsFastForwardAudioNonBlockingAndAccountsForEveryFrame") {
            2
        } else {
            1
        }
        for ($attempt = 1; $attempt -le $maximumAttempts; $attempt++) {
            Invoke-Adb -Arguments @("shell", "am", "force-stop", $Package) | Out-Null
            Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
            Invoke-Adb -Arguments @("logcat", "-b", "crash", "-c") | Out-Null

            Write-Host "N3DS_ISOLATED_TEST start=$test attempt=$attempt"
            $instrumentationArguments = @(
                "shell", "am", "instrument", "-w", "-r",
                "-e", "class", $test
            )
            $instrumentationArguments += $commonArguments
            $instrumentationArguments += $component
            $instrumentation = Invoke-Adb -Arguments $instrumentationArguments -AllowFailure
            $text = $instrumentation.Output -join "`n"
            $crash = Invoke-Adb -Arguments @("logcat", "-b", "crash", "-d") -AllowFailure
            $crashText = $crash.Output -join "`n"
            $fatal = Invoke-Adb -Arguments @("logcat", "-d", "-v", "brief") -AllowFailure
            $fatalText = ($fatal.Output | Where-Object {
                $_ -match "Fatal signal|FATAL EXCEPTION|VK_ERROR_DEVICE_LOST|stack corruption detected"
            }) -join "`n"

            $failedConditions = @(
                $instrumentation.ExitCode -ne 0
                $text -notmatch "OK \(1 test\)"
                $text -match "FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED"
                $crashText -match "Fatal signal|stack corruption detected"
                [bool] $fatalText
            )
            if ($failedConditions -notcontains $true) {
                $passed++
                Write-Host "N3DS_ISOLATED_TEST pass=$test attempt=$attempt"
                break
            }
            if ($attempt -lt $maximumAttempts) {
                $retries++
                Write-Warning "Variação transitória no gate $test; repetindo em PID limpo."
                continue
            }
            throw "Gate físico 3DS falhou em $test.`n$text`n$crashText`n$fatalText"
        }
    }
} finally {
    Invoke-Adb -Arguments @("shell", "am", "force-stop", $Package) -AllowFailure |
        Out-Null
}

$suiteStopwatch.Stop()
$mode = if (-not $Interactive) {
    "BASE"
} elseif ($InteractiveSmokeOnly) {
    "INTERACTIVE_SMOKE"
} else {
    "INTERACTIVE_STRICT_SAVE"
}
Write-Host "N3DS_ISOLATED_SUITE mode=$mode passed=$passed total=$($tests.Count) retries=$retries elapsedMillis=$($suiteStopwatch.ElapsedMilliseconds) processPolicy=FRESH_PER_TEST"
