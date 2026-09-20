$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$javaBin = (Get-ChildItem -LiteralPath (Join-Path $taskRoot 'tools\jdk') -Filter javac.exe -Recurse | Select-Object -First 1).Directory.FullName
$buildTools = (Get-ChildItem -LiteralPath (Join-Path $taskRoot 'tools\build-tools') -Filter aapt2.exe -Recurse | Select-Object -First 1).Directory.FullName
$androidJar = (Get-ChildItem -LiteralPath (Join-Path $taskRoot 'tools\platform') -Filter android.jar -Recurse | Select-Object -First 1).FullName
$build = Join-Path $taskRoot ('build\instrumentation-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $build,(Join-Path $build 'classes'),(Join-Path $build 'dex') | Out-Null
function Check-Exit { if($LASTEXITCODE -ne 0) { throw "Build failed: $LASTEXITCODE" } }
& (Join-Path $buildTools 'aapt2.exe') link -o (Join-Path $build 'unsigned.apk') -I $androidJar --manifest (Join-Path $PSScriptRoot 'android\AndroidManifest.xml') --min-sdk-version 31 --target-sdk-version 36
Check-Exit
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d (Join-Path $build 'classes') (Join-Path $PSScriptRoot 'android\StartupInstrumentation.java')
Check-Exit
& (Join-Path $javaBin 'jar.exe') --create --file (Join-Path $build 'classes.jar') -C (Join-Path $build 'classes') .
Check-Exit
& (Join-Path $javaBin 'java.exe') -cp (Join-Path $buildTools 'lib\d8.jar') com.android.tools.r8.D8 --lib $androidJar --min-api 31 --output (Join-Path $build 'dex') (Join-Path $build 'classes.jar')
Check-Exit
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open((Join-Path $build 'unsigned.apk'),[IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $build 'dex\classes.dex'),'classes.dex') | Out-Null } finally { $zip.Dispose() }
& (Join-Path $buildTools 'zipalign.exe') -f 4 (Join-Path $build 'unsigned.apk') (Join-Path $build 'aligned.apk')
Check-Exit
& (Join-Path $javaBin 'java.exe') -jar (Join-Path $buildTools 'lib\apksigner.jar') sign --ks (Join-Path $taskRoot 'tools\s9-local-development.jks') --ks-key-alias s9local --ks-pass pass:android --key-pass pass:android --out (Join-Path $taskRoot 'build\startup-tests.apk') (Join-Path $build 'aligned.apk')
Check-Exit
