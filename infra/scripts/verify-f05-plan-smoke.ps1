param(
    [string]$DockerPath = 'D:\DevTools\DockerDesktop\resources\bin\docker.exe',
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'

$baseUri = [Uri]$BaseUrl
if (-not $baseUri.IsLoopback) {
    throw 'F05 smoke is intentionally limited to a loopback development environment.'
}
if (-not (Test-Path -LiteralPath $DockerPath -PathType Leaf)) {
    $DockerPath = (Get-Command docker -ErrorAction Stop).Source
}

$testPassword = 'test-password'
$providerId = '22222222-2222-2222-2222-222222222222'
$serviceId = '33333333-3333-3333-3333-333333333333'
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

    try {
        $response = Invoke-RestMethod `
            -Method Post `
            -Uri "$BaseUrl/api/v1/auth/login" `
            -ContentType 'application/json' `
            -Body (@{
                loginName = $LoginName
                password = $testPassword
            } | ConvertTo-Json -Compress) `
            -Headers @{ 'X-Request-Id' = [Guid]::NewGuid().ToString() }
        return $response.accessToken
    } catch {
        throw 'F05 smoke identities are missing. Run verify-f04-reservation-smoke.ps1 once on an empty local database.'
    }
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

Wait-BackendHealth

$customerToken = Get-AccessToken -LoginName 'smoke-customer'
$otherCustomerToken = Get-AccessToken -LoginName 'smoke-other'
$providerToken = Get-AccessToken -LoginName 'smoke-provider'
$platformToken = Get-AccessToken -LoginName 'smoke-platform'

$reservationBody = @{
    serviceId = $serviceId
    cityCode = '370500'
    location = @{
        address = 'Dongying F05 smoke line'
        longitude = 118.5
        latitude = 37.4
    }
    lineLengthMeters = 18600
    preferredServiceDate = [DateTime]::UtcNow.AddDays(7).ToString('yyyy-MM-dd')
    inspectionItemCodes = @('POWER_LINE', 'TOWER')
    attachmentFileIds = @()
    note = 'F05 real plan smoke'
    contact = @{
        name = 'Zhang'
        mobile = '13800000000'
    }
    agreementVersion = '2026-07'
    agreementAccepted = $true
} | ConvertTo-Json -Depth 8 -Compress
$reservation = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/v1/inspection/reservations" `
    -ContentType 'application/json' `
    -Body $reservationBody `
    -Headers @{
        Authorization = "Bearer $customerToken"
        'Idempotency-Key' = [Guid]::NewGuid().ToString()
        'X-Request-Id' = 'f05-smoke-reservation'
    }

$publishKey = [Guid]::NewGuid().ToString()
$publishBodyObject = @{
    providerId = $providerId
    validUntil = [DateTime]::UtcNow.AddDays(2).ToString('o')
    serviceDate = [DateTime]::UtcNow.AddDays(7).ToString('yyyy-MM-dd')
    summary = 'UAV collection and professional review'
    planItems = @(
        @{
            code = 'POWER_LINE'
            name = 'Power line inspection'
            quantity = 24
            unit = 'tower'
            unitPriceMinor = 69900
        }
    )
    deliverables = @(
        @{ code = 'HD_MEDIA'; name = 'HD media' },
        @{ code = 'DEFECT_LIST'; name = 'Defect list' },
        @{ code = 'POINT_MARKS'; name = 'Point marks' },
        @{ code = 'REPORT'; name = 'Inspection report' }
    )
    currency = 'CNY'
}
$publishBody = $publishBodyObject | ConvertTo-Json -Depth 8 -Compress
$plan = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/v1/provider/inspection/reservations/$($reservation.id)/plans" `
    -ContentType 'application/json' `
    -Body $publishBody `
    -Headers @{
        Authorization = "Bearer $providerToken"
        'Idempotency-Key' = $publishKey
        'If-Match' = '"0"'
        'X-Request-Id' = 'f05-smoke-publish'
    }
$planReplay = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/v1/provider/inspection/reservations/$($reservation.id)/plans" `
    -ContentType 'application/json' `
    -Body $publishBody `
    -Headers @{
        Authorization = "Bearer $providerToken"
        'Idempotency-Key' = $publishKey
        'If-Match' = '"0"'
        'X-Request-Id' = 'f05-smoke-publish-replay'
    }

$changedPublishBody = $publishBodyObject.Clone()
$changedPublishBody.summary = 'Changed request with reused key'
$publishConflictStatus = Get-HttpFailureStatus {
    Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/provider/inspection/reservations/$($reservation.id)/plans" `
        -ContentType 'application/json' `
        -Body ($changedPublishBody | ConvertTo-Json -Depth 8 -Compress) `
        -Headers @{
            Authorization = "Bearer $providerToken"
            'Idempotency-Key' = $publishKey
            'If-Match' = '"1"'
        }
}

$customerPlan = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/plans/$($plan.id)" `
    -Headers @{ Authorization = "Bearer $customerToken" }
$platformPlan = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/plans/$($plan.id)" `
    -Headers @{ Authorization = "Bearer $platformToken" }
$crossCustomerReadStatus = Get-HttpFailureStatus {
    Invoke-RestMethod `
        -Method Get `
        -Uri "$BaseUrl/api/v1/inspection/plans/$($plan.id)" `
        -Headers @{ Authorization = "Bearer $otherCustomerToken" }
}

$confirmKey = [Guid]::NewGuid().ToString()
$confirmBody = @{
    agreementVersion = 'inspection-plan-v1'
    agreementAccepted = $true
} | ConvertTo-Json -Compress
$order = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/v1/inspection/plans/$($plan.id)/confirm" `
    -ContentType 'application/json' `
    -Body $confirmBody `
    -Headers @{
        Authorization = "Bearer $customerToken"
        'Idempotency-Key' = $confirmKey
        'If-Match' = '"0"'
        'X-Request-Id' = 'f05-smoke-confirm'
    }
$orderReplay = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/v1/inspection/plans/$($plan.id)/confirm" `
    -ContentType 'application/json' `
    -Body $confirmBody `
    -Headers @{
        Authorization = "Bearer $customerToken"
        'Idempotency-Key' = $confirmKey
        'If-Match' = '"0"'
        'X-Request-Id' = 'f05-smoke-confirm-replay'
    }

$crossCustomerConfirmStatus = Get-HttpFailureStatus {
    Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/plans/$($plan.id)/confirm" `
        -ContentType 'application/json' `
        -Body $confirmBody `
        -Headers @{
            Authorization = "Bearer $otherCustomerToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
            'If-Match' = '"1"'
        }
}
$platformConfirmStatus = Get-HttpFailureStatus {
    Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/plans/$($plan.id)/confirm" `
        -ContentType 'application/json' `
        -Body $confirmBody `
        -Headers @{
            Authorization = "Bearer $platformToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
            'If-Match' = '"1"'
        }
}

$reservationAfterPlan = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/reservations/$($reservation.id)" `
    -Headers @{ Authorization = "Bearer $customerToken" }
$confirmedPlan = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/plans/$($plan.id)" `
    -Headers @{ Authorization = "Bearer $customerToken" }

if ($plan.id -ne $planReplay.id -or $plan.status -ne 'PUBLISHED') {
    throw 'Plan publication was not idempotent.'
}
if ($publishConflictStatus -ne 409) {
    throw 'Reusing the publish idempotency key with another request did not return 409.'
}
if ($customerPlan.id -ne $plan.id -or $platformPlan.id -ne $plan.id) {
    throw 'Customer or read-only platform could not read the plan.'
}
if ($crossCustomerReadStatus -ne 404 -or $crossCustomerConfirmStatus -ne 404) {
    throw 'Cross-customer plan access did not fail closed with 404.'
}
if ($platformConfirmStatus -ne 403) {
    throw 'Read-only platform operator was able to confirm a plan.'
}
if ($order.id -ne $orderReplay.id -or $order.status -ne 'ARRANGING') {
    throw 'Plan confirmation did not return one idempotent ARRANGING order.'
}
if ($confirmedPlan.status -ne 'CONFIRMED' `
        -or $confirmedPlan.serviceOrderId -ne $order.id `
        -or $reservationAfterPlan.status -ne 'PLAN_PUBLISHED' `
        -or $reservationAfterPlan.currentPlanId -ne $plan.id) {
    throw 'The persisted reservation, plan and service-order chain is inconsistent.'
}

$databaseCheckSql = @"
SELECT
    (SELECT count(*) FROM inspection_service_plan WHERE id = '$($plan.id)') || '|' ||
    (SELECT count(*) FROM inspection_service_order WHERE id = '$($order.id)') || '|' ||
    (SELECT count(*) FROM inspection_state_transition
        WHERE aggregate_id IN ('$($plan.id)', '$($order.id)')) || '|' ||
    (SELECT count(*) FROM inspection_domain_event_outbox
        WHERE aggregate_id IN ('$($plan.id)', '$($order.id)'));
"@
$databaseCheck = (
    & $DockerPath exec inspection-local-postgres-1 `
        psql -At -U $databaseUser -d $databaseName -c $databaseCheckSql
).Trim()
if ($LASTEXITCODE -ne 0 -or $databaseCheck -ne '1|1|3|2') {
    throw "Unexpected persisted plan evidence: $databaseCheck"
}

Invoke-Compose -Arguments @('restart', 'backend')
Wait-BackendHealth
$customerTokenAfterRestart = Get-AccessToken -LoginName 'smoke-customer'
$afterRestart = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/plans/$($plan.id)" `
    -Headers @{ Authorization = "Bearer $customerTokenAfterRestart" }
if ($afterRestart.status -ne 'CONFIRMED' `
        -or $afterRestart.serviceOrderId -ne $order.id) {
    throw 'Confirmed plan and service-order link did not survive backend restart.'
}

[pscustomobject]@{
    ReservationId = $reservation.id
    PlanId = $plan.id
    ServiceOrderId = $order.id
    PublishReplaySameId = $true
    ConfirmReplaySameId = $true
    CrossCustomerReadStatus = $crossCustomerReadStatus
    CrossCustomerConfirmStatus = $crossCustomerConfirmStatus
    PlatformReadOnlyStatus = $platformConfirmStatus
    DatabaseEvidence = $databaseCheck
    RestartRecovery = $true
} | ConvertTo-Json -Compress
