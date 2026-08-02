[CmdletBinding()]
param(
    [string]$DockerPath = 'D:\DevTools\DockerDesktop\resources\bin\docker.exe',
    [string]$EnvFile,
    [string]$ComposeFile,
    [string]$InventoryPath,
    [switch]$PlanOnly,
    [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$expectedSize = 4720
$expectedSha256 = '04e84d40b68d21d7aff38fdeaa46d1ae26b8377da31600d35bd7f2c8f718af8a'
$legacySize = 153
$legacySha256 = '14b7c23ac7696b40cc4c03ade6713aa4e02e1a365c76d7a5709909c319fc480c'

function Get-DotEnvValue {
    param([string]$Path, [string]$Name)
    $prefix = "$Name="
    $line = Get-Content -LiteralPath $Path | Where-Object {
        $_.StartsWith($prefix, [StringComparison]::Ordinal)
    } | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($line)) { throw "$Name is missing from .env." }
    return $line.Substring($prefix.Length).Trim()
}

function Invoke-PostgresScalar {
    param([string]$Sql)
    $result = & $DockerPath compose --env-file $EnvFile -f $ComposeFile `
        exec -T postgres psql -qAt -U $script:databaseUser -d $script:databaseName -c $Sql
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL command failed with exit code $LASTEXITCODE." }
    return ($result | Out-String).Trim()
}

function Get-LiveInventory {
    $sql = @"
SELECT json_build_object(
  'Environment', '$script:environmentName',
  'Source', (SELECT row_to_json(s) FROM (
    SELECT o.id AS "ServiceOrderId", d.id AS "DeliveryId", fo.id AS "FileId",
           fo.bucket_name AS "BucketName", fo.object_key AS "ObjectKey",
           fo.size_bytes AS "SizeBytes", fo.sha256 AS "Sha256"
    FROM inspection_delivery_file df
    JOIN inspection_delivery d ON d.id = df.delivery_id
    JOIN inspection_service_order o ON o.id = d.service_order_id
    JOIN identity_user u ON u.id = o.customer_id AND u.login_name = 'smoke-customer'
    JOIN inspection_file_object fo ON fo.id = df.file_id
    WHERE df.file_type = 'DEFECT_LIST' AND fo.file_status = 'READY'
      AND fo.size_bytes = $expectedSize AND fo.sha256 = '$expectedSha256'
    ORDER BY fo.created_at DESC LIMIT 1
  ) s),
  'Candidates', COALESCE((SELECT json_agg(row_to_json(c)) FROM (
    SELECT o.provider_id AS "ProviderId", o.id AS "ServiceOrderId", d.id AS "DeliveryId",
           fo.id AS "FileId", fo.bucket_name AS "BucketName", fo.object_key AS "ObjectKey",
           fo.size_bytes AS "SizeBytes", fo.sha256 AS "Sha256",
           image_fo.sha256 AS "MatchingImageSha256"
    FROM inspection_delivery_file df
    JOIN inspection_delivery d ON d.id = df.delivery_id
    JOIN inspection_service_order o ON o.id = d.service_order_id
    JOIN identity_user u ON u.id = o.customer_id AND u.login_name = 'smoke-customer'
    JOIN inspection_file_object fo ON fo.id = df.file_id
    JOIN inspection_delivery_file image_df ON image_df.delivery_id = d.id AND image_df.file_type = 'IMAGE_ARCHIVE'
    JOIN inspection_file_object image_fo ON image_fo.id = image_df.file_id
    WHERE df.file_type = 'DEFECT_LIST' AND fo.file_status = 'READY'
      AND fo.size_bytes = $legacySize AND fo.sha256 = '$legacySha256'
      AND fo.size_bytes = image_fo.size_bytes AND fo.sha256 = image_fo.sha256
    ORDER BY fo.created_at
  ) c), '[]'::json)
)::text;
"@
    return (Invoke-PostgresScalar $sql) | ConvertFrom-Json
}

function New-RepairPlan {
    param($Inventory)
    if ($Inventory.Environment -notin @('dev', 'development')) {
        throw 'Legacy F07 artifact repair is restricted to the dev environment.'
    }
    if ($null -eq $Inventory.Source `
            -or [long]$Inventory.Source.SizeBytes -ne $expectedSize `
            -or $Inventory.Source.Sha256 -ne $expectedSha256) {
        throw 'The verified 4720-byte OpenXML source object is unavailable.'
    }
    $repairs = foreach ($candidate in @($Inventory.Candidates)) {
        if ([long]$candidate.SizeBytes -ne $legacySize `
                -or $candidate.Sha256 -ne $legacySha256 `
                -or $candidate.MatchingImageSha256 -ne $legacySha256 `
                -or $candidate.BucketName -ne $Inventory.Source.BucketName) {
            throw "Candidate $($candidate.FileId) does not match the legacy F07 signature."
        }
        foreach ($value in @($candidate.ProviderId, $candidate.ServiceOrderId, $candidate.DeliveryId,
                $candidate.FileId, $candidate.BucketName, $candidate.ObjectKey)) {
            if ($value -notmatch '^[A-Za-z0-9._/-]+$') { throw 'Unsafe object repair identifier.' }
        }
        [pscustomobject]@{
            ProviderId = $candidate.ProviderId
            ServiceOrderId = $candidate.ServiceOrderId
            DeliveryId = $candidate.DeliveryId
            FileId = $candidate.FileId
            BucketName = $candidate.BucketName
            ObjectKey = $candidate.ObjectKey
            BackupObjectKey = "repair-backup/f07/$($candidate.FileId)-legacy.xlsx"
            BundleObjectKey = "tenant/$($candidate.ProviderId)/order/$($candidate.ServiceOrderId)/bundle/$($candidate.DeliveryId).zip"
        }
    }
    return [pscustomobject]@{ Environment = $Inventory.Environment; Source = $Inventory.Source; Repairs = @($repairs) }
}

