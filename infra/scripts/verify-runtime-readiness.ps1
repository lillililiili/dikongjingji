[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackendBaseUrl,
    [string]$ComposeFile,
    [string]$EnvFile,
    [string]$BackendContainerName,
    [string]$WeatherCityCode = '370500',
    [string]$DockerPath = 'docker',
    [string]$ProviderProbeImage = 'curlimages/curl:8.10.1',
    [int]$MaxHealthAttempts = 12,
    [int]$HealthRetryDelaySeconds = 2,
    [switch]$PlanOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($ComposeFile)) {
    $ComposeFile = Join-Path $repositoryRoot 'infra\compose.yaml'
}
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot '.env'
}

$normalizedBaseUrl = $BackendBaseUrl.TrimEnd('/')
$healthUrl = "$normalizedBaseUrl/actuator/health"
$encodedCity = [System.Uri]::EscapeDataString($WeatherCityCode)
$weatherUrl = "$normalizedBaseUrl/api/v1/platform/weather/current?cityCode=$encodedCity"
$plan = [pscustomobject]@{
    BackendHealthUrl = $healthUrl
    WeatherUrl = $weatherUrl
    Checks = @(
        'backend-health'
        'provider-tls-from-backend-network'
        'weather-api'
    )
    StatusPolicy = [pscustomobject]@{
        Ready = 'all checks passed'
        Degraded = 'backend healthy but one or more weather checks failed'
        Blocked = 'backend health failed'
    }
}

if ($PlanOnly) {
    return $plan
}

function Test-BackendHealth {
    try {
        $body = Invoke-RestMethod `
            -Uri $healthUrl `
            -TimeoutSec 5
        return $body.status -eq 'UP'
    } catch {
        return $false
    }
}

function Resolve-BackendContainerId {
    try {
        if (-not [string]::IsNullOrWhiteSpace($BackendContainerName)) {
            $containerId = & $DockerPath inspect `
                --format '{{.Id}}' `
                $BackendContainerName 2>$null
            if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($containerId)) {
                return $containerId
            }
            return $null
        }
        $containerIds = @(& $DockerPath compose `
            --env-file $EnvFile `
            -f $ComposeFile `
            ps -q backend 2>$null)
        if ($LASTEXITCODE -ne 0) {
            return $null
        }
        return $containerIds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1
    } catch {
        return $null
    }
}

function Test-ProviderTlsFromBackendNetwork {
    $containerId = Resolve-BackendContainerId
    if ([string]::IsNullOrWhiteSpace($containerId)) {
        return $false
    }
    try {
        & $DockerPath run `
            --rm `
            --network "container:$containerId" `
            $ProviderProbeImage `
            -sS `
            -o /dev/null `
            --connect-timeout 5 `
            --max-time 10 `
            https://restapi.amap.com 2>$null | Out-Null
        return $LASTEXITCODE -eq 0
    } catch {
        return $false
    }
}

function Test-WeatherApi {
    try {
        $body = Invoke-RestMethod `
            -Uri $weatherUrl `
            -TimeoutSec 12
        return -not [string]::IsNullOrWhiteSpace($body.cityCode) `
            -and -not [string]::IsNullOrWhiteSpace($body.cityName) `
            -and -not [string]::IsNullOrWhiteSpace($body.conditionCode)
    } catch {
        return $false
    }
}

$backendHealthy = $false
for ($attempt = 1; $attempt -le $MaxHealthAttempts; $attempt += 1) {
    if (Test-BackendHealth) {
        $backendHealthy = $true
        break
    }
    if ($attempt -lt $MaxHealthAttempts) {
        Start-Sleep -Seconds $HealthRetryDelaySeconds
    }
}

$providerTls = $false
$weatherApi = $false
if ($backendHealthy) {
    $providerTls = Test-ProviderTlsFromBackendNetwork
    $weatherApi = Test-WeatherApi
}

$status = if (-not $backendHealthy) {
    'BLOCKED'
} elseif ($providerTls -and $weatherApi) {
    'READY'
} else {
    'DEGRADED'
}

return [pscustomobject]@{
    Status = $status
    BackendHealth = $backendHealthy
    ProviderTls = $providerTls
    WeatherApi = $weatherApi
    BackendBaseUrl = $normalizedBaseUrl
    TestedAt = (Get-Date).ToString('o')
}
