$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$jdkExe = Get-ChildItem -LiteralPath (Join-Path $taskRoot 'tools\jdk') -Filter javac.exe -Recurse | Select-Object -First 1
$aaptExe = Get-ChildItem -LiteralPath (Join-Path $taskRoot 'tools\build-tools') -Filter aapt2.exe -Recurse | Select-Object -First 1
$androidJar = Get-ChildItem -LiteralPath (Join-Path $taskRoot 'tools\platform') -Filter android.jar -Recurse | Select-Object -First 1
if (!$jdkExe -or !$aaptExe -or !$androidJar) { throw 'Run tools/bootstrap.ps1 to download the build tools first.' }
$javaBin = $jdkExe.Directory.FullName
$buildTools = $aaptExe.Directory.FullName
$build = Join-Path $taskRoot ('build\manual-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$dist = Join-Path $taskRoot 'dist'
$source = Join-Path $taskRoot 'app\src\main'
New-Item -ItemType Directory -Force -Path $build,$dist,(Join-Path $build 'gen'),(Join-Path $build 'classes'),(Join-Path $build 'dex') | Out-Null
function Assert-Exit([string]$Step) { if ($LASTEXITCODE -ne 0) { throw "$Step failed ($LASTEXITCODE)" } }
$manifest = (Get-Content -LiteralPath (Join-Path $source 'AndroidManifest.xml') -Raw).Replace('<manifest ', '<manifest package="com.s9local.app" ')
$manifestPath = Join-Path $build 'AndroidManifest.xml'
[IO.File]::WriteAllText($manifestPath, $manifest, [Text.UTF8Encoding]::new($false))
& $aaptExe.FullName compile --dir (Join-Path $source 'res') -o (Join-Path $build 'resources.zip')
Assert-Exit 'Resource compilation'
& $aaptExe.FullName link -o (Join-Path $build 'unsigned.apk') -I $androidJar.FullName --manifest $manifestPath --min-sdk-version 31 --target-sdk-version 36 --version-code 14 --version-name '0.2.10' --java (Join-Path $build 'gen') -A (Join-Path $source 'assets') (Join-Path $build 'resources.zip')
Assert-Exit 'Resource linking'
$javaFiles = @(Get-ChildItem -LiteralPath (Join-Path $source 'java'),(Join-Path $build 'gen') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& $jdkExe.FullName -encoding UTF-8 -source 8 -target 8 -classpath $androidJar.FullName -d (Join-Path $build 'classes') @javaFiles
Assert-Exit 'Java compilation'
& (Join-Path $javaBin 'jar.exe') --create --file (Join-Path $build 'classes.jar') -C (Join-Path $build 'classes') .
Assert-Exit 'Class packaging'
& (Join-Path $javaBin 'java.exe') -cp (Join-Path $buildTools 'lib\d8.jar') com.android.tools.r8.D8 --lib $androidJar.FullName --min-api 31 --output (Join-Path $build 'dex') (Join-Path $build 'classes.jar')
Assert-Exit 'DEX compilation'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open((Join-Path $build 'unsigned.apk'), [IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $build 'dex\classes.dex'), 'classes.dex', [IO.Compression.CompressionLevel]::Optimal) | Out-Null } finally { $zip.Dispose() }
& (Join-Path $buildTools 'zipalign.exe') -f -p 4 (Join-Path $build 'unsigned.apk') (Join-Path $build 'aligned.apk')
Assert-Exit 'ZIP alignment'
$keystore = Join-Path $taskRoot 'tools\s9-local-development.jks'
if (!(Test-Path -LiteralPath $keystore)) {
    & (Join-Path $javaBin 'keytool.exe') -genkeypair -keystore $keystore -storepass android -keypass android -alias s9local -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=S9 Local Development' -noprompt
    Assert-Exit 'Development signing key generation'
}
$apk = Join-Path $dist 'S9-local-v0.2.10.apk'
& (Join-Path $javaBin 'java.exe') -jar (Join-Path $buildTools 'lib\apksigner.jar') sign --ks $keystore --ks-key-alias s9local --ks-pass pass:android --key-pass pass:android --out $apk (Join-Path $build 'aligned.apk')
Assert-Exit 'APK signing'
& (Join-Path $javaBin 'java.exe') -jar (Join-Path $buildTools 'lib\apksigner.jar') verify --verbose $apk
Assert-Exit 'APK signature verification'
& $aaptExe.FullName dump badging $apk | Set-Content -LiteralPath (Join-Path $dist 'apk-info.txt')
Assert-Exit 'APK manifest verification'
Get-FileHash -LiteralPath $apk -Algorithm SHA256 | ForEach-Object { $_.Hash.ToLower() + '  S9-local-v0.2.10.apk' } | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt')
Get-Item -LiteralPath $apk | Select-Object FullName,Length