function Invoke-MinioRepair {
    param($Source, $Repair, [switch]$Restore)
    $sourceKey = if ($Restore) { $Repair.BackupObjectKey } else { $Source.ObjectKey }
    $targetKey = $Repair.ObjectKey
    $operations = if ($Restore) {
        'mc cp --preserve "local/{0}/{1}" "local/{0}/{2}"' -f $Repair.BucketName, $sourceKey, $targetKey
    } else {
        ('mc cp --preserve "local/{0}/{1}" "local/{0}/{2}"; ' +
         'mc cp --preserve "local/{0}/{3}" "local/{0}/{1}"; ' +
         'if mc stat "local/{0}/{4}" >/dev/null 2>&1; then mc rm --force "local/{0}/{4}"; fi') -f $Repair.BucketName, $targetKey,
            $Repair.BackupObjectKey, $Source.ObjectKey, $Repair.BundleObjectKey
    }
    $command = 'mc alias set local "$INSPECTION_MINIO_ENDPOINT" "$INSPECTION_MINIO_ACCESS_KEY" "$INSPECTION_MINIO_SECRET_KEY" >/dev/null; ' + $operations
    & $DockerPath compose --env-file $EnvFile -f $ComposeFile run --rm --no-deps `
        --entrypoint /bin/sh minio-init -ec $command | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "MinIO repair command failed with exit code $LASTEXITCODE." }
}

function Get-MinioObjectEvidence {
    param([string]$BucketName, [string]$ObjectKey)
    $command = ('mc alias set local "$INSPECTION_MINIO_ENDPOINT" "$INSPECTION_MINIO_ACCESS_KEY" "$INSPECTION_MINIO_SECRET_KEY" >/dev/null; ' +
        'mc cat "local/{0}/{1}" | tee /tmp/f07-object | sha256sum; wc -c </tmp/f07-object') -f $BucketName, $ObjectKey
    $output = & $DockerPath compose --env-file $EnvFile -f $ComposeFile run --rm --no-deps `
        --entrypoint /bin/sh minio-init -ec $command
    if ($LASTEXITCODE -ne 0) { throw "MinIO evidence command failed with exit code $LASTEXITCODE." }
    $hashLine = $output | Where-Object { $_ -match '^[a-f0-9]{64}\s' } | Select-Object -First 1
    $sizeLine = $output | Where-Object { $_ -match '^\d+$' } | Select-Object -Last 1
    return [pscustomobject]@{
        Sha256 = ([regex]::Match($hashLine, '^[a-f0-9]{64}')).Value
        SizeBytes = [long]$sizeLine
    }
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($EnvFile)) { $EnvFile = Join-Path $repositoryRoot '.env' }
if ([string]::IsNullOrWhiteSpace($ComposeFile)) { $ComposeFile = Join-Path $repositoryRoot 'infra\compose.yaml' }
if ($Apply -and $PlanOnly) { throw 'Choose either -PlanOnly or -Apply.' }

if ([string]::IsNullOrWhiteSpace($InventoryPath)) {
    if (-not (Test-Path -LiteralPath $DockerPath)) { $DockerPath = (Get-Command docker -ErrorAction Stop).Source }
    $script:environmentName = Get-DotEnvValue $EnvFile 'INSPECTION_ENVIRONMENT'
    $script:databaseUser = Get-DotEnvValue $EnvFile 'INSPECTION_DB_USERNAME'
    $script:databaseName = Get-DotEnvValue $EnvFile 'INSPECTION_DB_NAME'
    $inventory = Get-LiveInventory
} else {
    $inventory = Get-Content -LiteralPath $InventoryPath -Raw | ConvertFrom-Json
}
$plan = New-RepairPlan $inventory
if (-not $Apply) { return $plan }
if ([string]::IsNullOrWhiteSpace($InventoryPath) -eq $false) {
    throw 'Applying a repair from a supplied inventory file is prohibited.'
}
$sourceEvidence = Get-MinioObjectEvidence `
    -BucketName $plan.Source.BucketName `
    -ObjectKey $plan.Source.ObjectKey
if ($sourceEvidence.SizeBytes -ne $expectedSize -or $sourceEvidence.Sha256 -ne $expectedSha256) {
    throw 'The source MinIO object no longer matches the verified OpenXML workbook.'
}

foreach ($repair in $plan.Repairs) {
    Invoke-MinioRepair -Source $plan.Source -Repair $repair
    try {
        $targetEvidence = Get-MinioObjectEvidence `
            -BucketName $repair.BucketName `
            -ObjectKey $repair.ObjectKey
        if ($targetEvidence.SizeBytes -ne $expectedSize -or $targetEvidence.Sha256 -ne $expectedSha256) {
            throw 'The repaired MinIO object does not match the verified OpenXML workbook.'
        }
        $updatedId = Invoke-PostgresScalar @"
UPDATE inspection_file_object
SET size_bytes = $expectedSize, sha256 = '$expectedSha256', updated_at = now(), version = version + 1
WHERE id = '$($repair.FileId)' AND size_bytes = $legacySize AND sha256 = '$legacySha256'
RETURNING id;
"@
        if ($updatedId -ne $repair.FileId) { throw 'The guarded metadata update did not affect exactly the planned file.' }
    } catch {
        Invoke-MinioRepair -Source $plan.Source -Repair $repair -Restore
        throw
    }
}

return [pscustomobject]@{ Applied = $true; SourceSha256 = $expectedSha256; Repairs = $plan.Repairs }
