Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$smokeScript = Join-Path $PSScriptRoot 'verify-f07-delivery-smoke.ps1'
$repairScript = Join-Path $PSScriptRoot 'repair-f07-legacy-artifacts.ps1'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("inspection-f07-artifacts-" + [guid]::NewGuid())

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message Expected '$Expected', got '$Actual'."
    }
}

function New-ZipPackage {
    param([string]$Path, [hashtable]$Entries)

    Add-Type -AssemblyName System.IO.Compression
    $stream = [IO.File]::Create($Path)
    try {
        $archive = [IO.Compression.ZipArchive]::new(
            $stream,
            [IO.Compression.ZipArchiveMode]::Create,
            $false)
        try {
            foreach ($entryName in $Entries.Keys) {
                $entry = $archive.CreateEntry($entryName)
                $writer = [IO.StreamWriter]::new(
                    $entry.Open(),
                    [Text.UTF8Encoding]::new($false))
                try {
                    $writer.Write([string]$Entries[$entryName])
                } finally {
                    $writer.Dispose()
                }
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

try {
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
    $validXlsx = Join-Path $temporaryRoot 'valid.xlsx'
    New-ZipPackage -Path $validXlsx -Entries @{
        '[Content_Types].xml' = '<Types><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/></Types>'
        '_rels/.rels' = '<Relationships/>'
        'xl/workbook.xml' = '<workbook/>'
        'xl/_rels/workbook.xml.rels' = '<Relationships/>'
        'xl/worksheets/sheet1.xml' = '<worksheet/>'
    }
    $validHash = (Get-FileHash -LiteralPath $validXlsx -Algorithm SHA256).Hash.ToLowerInvariant()

    $bundle = Join-Path $temporaryRoot 'delivery-all.zip'
    Add-Type -AssemblyName System.IO.Compression
    $bundleStream = [IO.File]::Create($bundle)
    try {
        $bundleArchive = [IO.Compression.ZipArchive]::new(
            $bundleStream,
            [IO.Compression.ZipArchiveMode]::Create,
            $false)
        try {
            $entry = $bundleArchive.CreateEntry('defect-list.xlsx')
            $entryStream = $entry.Open()
            try {
                $xlsxStream = [IO.File]::OpenRead($validXlsx)
                try { $xlsxStream.CopyTo($entryStream) } finally { $xlsxStream.Dispose() }
            } finally {
                $entryStream.Dispose()
            }
        } finally {
            $bundleArchive.Dispose()
        }
    } finally {
        $bundleStream.Dispose()
    }

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $smokeScript `
        -ValidateArtifactsOnly `
        -XlsxPath $validXlsx `
        -BundlePath $bundle `
        -BaseUrl 'http://8.8.8.8' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Artifact validation failed in a clean PowerShell process.'
    }
    $validation = & $smokeScript `
        -ValidateArtifactsOnly `
        -XlsxPath $validXlsx `
        -BundlePath $bundle `
        -BaseUrl 'http://8.8.8.8'
    Assert-Equal $validation.XlsxSha256 $validHash 'Valid OpenXML hash was not reported.'
    Assert-Equal $validation.BundleXlsxSha256 $validHash 'Bundle XLSX bytes differ from the single file.'

    $invalidXlsx = Join-Path $temporaryRoot 'invalid.xlsx'
    New-ZipPackage -Path $invalidXlsx -Entries @{ 'source.txt' = 'not an xlsx workbook' }
    $invalidRejected = $false
    try {
        & $smokeScript `
            -ValidateArtifactsOnly `
            -XlsxPath $invalidXlsx `
            -BaseUrl 'http://8.8.8.8' | Out-Null
    } catch {
        $invalidRejected = $_.Exception.Message -match 'OpenXML'
    }
    if (-not $invalidRejected) {
        throw 'A ZIP renamed to .xlsx was not rejected.'
    }

    $inventoryPath = Join-Path $temporaryRoot 'inventory.json'
    @{
        Environment = 'development'
        Source = @{
            ServiceOrderId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
            DeliveryId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
            FileId = 'cccccccc-cccc-cccc-cccc-cccccccccccc'
            BucketName = 'development-inspection-private'
            ObjectKey = 'tenant/source/defect-list'
            SizeBytes = 4720
            Sha256 = '04e84d40b68d21d7aff38fdeaa46d1ae26b8377da31600d35bd7f2c8f718af8a'
        }
        Candidates = @(@{
            ProviderId = '11111111-1111-1111-1111-111111111111'
            ServiceOrderId = '22222222-2222-2222-2222-222222222222'
            DeliveryId = '33333333-3333-3333-3333-333333333333'
            FileId = '44444444-4444-4444-4444-444444444444'
            BucketName = 'development-inspection-private'
            ObjectKey = 'tenant/target/defect-list'
            SizeBytes = 153
            Sha256 = '14b7c23ac7696b40cc4c03ade6713aa4e02e1a365c76d7a5709909c319fc480c'
            MatchingImageSha256 = '14b7c23ac7696b40cc4c03ade6713aa4e02e1a365c76d7a5709909c319fc480c'
        })
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $inventoryPath -Encoding utf8

    $plan = & $repairScript -InventoryPath $inventoryPath -PlanOnly
    Assert-Equal @($plan.Repairs).Count 1 'Legacy repair plan did not contain the bad smoke workbook.'
    Assert-Equal $plan.Repairs[0].BundleObjectKey `
        'tenant/11111111-1111-1111-1111-111111111111/order/22222222-2222-2222-2222-222222222222/bundle/33333333-3333-3333-3333-333333333333.zip' `
        'Legacy bundle invalidation key is incorrect.'

    $fakeDocker = Join-Path $temporaryRoot 'fake-docker.ps1'
    @'
$DockerArgs = @($args)
$command = $DockerArgs -join ' '
if ($command -match 'SELECT json_build_object') {
    Write-Output $env:F07_FAKE_INVENTORY
} elseif ($command -match 'UPDATE inspection_file_object') {
    Write-Output '44444444-4444-4444-4444-444444444444'
    if ($DockerArgs -notcontains '-qAt') { Write-Output 'UPDATE 1' }
} elseif ($command -match 'mc cat') {
    Write-Output '04e84d40b68d21d7aff38fdeaa46d1ae26b8377da31600d35bd7f2c8f718af8a  -'
    Write-Output '4720'
} elseif ($command -match 'mc rm --force' -and $command -notmatch 'mc stat') {
    exit 1
}
exit 0
'@ | Set-Content -LiteralPath $fakeDocker -Encoding utf8
    $fakeEnv = Join-Path $temporaryRoot '.env'
    @(
        'INSPECTION_ENVIRONMENT=dev'
        'INSPECTION_DB_USERNAME=test'
        'INSPECTION_DB_NAME=test'
    ) | Set-Content -LiteralPath $fakeEnv -Encoding utf8
    $fakeCompose = Join-Path $temporaryRoot 'compose.yaml'
    'services: {}' | Set-Content -LiteralPath $fakeCompose -Encoding utf8
    $env:F07_FAKE_INVENTORY = Get-Content -LiteralPath $inventoryPath -Raw
    $applyResult = & $repairScript `
        -DockerPath $fakeDocker `
        -EnvFile $fakeEnv `
        -ComposeFile $fakeCompose `
        -Apply
    Assert-Equal $applyResult.Applied $true 'Guarded repair did not complete with quiet tuple output.'

    Write-Output 'F07 delivery artifact tests passed.'
} finally {
    [Environment]::SetEnvironmentVariable('F07_FAKE_INVENTORY', $null, 'Process')
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
