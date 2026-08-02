Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$verifyScript = Join-Path $PSScriptRoot 'verify-runtime-readiness.ps1'
$lanScript = Join-Path $PSScriptRoot 'start-lan-acceptance.ps1'

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message Expected '$Expected', got '$Actual'."
    }
}

$plan = & $verifyScript `
    -BackendBaseUrl 'http://192.168.10.7:18081' `
    -ComposeFile 'C:\repo\infra\compose.yaml' `
    -EnvFile 'C:\repo\.env' `
    -PlanOnly

Assert-Equal $plan.BackendHealthUrl 'http://192.168.10.7:18081/actuator/health' `
    'The health check must use the LAN-visible backend URL.'
Assert-True ($plan.WeatherUrl -match 'cityCode=370500') `
    'The readiness plan must include a real weather API request.'
Assert-True ($plan.Checks -contains 'backend-health') `
    'The readiness plan must check backend health.'
Assert-True ($plan.Checks -contains 'provider-tls-from-backend-network') `
    'The readiness plan must check provider TLS from the backend network namespace.'
Assert-True ($plan.Checks -contains 'weather-api') `
    'The readiness plan must check the weather API response.'
Assert-Equal $plan.StatusPolicy.Ready 'all checks passed' `
    'READY must require all checks.'
Assert-Equal $plan.StatusPolicy.Degraded 'backend healthy but one or more weather checks failed' `
    'DEGRADED must distinguish a weather-only failure.'
Assert-Equal $plan.StatusPolicy.Blocked 'backend health failed' `
    'BLOCKED must represent a core backend failure.'

$verifySource = Get-Content -LiteralPath $verifyScript -Raw
$lanSource = Get-Content -LiteralPath $lanScript -Raw
Assert-True (-not $verifySource.Contains('PLATFORM_WEATHER_API_KEY')) `
    'The readiness check must not read or print the weather API key.'
Assert-True ($verifySource.Contains('Invoke-RestMethod')) `
    'The readiness check must parse JSON consistently on Windows PowerShell.'
Assert-True ($lanSource.Contains('verify-runtime-readiness.ps1')) `
    'LAN startup must automatically invoke the readiness check.'
Assert-True ($lanSource.Contains('DEGRADED')) `
    'LAN startup must visibly report weather degradation.'

Write-Output 'Runtime readiness tests passed.'
