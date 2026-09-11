[CmdletBinding()]
param(
    [string] $Destination = (Join-Path $env:TEMP 'emuorbit-nintendo3ds-azahar-2126.0'),
    [string] $Git = 'git'
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

$expectedRepository = [string] $upstream.repository
$expectedCommit = [string] $upstream.commit
$expectedSubmoduleCount = [int] $upstream.recursiveSubmoduleCount
if ($expectedRepository -notmatch '^https://github\.com/azahar-emu/azahar\.git$' -or
        $expectedCommit -notmatch '^[0-9a-f]{40}$' -or
        $expectedSubmoduleCount -le 0) {
    throw 'O pin de aquisição do Azahar no manifesto é inválido.'
}

$destinationPath = [System.IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $destinationPath) {
    if (-not (Test-Path -LiteralPath (Join-Path $destinationPath '.git'))) {
        throw "O destino já existe e não é um checkout Git: $destinationPath"
    }
    $actualRepository = (& $Git -C $destinationPath remote get-url origin).Trim()
    $actualCommit = (& $Git -C $destinationPath rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $actualRepository -ne $expectedRepository -or
            $actualCommit -ne $expectedCommit) {
        throw 'O checkout existente diverge da URL ou do commit fixado; nada foi sobrescrito.'
    }
    $dirty = @(& $Git -C $destinationPath status --porcelain --untracked-files=no)
    if ($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0) {
        throw 'O checkout existente contém alterações; nada foi sobrescrito.'
    }
} else {
    $destinationParent = Split-Path -Parent $destinationPath
    New-Item -ItemType Directory -Force -Path $destinationParent | Out-Null
    & $Git clone --filter=blob:none --no-checkout -- $expectedRepository $destinationPath
    if ($LASTEXITCODE -ne 0) {
        throw 'Falha ao adquirir a fonte oficial do Azahar.'
    }
    & $Git -C $destinationPath -c advice.detachedHead=false checkout --detach $expectedCommit
    if ($LASTEXITCODE -ne 0) {
        throw 'Falha ao posicionar o checkout no commit fixado do Azahar.'
    }
}

& $Git -C $destinationPath submodule sync --recursive
if ($LASTEXITCODE -ne 0) {
    throw 'Falha ao sincronizar as URLs dos submódulos do Azahar.'
}
& $Git -C $destinationPath submodule update --init --recursive --recommend-shallow
if ($LASTEXITCODE -ne 0) {
    throw 'Falha ao adquirir os submódulos fixados do Azahar.'
}

$submoduleStatus = @(& $Git -C $destinationPath submodule status --recursive)
if ($LASTEXITCODE -ne 0 -or $submoduleStatus.Count -ne $expectedSubmoduleCount -or
        @($submoduleStatus | Where-Object { $_ -notmatch '^ [0-9a-f]{40} ' }).Count -ne 0) {
    throw "A árvore adquirida não contém os $expectedSubmoduleCount submódulos fixados."
}

$python = (Get-Command python -ErrorAction Stop).Source
& $python (Join-Path $PSScriptRoot 'audit-nintendo3ds-upstream.py') `
    --source-root $destinationPath `
    --manifest $manifestPath
if ($LASTEXITCODE -ne 0) {
    throw 'A auditoria da fonte adquirida do Nintendo 3DS falhou.'
}

$normalizedStatus = @($submoduleStatus | ForEach-Object {
        if ($_ -match '^ ([0-9a-f]{40}) ([^ ]+)') {
            "$($Matches[2]) $($Matches[1])`n"
        }
    } | Sort-Object) -join ''
$digestBytes = [System.Text.Encoding]::UTF8.GetBytes($normalizedStatus)
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $digest = -join @($sha256.ComputeHash($digestBytes) | ForEach-Object {
        $_.ToString('x2')
    })
} finally {
    $sha256.Dispose()
}
if ($digest -ne [string] $manifest.upstreamAudit.recursiveSubmoduleStatusSha256) {
    throw 'O digest da árvore de submódulos diverge do manifesto.'
}

Write-Host "N3DS_SOURCE=$destinationPath"
Write-Host "N3DS_SOURCE_COMMIT=$expectedCommit"
Write-Host "N3DS_SUBMODULE_COUNT=$expectedSubmoduleCount"
Write-Host "N3DS_SUBMODULE_SHA256=$digest"
