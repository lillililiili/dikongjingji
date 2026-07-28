param(
    [string]$DockerPath = 'D:\DevTools\DockerDesktop\resources\bin\docker.exe',
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

$ErrorActionPreference = 'Stop'

$baseUri = [Uri]$BaseUrl
if (-not $baseUri.IsLoopback) {
    throw 'F07 smoke is intentionally limited to a loopback development environment.'
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
        & $Request | Out-Null
        return 200
    } catch {
        if ($null -eq $_.Exception.Response) {
            throw
        }
        return [int]$_.Exception.Response.StatusCode
    }
}

function New-DeliveryFile {
    param(
        [string]$Path,
        [string]$Type,
        [string]$ContentType,
        [string]$DisplayName,
        [string]$Token,
        [string]$OrderId,
        [Nullable[int]]$PointCount
    )

    $file = Get-Item -LiteralPath $Path
    $sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    $intent = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/file-objects/upload-intents" `
        -ContentType 'application/json' `
        -Body (@{
            purpose = 'DELIVERY_ARTIFACT'
            ownerId = $OrderId
            deliveryFileType = $Type
            originalName = $DisplayName
            contentType = $ContentType
            sizeBytes = $file.Length
            sha256 = $sha256
        } | ConvertTo-Json -Compress) `
        -Headers @{
            Authorization = "Bearer $Token"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        }

    Invoke-WebRequest `
        -UseBasicParsing `
        -Method Put `
        -Uri $intent.uploadUrl `
        -InFile $Path `
        -ContentType $ContentType | Out-Null

    $ready = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/file-objects/$($intent.fileId)/complete" `
        -ContentType 'application/json' `
        -Body (@{
            etag = "f07-$($intent.fileId)"
            sizeBytes = $file.Length
            sha256 = $sha256
        } | ConvertTo-Json -Compress) `
        -Headers @{
            Authorization = "Bearer $Token"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        }
    if ($ready.status -ne 'READY') {
        throw "Delivery file $DisplayName did not become READY."
    }
    return @{
        fileId = $intent.fileId
        type = $Type
        displayName = $DisplayName
        pointCount = $PointCount
    }
}

Wait-BackendHealth
$f06Json = & "$PSScriptRoot\verify-f06-order-smoke.ps1" `
    -DockerPath $DockerPath `
    -BaseUrl $BaseUrl |
    Select-Object -Last 1
$f06 = $f06Json | ConvertFrom-Json
Wait-BackendHealth

$customerToken = Get-AccessToken -LoginName 'smoke-customer'
$otherCustomerToken = Get-AccessToken -LoginName 'smoke-other'
$providerToken = Get-AccessToken -LoginName 'smoke-provider'
$platformToken = Get-AccessToken -LoginName 'smoke-platform'
$orderId = $f06.ServiceOrderId

$systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$tempRoot = Join-Path $systemTemp ("inspection-f07-" + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($tempRoot) | Out-Null

try {
    $sourceText = Join-Path $tempRoot 'source.txt'
    [IO.File]::WriteAllText(
        $sourceText,
        'inspection delivery smoke content',
        [Text.UTF8Encoding]::new($false))
    $zipPath = Join-Path $tempRoot 'site-images.zip'
    Compress-Archive -LiteralPath $sourceText -DestinationPath $zipPath
    $xlsxPath = Join-Path $tempRoot 'defects.xlsx'
    [IO.File]::Copy($zipPath, $xlsxPath)
    $kmlPath = Join-Path $tempRoot 'inspection-points.kml'
    [IO.File]::WriteAllText(
        $kmlPath,
        '<?xml version="1.0" encoding="UTF-8"?><kml><Document/></kml>',
        [Text.UTF8Encoding]::new($false))
    $pdfPath = Join-Path $tempRoot 'inspection-report.pdf'
    [IO.File]::WriteAllBytes(
        $pdfPath,
        [Text.Encoding]::ASCII.GetBytes(
            "%PDF-1.4`n1 0 obj<</Type/Catalog>>endobj`n%%EOF"))

    $deliveryFiles = @(
        (New-DeliveryFile `
            -Path $zipPath `
            -Type 'IMAGE_ARCHIVE' `
            -ContentType 'application/zip' `
            -DisplayName 'site-images.zip' `
            -Token $providerToken `
            -OrderId $orderId)
        (New-DeliveryFile `
            -Path $xlsxPath `
            -Type 'DEFECT_LIST' `
            -ContentType 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' `
            -DisplayName 'defect-list.xlsx' `
            -Token $providerToken `
            -OrderId $orderId)
        (New-DeliveryFile `
            -Path $kmlPath `
            -Type 'POINT_KML' `
            -ContentType 'application/vnd.google-earth.kml+xml' `
            -DisplayName 'inspection-points.kml' `
            -Token $providerToken `
            -OrderId $orderId `
            -PointCount 24)
        (New-DeliveryFile `
            -Path $pdfPath `
            -Type 'INSPECTION_REPORT' `
            -ContentType 'application/pdf' `
            -DisplayName 'inspection-report.pdf' `
            -Token $providerToken `
            -OrderId $orderId)
    )

    $publishObject = @{
        towerCount = 24
        inspectedLengthMeters = 18600
        issueCount = 3
        issueSummary = @(
            @{ severityCode = 'GENERAL'; severityName = 'General issues'; count = 2 },
            @{ severityCode = 'WATCH'; severityName = 'Watch items'; count = 1 }
        )
        files = $deliveryFiles
    }
    $publishBody = $publishObject | ConvertTo-Json -Depth 8 -Compress
    $publishKey = [Guid]::NewGuid().ToString()
    $published = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/provider/inspection/service-orders/$orderId/delivery" `
        -ContentType 'application/json' `
        -Body $publishBody `
        -Headers @{
            Authorization = "Bearer $providerToken"
            'Idempotency-Key' = $publishKey
            'If-Match' = '"4"'
            'X-Request-Id' = 'f07-smoke-publish'
        }
    $publishReplay = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/provider/inspection/service-orders/$orderId/delivery" `
        -ContentType 'application/json' `
        -Body $publishBody `
        -Headers @{
            Authorization = "Bearer $providerToken"
            'Idempotency-Key' = $publishKey
            'If-Match' = '"4"'
            'X-Request-Id' = 'f07-smoke-publish-replay'
        }
    $changedPublish = $publishObject.Clone()
    $changedPublish.inspectedLengthMeters = 18601
    $publishConflictStatus = Get-HttpFailureStatus {
        Invoke-RestMethod `
            -Method Post `
            -Uri "$BaseUrl/api/v1/provider/inspection/service-orders/$orderId/delivery" `
            -ContentType 'application/json' `
            -Body ($changedPublish | ConvertTo-Json -Depth 8 -Compress) `
            -Headers @{
                Authorization = "Bearer $providerToken"
                'Idempotency-Key' = $publishKey
                'If-Match' = '"4"'
            }
    }

    $customerDelivery = Invoke-RestMethod `
        -Method Get `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/delivery" `
        -Headers @{ Authorization = "Bearer $customerToken" }
    $platformDelivery = Invoke-RestMethod `
        -Method Get `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/delivery" `
        -Headers @{ Authorization = "Bearer $platformToken" }
    $otherCustomerReadStatus = Get-HttpFailureStatus {
        Invoke-RestMethod `
            -Method Get `
            -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/delivery" `
            -Headers @{ Authorization = "Bearer $otherCustomerToken" }
    }

    $firstFile = $published.files[0]
    $download = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/delivery-files/$($firstFile.fileId)/download-intent" `
        -Headers @{
            Authorization = "Bearer $customerToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        }
    $downloadPath = Join-Path $tempRoot 'downloaded.zip'
    Invoke-WebRequest `
        -UseBasicParsing `
        -Uri $download.downloadUrl `
        -OutFile $downloadPath
    if ((Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash `
            -ne (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash) {
        throw 'The controlled file download did not match the private MinIO object.'
    }

    $bundle = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/delivery-files/download-all-intent" `
        -Headers @{
            Authorization = "Bearer $customerToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        }
    $bundlePath = Join-Path $tempRoot 'delivery-all.zip'
    Invoke-WebRequest `
        -UseBasicParsing `
        -Uri $bundle.downloadUrl `
        -OutFile $bundlePath
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($bundlePath)
    try {
        $bundleEntryCount = $archive.Entries.Count
    } finally {
        $archive.Dispose()
    }

    $platformDownload = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/delivery-files/$($firstFile.fileId)/download-intent" `
        -Headers @{
            Authorization = "Bearer $platformToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        }
    $platformAcceptStatus = Get-HttpFailureStatus {
        Invoke-RestMethod `
            -Method Post `
            -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/acceptance" `
            -ContentType 'application/json' `
            -Body '{"confirmed":true}' `
            -Headers @{
                Authorization = "Bearer $platformToken"
                'Idempotency-Key' = [Guid]::NewGuid().ToString()
                'If-Match' = '"5"'
            }
    }

    $acceptKey = [Guid]::NewGuid().ToString()
    $acceptBody = '{"confirmed":true,"note":"delivery accepted in F07 smoke"}'
    $accepted = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/acceptance" `
        -ContentType 'application/json' `
        -Body $acceptBody `
        -Headers @{
            Authorization = "Bearer $customerToken"
            'Idempotency-Key' = $acceptKey
            'If-Match' = '"5"'
            'X-Request-Id' = 'f07-smoke-accept'
        }
    $acceptReplay = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/acceptance" `
        -ContentType 'application/json' `
        -Body $acceptBody `
        -Headers @{
            Authorization = "Bearer $customerToken"
            'Idempotency-Key' = $acceptKey
            'If-Match' = '"5"'
            'X-Request-Id' = 'f07-smoke-accept-replay'
        }

    $acceptedOrder = Invoke-RestMethod `
        -Method Get `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId" `
        -Headers @{ Authorization = "Bearer $customerToken" }
    $acceptedDelivery = Invoke-RestMethod `
        -Method Get `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/delivery" `
        -Headers @{ Authorization = "Bearer $customerToken" }

    if ($published.id -ne $publishReplay.id `
            -or $published.status -ne 'PUBLISHED' `
            -or $published.files.Count -ne 4 `
            -or $publishConflictStatus -ne 409) {
        throw 'Delivery publication or idempotency is inconsistent.'
    }
    if ($customerDelivery.id -ne $published.id `
            -or $platformDelivery.id -ne $published.id `
            -or $otherCustomerReadStatus -ne 404) {
        throw 'Delivery ownership authorization failed closed.'
    }
    if ($bundleEntryCount -ne 4 `
            -or [string]::IsNullOrWhiteSpace($platformDownload.downloadUrl) `
            -or $platformAcceptStatus -ne 403) {
        throw 'Controlled downloads or platform read-only authorization failed.'
    }
    if ($accepted.id -ne $acceptReplay.id `
            -or $acceptedOrder.status -ne 'ACCEPTED' `
            -or $acceptedOrder.version -ne 6 `
            -or $acceptedDelivery.status -ne 'ACCEPTED') {
        throw 'Acceptance state or idempotency is inconsistent.'
    }

    $databaseCheckSql = @"
SELECT
    (SELECT count(*) FROM inspection_delivery WHERE service_order_id = '$orderId') || '|' ||
    (SELECT count(*) FROM inspection_delivery_file df JOIN inspection_delivery d ON d.id = df.delivery_id WHERE d.service_order_id = '$orderId') || '|' ||
    (SELECT count(*) FROM inspection_acceptance WHERE service_order_id = '$orderId') || '|' ||
    (SELECT status FROM inspection_service_order WHERE id = '$orderId') || '|' ||
    (SELECT version FROM inspection_service_order WHERE id = '$orderId');
"@
    $databaseCheck = (
        & $DockerPath exec inspection-local-postgres-1 `
            psql -At -U $databaseUser -d $databaseName -c $databaseCheckSql
    ).Trim()
    if ($LASTEXITCODE -ne 0 -or $databaseCheck -ne '1|4|1|ACCEPTED|6') {
        throw "Unexpected persisted delivery evidence: $databaseCheck"
    }

    Invoke-Compose -Arguments @('restart', 'backend')
    Wait-BackendHealth
    $customerTokenAfterRestart = Get-AccessToken -LoginName 'smoke-customer'
    $afterRestart = Invoke-RestMethod `
        -Method Get `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/delivery" `
        -Headers @{ Authorization = "Bearer $customerTokenAfterRestart" }
    $downloadAfterRestart = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/delivery-files/$($firstFile.fileId)/download-intent" `
        -Headers @{
            Authorization = "Bearer $customerTokenAfterRestart"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        }
    if ($afterRestart.status -ne 'ACCEPTED' `
            -or [string]::IsNullOrWhiteSpace($downloadAfterRestart.downloadUrl)) {
        throw 'Delivery state or private file access did not survive backend restart.'
    }

    [pscustomobject]@{
        ServiceOrderId = $orderId
        DeliveryId = $published.id
        PublishReplaySameId = $true
        PublishConflictStatus = $publishConflictStatus
        CrossCustomerReadStatus = $otherCustomerReadStatus
        PlatformReadOnlyStatus = $platformAcceptStatus
        PrivateDownloadVerified = $true
        BundleEntries = $bundleEntryCount
        AcceptanceReplaySameId = $true
        DatabaseEvidence = $databaseCheck
        RestartRecovery = $true
    } | ConvertTo-Json -Compress
} finally {
    $resolvedTemp = [IO.Path]::GetFullPath($tempRoot)
    if ($resolvedTemp.StartsWith($systemTemp, [StringComparison]::OrdinalIgnoreCase) `
            -and [IO.Path]::GetFileName($resolvedTemp).StartsWith(
                'inspection-f07-',
                [StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}
