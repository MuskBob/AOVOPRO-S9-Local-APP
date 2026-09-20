$ErrorActionPreference = 'Stop'
$taskTools = $PSScriptRoot
$packages = @(
    @{Name='platform'; Url='https://dl.google.com/android/repository/platform-36_r02.zip'; Hash='2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576'; Algo='SHA1'},
    @{Name='build-tools'; Url='https://dl.google.com/android/repository/build-tools_r36_windows.zip'; Hash='f16ccffd34de8790dede813a6c7d8e2c11a27b50'; Algo='SHA1'},
    @{Name='platform-tools'; Url='https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip'; Hash='e03e78b1d80b396f1c3358e31251cb31740e1110'; Algo='SHA1'}
)
foreach ($p in $packages) {
    $archive = Join-Path $taskTools ($p.Name + '.zip')
    if (!(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm $p.Algo).Hash -ne $p.Hash) {
        Write-Output ('Downloading ' + $p.Name)
        & curl.exe --fail --location --retry 3 --connect-timeout 30 --max-time 600 --silent --show-error $p.Url --output $archive
        if ($LASTEXITCODE -ne 0) { throw ('Download failed: ' + $p.Name) }
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm $p.Algo).Hash -ne $p.Hash) { throw ('Checksum mismatch: ' + $p.Name) }
    $destination = Join-Path $taskTools $p.Name
    if (!(Test-Path -LiteralPath (Join-Path $destination '.ready'))) {
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        & tar.exe -xf $archive -C $destination
        if ($LASTEXITCODE -ne 0) { throw ('Extraction failed: ' + $p.Name) }
        Set-Content -LiteralPath (Join-Path $destination '.ready') -Value $p.Hash
    }
    Write-Output ($p.Name + ': verified and ready')
}
Write-Output 'Downloading Microsoft OpenJDK 17'
$jdkArchive = Join-Path $taskTools 'microsoft-jdk.zip'
$jdkChecksum = Join-Path $taskTools 'microsoft-jdk.sha256'
& curl.exe --fail --location --retry 2 --connect-timeout 30 --max-time 60 --silent --show-error 'https://aka.ms/download-jdk/microsoft-jdk-17.0.20.1-windows-x64.zip.sha256sum.txt' --output $jdkChecksum
if ($LASTEXITCODE -ne 0) { throw 'Cannot download JDK checksum' }
$jdkHash = ((Get-Content -LiteralPath $jdkChecksum -Raw).Trim() -split '\s+')[0]
if ($jdkHash -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid JDK checksum' }
if (!(Test-Path -LiteralPath $jdkArchive) -or (Get-FileHash -LiteralPath $jdkArchive -Algorithm SHA256).Hash -ne $jdkHash) {
    & curl.exe --fail --location --retry 2 --connect-timeout 30 --max-time 900 --silent --show-error 'https://aka.ms/download-jdk/microsoft-jdk-17.0.20.1-windows-x64.zip' --output $jdkArchive
    if ($LASTEXITCODE -ne 0) { throw 'JDK download failed' }
}
if ((Get-FileHash -LiteralPath $jdkArchive -Algorithm SHA256).Hash -ne $jdkHash) { throw 'JDK checksum mismatch' }
$jdkDirectory = Join-Path $taskTools 'jdk'
New-Item -ItemType Directory -Force -Path $jdkDirectory | Out-Null
& tar.exe -xf $jdkArchive -C $jdkDirectory
if ($LASTEXITCODE -ne 0) { throw 'JDK extraction failed' }
Write-Output 'JDK: verified and ready'
