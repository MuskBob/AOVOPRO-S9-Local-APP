$ErrorActionPreference = 'Stop'
$runtime = Join-Path $PSScriptRoot 'android-runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$emulatorZip = Join-Path $runtime 'emulator.zip'
$imageZip = Join-Path $runtime 'android36.zip'
& curl.exe -4 --fail --location --retry 2 --connect-timeout 30 --max-time 1200 --silent --show-error 'https://dl.google.com/android/repository/emulator-windows_x64-15917651.zip' --output $emulatorZip
if ($LASTEXITCODE -ne 0) { throw 'Emulator download failed' }
& curl.exe -4 --fail --location --retry 2 --connect-timeout 30 --max-time 1200 --silent --show-error 'https://dl.google.com/android/repository/sys-img/android/x86_64-36_r02.zip' --output $imageZip
if ($LASTEXITCODE -ne 0) { throw 'Emulator download failed' }
if ((Get-FileHash $emulatorZip -Algorithm SHA1).Hash -ne '54fa750822ff462d57e04fc8e98e60f08df2bb61') { throw 'Emulator checksum mismatch' }
if ((Get-FileHash $imageZip -Algorithm SHA1).Hash -ne '829c076e8ff448a336097ae25a355b495ba36e2c') { throw 'System image checksum mismatch' }
Write-Output 'Both packages verified'
& tar.exe -xf $emulatorZip -C $runtime
if ($LASTEXITCODE -ne 0) { throw 'Emulator extraction failed' }
$imageDir = Join-Path $runtime 'system-images\android-36\default'
New-Item -ItemType Directory -Force -Path $imageDir | Out-Null
& tar.exe -xf $imageZip -C $imageDir
if ($LASTEXITCODE -ne 0) { throw 'Image extraction failed' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'platform-tools\platform-tools') -Destination $runtime -Recurse -Force
$avds = Join-Path $PSScriptRoot 'avd'
$avdDir = Join-Path $avds 's9-api36.avd'
New-Item -ItemType Directory -Force -Path $avdDir | Out-Null
@("avd.ini.encoding=UTF-8", "path=$avdDir", "target=android-36") | Set-Content -LiteralPath (Join-Path $avds 's9-api36.ini') -Encoding utf8
$sysDir = (Join-Path $imageDir 'x86_64').Replace('\','/') + '/'
@(
'AvdId=s9-api36','avd.ini.displayname=S9 API 36 Test','abi.type=x86_64','hw.cpu.arch=x86_64',
'hw.cpu.ncore=4','hw.ramSize=2048','hw.lcd.width=393','hw.lcd.height=873','hw.lcd.density=160',
'hw.keyboard=yes','hw.gpu.enabled=yes','hw.gpu.mode=swiftshader_indirect','hw.audioInput=no',
'disk.dataPartition.size=2G',"image.sysdir.1=$sysDir",'tag.id=default','tag.display=Default',
'PlayStore.enabled=false','showDeviceFrame=no','fastboot.forceColdBoot=yes','hw.mainKeys=no'
) | Set-Content -LiteralPath (Join-Path $avdDir 'config.ini') -Encoding utf8
& (Join-Path $runtime 'emulator\emulator.exe') -accel-check
Write-Output 'Android runtime prepared'
