[CmdletBinding()]
param(
    [string]$LanAddress,
    [string]$EnvFile,
    [switch]$PlanOnly,
    [string]$DockerPath = 'docker'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Test-PrivateIpv4 {
    param([string]$Address)

    $parsedAddress = $null
    if (-not [System.Net.IPAddress]::TryParse($Address, [ref]$parsedAddress)) {
        return $false
    }
    if ($parsedAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
        return $false
    }

    $octets = $parsedAddress.GetAddressBytes()
    return $octets[0] -eq 10 `
        -or ($octets[0] -eq 172 -and $octets[1] -ge 16 -and $octets[1] -le 31) `
        -or ($octets[0] -eq 192 -and $octets[1] -eq 168)
}

function Resolve-LanIpv4 {
    param([string]$RequestedAddress)

    if (-not [string]::IsNullOrWhiteSpace($RequestedAddress)) {
        if (-not (Test-PrivateIpv4 $RequestedAddress)) {
            throw "LAN acceptance requires an RFC1918 private IPv4 address: $RequestedAddress"
        }
        return $RequestedAddress
    }

    try {
        $routes = @(Get-NetRoute -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |
            Sort-Object RouteMetric, InterfaceIndex)
        foreach ($route in $routes) {
            $addresses = @(Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $route.InterfaceIndex -AddressState Preferred -ErrorAction SilentlyContinue)
            foreach ($candidate in $addresses) {
                if (Test-PrivateIpv4 $candidate.IPAddress) {
                    return $candidate.IPAddress
                }
            }
        }
    } catch {
        # Restricted shells may deny CIM route access; use the active gateway adapter below.
    }

    $networkInterfaces = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
        Where-Object { $_.OperationalStatus -eq [System.Net.NetworkInformation.OperationalStatus]::Up }
    foreach ($networkInterface in $networkInterfaces) {
        $properties = $networkInterface.GetIPProperties()
        $hasIpv4Gateway = @($properties.GatewayAddresses | Where-Object {
            $_.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork `
                -and $_.Address.ToString() -ne '0.0.0.0'
        }).Count -gt 0
        if (-not $hasIpv4Gateway) {
            continue
        }
        foreach ($candidate in $properties.UnicastAddresses) {
            $candidateAddress = $candidate.Address.ToString()
            if (Test-PrivateIpv4 $candidateAddress) {
                return $candidateAddress
            }
        }
    }

    throw 'No active RFC1918 IPv4 address was found. Pass -LanAddress as a manual fallback.'
}

function Get-DotEnvValue {
    param([string]$Path, [string]$Name, [string]$DefaultValue)

    $prefix = "$Name="
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line.StartsWith($prefix, [System.StringComparison]::Ordinal)) {
            $value = $line.Substring($prefix.Length).Trim()
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                return $value
            }
        }
    }
    return $DefaultValue
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$composeFile = Join-Path $repositoryRoot 'infra\compose.yaml'
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot '.env'
}
$resolvedEnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
$address = Resolve-LanIpv4 $LanAddress
$appPort = Get-DotEnvValue $resolvedEnvFile 'INSPECTION_APP_PORT' '8080'
$minioPort = Get-DotEnvValue $resolvedEnvFile 'INSPECTION_MINIO_API_PORT' '9000'

$plan = [pscustomobject]@{
    LanAddress = $address
    BackendBaseUrl = "http://${address}:$appPort"
    MinioPublicEndpoint = "http://${address}:$minioPort"
    BackendBindAddress = '0.0.0.0'
    MinioBindAddress = '0.0.0.0'
    AppBindAddress = '127.0.0.1'
    EnvFile = $resolvedEnvFile
    ComposeFile = $composeFile
}

if ($PlanOnly) {
    return $plan
}

$overrides = @{
    INSPECTION_APP_BIND_ADDRESS = $plan.AppBindAddress
    INSPECTION_BACKEND_BIND_ADDRESS = $plan.BackendBindAddress
    INSPECTION_MINIO_BIND_ADDRESS = $plan.MinioBindAddress
    INSPECTION_MINIO_PUBLIC_ENDPOINT = $plan.MinioPublicEndpoint
}
$previousValues = @{}
try {
    foreach ($name in $overrides.Keys) {
        $previousValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $overrides[$name], 'Process')
    }

    Write-Host "LAN backend: $($plan.BackendBaseUrl)"
    Write-Host "LAN MinIO:   $($plan.MinioPublicEndpoint)"
    & $DockerPath compose --env-file $resolvedEnvFile -f $composeFile up -d --build
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose failed with exit code $LASTEXITCODE."
    }
} finally {
    foreach ($name in $overrides.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousValues[$name], 'Process')
    }
}

return $plan
