Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$verifyScript = Join-Path $PSScriptRoot 'verify-compose-static.ps1'
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("inspection-compose-static-" + [guid]::NewGuid())

function New-Fixture {
    param([string]$Name)

    $fixture = Join-Path $temporaryRoot $Name
    New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'infra') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'backend\bootstrap\src\main\resources') | Out-Null
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'infra\compose.yaml') -Destination (Join-Path $fixture 'infra\compose.yaml')
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'backend\Dockerfile') -Destination (Join-Path $fixture 'backend\Dockerfile')
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'backend\bootstrap\src\main\resources\application.yml') -Destination (Join-Path $fixture 'backend\bootstrap\src\main\resources\application.yml')
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'backend\bootstrap\src\main\resources\application-dev.yml') -Destination (Join-Path $fixture 'backend\bootstrap\src\main\resources\application-dev.yml')
    Copy-Item -LiteralPath (Join-Path $repositoryRoot '.env.example') -Destination (Join-Path $fixture '.env.example')
    return $fixture
}

function Test-LegacyGlobalFragments {
    param([string]$Fixture)

    $compose = Get-Content -LiteralPath (Join-Path $Fixture 'infra\compose.yaml') -Raw
    $docker = Get-Content -LiteralPath (Join-Path $Fixture 'backend\Dockerfile') -Raw
    $application = Get-Content -LiteralPath (Join-Path $Fixture 'backend\bootstrap\src\main\resources\application.yml') -Raw
    $applicationDev = Get-Content -LiteralPath (Join-Path $Fixture 'backend\bootstrap\src\main\resources\application-dev.yml') -Raw
    $env = Get-Content -LiteralPath (Join-Path $Fixture '.env.example') -Raw

    $composeFragments = @('postgis/postgis:16-3.5', 'redis:7.2.4-alpine', 'minio/minio:RELEASE.2025-04-22T22-12-26Z', 'minio/mc:RELEASE.2025-04-16T18-13-26Z', 'internal: true', 'condition: service_healthy', 'condition: service_completed_successfully', 'mc mb --ignore-existing', 'mc anonymous set none', 'inspection-quarantine', 'inspection-private', '127.0.0.1')
    $missingComposeFragments = @($composeFragments | Where-Object { -not $compose.Contains($_) })
    return $missingComposeFragments.Count -eq 0 `
        -and $docker.Contains('eclipse-temurin:21-jdk-jammy') `
        -and $docker.Contains('eclipse-temurin:21-jre-jammy') `
        -and $docker.Contains('HEALTHCHECK') `
        -and $application.Contains('hmac-base64-secret: ${INSPECTION_JWT_HMAC_BASE64_SECRET}') `
        -and $applicationDev -notmatch '(?m)^\s*environment:\s*dev\s*$' `
        -and $env.Contains('at least 32 random bytes')
}

function Assert-ScopedCheckerRejects {
    param(
        [string]$Name,
        [scriptblock]$Mutate
    )

    $fixture = New-Fixture $Name
    & $Mutate $fixture
    if (-not (Test-LegacyGlobalFragments $fixture)) {
        throw "The legacy global-fragment checker no longer demonstrates the intended leak for $Name."
    }

    $rejected = $false
    try {
        & $verifyScript -RepositoryRoot $fixture
    } catch {
        $rejected = $true
    }
    if (-not $rejected) {
        throw "Scoped checker accepted tampered fixture: $Name"
    }
}

try {
    New-Item -ItemType Directory -Force -Path $temporaryRoot | Out-Null
    & $verifyScript -RepositoryRoot $repositoryRoot | Out-Null
    Assert-ScopedCheckerRejects 'missing-user' {
        param($fixture)
        $path = Join-Path $fixture 'backend\Dockerfile'
        (Get-Content -LiteralPath $path -Raw) -replace '(?m)^USER spring\r?\n', '' | Set-Content -LiteralPath $path -NoNewline
    }
    Assert-ScopedCheckerRejects 'wrong-health-path' {
        param($fixture)
        $path = Join-Path $fixture 'backend\Dockerfile'
        (Get-Content -LiteralPath $path -Raw).Replace('GET /actuator/health', 'GET /healthz') | Set-Content -LiteralPath $path -NoNewline
    }
    Assert-ScopedCheckerRejects 'backend-network-removed' {
        param($fixture)
        $path = Join-Path $fixture 'infra\compose.yaml'
        (Get-Content -LiteralPath $path -Raw) -replace '(?ms)(^  backend:\s*\r?\n.*?^    networks:\s*\r?\n)      - inspection-internal', '${1}      - other-network' | Set-Content -LiteralPath $path -NoNewline
    }
    Assert-ScopedCheckerRejects 'backend-has-second-network' {
        param($fixture)
        $path = Join-Path $fixture 'infra\compose.yaml'
        (Get-Content -LiteralPath $path -Raw) -replace '(?ms)(^  backend:\s*\r?\n.*?^    networks:\s*\r?\n      - inspection-internal)', ('${1}' + [Environment]::NewLine + '      - public-edge') | Set-Content -LiteralPath $path -NoNewline
    }
    Assert-ScopedCheckerRejects 'backend-port-not-loopback' {
        param($fixture)
        $path = Join-Path $fixture 'infra\compose.yaml'
        (Get-Content -LiteralPath $path -Raw) -replace '(?ms)(  backend:.*?INSPECTION_APP_BIND_ADDRESS:-)127\.0\.0\.1', '${1}0.0.0.0' | Set-Content -LiteralPath $path -NoNewline
    }
    Assert-ScopedCheckerRejects 'backend-jwt-environment-missing' {
        param($fixture)
        $path = Join-Path $fixture 'infra\compose.yaml'
        (Get-Content -LiteralPath $path -Raw) -replace '(?m)^      INSPECTION_JWT_HMAC_BASE64_SECRET:.*\r?\n', '' | Set-Content -LiteralPath $path -NoNewline
    }
    Assert-ScopedCheckerRejects 'backend-redis-health-dependency-missing' {
        param($fixture)
        $path = Join-Path $fixture 'infra\compose.yaml'
        (Get-Content -LiteralPath $path -Raw) -replace '(?ms)(  backend:.*?      redis:\s*\r?\n        condition:\s*)service_healthy', '${1}service_started' | Set-Content -LiteralPath $path -NoNewline
    }
    Assert-ScopedCheckerRejects 'env-example-has-secrets' {
        param($fixture)
        $path = Join-Path $fixture '.env.example'
        (Get-Content -LiteralPath $path -Raw).Replace('REPLACE_WITH_LOCAL_DB_PASSWORD', 'local-password').Replace('REPLACE_WITH_BASE64_OF_AT_LEAST_32_RANDOM_BYTES', 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=') | Set-Content -LiteralPath $path -NoNewline
    }
    Write-Output 'Scoped Compose static-check regression tests passed.'
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
