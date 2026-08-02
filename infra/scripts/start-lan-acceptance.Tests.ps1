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

function Assert-ContainsLine {
    param([string[]]$Lines, [string]$Expected, [string]$Message)
    if ($Lines -notcontains $Expected) {
        throw "$Message Missing '$Expected'."
    }
}

try {
    New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
    $envFile = Join-Path $temporaryRoot '.env'
    @(
        'INSPECTION_APP_PORT=8080'
        'INSPECTION_MINIO_API_PORT=19000'
    ) | Set-Content -LiteralPath $envFile
    $mobileEnvFile = Join-Path $temporaryRoot '.env.development.local'
    $expectedCityName = -join ([char]0x4E1C, [char]0x8425, [char]0x5E02)
    [IO.File]::WriteAllLines($mobileEnvFile, @(
        'VITE_INSPECTION_API_BASE_URL=http://127.0.0.1:18081'
        'VITE_INSPECTION_ALLOW_INSECURE_LOCAL_API=true'
        "VITE_INSPECTION_DEFAULT_CITY_NAME=$expectedCityName"
        'VITE_PLATFORM_PREVIEW_ENABLED=true'
        'CUSTOM_SETTING=preserved'
    ), [Text.UTF8Encoding]::new($false))

    $plan = . $startScript -LanAddress '192.168.10.7' -EnvFile $envFile -MobileEnvFile $mobileEnvFile -PlanOnly
    Assert-Equal $plan.BackendBaseUrl 'http://192.168.10.7:8080' 'Backend URL must use the supplied private address.'
    Assert-Equal $plan.MobileApiBaseUrl 'http://192.168.10.7:8080' 'Mobile API URL must use the current LAN address.'
    Assert-Equal $plan.MobileEnvFile $mobileEnvFile 'The requested mobile environment file must be preserved in the plan.'
    Assert-Equal $plan.MinioPublicEndpoint 'http://192.168.10.7:19000' 'MinIO URL must use the supplied private address.'
    Assert-Equal $plan.BackendBindAddress '0.0.0.0' 'Backend must be reachable from the LAN.'
    Assert-Equal $plan.MinioBindAddress '0.0.0.0' 'MinIO must be reachable from the LAN.'
    Assert-Equal $plan.AppBindAddress '127.0.0.1' 'PostgreSQL and Redis must stay on loopback.'

    Sync-MobileDevelopmentEnvironment -Path $mobileEnvFile -ApiBaseUrl $plan.MobileApiBaseUrl
    $mobileEnvText = [IO.File]::ReadAllText(
        $mobileEnvFile,
        [Text.UTF8Encoding]::new($false, $true)
    )
    $mobileEnvLines = @($mobileEnvText -split "`r?`n")
    Assert-ContainsLine $mobileEnvLines 'VITE_INSPECTION_API_BASE_URL=http://192.168.10.7:8080' 'Mobile API URL was not synchronized.'
    Assert-ContainsLine $mobileEnvLines 'VITE_INSPECTION_ALLOW_INSECURE_LAN_API=true' 'LAN HTTP allowance was not enabled.'
    Assert-ContainsLine $mobileEnvLines 'VITE_INSPECTION_ENABLE_LAN_DISCOVERY=true' 'LAN discovery was not enabled.'
    Assert-ContainsLine $mobileEnvLines "VITE_INSPECTION_DEFAULT_CITY_NAME=$expectedCityName" 'UTF-8 mobile environment values must remain unchanged.'
    Assert-ContainsLine $mobileEnvLines 'VITE_PLATFORM_PREVIEW_ENABLED=true' 'Separate mobile environment variables must remain on separate lines.'
    Assert-ContainsLine $mobileEnvLines 'CUSTOM_SETTING=preserved' 'Unrelated mobile environment values must be preserved.'

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
    if (-not $source.Contains('verify-runtime-readiness.ps1')) {
        throw 'Startup script must run the runtime readiness check after Docker startup.'
    }

    Write-Output 'LAN acceptance startup tests passed.'
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
