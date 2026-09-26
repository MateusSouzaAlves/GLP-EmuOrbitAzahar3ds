[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $CoreFile,
    [Parameter(Mandatory = $true)]
    [string] $BundletoolJar,
    [Parameter(Mandatory = $true)]
    [string] $ContentFile,
    [Parameter(Mandatory = $true)]
    [string] $DeviceSerial,
    [string] $AdbPath
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$expectedBranch = 'codex/nintendo-3ds-analysis'
$baselineMain = '6babe3363abc851ccf11647b2cabf8db0c669819'
$testPackage = 'com.mateussouza.emuorbit.advance.n3ds.deliverytest'
$testActivity = 'com.mateussouza.emuorbit.advance.nintendo3ds.delivery.Nintendo3DsDeliveryTestActivity'
$proofFile = 'files/nintendo3ds-delivery-proof.txt'
$deviceContentDirectory = '/sdcard/Download/EmuOrbitN3dsDeliveryTest'
$deviceContentPath = "$deviceContentDirectory/Mars3D.3dsx"
$expectedCoreBytes = 23157736L
$expectedCoreSha256 = '64221F5CA8E731846523669DAB3CA569F796CCEE57F5E4F78B29B4E0330A734C'
$expectedContentBytes = 713384L
$expectedContentSha256 = '00FB87D97ECB866A99902740AB67E38E05F81D74295E0C3774EB62B90B0A335B'
$buildSeed = 'n3ds-delivery-gate-v1'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'emuorbit-n3ds-delivery-' + [guid]::NewGuid().ToString('N'))
$networkSnapshot = $null
$candidateInstalledByScript = $false

function Resolve-Leaf {
    param([string] $Path, [string] $Label)
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "$Label inválido: $resolved"
    }
    return $resolved
}

