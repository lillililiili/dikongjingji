param(
    [string]$DockerPath = 'D:\DevTools\DockerDesktop\resources\bin\docker.exe',
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [switch]$DeferCleanup
)

$ErrorActionPreference = 'Stop'

$baseUri = [Uri]$BaseUrl
if (-not $baseUri.IsLoopback) {
    throw 'F06 smoke is intentionally limited to a loopback development environment.'
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

$composeArguments = @(
    'compose',
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

function Invoke-Compose {
    param([string[]]$Arguments)

    Invoke-Docker -Arguments ($composeArguments + $Arguments)
}

function Wait-BackendHealth {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $response = Invoke-WebRequest `
                -UseBasicParsing `
                -Uri "$BaseUrl/actuator/health" `
                -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 750
        }
    }
    throw 'Backend did not return a healthy response.'
}

function Get-AccessToken {
    param([string]$LoginName)

    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/auth/login" `
        -ContentType 'application/json' `
        -Body (@{
            loginName = $LoginName
            password = 'test-password'
        } | ConvertTo-Json -Compress) `
        -Headers @{ 'X-Request-Id' = [Guid]::NewGuid().ToString() }
    return $response.accessToken
}

function Get-HttpFailureStatus {
    param([scriptblock]$Request)

    try {
        & $Request
        return 200
    } catch {
        if ($null -eq $_.Exception.Response) {
            throw
        }
        return [int]$_.Exception.Response.StatusCode
    }
}

$reservationIdForCleanup = $null
$completed = $false
try {
Wait-BackendHealth
$f05Json = & "$PSScriptRoot\verify-f05-plan-smoke.ps1" `
    -DockerPath $DockerPath `
    -BaseUrl $BaseUrl `
    -DeferCleanup |
    Select-Object -Last 1
$f05 = $f05Json | ConvertFrom-Json
$reservationIdForCleanup = [Guid]::Parse($f05.ReservationId)
Wait-BackendHealth

$customerToken = Get-AccessToken -LoginName 'smoke-customer'
$otherCustomerToken = Get-AccessToken -LoginName 'smoke-other'
$providerToken = Get-AccessToken -LoginName 'smoke-provider'
$platformToken = Get-AccessToken -LoginName 'smoke-platform'
$orderId = $f05.ServiceOrderId
$reservationDuringOrderFlow = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/reservations/$($f05.ReservationId)" `
    -Headers @{ Authorization = "Bearer $customerToken" }
if ($reservationDuringOrderFlow.status -ne 'PLAN_PUBLISHED') {
    throw 'F05 reservation was cleaned before the order smoke took ownership.'
}

$arrangeKey = [Guid]::NewGuid().ToString()
$arrangeObject = @{
    assignments = @(
        @{
            type = 'TEAM_MEMBER'
            resourceId = '77777777-7777-4777-8777-777777777701'
            displayName = 'Dongying inspection team'
            roleLabel = 'Project lead'
            contactMobile = '13812346621'
        },
        @{
            type = 'DEVICE'
            resourceId = '77777777-7777-4777-8777-777777777702'
            displayName = 'M350 RTK + H30T'
            roleLabel = $null
        }
    )
}
$arrangeBody = $arrangeObject | ConvertTo-Json -Depth 6 -Compress
$arranged = Invoke-RestMethod `
    -Method Put `
    -Uri "$BaseUrl/api/v1/provider/inspection/service-orders/$orderId/arrangement" `
    -ContentType 'application/json' `
    -Body $arrangeBody `
    -Headers @{
        Authorization = "Bearer $providerToken"
        'Idempotency-Key' = $arrangeKey
        'If-Match' = '"0"'
        'X-Request-Id' = 'f06-smoke-arrange'
    }
$arrangeReplay = Invoke-RestMethod `
    -Method Put `
    -Uri "$BaseUrl/api/v1/provider/inspection/service-orders/$orderId/arrangement" `
    -ContentType 'application/json' `
    -Body $arrangeBody `
    -Headers @{
        Authorization = "Bearer $providerToken"
        'Idempotency-Key' = $arrangeKey
        'If-Match' = '"0"'
        'X-Request-Id' = 'f06-smoke-arrange-replay'
    }

$changedArrangement = @{
    assignments = @(
        @{
            type = 'TEAM_MEMBER'
            resourceId = '77777777-7777-4777-8777-777777777701'
            displayName = 'Changed inspection team'
            roleLabel = 'Project lead'
        }
    )
}
$arrangeConflictStatus = Get-HttpFailureStatus {
    Invoke-RestMethod `
        -Method Put `
        -Uri "$BaseUrl/api/v1/provider/inspection/service-orders/$orderId/arrangement" `
        -ContentType 'application/json' `
        -Body ($changedArrangement | ConvertTo-Json -Depth 6 -Compress) `
        -Headers @{
            Authorization = "Bearer $providerToken"
            'Idempotency-Key' = $arrangeKey
            'If-Match' = '"1"'
        }
}

$visitId = $arranged.visits[0].id
$visitPath = "$BaseUrl/api/v1/provider/inspection/service-orders/$orderId/visits/$visitId"
$scheduleKey = [Guid]::NewGuid().ToString()
$scheduleBody = @{
    plannedStartAt = [DateTime]::UtcNow.AddDays(7).ToString('o')
} | ConvertTo-Json -Compress
$scheduled = Invoke-RestMethod `
    -Method Put `
    -Uri "$visitPath/schedule" `
    -ContentType 'application/json' `
    -Body $scheduleBody `
    -Headers @{
        Authorization = "Bearer $providerToken"
        'Idempotency-Key' = $scheduleKey
        'If-Match' = '"0"'
        'X-Request-Id' = 'f06-smoke-schedule'
    }
$scheduleReplay = Invoke-RestMethod `
    -Method Put `
    -Uri "$visitPath/schedule" `
    -ContentType 'application/json' `
    -Body $scheduleBody `
    -Headers @{
        Authorization = "Bearer $providerToken"
        'Idempotency-Key' = $scheduleKey
        'If-Match' = '"0"'
        'X-Request-Id' = 'f06-smoke-schedule-replay'
    }

$invalidJumpStatus = Get-HttpFailureStatus {
    Invoke-RestMethod `
        -Method Post `
        -Uri "$visitPath/progress-events" `
        -ContentType 'application/json' `
        -Body (@{
            eventCode = 'SERVICE_COMPLETED'
            occurredAt = [DateTime]::UtcNow.ToString('o')
            message = 'Invalid jump'
        } | ConvertTo-Json -Compress) `
        -Headers @{
            Authorization = "Bearer $providerToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
            'If-Match' = '"1"'
        }
}

$progressVersion = 1
$progressEvents = @(
    @{ code = 'SERVICE_STARTED'; message = 'Inspection started' },
    @{ code = 'SERVICE_PROGRESS'; message = 'Twelve towers inspected' },
    @{ code = 'SERVICE_COMPLETED'; message = 'On-site inspection completed' }
)
$startKey = [Guid]::NewGuid().ToString()
$startedBody = @{
    eventCode = $progressEvents[0].code
    occurredAt = [DateTime]::UtcNow.AddSeconds(1).ToString('o')
    message = $progressEvents[0].message
} | ConvertTo-Json -Compress
$updated = Invoke-RestMethod `
    -Method Post `
    -Uri "$visitPath/progress-events" `
    -ContentType 'application/json' `
    -Body $startedBody `
    -Headers @{
        Authorization = "Bearer $providerToken"
        'Idempotency-Key' = $startKey
        'If-Match' = "`"$progressVersion`""
        'X-Request-Id' = 'f06-smoke-start'
    }
$startReplay = Invoke-RestMethod `
    -Method Post `
    -Uri "$visitPath/progress-events" `
    -ContentType 'application/json' `
    -Body $startedBody `
    -Headers @{
        Authorization = "Bearer $providerToken"
        'Idempotency-Key' = $startKey
        'If-Match' = '"1"'
        'X-Request-Id' = 'f06-smoke-start-replay'
    }

for ($index = 1; $index -lt $progressEvents.Count; $index++) {
    $progressVersion++
    $updated = Invoke-RestMethod `
        -Method Post `
        -Uri "$visitPath/progress-events" `
        -ContentType 'application/json' `
        -Body (@{
            eventCode = $progressEvents[$index].code
            occurredAt = [DateTime]::UtcNow.AddSeconds($index + 1).ToString('o')
            message = $progressEvents[$index].message
        } | ConvertTo-Json -Compress) `
        -Headers @{
            Authorization = "Bearer $providerToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
            'If-Match' = "`"$progressVersion`""
            'X-Request-Id' = "f06-smoke-progress-$index"
        }
}

$customerOrder = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId" `
    -Headers @{ Authorization = "Bearer $customerToken" }
$platformOrder = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId" `
    -Headers @{ Authorization = "Bearer $platformToken" }
$otherCustomerReadStatus = Get-HttpFailureStatus {
    Invoke-RestMethod `
        -Method Get `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId" `
        -Headers @{ Authorization = "Bearer $otherCustomerToken" }
}
$platformWriteStatus = Get-HttpFailureStatus {
    Invoke-RestMethod `
        -Method Post `
        -Uri "$visitPath/progress-events" `
        -ContentType 'application/json' `
        -Body (@{
            eventCode = 'SERVICE_PROGRESS'
            occurredAt = [DateTime]::UtcNow.AddSeconds(10).ToString('o')
            message = 'Platform must not write'
        } | ConvertTo-Json -Compress) `
        -Headers @{
            Authorization = "Bearer $platformToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
            'If-Match' = '"4"'
        }
}

if ($arranged.id -ne $arrangeReplay.id -or $arranged.version -ne 1) {
    throw 'Arrangement was not idempotent.'
}
if ($scheduled.status -ne 'SCHEDULED' `
        -or $scheduled.version -ne 1 `
        -or $scheduleReplay.version -ne 1) {
    throw 'Visit scheduling was not idempotent.'
}
if ($arrangeConflictStatus -ne 409) {
    throw 'Reusing the arrangement key with changed input did not return 409.'
}
if ($invalidJumpStatus -ne 409) {
    throw 'Illegal progress jump was not rejected.'
}
if ($updated.status -ne 'IN_PROGRESS' `
        -or $updated.version -ne 4 `
        -or $updated.progress.Count -ne 3 `
        -or $startReplay.version -ne 2 `
        -or $customerOrder.status -ne 'IN_PROGRESS') {
    throw 'The persisted fulfillment progress is inconsistent.'
}
if ($customerOrder.id -ne $orderId -or $platformOrder.id -ne $orderId) {
    throw 'Customer or read-only platform could not read the service order.'
}
if ($otherCustomerReadStatus -ne 404 -or $platformWriteStatus -ne 403) {
    throw 'Order ownership or read-only platform authorization failed closed.'
}

$databaseCheckSql = @"
SELECT
    (SELECT count(*) FROM inspection_service_order WHERE id = '$orderId') || '|' ||
    (SELECT count(*) FROM inspection_order_assignment WHERE service_order_id = '$orderId') || '|' ||
    (SELECT count(*) FROM inspection_order_progress WHERE service_order_id = '$orderId') || '|' ||
    (SELECT version FROM inspection_service_order WHERE id = '$orderId');
"@
$databaseCheck = (
    & $DockerPath exec inspection-local-postgres-1 `
        psql -At -U $databaseUser -d $databaseName -c $databaseCheckSql
).Trim()
if ($LASTEXITCODE -ne 0 -or $databaseCheck -ne '1|2|4|2') {
    throw "Unexpected persisted order evidence: $databaseCheck"
}

Invoke-Compose -Arguments @('restart', 'backend')
Wait-BackendHealth
$customerTokenAfterRestart = Get-AccessToken -LoginName 'smoke-customer'
$afterRestart = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId" `
    -Headers @{ Authorization = "Bearer $customerTokenAfterRestart" }
if ($afterRestart.version -ne 2 `
        -or $afterRestart.visits[0].version -ne 4 `
        -or $afterRestart.visits[0].progress.Count -ne 3 `
        -or $afterRestart.assignments.Count -ne 2) {
    throw 'Order arrangement and progress did not survive backend restart.'
}

$result = [pscustomobject]@{
    ReservationId = $f05.ReservationId
    ServiceOrderId = $orderId
    VisitId = $visitId
    ReservationActiveDuringOrderFlow = $true
    ArrangementReplaySameId = $true
    ProgressReplaySameId = $true
    IllegalJumpStatus = $invalidJumpStatus
    CrossCustomerReadStatus = $otherCustomerReadStatus
    PlatformReadOnlyStatus = $platformWriteStatus
    DatabaseEvidence = $databaseCheck
    RestartRecovery = $true
} | ConvertTo-Json -Compress
$completed = $true
$result
} finally {
    if ($null -ne $reservationIdForCleanup `
            -and (-not $DeferCleanup -or -not $completed)) {
        & "$PSScriptRoot\verify-f05-plan-smoke.ps1" `
            -DockerPath $DockerPath `
            -BaseUrl $BaseUrl `
            -CleanupReservationId $reservationIdForCleanup
    }
}
