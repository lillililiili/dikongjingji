param(
    [string]$RepositoryRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($RepositoryRoot) {
    $repositoryRoot = Resolve-Path $RepositoryRoot
} else {
    $repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
}
$composeFile = Join-Path $repositoryRoot 'infra\compose.yaml'
$dockerfile = Join-Path $repositoryRoot 'backend\Dockerfile'
$applicationFile = Join-Path $repositoryRoot 'backend\bootstrap\src\main\resources\application.yml'
$applicationDevFile = Join-Path $repositoryRoot 'backend\bootstrap\src\main\resources\application-dev.yml'
$envExample = Join-Path $repositoryRoot '.env.example'

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Get-ContentWithoutComments {
    param([string]$Path)

    Assert-True (Test-Path -LiteralPath $Path) "Required infrastructure file is missing: $Path"
    $lines = foreach ($line in (Get-Content -LiteralPath $Path)) {
        $withoutComment = $line -replace '\s+#.*$', ''
        if (-not [string]::IsNullOrWhiteSpace($withoutComment)) {
            $withoutComment
        }
    }
    return $lines -join "`n"
}

function Get-ComposeServiceBlock {
    param(
        [string]$Compose,
        [string]$Service
    )

    $escapedService = [regex]::Escape($Service)
    $match = [regex]::Match($Compose, "(?ms)^  ${escapedService}:[ \t]*\r?\n(?<block>.*?)(?=^  [A-Za-z0-9_-]+:[ \t]*$|^networks:[ \t]*$|^volumes:[ \t]*$|\z)")
    Assert-True $match.Success "Compose service is missing: $Service"
    return $match.Groups['block'].Value
}

function Get-ServiceSection {
    param(
        [string]$ServiceBlock,
        [string]$Key
    )

    $escapedKey = [regex]::Escape($Key)
    $match = [regex]::Match($ServiceBlock, "(?ms)^    ${escapedKey}:[ \t]*\r?\n(?<section>.*?)(?=^    [A-Za-z0-9_-]+:[ \t]*$|\z)")
    Assert-True $match.Success "Compose service section is missing: $Key"
    return $match.Groups['section'].Value
}

function Get-SectionListItems {
    param([string]$Section)

    return @([regex]::Matches($Section, '(?m)^      - (?<value>[^\r\n]+)$') | ForEach-Object { $_.Groups['value'].Value.Trim() })
}

function Get-EnvironmentValue {
    param(
        [string]$EnvironmentSection,
        [string]$Key
    )

    $escapedKey = [regex]::Escape($Key)
    $match = [regex]::Match($EnvironmentSection, "(?m)^      ${escapedKey}:[ \t]*(?<value>[^\r\n]+)$")
    Assert-True $match.Success "Compose environment variable is missing: $Key"
    return $match.Groups['value'].Value.Trim()
}

function Assert-ServiceNetwork {
    param(
        [string]$Compose,
        [string]$Service
    )

    $networks = @(Get-SectionListItems (Get-ServiceSection (Get-ComposeServiceBlock $Compose $Service) 'networks'))
    Assert-True ($networks.Count -eq 1 -and $networks[0] -eq 'inspection-internal') "Compose service must use only inspection-internal: $Service"
}

function Assert-LoopbackPorts {
    param(
        [string]$Compose,
        [string]$Service
    )

    $ports = @(Get-SectionListItems (Get-ServiceSection (Get-ComposeServiceBlock $Compose $Service) 'ports'))
    Assert-True ($ports.Count -gt 0) "Compose service has no development port binding: $Service"
    foreach ($port in $ports) {
        Assert-True $port.Contains('${INSPECTION_APP_BIND_ADDRESS:-127.0.0.1}:') "Compose service port must default to loopback: $Service"
    }
}

function Assert-DependencyCondition {
    param(
        [string]$ServiceBlock,
        [string]$Dependency,
        [string]$Condition
    )

    $dependencies = Get-ServiceSection $ServiceBlock 'depends_on'
    $escapedDependency = [regex]::Escape($Dependency)
    $escapedCondition = [regex]::Escape($Condition)
    Assert-True ([regex]::IsMatch($dependencies, "(?ms)^      ${escapedDependency}:[ \t]*\r?\n^        condition:[ \t]*${escapedCondition}[ \t]*$")) "Compose dependency condition is missing: $Dependency -> $Condition"
}

function Get-EnvExampleValue {
    param(
        [string]$EnvExample,
        [string]$Key
    )

    $escapedKey = [regex]::Escape($Key)
    $match = [regex]::Match($EnvExample, "(?m)^${escapedKey}=(?<value>[^\r\n]*)$")
    Assert-True $match.Success "Environment example variable is missing: $Key"
    return $match.Groups['value'].Value
}

$compose = Get-ContentWithoutComments $composeFile
$docker = Get-ContentWithoutComments $dockerfile
$application = Get-ContentWithoutComments $applicationFile
$applicationDev = Get-ContentWithoutComments $applicationDevFile
$env = Get-Content -LiteralPath $envExample -Raw
$envWithoutComments = Get-ContentWithoutComments $envExample

$expectedImages = @{
    postgres = 'postgis/postgis:16-3.5'
    redis = 'redis:7.2.4-alpine'
    minio = 'minio/minio:RELEASE.2025-04-22T22-12-26Z'
    'minio-init' = 'minio/mc:RELEASE.2025-04-16T18-13-26Z'
}
foreach ($service in $expectedImages.Keys) {
    $serviceBlock = Get-ComposeServiceBlock $compose $service
    Assert-True ([regex]::IsMatch($serviceBlock, "(?m)^    image:[ \t]*$([regex]::Escape($expectedImages[$service]))[ \t]*$")) "Compose image is incorrect for service: $service"
    Assert-ServiceNetwork $compose $service
}
Assert-ServiceNetwork $compose 'backend'

foreach ($service in @('postgres', 'redis', 'minio', 'backend')) {
    Assert-LoopbackPorts $compose $service
}

$networkBlock = [regex]::Match($compose, '(?ms)^networks:[ \t]*\n  inspection-internal:[ \t]*\n    driver:[ \t]*bridge[ \t]*$')
Assert-True $networkBlock.Success 'Compose network inspection-internal must use an isolated bridge that permits loopback port publishing.'

$backend = Get-ComposeServiceBlock $compose 'backend'
Assert-True ([regex]::IsMatch($backend, '(?ms)^    build:[ \t]*\n      context:[ \t]*\.\.[ \t]*\n      dockerfile:[ \t]*backend/Dockerfile[ \t]*$')) 'Backend build context or Dockerfile is incorrect.'
$backendEnvironment = Get-ServiceSection $backend 'environment'
foreach ($key in @('SPRING_PROFILES_ACTIVE', 'INSPECTION_ENVIRONMENT', 'INSPECTION_DB_URL', 'INSPECTION_DB_USERNAME', 'INSPECTION_DB_PASSWORD', 'INSPECTION_REDIS_HOST', 'INSPECTION_REDIS_PORT', 'INSPECTION_REDIS_PASSWORD', 'INSPECTION_MINIO_ENABLED', 'INSPECTION_MINIO_ENDPOINT', 'INSPECTION_MINIO_PUBLIC_ENDPOINT', 'INSPECTION_MINIO_ACCESS_KEY', 'INSPECTION_MINIO_SECRET_KEY', 'INSPECTION_JWT_HMAC_BASE64_SECRET', 'INSPECTION_DATA_ENCRYPTION_BASE64_KEY', 'INSPECTION_RESERVATION_ALLOWED_MIME_TYPES', 'INSPECTION_CORS_ALLOWED_ORIGINS')) {
    $null = Get-EnvironmentValue $backendEnvironment $key
}
Assert-True ((Get-EnvironmentValue $backendEnvironment 'SPRING_PROFILES_ACTIVE') -eq 'dev') 'Backend must run the dev profile locally.'
Assert-True ((Get-EnvironmentValue $backendEnvironment 'INSPECTION_REDIS_HOST') -eq 'redis') 'Backend Redis host must be the redis service.'
Assert-True ((Get-EnvironmentValue $backendEnvironment 'INSPECTION_MINIO_ENDPOINT') -eq 'http://minio:9000') 'Backend MinIO endpoint must be the minio service.'
Assert-True ((Get-EnvironmentValue $backendEnvironment 'INSPECTION_MINIO_PUBLIC_ENDPOINT') -eq '${INSPECTION_MINIO_PUBLIC_ENDPOINT:?set INSPECTION_MINIO_PUBLIC_ENDPOINT in .env}') 'Backend public MinIO endpoint must be supplied explicitly.'
Assert-DependencyCondition $backend 'postgres' 'service_healthy'
Assert-DependencyCondition $backend 'redis' 'service_healthy'
Assert-DependencyCondition $backend 'minio-init' 'service_completed_successfully'

$minioInit = Get-ComposeServiceBlock $compose 'minio-init'
Assert-DependencyCondition $minioInit 'minio' 'service_healthy'
Assert-True ([regex]::IsMatch($minioInit, '(?m)^    restart:[ \t]*"no"[ \t]*$')) 'MinIO initialization must not restart indefinitely.'
foreach ($bucket in @('inspection-quarantine', 'inspection-private')) {
    $bucketPath = 'local/$${{INSPECTION_ENVIRONMENT}}-{0}' -f $bucket
    Assert-True ($minioInit.Contains(('mc mb --ignore-existing "{0}"' -f $bucketPath))) "MinIO initialization must create the private bucket: $bucket"
    Assert-True ($minioInit.Contains(('mc anonymous set none "{0}"' -f $bucketPath))) "MinIO initialization must disable anonymous access: $bucket"
}

Assert-True ([regex]::IsMatch($docker, '(?m)^FROM\s+eclipse-temurin:21-jdk-jammy\s+AS\s+build\s*$')) 'Dockerfile builder image must be Java 21.'
Assert-True ([regex]::IsMatch($docker, '(?m)^FROM\s+eclipse-temurin:21-jre-jammy\s*$')) 'Dockerfile runtime image must be Java 21.'
Assert-True ([regex]::IsMatch($docker, '(?m)^USER\s+spring\s*$')) 'Dockerfile must run as the spring user.'
Assert-True ([regex]::IsMatch($docker, '(?m)^HEALTHCHECK\b.*GET /actuator/health.*$')) 'Dockerfile healthcheck must request /actuator/health.'

Assert-True ($application -match '(?m)^    hmac-base64-secret:[ \t]*\$\{INSPECTION_JWT_HMAC_BASE64_SECRET\}[ \t]*$') 'The shared application configuration must bind INSPECTION_JWT_HMAC_BASE64_SECRET.'
Assert-True ($applicationDev -notmatch '(?m)^\s*environment:\s*dev\s*$') 'The dev profile must not supply a fallback inspection environment.'

foreach ($key in @('INSPECTION_DB_USERNAME', 'INSPECTION_DB_PASSWORD', 'INSPECTION_MINIO_ACCESS_KEY', 'INSPECTION_MINIO_SECRET_KEY', 'INSPECTION_JWT_HMAC_BASE64_SECRET', 'INSPECTION_DATA_ENCRYPTION_BASE64_KEY')) {
    Assert-True ((Get-EnvExampleValue $envWithoutComments $key) -match '^REPLACE_WITH_[A-Z0-9_]+$') "Environment example must use a non-secret placeholder: $key"
}
Assert-True ($env -match '(?m)^# Replace with Base64 encoding of at least 32 random bytes\. This invalid placeholder is not a secret\.$') 'The JWT placeholder must explain the 32-byte minimum without providing a secret.'
Assert-True ((Get-EnvExampleValue $envWithoutComments 'INSPECTION_RESERVATION_ALLOWED_MIME_TYPES') -eq 'image/jpeg,image/png,application/pdf') 'Reservation upload MIME policy must be explicit in the environment example.'

Write-Output 'Compose static invariants passed.'