function Resolve-AdbExecutable {
    if ($AdbPath) {
        return Resolve-Leaf $AdbPath 'ADB'
    }
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    return Resolve-Leaf (
        Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') 'ADB'
}

function Invoke-Adb {
    param([string[]] $Arguments)
    $output = @(& $script:adb -s $DeviceSerial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "ADB falhou: $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Invoke-Java {
    param([string[]] $Arguments)
    & $script:java @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Java falhou: $($Arguments -join ' ')"
    }
}

function Test-PackageInstalled {
    param([string] $PackageName)
    $output = Invoke-Adb @('shell', 'pm', 'list', 'packages', $PackageName)
    return $output -contains "package:$PackageName"
}

function Assert-PinnedFile {
    param(
        [string] $Path,
        [int64] $Bytes,
        [string] $Sha256,
        [string] $Label
    )
    $item = Get-Item -LiteralPath $Path
    $hash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($item.Length -ne $Bytes -or $hash -ne $Sha256) {
        throw "$Label divergente: $($item.Length) bytes/$hash"
    }
}

function Get-Utf8Sha256 {
    param([string] $Value)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        return ([BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace('-', '')
    }
    finally {
        $sha256.Dispose()
    }
}

function Invoke-GradleBundle {
    param([int] $VersionCode, [string] $Destination)
    Push-Location $projectRoot
    try {
        & .\gradlew.bat :app:bundleDebug `
            '-PEMUORBIT_N3DS_DELIVERY_TEST=true' `
            "-PEMUORBIT_N3DS_DELIVERY_TEST_VERSION_CODE=$VersionCode" `
            "-PEMUORBIT_N3DS_CORE_FILE=$script:core" `
            "-PEMUORBIT_BUILD_SEED=$buildSeed" `
            --no-daemon
        if ($LASTEXITCODE -ne 0) {
            throw "Bundle debug Nintendo 3DS $VersionCode falhou."
        }
        $generated = Join-Path $projectRoot 'app\build\outputs\bundle\debug\app-debug.aab'
        if (-not (Test-Path -LiteralPath $generated -PathType Leaf)) {
            throw "AAB debug não foi gerado: $generated"
        }
        Copy-Item -LiteralPath $generated -Destination $Destination -Force
    } finally {
        Pop-Location
    }
}

function Build-LocalApks {
    param([string] $Bundle, [string] $Destination)
    Invoke-Java @(
        '-jar', $script:bundletool, 'build-apks',
        "--bundle=$Bundle", "--output=$Destination", '--local-testing', '--overwrite')
}

function Install-Apks {
    param([string] $Archive)
    Invoke-Java @(
        '-jar', $script:bundletool, 'install-apks',
        "--apks=$Archive", "--device-id=$DeviceSerial", "--adb=$script:adb")
    $script:candidateInstalledByScript = $true
}

function Read-Proof {
    $lines = @(& $script:adb -s $DeviceSerial shell run-as $testPackage cat $proofFile 2>$null)
    if ($LASTEXITCODE -ne 0) {
        return ''
    }
    return $lines -join "`n"
}

function Start-Proof {
    param(
        [string] $Mode,
        [string] $RunId,
        [string[]] $RequiredTokens,
        [switch] $WithContent
    )
    Invoke-Adb @('shell', 'am', 'force-stop', $testPackage) | Out-Null
    $arguments = @(
        'shell', 'am', 'start', '-W',
        '-n', "$testPackage/$testActivity",
        '--es', 'n3ds.delivery.test.mode', $Mode,
        '--es', 'n3ds.delivery.test.run_id', $RunId)
    if ($WithContent) {
        $arguments += @('--es', 'n3ds.delivery.test.content_path', $deviceContentPath)
    }
    Invoke-Adb $arguments | Out-Null

    $deadline = [DateTime]::UtcNow.AddSeconds(150)
    do {
        $proof = Read-Proof
        $complete = $proof.Contains("`t$RunId`t")
        foreach ($token in $RequiredTokens) {
            $complete = $complete -and $proof.Contains($token)
        }
        if ($complete) {
            return $proof
        }
        Start-Sleep -Milliseconds 350
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Prova $Mode/$RunId expirou.`n$proof"
}

function Get-NetworkSnapshot {
    return @{
        wifi = ((Invoke-Adb @('shell', 'settings', 'get', 'global', 'wifi_on')) |
            Select-Object -Last 1).Trim()
        data = ((Invoke-Adb @('shell', 'settings', 'get', 'global', 'mobile_data')) |
            Select-Object -Last 1).Trim()
    }
}

function Set-NetworkOffline {
    Invoke-Adb @('shell', 'svc', 'wifi', 'disable') | Out-Null
    Invoke-Adb @('shell', 'svc', 'data', 'disable') | Out-Null
}

function Restore-Network {
    if ($null -eq $script:networkSnapshot) {
        return
    }
    $wifiAction = if ($script:networkSnapshot.wifi -eq '1') { 'enable' } else { 'disable' }
    $dataAction = if ($script:networkSnapshot.data -eq '1') { 'enable' } else { 'disable' }
    Invoke-Adb @('shell', 'svc', 'wifi', $wifiAction) | Out-Null
    Invoke-Adb @('shell', 'svc', 'data', $dataAction) | Out-Null
    $script:networkSnapshot = $null
}

function Assert-ProtectedPackages {
    param([hashtable] $Before)
    foreach ($name in $Before.Keys) {
        if ($Before[$name] -ne (Test-PackageInstalled $name)) {
            throw "O estado do pacote protegido mudou: $name"
        }
    }
}

$core = Resolve-Leaf $CoreFile 'Core Nintendo 3DS'
$bundletool = Resolve-Leaf $BundletoolJar 'Bundletool'
$content = Resolve-Leaf $ContentFile 'Homebrew de prova'
$adb = Resolve-AdbExecutable
$javaCommand = Get-Command java.exe -ErrorAction Stop
$java = $javaCommand.Source

$branch = (& git -C $projectRoot branch --show-current).Trim()
$main = (& git -C $projectRoot rev-parse main).Trim()
$originMain = (& git -C $projectRoot rev-parse origin/main).Trim()
if ($branch -ne $expectedBranch -or $main -ne $baselineMain -or $originMain -ne $baselineMain) {
    throw "Fronteira Git recusada: branch=$branch main=$main origin/main=$originMain"
}
Assert-PinnedFile $core $expectedCoreBytes $expectedCoreSha256 'Core Nintendo 3DS'
Assert-PinnedFile $content $expectedContentBytes $expectedContentSha256 'Homebrew Mars3D'
$protectedAssetName = 'q' + (
    Get-Utf8Sha256 "$buildSeed|n3ds-arm64-v8a|container-asset"
).Substring(0, 23).ToLowerInvariant()
$bundletoolVersion = @(& $java '-jar' $bundletool 'version' 2>&1) -join ''
if ($LASTEXITCODE -ne 0 -or -not $bundletoolVersion.Contains('1.18.3')) {
    throw "Bundletool 1.18.3 obrigatório; observado: $bundletoolVersion"
}
$deviceState = @(& $adb -s $DeviceSerial get-state 2>&1) -join ''
if ($LASTEXITCODE -ne 0 -or $deviceState.Trim() -ne 'device') {
    throw "Aparelho ADB indisponível: $DeviceSerial"
}
$abi = ((Invoke-Adb @('shell', 'getprop', 'ro.product.cpu.abi')) | Select-Object -Last 1).Trim()
if ($abi -ne 'arm64-v8a') {
    throw "A prova N3DS-12D3 exige arm64-v8a; observado: $abi"
}

$protectedBefore = @{
    'com.mateussouza.emuorbit.advance' = Test-PackageInstalled 'com.mateussouza.emuorbit.advance'
    'com.mateussouza.emuorbit.n3ds.core.test' = Test-PackageInstalled 'com.mateussouza.emuorbit.n3ds.core.test'
}
$results = [ordered]@{
    schemaVersion = 2
    status = 'RUNNING'
    delivery = 'INSTALL_TIME_REMOVABLE_SPLIT'
    deviceSerial = $DeviceSerial
    deviceModel = ((Invoke-Adb @('shell', 'getprop', 'ro.product.model')) | Select-Object -Last 1).Trim()
    android = ((Invoke-Adb @('shell', 'getprop', 'ro.build.version.release')) | Select-Object -Last 1).Trim()
    bundletool = $bundletoolVersion.Trim()
    coreSha256 = $expectedCoreSha256.ToLowerInvariant()
    contentSha256 = $expectedContentSha256.ToLowerInvariant()
    proofs = [ordered]@{}
}

New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    if (Test-PackageInstalled $testPackage) {
        Invoke-Adb @('uninstall', $testPackage) | Out-Null
    }
    Invoke-Adb @('shell', 'rm', '-rf', $deviceContentDirectory) | Out-Null

    $v1Bundle = Join-Path $temporaryRoot 'n3ds-delivery-v900100.aab'
    $v1Apks = Join-Path $temporaryRoot 'n3ds-delivery-v900100.apks'
    Invoke-GradleBundle 900100 $v1Bundle
    Build-LocalApks $v1Bundle $v1Apks
    Install-Apks $v1Apks
    Invoke-Adb @('shell', 'appops', 'set', $testPackage, 'MANAGE_EXTERNAL_STORAGE', 'allow') |
        Out-Null
    Invoke-Adb @('shell', 'mkdir', '-p', $deviceContentDirectory) | Out-Null
    & $adb -s $DeviceSerial push $content $deviceContentPath | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Não foi possível copiar a homebrew aberta para a pasta temporária do aparelho.'
    }

    $installedPaths = Invoke-Adb @('shell', 'pm', 'path', $testPackage)
    if (-not ($installedPaths -match 'split_nintendo3dscore')) {
        throw 'O feature Nintendo 3DS não foi entregue junto com a instalação.'
    }
    $results.proofs.installTimePresence = Start-Proof `
        -Mode 'status' -RunId 'install-time-presence' `
        -RequiredTokens @('STATE:INSTALLED')
    $results.proofs.nativeAtFirstLaunch = Start-Proof `
        -Mode 'install' -RunId 'first-launch' -WithContent `
        -RequiredTokens @('STATE:INSTALLED', 'PROBE:CORE:Azahar:', 'PROBE:SUCCESS:')

    Invoke-Adb @('shell', 'am', 'force-stop', $testPackage) | Out-Null
    $results.proofs.processRecreation = Start-Proof `
        -Mode 'status' -RunId 'process-recreation' -RequiredTokens @('STATE:INSTALLED')
    $beforeUpdateProof = Read-Proof
    $beforeUpdateHash = Get-Utf8Sha256 $beforeUpdateProof

    $v2Bundle = Join-Path $temporaryRoot 'n3ds-delivery-v900101.aab'
    $v2Apks = Join-Path $temporaryRoot 'n3ds-delivery-v900101.apks'
    Invoke-GradleBundle 900101 $v2Bundle
    Build-LocalApks $v2Bundle $v2Apks
    Install-Apks $v2Apks
    $afterUpdateProof = Read-Proof
    $afterUpdateHash = Get-Utf8Sha256 $afterUpdateProof
    if ($afterUpdateHash -ne $beforeUpdateHash) {
        throw 'A atualização substituiu ou limpou os dados privados do pacote de prova.'
    }
    $packageDump = Invoke-Adb @('shell', 'dumpsys', 'package', $testPackage)
    if (-not (($packageDump -join "`n") -match 'versionCode=900101')) {
        throw 'A atualização para versionCode 900101 não foi aplicada.'
    }
    $results.proofs.update = Start-Proof `
        -Mode 'install' -RunId 'update' -WithContent `
        -RequiredTokens @('PROBE:SUCCESS:')

    $networkSnapshot = Get-NetworkSnapshot
    Set-NetworkOffline
    $results.proofs.offline = Start-Proof `
        -Mode 'install' -RunId 'offline' -WithContent `
        -RequiredTokens @('STATE:INSTALLED', 'PROBE:SUCCESS:')
    Restore-Network

    $universalApks = Join-Path $temporaryRoot 'n3ds-delivery-universal.apks'
    Invoke-Java @(
        '-jar', $bundletool, 'build-apks',
        "--bundle=$v2Bundle", "--output=$universalApks", '--mode=universal', '--overwrite')
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $outer = [IO.Compression.ZipFile]::OpenRead($universalApks)
    try {
        $universalEntry = $outer.GetEntry('universal.apk')
        if ($null -eq $universalEntry) {
            throw 'APK universal ausente no arquivo APKS.'
        }
        $universalPath = Join-Path $temporaryRoot 'universal.apk'
        [IO.Compression.ZipFileExtensions]::ExtractToFile($universalEntry, $universalPath, $true)
    } finally {
        $outer.Dispose()
    }
    $universal = [IO.Compression.ZipFile]::OpenRead($universalPath)
    try {
        $universalCore = $universal.GetEntry("assets/$protectedAssetName")
        $rawUniversalCore = $universal.GetEntry('lib/arm64-v8a/libazahar_libretro.so')
        $universalBootstrap = $universal.GetEntry(
            'lib/arm64-v8a/libemuorbit_n3ds_bootstrap.so')
        if ($null -eq $universalCore -or $null -ne $rawUniversalCore -or
                $null -eq $universalBootstrap) {
            throw 'O APK universal não fundiu o payload Nintendo 3DS auditado.'
        }
        $results.universalApkBytes = (Get-Item -LiteralPath $universalPath).Length
        $results.universalApkSha256 =
            (Get-FileHash -LiteralPath $universalPath -Algorithm SHA256).Hash.ToLowerInvariant()
    } finally {
        $universal.Dispose()
    }

    $results.v1BundleBytes = (Get-Item -LiteralPath $v1Bundle).Length
    $results.v1BundleSha256 =
        (Get-FileHash -LiteralPath $v1Bundle -Algorithm SHA256).Hash.ToLowerInvariant()
    $results.v2BundleBytes = (Get-Item -LiteralPath $v2Bundle).Length
    $results.v2BundleSha256 =
        (Get-FileHash -LiteralPath $v2Bundle -Algorithm SHA256).Hash.ToLowerInvariant()
    $results.privateDataPreservedAcrossUpdate = $true
    $results.status = 'PASSED'
} finally {
    try { Restore-Network } catch { Write-Warning $_ }
    try { Invoke-Adb @('shell', 'rm', '-rf', $deviceContentDirectory) | Out-Null } catch {
        Write-Warning $_
    }
    if ($candidateInstalledByScript -or (Test-PackageInstalled $testPackage)) {
        try { Invoke-Adb @('uninstall', $testPackage) | Out-Null } catch { Write-Warning $_ }
    }
    Assert-ProtectedPackages $protectedBefore
    if (Test-Path -LiteralPath $temporaryRoot -PathType Container) {
        $resolvedTemporary = (Resolve-Path -LiteralPath $temporaryRoot).Path
        $expectedPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        $insideSystemTemp = $resolvedTemporary.StartsWith(
            $expectedPrefix,
            [StringComparison]::OrdinalIgnoreCase)
        $hasExpectedName = (Split-Path -Leaf $resolvedTemporary).StartsWith(
            'emuorbit-n3ds-delivery-',
            [StringComparison]::Ordinal)
        if (-not $insideSystemTemp -or -not $hasExpectedName) {
            throw "Diretório temporário recusado na limpeza: $resolvedTemporary"
        }
        Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
    }
}

$results | ConvertTo-Json -Depth 5
