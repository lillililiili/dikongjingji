Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$startScript = Join-Path $PSScriptRoot 'start-lan-acceptance.ps1'
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("inspection-lan-start-" + [guid]::NewGuid())

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message Expected '$Expected', got '$Actual'."
    }
}

try {
    New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
    $envFile = Join-Path $temporaryRoot '.env'
    @(
        'INSPECTION_APP_PORT=8080'
        'INSPECTION_MINIO_API_PORT=19000'
    ) | Set-Content -LiteralPath $envFile

    $plan = & $startScript -LanAddress '192.168.10.7' -EnvFile $envFile -PlanOnly
    Assert-Equal $plan.BackendBaseUrl 'http://192.168.10.7:8080' 'Backend URL must use the supplied private address.'
    Assert-Equal $plan.MinioPublicEndpoint 'http://192.168.10.7:19000' 'MinIO URL must use the supplied private address.'
    Assert-Equal $plan.BackendBindAddress '0.0.0.0' 'Backend must be reachable from the LAN.'
    Assert-Equal $plan.MinioBindAddress '0.0.0.0' 'MinIO must be reachable from the LAN.'
    Assert-Equal $plan.AppBindAddress '127.0.0.1' 'PostgreSQL and Redis must stay on loopback.'

    $publicAddressRejected = $false
    try {
        & $startScript -LanAddress '8.8.8.8' -EnvFile $envFile -PlanOnly | Out-Null
    } catch {
        $publicAddressRejected = $true
    }
    if (-not $publicAddressRejected) {
        throw 'Public IPv4 address was accepted for LAN mode.'
    }

    $source = Get-Content -LiteralPath $startScript -Raw
    if (-not $source.Contains('Get-NetRoute')) {
        throw 'Startup script does not automatically inspect the active default route.'
    }
    if ($source -match 'Set-Content[^\r\n]*\.env') {
        throw 'Startup script must not rewrite the local .env file.'
    }

    Write-Output 'LAN acceptance startup tests passed.'
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
