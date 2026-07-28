param(
    [string]$DockerPath = 'D:\DevTools\DockerDesktop\resources\bin\docker.exe',
    [int]$BackendPort = 18081,
    [int]$PostgresPort = 25433,
    [int]$RedisPort = 26380,
    [int]$MinioApiPort = 29002,
    [int]$MinioConsolePort = 29003
)

$ErrorActionPreference = 'Stop'
$projectName = "inspection-f08-emptydb-$PID"
if ($projectName -notmatch '^inspection-f08-emptydb-[0-9]+$') {
    throw 'Refusing to use an unexpected temporary Compose project name.'
}
if (-not (Test-Path -LiteralPath $DockerPath -PathType Leaf)) {
    $DockerPath = (Get-Command docker -ErrorAction Stop).Source
}

$databaseUser = (
    Get-Content -LiteralPath '.env' |
    Where-Object { $_ -like 'INSPECTION_DB_USERNAME=*' } |
    Select-Object -First 1
) -replace '^INSPECTION_DB_USERNAME=', ''
$databaseName = (
    Get-Content -LiteralPath '.env' |
    Where-Object { $_ -like 'INSPECTION_DB_NAME=*' } |
    Select-Object -First 1
) -replace '^INSPECTION_DB_NAME=', ''
if ([string]::IsNullOrWhiteSpace($databaseUser) `
        -or [string]::IsNullOrWhiteSpace($databaseName)) {
    throw 'Database name or username is missing from .env.'
}

$env:INSPECTION_APP_PORT = [string]$BackendPort
$env:INSPECTION_POSTGRES_PORT = [string]$PostgresPort
$env:INSPECTION_REDIS_PORT = [string]$RedisPort
$env:INSPECTION_MINIO_API_PORT = [string]$MinioApiPort
$env:INSPECTION_MINIO_CONSOLE_PORT = [string]$MinioConsolePort
$env:INSPECTION_MINIO_PUBLIC_ENDPOINT = "http://127.0.0.1:$MinioApiPort"

$compose = @(
    'compose',
    '--project-name', $projectName,
    '--env-file', '.env',
    '-f', 'infra/compose.yaml'
)

function Invoke-Docker {
    param([string[]]$Arguments)

    & $DockerPath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed with exit code $LASTEXITCODE."
    }
}

try {
    Invoke-Docker -Arguments ($compose + @('up', '-d', '--build'))

    $healthy = $false
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        try {
            $health = Invoke-RestMethod `
                -Uri "http://127.0.0.1:$BackendPort/actuator/health" `
                -TimeoutSec 2
            if ($health.status -eq 'UP') {
                $healthy = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $healthy) {
        throw 'Temporary backend did not become healthy after empty-database migration.'
    }

    $sql = @"
SELECT
    (SELECT count(*) FROM flyway_history_identity WHERE success) || '|' ||
    (SELECT count(*) FROM flyway_history_inspection WHERE success) || '|' ||
    (SELECT count(*) FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name IN (
              'inspection_reservation',
              'inspection_service_plan',
              'inspection_service_order',
              'inspection_delivery',
              'inspection_acceptance',
              'inspection_file_object'
          )) || '|' ||
    (SELECT count(*) FROM pg_extension WHERE extname = 'postgis');
"@
    $migrationEvidence = (
        & $DockerPath @compose exec -T postgres `
            psql -U $databaseUser -d $databaseName -Atc $sql
    ).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not inspect the temporary migration database.'
    }
    $parts = $migrationEvidence -split '\|'
    if ($parts.Count -ne 4 `
            -or [int]$parts[0] -lt 2 `
            -or [int]$parts[1] -lt 3 `
            -or [int]$parts[2] -ne 6 `
            -or [int]$parts[3] -ne 1) {
        throw "Unexpected empty-database migration evidence: $migrationEvidence"
    }

    [ordered]@{
        Project = $projectName
        Health = 'UP'
        IdentityMigrations = [int]$parts[0]
        InspectionMigrations = [int]$parts[1]
        CoreTableCount = [int]$parts[2]
        PostgisExtensionCount = [int]$parts[3]
    } | ConvertTo-Json -Compress
} finally {
    & $DockerPath @compose down --volumes --remove-orphans | Out-Null
}
