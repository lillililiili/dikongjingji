param(
    [string]$DockerPath = 'D:\DevTools\DockerDesktop\resources\bin\docker.exe',
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Net.Http

$baseUri = [Uri]$BaseUrl
if (-not $baseUri.IsLoopback) {
    throw 'F04 smoke is intentionally limited to a loopback development environment.'
}
if (-not (Test-Path -LiteralPath $DockerPath -PathType Leaf)) {
    $DockerPath = (Get-Command docker -ErrorAction Stop).Source
}

$customerId = '11111111-1111-1111-1111-111111111111'
$otherCustomerId = '11111111-1111-1111-1111-222222222222'
$providerId = '22222222-2222-2222-2222-222222222222'
$serviceId = '33333333-3333-3333-3333-333333333333'
$testPassword = 'test-password'
$environmentName = (
    Get-Content -LiteralPath '.env' `
    | Where-Object { $_ -like 'INSPECTION_ENVIRONMENT=*' } `
    | Select-Object -First 1
) -replace '^INSPECTION_ENVIRONMENT=', ''
if ([string]::IsNullOrWhiteSpace($environmentName)) {
    throw 'INSPECTION_ENVIRONMENT is missing from .env.'
}
$expectedPrivateBucket = "$environmentName-inspection-private"
$databaseUser = (
    Get-Content -LiteralPath '.env' `
    | Where-Object { $_ -like 'INSPECTION_DB_USERNAME=*' } `
    | Select-Object -First 1
) -replace '^INSPECTION_DB_USERNAME=', ''
$databaseName = (
    Get-Content -LiteralPath '.env' `
    | Where-Object { $_ -like 'INSPECTION_DB_NAME=*' } `
    | Select-Object -First 1
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

function Get-AccessToken {
    param([string]$LoginName)

    $body = @{
        loginName = $LoginName
        password = $testPassword
    } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/auth/login" `
        -ContentType 'application/json' `
        -Body $body `
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
    throw 'Backend did not return a healthy response after restart.'
}

function ConvertTo-SmokeClock {
    param([string]$Value)

    if ($Value -notmatch '^(\d{2}:\d{2})(?::\d{2})?$') {
        throw "Unexpected time-slot clock value: $Value"
    }
    return $Matches[1]
}

function Set-SmokeReservationsCancelled {
    param([Guid[]]$ReservationIds)

    if ($null -eq $ReservationIds -or $ReservationIds.Count -eq 0) {
        return
    }
    $sqlIds = ($ReservationIds | ForEach-Object { "'$($_.ToString())'::uuid" }) -join ', '
    $cleanupSql = @"
UPDATE inspection_reservation
SET status = 'CANCELLED',
    cancelled_at = COALESCE(cancelled_at, now()),
    version = version + 1,
    updated_by = '$customerId'::uuid,
    updated_at = now()
WHERE id IN ($sqlIds)
  AND customer_id = '$customerId'::uuid
  AND service_id = '$serviceId'::uuid
  AND status <> 'CANCELLED';

SELECT count(*)
FROM inspection_reservation
WHERE id IN ($sqlIds)
  AND customer_id = '$customerId'::uuid
  AND service_id = '$serviceId'::uuid
  AND status = 'CANCELLED';
"@
    $cleanupRows = @(
        $cleanupSql | & $DockerPath exec -i inspection-local-postgres-1 `
            psql -At -v ON_ERROR_STOP=1 -U $databaseUser -d $databaseName
    )
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to cancel this F04 run reservations.'
    }
    $verifiedCount = [int](
        $cleanupRows `
        | Where-Object { $_ -match '^\d+$' } `
        | Select-Object -Last 1
    )
    if ($verifiedCount -ne $ReservationIds.Count) {
        throw "F04 cleanup verified $verifiedCount of $($ReservationIds.Count) reservations."
    }
}

Wait-BackendHealth

$seedSql = @'
INSERT INTO identity_organization (
    id, organization_code, organization_name, organization_type
) VALUES
    ('11111111-1111-1111-1111-aaaaaaaaaaaa', 'SMOKE-CUSTOMER', 'Smoke customer org', 'CUSTOMER'),
    ('11111111-1111-1111-1111-bbbbbbbbbbbb', 'SMOKE-OTHER', 'Smoke other customer org', 'CUSTOMER'),
    ('22222222-2222-2222-2222-222222222222', 'SMOKE-PROVIDER', 'Smoke provider', 'PROVIDER'),
    ('99999999-9999-9999-9999-999999999999', 'SMOKE-PLATFORM', 'Smoke platform org', 'PLATFORM')
ON CONFLICT (id) DO UPDATE
SET organization_name = EXCLUDED.organization_name;

INSERT INTO identity_user (
    id, login_name, display_name, password_hash, default_organization_id
) VALUES
    (
        '11111111-1111-1111-1111-111111111111',
        'smoke-customer',
        'Smoke customer',
        crypt('test-password', gen_salt('bf', 10)),
        '11111111-1111-1111-1111-aaaaaaaaaaaa'
    ),
    (
        '11111111-1111-1111-1111-222222222222',
        'smoke-other',
        'Smoke other customer',
        crypt('test-password', gen_salt('bf', 10)),
        '11111111-1111-1111-1111-bbbbbbbbbbbb'
    ),
    (
        '22222222-2222-2222-2222-333333333333',
        'smoke-provider',
        'Smoke provider operator',
        crypt('test-password', gen_salt('bf', 10)),
        '22222222-2222-2222-2222-222222222222'
    ),
    (
        '99999999-9999-9999-9999-888888888888',
        'smoke-platform',
        'Smoke platform operator',
        crypt('test-password', gen_salt('bf', 10)),
        '99999999-9999-9999-9999-999999999999'
    )
ON CONFLICT (id) DO UPDATE
SET password_hash = EXCLUDED.password_hash,
    default_organization_id = EXCLUDED.default_organization_id,
    status = 'ACTIVE';

INSERT INTO identity_organization_member (organization_id, user_id) VALUES
    ('11111111-1111-1111-1111-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111'),
    ('11111111-1111-1111-1111-bbbbbbbbbbbb', '11111111-1111-1111-1111-222222222222'),
    ('22222222-2222-2222-2222-222222222222', '22222222-2222-2222-2222-333333333333'),
    ('99999999-9999-9999-9999-999999999999', '99999999-9999-9999-9999-888888888888')
ON CONFLICT (organization_id, user_id) DO UPDATE
SET member_status = 'ACTIVE';

INSERT INTO identity_user_role (user_id, role_id, organization_id)
SELECT '11111111-1111-1111-1111-111111111111', id, NULL
FROM identity_role WHERE role_code = 'CUSTOMER'
ON CONFLICT DO NOTHING;

INSERT INTO identity_user_role (user_id, role_id, organization_id)
SELECT '11111111-1111-1111-1111-222222222222', id, NULL
FROM identity_role WHERE role_code = 'CUSTOMER'
ON CONFLICT DO NOTHING;

INSERT INTO identity_user_role (user_id, role_id, organization_id)
SELECT
    '22222222-2222-2222-2222-333333333333',
    id,
    '22222222-2222-2222-2222-222222222222'
FROM identity_role WHERE role_code = 'PROVIDER_OPERATOR'
ON CONFLICT DO NOTHING;

INSERT INTO identity_user_role (user_id, role_id, organization_id)
SELECT '99999999-9999-9999-9999-888888888888', id, NULL
FROM identity_role WHERE role_code = 'PLATFORM_OPERATOR'
ON CONFLICT DO NOTHING;

INSERT INTO inspection_service (
    id, provider_id, title, cover_url, city_code, status
) VALUES (
    '33333333-3333-3333-3333-333333333333',
    '22222222-2222-2222-2222-222222222222',
    U&'\8F93\7535\7EBF\8DEF\65E0\4EBA\673A\5DE1\68C0',
    NULL,
    '370500',
    'ACTIVE'
)
ON CONFLICT (id) DO UPDATE
SET provider_id = EXCLUDED.provider_id,
    title = EXCLUDED.title,
    city_code = EXCLUDED.city_code,
    status = 'ACTIVE';

UPDATE inspection_service
SET service_no = 'INS-SMOKE-001',
    category_code = 'POWER',
    category_name = U&'\7535\529B\5DE1\68C0',
    summary = U&'\4E13\4E1A\65E0\4EBA\673A\7535\529B\5DE1\68C0\670D\52A1',
    price_mode = 'STARTING_PRICE',
    starting_amount_minor = 69900,
    currency = 'CNY',
    unit_code = 'KM',
    unit_label = U&'\516C\91CC\8D77',
    availability = 'RESERVABLE',
    primary_action = 'RESERVE'
WHERE id = '33333333-3333-3333-3333-333333333333';

INSERT INTO inspection_service_booking_item (
    service_id, item_code, item_label, display_order
) VALUES
    (
        '33333333-3333-3333-3333-333333333333',
        'POWER_LINE',
        U&'\7EBF\8DEF\672C\4F53\5DE1\68C0',
        0
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'TOWER',
        U&'\6746\5854\8BBE\5907\5DE1\68C0',
        1
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'INSULATOR_FITTINGS',
        U&'\7EDD\7F18\5B50\53CA\91D1\5177\5DE1\68C0',
        2
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'CORRIDOR_ENV',
        U&'\901A\9053\73AF\5883\5DE1\68C0',
        3
    )
ON CONFLICT (service_id, item_code) DO UPDATE
SET item_label = EXCLUDED.item_label,
    display_order = EXCLUDED.display_order,
    status = 'ACTIVE';
'@

$seedSql | & $DockerPath exec -i inspection-local-postgres-1 sh -c `
    'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to seed the local F04 smoke identities and service.'
}

$createdReservationIds = [Collections.Generic.HashSet[Guid]]::new()
try {
$customerToken = Get-AccessToken -LoginName 'smoke-customer'
$otherCustomerToken = Get-AccessToken -LoginName 'smoke-other'
$providerToken = Get-AccessToken -LoginName 'smoke-provider'
$platformToken = Get-AccessToken -LoginName 'smoke-platform'

$catalog = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/services?cityCode=370500&page=1&size=50" `
    -Headers @{ Authorization = "Bearer $customerToken" }
$catalogService = $catalog.items | Where-Object { $_.serviceId -eq $serviceId }
if ($null -eq $catalogService `
        -or $catalogService.price.mode -ne 'STARTING_PRICE' `
        -or $catalogService.price.startingAmountMinor -ne 69900 `
        -or $catalogService.features[0].code -ne 'POWER_LINE') {
    throw 'The frozen inspection service catalog DTO is not usable by booking.'
}

$createKey = [Guid]::NewGuid().ToString()
$createHeaders = @{
    Authorization = "Bearer $customerToken"
    'Idempotency-Key' = $createKey
    'X-Request-Id' = [Guid]::NewGuid().ToString()
}
$smokeAddress = 'Dongying smoke line 1'
$smokeNote = 'F04 real flow smoke'
$smokeContactName = 'Zhang'
$serviceDate = (Get-Date).Date.AddDays(1).ToString('yyyy-MM-dd')
$reservationBody = @{
    serviceId = $serviceId
    cityCode = '370500'
    location = @{
        address = $smokeAddress
        longitude = 118.6747
        latitude = 37.4348
    }
    lineLengthMeters = 3200
    preferredServiceDate = $serviceDate
    preferredTimeSlotCode = 'MORNING'
    inspectionItemCodes = @('POWER_LINE', 'TOWER')
    attachmentFileIds = @()
    note = $smokeNote
    contact = @{
        name = $smokeContactName
        mobile = '13800138000'
    }
    agreementVersion = 'v1.0'
    agreementAccepted = $true
} | ConvertTo-Json -Depth 6 -Compress

$availability = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/services/$serviceId/available-time-slots?cityCode=370500&startDate=$serviceDate&days=1" `
    -Headers @{ Authorization = "Bearer $customerToken" }
if ($availability[0].slots.Count -ne 3) {
    throw 'Time-slot availability did not return three fixed slots.'
}
$expectedSlots = @(
    @{ code = 'MORNING'; start = '09:00'; end = '12:00' }
    @{ code = 'AFTERNOON'; start = '14:00'; end = '17:00' }
    @{ code = 'EVENING'; start = '18:00'; end = '21:00' }
)
foreach ($expectedSlot in $expectedSlots) {
    $actualSlots = @(
        $availability[0].slots `
        | Where-Object { $_.slotCode -eq $expectedSlot.code }
    )
    if ($actualSlots.Count -ne 1 `
            -or (ConvertTo-SmokeClock $actualSlots[0].startTime) -ne $expectedSlot.start `
            -or (ConvertTo-SmokeClock $actualSlots[0].endTime) -ne $expectedSlot.end) {
        throw "Unexpected fixed time slot: $($expectedSlot.code)"
    }
}
if (@($availability[0].slots | Where-Object { $_.remainingCount -ne 9 }).Count -ne 0) {
    throw 'Every initial time slot must expose capacity nine.'
}

$reservation = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/v1/inspection/reservations" `
    -ContentType 'application/json; charset=utf-8' `
    -Body $reservationBody `
    -Headers $createHeaders
[void]$createdReservationIds.Add([Guid]::Parse($reservation.id))
$reservationReplay = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/v1/inspection/reservations" `
    -ContentType 'application/json; charset=utf-8' `
    -Body $reservationBody `
    -Headers $createHeaders
if ($reservation.id -ne $reservationReplay.id) {
    throw 'Reservation idempotency replay created a second resource.'
}

$capacityDate = (Get-Date).Date.AddDays(2).ToString('yyyy-MM-dd')
$capacityAvailability = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/services/$serviceId/available-time-slots?cityCode=370500&startDate=$capacityDate&days=1" `
    -Headers @{ Authorization = "Bearer $customerToken" }
$capacitySlot = $capacityAvailability[0].slots `
    | Where-Object { $_.slotCode -eq 'EVENING' }
if ($null -eq $capacitySlot -or $capacitySlot.remainingCount -lt 2) {
    throw 'Capacity smoke requires at least two remaining EVENING slots.'
}

$capacityObject = $reservationBody | ConvertFrom-Json
$capacityObject.preferredServiceDate = $capacityDate
$capacityObject.preferredTimeSlotCode = 'EVENING'
$capacityBody = $capacityObject | ConvertTo-Json -Depth 6 -Compress

for ($index = 1; $index -lt $capacitySlot.remainingCount; $index++) {
    $capacityReservation = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/reservations" `
        -ContentType 'application/json; charset=utf-8' `
        -Body $capacityBody `
        -Headers @{
            Authorization = "Bearer $customerToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
            'X-Request-Id' = [Guid]::NewGuid().ToString()
        }
    [void]$createdReservationIds.Add([Guid]::Parse($capacityReservation.id))
}

$client = [System.Net.Http.HttpClient]::new()
$requests = @()
$responses = @()
try {
    $client.DefaultRequestHeaders.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
            'Bearer',
            $customerToken
        )
    $requests = 1..2 | ForEach-Object {
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Post,
            "$BaseUrl/api/v1/inspection/reservations"
        )
        $request.Headers.Add(
            'Idempotency-Key',
            [Guid]::NewGuid().ToString()
        )
        $request.Headers.Add(
            'X-Request-Id',
            [Guid]::NewGuid().ToString()
        )
        $request.Content = [System.Net.Http.StringContent]::new(
            $capacityBody,
            [System.Text.Encoding]::UTF8,
            'application/json'
        )
        $request
    }
    $tasks = @(
        $client.SendAsync($requests[0]),
        $client.SendAsync($requests[1])
    )
    [System.Threading.Tasks.Task]::WaitAll(
        [System.Threading.Tasks.Task[]]$tasks
    )
    $responses = $tasks | ForEach-Object { $_.Result }
    $statuses = $responses `
        | ForEach-Object { [int]$_.StatusCode } `
        | Sort-Object
    if (($statuses -join ',') -ne '201,409') {
        throw "Concurrent capacity result was $($statuses -join ',')."
    }
    $conflict = $responses `
        | Where-Object { [int]$_.StatusCode -eq 409 } `
        | Select-Object -First 1
    $conflictBody = $conflict.Content.ReadAsStringAsync().Result `
        | ConvertFrom-Json
    if ($conflictBody.code -ne 'INS_TIME_SLOT_FULL') {
        throw "Unexpected capacity conflict code: $($conflictBody.code)"
    }
} finally {
    foreach ($task in $tasks) {
        if ($task.Status -eq [Threading.Tasks.TaskStatus]::RanToCompletion) {
            $completedResponse = $task.Result
            if ($null -ne $completedResponse -and -not $responses.Contains($completedResponse)) {
                $responses += $completedResponse
            }
        }
    }
    foreach ($response in $responses) {
        if ([int]$response.StatusCode -eq 201) {
            $createdBody = $response.Content.ReadAsStringAsync().Result `
                | ConvertFrom-Json
            [void]$createdReservationIds.Add([Guid]::Parse($createdBody.id))
        }
    }
    foreach ($request in $requests) {
        $request.Dispose()
    }
    foreach ($response in $responses) {
        $response.Dispose()
    }
    $client.Dispose()
}

$pngBytes = [Convert]::FromBase64String(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII='
)
$sha256Algorithm = [Security.Cryptography.SHA256]::Create()
try {
    $sha256Bytes = $sha256Algorithm.ComputeHash($pngBytes)
} finally {
    $sha256Algorithm.Dispose()
}
$sha256 = -join ($sha256Bytes | ForEach-Object { $_.ToString('x2') })
$intentBody = @{
    purpose = 'RESERVATION_ATTACHMENT'
    ownerId = $reservation.id
    deliveryFileType = $null
    originalName = 'site-photo.png'
    contentType = 'image/png'
    sizeBytes = $pngBytes.Length
    sha256 = $sha256
} | ConvertTo-Json -Compress
$intent = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/v1/inspection/file-objects/upload-intents" `
    -ContentType 'application/json' `
    -Body $intentBody `
    -Headers @{
        Authorization = "Bearer $customerToken"
        'Idempotency-Key' = [Guid]::NewGuid().ToString()
        'X-Request-Id' = [Guid]::NewGuid().ToString()
    }

$temporaryFile = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllBytes($temporaryFile, $pngBytes)
    & curl.exe `
        --silent `
        --show-error `
        --fail `
        -X PUT `
        -H 'Content-Type: image/png' `
        --data-binary "@$temporaryFile" `
        $intent.uploadUrl
    if ($LASTEXITCODE -ne 0) {
        throw 'The MinIO presigned PUT failed.'
    }
} finally {
    $resolvedTemporaryFile = [IO.Path]::GetFullPath($temporaryFile)
    $resolvedTemporaryRoot = [IO.Path]::GetFullPath($env:TEMP).TrimEnd(
        [IO.Path]::DirectorySeparatorChar
    ) + [IO.Path]::DirectorySeparatorChar
    if ($resolvedTemporaryFile.StartsWith(
            $resolvedTemporaryRoot,
            [StringComparison]::OrdinalIgnoreCase
        )) {
        Remove-Item -LiteralPath $resolvedTemporaryFile -Force
    }
}

$completeKey = [Guid]::NewGuid().ToString()
$completeHeaders = @{
    Authorization = "Bearer $customerToken"
    'Idempotency-Key' = $completeKey
    'X-Request-Id' = [Guid]::NewGuid().ToString()
}
$completeBody = @{
    etag = 'smoke-etag'
    sizeBytes = $pngBytes.Length
    sha256 = $sha256
} | ConvertTo-Json -Compress
$completed = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/v1/inspection/file-objects/$($intent.fileId)/complete" `
    -ContentType 'application/json' `
    -Body $completeBody `
    -Headers $completeHeaders
$completeReplay = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/v1/inspection/file-objects/$($intent.fileId)/complete" `
    -ContentType 'application/json' `
    -Body $completeBody `
    -Headers $completeHeaders
if ($completed.status -ne 'READY' -or $completeReplay.status -ne 'READY') {
    throw 'File completion did not reach READY idempotently.'
}

$customerRead = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/reservations/$($reservation.id)" `
    -Headers @{ Authorization = "Bearer $customerToken" }
$providerRead = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/reservations/$($reservation.id)" `
    -Headers @{ Authorization = "Bearer $providerToken" }
$platformRead = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/reservations/$($reservation.id)" `
    -Headers @{ Authorization = "Bearer $platformToken" }

$crossCustomerStatus = Get-HttpFailureStatus -Request {
    Invoke-WebRequest `
        -UseBasicParsing `
        -Method Get `
        -Uri "$BaseUrl/api/v1/inspection/reservations/$($reservation.id)" `
        -Headers @{ Authorization = "Bearer $otherCustomerToken" } | Out-Null
}
$crossFileStatus = Get-HttpFailureStatus -Request {
    Invoke-WebRequest `
        -UseBasicParsing `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/file-objects/upload-intents" `
        -ContentType 'application/json' `
        -Body $intentBody `
        -Headers @{
            Authorization = "Bearer $otherCustomerToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        } | Out-Null
}
$platformCreateStatus = Get-HttpFailureStatus -Request {
    Invoke-WebRequest `
        -UseBasicParsing `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/reservations" `
        -ContentType 'application/json' `
        -Body $reservationBody `
        -Headers @{
            Authorization = "Bearer $platformToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        } | Out-Null
}

if ($customerRead.attachments.Count -ne 1 `
        -or $customerRead.attachments[0].status -ne 'READY') {
    throw 'The reservation did not return one READY private attachment.'
}
if ($providerRead.id -ne $reservation.id -or $platformRead.id -ne $reservation.id) {
    throw 'An authorized provider/platform read failed.'
}
if ($crossCustomerStatus -ne 404 -or $crossFileStatus -ne 404) {
    throw 'A cross-customer business or file read did not fail closed with 404.'
}
if ($platformCreateStatus -ne 403) {
    throw 'The read-only platform operator was able to create a reservation.'
}

$fileRowQuery = @"
SELECT bucket_name || '|' || object_key
FROM inspection_file_object
WHERE id = '$($intent.fileId)';
"@
$fileRows = @(
    (
        & $DockerPath exec inspection-local-postgres-1 `
            psql -At -U $databaseUser -d $databaseName -c $fileRowQuery
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)
if ($LASTEXITCODE -ne 0 -or $fileRows.Count -ne 1) {
    throw 'Unable to read the persisted file object row.'
}
$bucketAndKey = ([string]$fileRows[0]).Trim().Split('|', 2)
if ($bucketAndKey.Count -ne 2 -or $bucketAndKey[0] -ne $expectedPrivateBucket) {
    throw 'The READY file row does not point at the private bucket.'
}

Invoke-Compose -Arguments @(
    'run',
    '--rm',
    '--no-deps',
    '-e', "SMOKE_OBJECT_KEY=$($bucketAndKey[1])",
    '--entrypoint', '/bin/sh',
    'minio-init',
    '-ec',
    @'
mc alias set local "$INSPECTION_MINIO_ENDPOINT" "$INSPECTION_MINIO_ACCESS_KEY" "$INSPECTION_MINIO_SECRET_KEY" >/dev/null
mc stat "local/${INSPECTION_ENVIRONMENT}-inspection-private/${SMOKE_OBJECT_KEY}" >/dev/null
if mc stat "local/${INSPECTION_ENVIRONMENT}-inspection-quarantine/${SMOKE_OBJECT_KEY}" >/dev/null 2>&1; then
  exit 9
fi
'@
)

Invoke-Compose -Arguments @('restart', 'backend')
Wait-BackendHealth
$customerTokenAfterRestart = Get-AccessToken -LoginName 'smoke-customer'
$afterRestart = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/reservations/$($reservation.id)" `
    -Headers @{ Authorization = "Bearer $customerTokenAfterRestart" }
if ($afterRestart.id -ne $reservation.id `
        -or $afterRestart.attachments.Count -ne 1 `
        -or $afterRestart.attachments[0].status -ne 'READY' `
        -or $afterRestart.location.address -ne $smokeAddress `
        -or $afterRestart.note -ne $smokeNote `
        -or $afterRestart.contact.name -ne $smokeContactName) {
    throw 'Reservation state or encrypted fields did not survive the backend restart.'
}

[pscustomobject]@{
    ReservationId = $reservation.id
    FileId = $intent.fileId
    ReservationReplaySameId = $true
    AvailabilitySlotCount = $availability[0].slots.Count
    AvailabilityCapacity = 9
    CapacityConcurrentStatuses = $statuses -join ','
    CapacityConflictCode = $conflictBody.code
    CatalogServiceLoaded = $true
    AttachmentStatus = $completed.status
    PrivateObjectVerified = $true
    ProviderRead = $true
    PlatformReadOnly = $true
    CrossCustomerStatus = $crossCustomerStatus
    CrossFileStatus = $crossFileStatus
    RestartRecovery = $true
} | ConvertTo-Json -Compress
} finally {
    Set-SmokeReservationsCancelled -ReservationIds @($createdReservationIds)
}
