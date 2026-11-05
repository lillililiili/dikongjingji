param(
    [string]$DockerPath = 'D:\DevTools\DockerDesktop\resources\bin\docker.exe',
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [switch]$ValidateArtifactsOnly,
    [string]$XlsxPath,
    [string]$BundlePath
)

$ErrorActionPreference = 'Stop'

function Get-XlsxOpenXmlEvidence {
    param([byte[]]$Bytes, [string]$Label)

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $requiredEntries = @(
        '[Content_Types].xml',
        '_rels/.rels',
        'xl/workbook.xml',
        'xl/_rels/workbook.xml.rels',
        'xl/worksheets/sheet1.xml'
    )
    $stream = [IO.MemoryStream]::new($Bytes, $false)
    try {
        try {
            $archive = [IO.Compression.ZipArchive]::new(
                $stream,
                [IO.Compression.ZipArchiveMode]::Read,
                $true)
        } catch {
            throw "$Label is not a readable OpenXML ZIP package."
        }
        try {
            $entryNames = @($archive.Entries | ForEach-Object { $_.FullName })
            foreach ($requiredEntry in $requiredEntries) {
                if ($entryNames -notcontains $requiredEntry) {
                    throw "$Label is missing required OpenXML entry '$requiredEntry'."
                }
            }
            $contentTypesEntry = $archive.GetEntry('[Content_Types].xml')
            $reader = [IO.StreamReader]::new($contentTypesEntry.Open(), [Text.Encoding]::UTF8)
            try { $contentTypes = $reader.ReadToEnd() } finally { $reader.Dispose() }
            if ($contentTypes -notmatch 'spreadsheetml\.sheet\.main\+xml') {
                throw "$Label does not declare an XLSX workbook content type."
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $stream.Dispose()
    }

    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $sha256 = [BitConverter]::ToString($hasher.ComputeHash($Bytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        $hasher.Dispose()
    }
    return [pscustomobject]@{ SizeBytes = $Bytes.Length; Sha256 = $sha256 }
}

function Get-XlsxOpenXmlFileEvidence {
    param([string]$Path, [string]$Label)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label does not exist: $Path"
    }
    return Get-XlsxOpenXmlEvidence -Bytes ([IO.File]::ReadAllBytes($Path)) -Label $Label
}

function Get-BundleXlsxEvidence {
    param([string]$Path, [string]$EntryName = 'defect-list.xlsx')

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $matches = @($archive.Entries | Where-Object { $_.FullName -eq $EntryName })
        if ($matches.Count -ne 1) {
            throw "Delivery bundle must contain exactly one '$EntryName' entry."
        }
        $memory = [IO.MemoryStream]::new()
        try {
            $entryStream = $matches[0].Open()
            try { $entryStream.CopyTo($memory) } finally { $entryStream.Dispose() }
            $xlsxEvidence = Get-XlsxOpenXmlEvidence -Bytes $memory.ToArray() -Label "Bundle entry $EntryName"
        } finally {
            $memory.Dispose()
        }
        return [pscustomobject]@{
            EntryCount = $archive.Entries.Count
            XlsxSha256 = $xlsxEvidence.Sha256
            XlsxSizeBytes = $xlsxEvidence.SizeBytes
        }
    } finally {
        $archive.Dispose()
    }
}

if ($ValidateArtifactsOnly) {
    $xlsxEvidence = Get-XlsxOpenXmlFileEvidence -Path $XlsxPath -Label 'XLSX file'
    $bundleEvidence = if ([string]::IsNullOrWhiteSpace($BundlePath)) {
        $null
    } else {
        Get-BundleXlsxEvidence -Path $BundlePath
    }
    return [pscustomobject]@{
        XlsxSha256 = $xlsxEvidence.Sha256
        XlsxSizeBytes = $xlsxEvidence.SizeBytes
        BundleXlsxSha256 = $bundleEvidence.XlsxSha256
        BundleXlsxSizeBytes = $bundleEvidence.XlsxSizeBytes
    }
}

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
        [string]$VisitId,
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
            ownerId = $VisitId
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

$reservationIdForCleanup = $null
try {
Wait-BackendHealth
$f06Json = & "$PSScriptRoot\verify-f06-order-smoke.ps1" `
    -DockerPath $DockerPath `
    -BaseUrl $BaseUrl `
    -DeferCleanup |
    Select-Object -Last 1
$f06 = $f06Json | ConvertFrom-Json
$reservationIdForCleanup = [Guid]::Parse($f06.ReservationId)
Wait-BackendHealth

$customerToken = Get-AccessToken -LoginName 'smoke-customer'
$otherCustomerToken = Get-AccessToken -LoginName 'smoke-other'
$providerToken = Get-AccessToken -LoginName 'smoke-provider'
$platformToken = Get-AccessToken -LoginName 'smoke-platform'
$orderId = $f06.ServiceOrderId
$visitId = $f06.VisitId
$reservationDuringDeliveryFlow = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/reservations/$($f06.ReservationId)" `
    -Headers @{ Authorization = "Bearer $customerToken" }
if ($reservationDuringDeliveryFlow.status -ne 'PLAN_PUBLISHED') {
    throw 'F06 reservation was cleaned before the delivery smoke took ownership.'
}

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
    # Real OpenXML workbook fixture rendered and verified with artifact-tool.
    # The hash guard prevents a truncated or non-XLSX payload reaching MinIO.
    $xlsxFixtureBase64 = @'
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
o+o7VVBtYaU6BIPVoHdOwOoHHDc/loPVqtE/x3FRD855nJavBtcVbL+8uKMX+uZL0O0YzSaOizscO/pkr3+BpaUh4puO+x/ScT/K
j94c6CBajPiwrUZL2dGtDNolY/WjWduEy++xrfbg9O6+3tsDZ5tG49TcfwfO9kD91Ljugpsm/NzQT66x/fdIBvdfgsY2QQexzffa
QecClI+N/hasH8B2Ga59MJoNsPoaa4TTKG7UwJsy8nC8F4GHeBFAJfxDXjBYL9BS1j+pFx4cOhzMUs1cegM+l8w/TuHhllmrG619
/Z8uWKoa/S2sHR75HDt83JTPj3XEgwPNNbjxWt84B/UOOKrAwza4+jLoN8Haf1hTnI4Fvs+U4ENMCaISwSFTsO/EJ87SR6O7KV/A
i1OzcYV1xAMyV7ZAuWX0/4Ybx+DoBWz/CyorZr0E/1zSu2fGyTL4dIB1xCOf4wiDfbRjHtCg/Qp+bKB3m7lS0ZevLFMuX2G9QDk4
erSwvWHUA6wd1MhnjyQoaWFeyKIvIndEKELK+tAJWd8Q9qEzOsWErOMXO8WGrPMPNxVlQ9a5hU6s4dJ5Pi38witpMacSWSGlhUl6
OkASCrpH7GtNzttXHEk8kzVNlr6OMgKfFBRrxJJESpY1d4Aquf8eIv8DUEsDBBQAAAAAAJd9Al0gyG1EKAEAACgBAAALAAAAX3Jl
bHMvLnJlbHPvu788P3htbCB2ZXJzaW9uPSIxLjAiIGVuY29kaW5nPSJ1dGYtOCI/PjxSZWxhdGlvbnNoaXBzIHhtbG5zPSJodHRw
Oi8vc2NoZW1hcy5vcGVueG1sZm9ybWF0cy5vcmcvcGFja2FnZS8yMDA2L3JlbGF0aW9uc2hpcHMiPjxSZWxhdGlvbnNoaXAgVHlw
ZT0iaHR0cDovL3NjaGVtYXMub3BlbnhtbGZvcm1hdHMub3JnL29mZmljZURvY3VtZW50LzIwMDYvcmVsYXRpb25zaGlwcy9vZmZp
Y2VEb2N1bWVudCIgVGFyZ2V0PSIveGwvd29ya2Jvb2sueG1sIiBJZD0iUjEzNzlkMGRhZTFhNzQwNTkiIC8+PC9SZWxhdGlvbnNo
aXBzPlBLAwQUAAAACACXfQJdIpBR4Q8BAADyAgAAGgAAAHhsL19yZWxzL3dvcmtib29rLnhtbC5yZWxztZI9TgMxEEavYrlnbROD
vSibNDS0IRcw9ni9in9WtgObs1FwJK6ACAjtIgqaNFN8Iz29+TTvr2/r7RQ8eoZchhQ7zBqKEUSdzBD7Dh+rvZJ4u1nvwKs6pFjc
MBY0BR9Lh12t4x0hRTsIqjRphDgFb1MOqpYm5Z6MSh9UD+Sa0luS5wy8ZKL9aYT/EJO1g4b7pI8BYv0DTEo9eSgY7VXuoXaYTP47
a6bgMXowHd4pqaS5oVZq9sTblcWIXEyoOgiw9DlHX5PNrFZCtJyJVikmODVwSaviVAbzWPMQ+99tzVczPSqE1VYqIxjnhqlL6r2k
fCgOoC7VfuLPAwDqvD0OtLUCOJfScqHbsx5ZfO7mA1BLAwQUAAAACACXfQJdjYLZqRYBAABTAwAAEwAAAFtDb250ZW50X1R5cGVz
XS54bWytk0FOwzAQRa8SeYtqpywQQkm7ALaABBewnEli1R5bnmlIz8aCI3EFVAdFgJAi1G48m/F7/y/m4+292o7eFQMksgFrsZal
KABNaCx2tdhzu7oW2031cohAxegdUi165nijFJkevCYZIuDoXRuS10wypE5FbXa6A3VZllfKBGRAXvGRITbVHbR677i4Hxlw0o7e
ieJ22juqaqFjdNZotgHVgM0vySq0rTXQBLP3gCwpJtAN9QDsncxTem3xIoPVn84Ejv4n/WolE7i8Q72NNCseB0jJNlA86cQP2kMt
1OgU8cEByTM3zNAlNffgYXrXJwfImMWyvU7QPHOy2J2983f2UpDXkHb5I6k8Tu//M8zMn4OofCKbT1BLAQIUAxQAAAAIAJZ9Al0C
5jre1AAAACoBAAAPAAAAAAAAAAAAAACkgQAAAAB4bC93b3JrYm9vay54bWxQSwECFAMUAAAACACWfQJdTnkYkC0DAABWNQAADQAA
AAAAAAAAAAAApIEBAQAAeGwvc3R5bGVzLnhtbFBLAQIUAxQAAAAIAJd9Al36XAFZAwMAANoNAAATAAAAAAAAAAAAAACkgVkEAAB4
bC90aGVtZS90aGVtZTEueG1sUEsBAhQDFAAAAAgAl30CXQ0euehlAAAAcwAAABQAAAAAAAAAAAAAAKSBjQcAAHhsL3NoYXJlZFN0
cmluZ3MueG1sUEsBAhQDFAAAAAgAl30CXdrawyweBAAAcQwAABgAAAAAAAAAAAAAAKSBJAgAAHhsL3dvcmtzaGVldHMvc2hlZXQx
LnhtbFBLAQIUAxQAAAAAAJd9Al0gyG1EKAEAACgBAAALAAAAAAAAAAAAAACkgXgMAABfcmVscy8ucmVsc1BLAQIUAxQAAAAIAJd9
Al0ikFHhDwEAAPICAAAaAAAAAAAAAAAAAACkgckNAAB4bC9fcmVscy93b3JrYm9vay54bWwucmVsc1BLAQIUAxQAAAAIAJd9Al2N
gtmpFgEAAFMDAAATAAAAAAAAAAAAAACkgRAPAABbQ29udGVudF9UeXBlc10ueG1sUEsFBgAAAAAIAAgAAwIAAFcQAAAAAA==
'@
    $xlsxBytes = [Convert]::FromBase64String($xlsxFixtureBase64)
    $xlsxHasher = [Security.Cryptography.SHA256]::Create()
    try {
        $xlsxSha256 = [BitConverter]::ToString(
            $xlsxHasher.ComputeHash($xlsxBytes)
        ).Replace('-', '').ToLowerInvariant()
    } finally {
        $xlsxHasher.Dispose()
    }
    if ($xlsxSha256 -ne '04e84d40b68d21d7aff38fdeaa46d1ae26b8377da31600d35bd7f2c8f718af8a') {
        throw 'Defect list XLSX fixture integrity check failed.'
    }
    [IO.File]::WriteAllBytes($xlsxPath, $xlsxBytes)
    $xlsxEvidence = Get-XlsxOpenXmlFileEvidence -Path $xlsxPath -Label 'Generated defect list'
    $kmlPath = Join-Path $tempRoot 'inspection-points.kml'
    [IO.File]::WriteAllText(
        $kmlPath,
        '<?xml version="1.0" encoding="UTF-8"?><kml><Document/></kml>',
        [Text.UTF8Encoding]::new($false))
    $pdfPath = Join-Path $tempRoot 'inspection-report.pdf'
    # Stable one-page fixture verified with pypdf and a rendered PNG. The hash guard
    # prevents an accidentally truncated or edited payload from reaching MinIO.
    $pdfFixtureBase64 = @'
JVBERi0xLjQKJZOMi54gUmVwb3J0TGFiIEdlbmVyYXRlZCBQREYgZG9jdW1lbnQgKG9wZW5zb3Vy
Y2UpCjEgMCBvYmoKPDwKL0YxIDIgMCBSIC9GMiAzIDAgUgo+PgplbmRvYmoKMiAwIG9iago8PAov
QmFzZUZvbnQgL0hlbHZldGljYSAvRW5jb2RpbmcgL1dpbkFuc2lFbmNvZGluZyAvTmFtZSAvRjEg
L1N1YnR5cGUgL1R5cGUxIC9UeXBlIC9Gb250Cj4+CmVuZG9iagozIDAgb2JqCjw8Ci9CYXNlRm9u
dCAvSGVsdmV0aWNhLUJvbGQgL0VuY29kaW5nIC9XaW5BbnNpRW5jb2RpbmcgL05hbWUgL0YyIC9T
dWJ0eXBlIC9UeXBlMSAvVHlwZSAvRm9udAo+PgplbmRvYmoKNCAwIG9iago8PAovQ29udGVudHMg
OCAwIFIgL01lZGlhQm94IFsgMCAwIDU5NS4yNzU2IDg0MS44ODk4IF0gL1BhcmVudCA3IDAgUiAv
UmVzb3VyY2VzIDw8Ci9Gb250IDEgMCBSIC9Qcm9jU2V0IFsgL1BERiAvVGV4dCAvSW1hZ2VCIC9J
bWFnZUMgL0ltYWdlSSBdCj4+IC9Sb3RhdGUgMCAvVHJhbnMgPDwKCj4+IAogIC9UeXBlIC9QYWdl
Cj4+CmVuZG9iago1IDAgb2JqCjw8Ci9QYWdlTW9kZSAvVXNlTm9uZSAvUGFnZXMgNyAwIFIgL1R5
cGUgL0NhdGFsb2cKPj4KZW5kb2JqCjYgMCBvYmoKPDwKL0F1dGhvciAoYW5vbnltb3VzKSAvQ3Jl
YXRpb25EYXRlIChEOjIwMDAwMTAxMDAwMDAwKzAwJzAwJykgL0NyZWF0b3IgKGFub255bW91cykg
L0tleXdvcmRzICgpIC9Nb2REYXRlIChEOjIwMDAwMTAxMDAwMDAwKzAwJzAwJykgL1Byb2R1Y2Vy
IChSZXBvcnRMYWIgUERGIExpYnJhcnkgLSBcKG9wZW5zb3VyY2VcKSkgCiAgL1N1YmplY3QgKHVu
c3BlY2lmaWVkKSAvVGl0bGUgKEluc3BlY3Rpb24gU2VydmljZSBSZXBvcnQpIC9UcmFwcGVkIC9G
YWxzZQo+PgplbmRvYmoKNyAwIG9iago8PAovQ291bnQgMSAvS2lkcyBbIDQgMCBSIF0gL1R5cGUg
L1BhZ2VzCj4+CmVuZG9iago4IDAgb2JqCjw8Ci9GaWx0ZXIgWyAvQVNDSUk4NURlY29kZSAvRmxh
dGVEZWNvZGUgXSAvTGVuZ3RoIDcwMQo+PgpzdHJlYW0KR2F0PWk7L2IvQidTWUg5LydkMDVGXWs2
UUVVcTBUOVBDUGNCXCRtWT1ebj4tP3E2NGJnVjk/b09xOilfJ25jX1IsK2o0cidDLnBBZmJcQVtv
LkJHbGBNWV1zbldCdD82OHE7WU1jbz5QRiEmLUpibCxxUkY5NDkjTVw3dC9QOE5PIm1oVzQzR10m
MXRHaTNscUVmTG08UFcldW1yXlFhWDNEU0xlS0xUcmwnaW5cNCEuLjVFNkJpTERbXCxxR3FKODhF
bl9nbyZVbjdQUVtcajYpYWVGay9rYCskbk87OHNYclFfRF5MPFFFTGg4aG90KEs2QzdaLzssOStl
Zk8sY20tSi5qai4tYGEuP1hGaCRdQGA0JnUtT1VzWjFuSCVDUjZAYFMuSzwxPDdZaGRcODtRdE9b
YTlDK0FkWVwnKU85L2xELlx0ZllTTlM5VThWXUohdTcoU1daPWUhaFlPZVw7VnB0WS4+PFciPz0n
Py1nJUY4YD9YUDJsSCMmUm5qXS48KjVIKT1UPmwjV1E/YkhcWWdeZzE5P1ZeNWhuVEREbXBeYDtH
KDxqX0ZNIVIvT09eMTE5OjAhUDxzM2hZdEBCPmZdN1FjJzxmaFY5b0hMR0c8Lzg3cVBHXDVNPnBO
J1syYz5MTzRKaSErJ1VjNVgyayshNlJjWjFkL0BqSVswaUImNUxeamdNRmRuKXA+YiI9ZUhKOlxc
WWJlPj8iM2NAdDJSMSFXQE4qXWE6PikvTlVgakFoQ181N3M4WFg0NnFAXiM7ZDo/Y05nLlxxOE1N
S2g8NSo9PiQ7Q048LSRXKFtgXzk/TiNmWUtGam1aZ10/LCwkaEEsYjttcyhoM1ZGY3AzM05sKydt
SmZLKEhXVmVYcjdJJ1M2OCVcJ1c0YWBePGpZRm5BPiw4OnFTakttS18jXWAiSihlfj5lbmRzdHJl
YW0KZW5kb2JqCnhyZWYKMCA5CjAwMDAwMDAwMDAgNjU1MzUgZiAKMDAwMDAwMDA2MSAwMDAwMCBu
IAowMDAwMDAwMTAyIDAwMDAwIG4gCjAwMDAwMDAyMDkgMDAwMDAgbiAKMDAwMDAwMDMyMSAwMDAw
MCBuIAowMDAwMDAwNTI0IDAwMDAwIG4gCjAwMDAwMDA1OTIgMDAwMDAgbiAKMDAwMDAwMDg3MCAw
MDAwMCBuIAowMDAwMDAwOTI5IDAwMDAwIG4gCnRyYWlsZXIKPDwKL0lEIApbPGE2ZWExOTc4YzE3
MjNhMjY2MDc4MDg0ODlkMmRkYmIxPjxhNmVhMTk3OGMxNzIzYTI2NjA3ODA4NDg5ZDJkZGJiMT5d
CiUgUmVwb3J0TGFiIGdlbmVyYXRlZCBQREYgZG9jdW1lbnQgLS0gZGlnZXN0IChvcGVuc291cmNl
KQoKL0luZm8gNiAwIFIKL1Jvb3QgNSAwIFIKL1NpemUgOQo+PgpzdGFydHhyZWYKMTcyMAolJUVP
Rgo=
'@
    $pdfBytes = [Convert]::FromBase64String($pdfFixtureBase64)
    $pdfHasher = [Security.Cryptography.SHA256]::Create()
    try {
        $pdfSha256 = [BitConverter]::ToString(
            $pdfHasher.ComputeHash($pdfBytes)
        ).Replace('-', '').ToLowerInvariant()
    } finally {
        $pdfHasher.Dispose()
    }
    if ($pdfSha256 -ne '4eda54e6a8e49fb3bd4f29058687c4f4ff8bb04058504a1b12b424fe2aa17f7f') {
        throw 'Inspection report PDF fixture integrity check failed.'
    }
    [IO.File]::WriteAllBytes($pdfPath, $pdfBytes)

    $deliveryFiles = @(
        (New-DeliveryFile `
            -Path $zipPath `
            -Type 'IMAGE_ARCHIVE' `
            -ContentType 'application/zip' `
            -DisplayName 'site-images.zip' `
            -Token $providerToken `
            -VisitId $visitId)
        (New-DeliveryFile `
            -Path $xlsxPath `
            -Type 'DEFECT_LIST' `
            -ContentType 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' `
            -DisplayName 'defect-list.xlsx' `
            -Token $providerToken `
            -VisitId $visitId)
        (New-DeliveryFile `
            -Path $kmlPath `
            -Type 'POINT_KML' `
            -ContentType 'application/vnd.google-earth.kml+xml' `
            -DisplayName 'inspection-points.kml' `
            -Token $providerToken `
            -VisitId $visitId `
            -PointCount 24)
        (New-DeliveryFile `
            -Path $pdfPath `
            -Type 'INSPECTION_REPORT' `
            -ContentType 'application/pdf' `
            -DisplayName 'inspection-report.pdf' `
            -Token $providerToken `
            -VisitId $visitId)
    )

    $publishObject = @{
        towerCount = 24
        inspectedLengthMeters = 18600
        issueCount = 3
        issueGroupBy = 'SEVERITY'
        issueSummary = @(
            @{ groupCode = 'GENERAL'; groupName = 'General issues'; count = 2 },
            @{ groupCode = 'WATCH'; groupName = 'Watch items'; count = 1 }
        )
        files = $deliveryFiles
    }
    $publishBody = $publishObject | ConvertTo-Json -Depth 8 -Compress
    $publishKey = [Guid]::NewGuid().ToString()
    $published = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/provider/inspection/service-orders/$orderId/visits/$visitId/delivery" `
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
        -Uri "$BaseUrl/api/v1/provider/inspection/service-orders/$orderId/visits/$visitId/delivery" `
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
            -Uri "$BaseUrl/api/v1/provider/inspection/service-orders/$orderId/visits/$visitId/delivery" `
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
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/delivery" `
        -Headers @{ Authorization = "Bearer $customerToken" }
    $platformDelivery = Invoke-RestMethod `
        -Method Get `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/delivery" `
        -Headers @{ Authorization = "Bearer $platformToken" }
    $otherCustomerReadStatus = Get-HttpFailureStatus {
        Invoke-RestMethod `
            -Method Get `
            -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/delivery" `
            -Headers @{ Authorization = "Bearer $otherCustomerToken" }
    }

    $firstFile = $published.files[0]
    $xlsxFile = @($published.files | Where-Object { $_.type -eq 'DEFECT_LIST' }) | Select-Object -First 1
    if ($null -eq $xlsxFile) {
        throw 'Published delivery is missing the DEFECT_LIST file.'
    }
    $download = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/delivery-files/$($firstFile.fileId)/download-intent" `
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

    $xlsxDownload = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/delivery-files/$($xlsxFile.fileId)/download-intent" `
        -Headers @{
            Authorization = "Bearer $customerToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        }
    $downloadedXlsxPath = Join-Path $tempRoot 'downloaded-defect-list.xlsx'
    Invoke-WebRequest -UseBasicParsing -Uri $xlsxDownload.downloadUrl -OutFile $downloadedXlsxPath
    $downloadedXlsxEvidence = Get-XlsxOpenXmlFileEvidence `
        -Path $downloadedXlsxPath `
        -Label 'Downloaded defect list'
    if ($downloadedXlsxEvidence.Sha256 -ne $xlsxEvidence.Sha256) {
        throw 'The downloaded defect list differs from the generated OpenXML workbook.'
    }

    $bundle = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/delivery-files/download-all-intent" `
        -Headers @{
            Authorization = "Bearer $customerToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        }
    $bundlePath = Join-Path $tempRoot 'delivery-all.zip'
    Invoke-WebRequest `
        -UseBasicParsing `
        -Uri $bundle.downloadUrl `
        -OutFile $bundlePath
    $bundleEvidence = Get-BundleXlsxEvidence -Path $bundlePath
    $bundleEntryCount = $bundleEvidence.EntryCount
    if ($bundleEvidence.XlsxSha256 -ne $xlsxEvidence.Sha256) {
        throw 'The bundle defect list differs from the generated OpenXML workbook.'
    }

    $platformDownload = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/delivery-files/$($xlsxFile.fileId)/download-intent" `
        -Headers @{
            Authorization = "Bearer $platformToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        }
    $platformAcceptStatus = Get-HttpFailureStatus {
        Invoke-RestMethod `
            -Method Post `
            -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/acceptance" `
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
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/acceptance" `
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
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/acceptance" `
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
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/delivery" `
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
            -or $acceptedOrder.version -ne 4 `
            -or $acceptedOrder.visits[0].status -ne 'ACCEPTED' `
            -or $acceptedOrder.visits[0].version -ne 6 `
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
    if ($LASTEXITCODE -ne 0 -or $databaseCheck -ne '1|4|1|ACCEPTED|4') {
        throw "Unexpected persisted delivery evidence: $databaseCheck"
    }

    Invoke-Compose -Arguments @('restart', 'backend')
    Wait-BackendHealth
    $customerTokenAfterRestart = Get-AccessToken -LoginName 'smoke-customer'
    $afterRestart = Invoke-RestMethod `
        -Method Get `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/delivery" `
        -Headers @{ Authorization = "Bearer $customerTokenAfterRestart" }
    $downloadAfterRestart = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/delivery-files/$($xlsxFile.fileId)/download-intent" `
        -Headers @{
            Authorization = "Bearer $customerTokenAfterRestart"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        }
    $restartXlsxPath = Join-Path $tempRoot 'downloaded-after-restart.xlsx'
    Invoke-WebRequest -UseBasicParsing -Uri $downloadAfterRestart.downloadUrl -OutFile $restartXlsxPath
    $restartXlsxEvidence = Get-XlsxOpenXmlFileEvidence `
        -Path $restartXlsxPath `
        -Label 'Downloaded defect list after restart'
    $bundleAfterRestart = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/service-orders/$orderId/visits/$visitId/delivery-files/download-all-intent" `
        -Headers @{
            Authorization = "Bearer $customerTokenAfterRestart"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
        }
    $bundleAfterRestartPath = Join-Path $tempRoot 'delivery-all-after-restart.zip'
    Invoke-WebRequest -UseBasicParsing -Uri $bundleAfterRestart.downloadUrl -OutFile $bundleAfterRestartPath
    $bundleAfterRestartEvidence = Get-BundleXlsxEvidence -Path $bundleAfterRestartPath
    if ($afterRestart.status -ne 'ACCEPTED' `
            -or $restartXlsxEvidence.Sha256 -ne $xlsxEvidence.Sha256 `
            -or $bundleAfterRestartEvidence.XlsxSha256 -ne $xlsxEvidence.Sha256) {
        throw 'Delivery state or private file access did not survive backend restart.'
    }

    [pscustomobject]@{
        ServiceOrderId = $orderId
        DeliveryId = $published.id
        ReservationActiveDuringDeliveryFlow = $true
        PublishReplaySameId = $true
        PublishConflictStatus = $publishConflictStatus
        CrossCustomerReadStatus = $otherCustomerReadStatus
        PlatformReadOnlyStatus = $platformAcceptStatus
        PrivateDownloadVerified = $true
        XlsxOpenXmlVerified = $true
        BundleEntries = $bundleEntryCount
        BundleXlsxVerified = $true
        AcceptanceReplaySameId = $true
        DatabaseEvidence = $databaseCheck
        RestartRecovery = $true
        RestartXlsxVerified = $true
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
} finally {
    if ($null -ne $reservationIdForCleanup) {
        & "$PSScriptRoot\verify-f05-plan-smoke.ps1" `
            -DockerPath $DockerPath `
            -BaseUrl $BaseUrl `
            -CleanupReservationId $reservationIdForCleanup
    }
}
