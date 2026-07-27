Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$composeFile = Join-Path $repositoryRoot 'infra\compose.yaml'
$dockerfile = Join-Path $repositoryRoot 'backend\Dockerfile'
$applicationFile = Join-Path $repositoryRoot 'backend\bootstrap\src\main\resources\application.yml'
$applicationDevFile = Join-Path $repositoryRoot 'backend\bootstrap\src\main\resources\application-dev.yml'
$envExample = Join-Path $repositoryRoot '.env.example'

foreach ($path in @($composeFile, $dockerfile, $applicationFile, $applicationDevFile, $envExample)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required infrastructure file is missing: $path"
    }
}

$compose = Get-Content -LiteralPath $composeFile -Raw
$docker = Get-Content -LiteralPath $dockerfile -Raw
$application = Get-Content -LiteralPath $applicationFile -Raw
$applicationDev = Get-Content -LiteralPath $applicationDevFile -Raw
$env = Get-Content -LiteralPath $envExample -Raw

$requiredComposeFragments = @(
    'postgis/postgis:16-3.5',
    'redis:7.2.4-alpine',
    'minio/minio:RELEASE.2025-04-22T22-12-26Z',
    'minio/mc:RELEASE.2025-04-16T18-13-26Z',
    'internal: true',
    'condition: service_healthy',
    'condition: service_completed_successfully',
    'mc mb --ignore-existing',
    'mc anonymous set none',
    'inspection-quarantine',
    'inspection-private',
    '127.0.0.1'
)

foreach ($fragment in $requiredComposeFragments) {
    if (-not $compose.Contains($fragment)) {
        throw "Compose invariant is missing: $fragment"
    }
}

if ($compose -match '(?im)^\s*image:\s*[^#\r\n]*:latest\s*$') {
    throw 'Compose must not use a latest image tag.'
}

foreach ($fragment in @('eclipse-temurin:21-jdk-jammy', 'eclipse-temurin:21-jre-jammy', 'HEALTHCHECK')) {
    if (-not $docker.Contains($fragment)) {
        throw "Dockerfile invariant is missing: $fragment"
    }
}

if (-not $application.Contains('hmac-base64-secret: ${INSPECTION_JWT_HMAC_BASE64_SECRET}')) {
    throw 'The shared application configuration must bind INSPECTION_JWT_HMAC_BASE64_SECRET.'
}

if ($applicationDev -match '(?m)^\s*environment:\s*dev\s*$') {
    throw 'The dev profile must not supply a fallback inspection environment.'
}

if (-not $env.Contains('at least 32 random bytes')) {
    throw 'The JWT placeholder must instruct users to provide at least 32 random bytes.'
}

Write-Output 'Compose static invariants passed.'
