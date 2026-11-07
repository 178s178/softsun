param([switch]$Software)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$env:ANDROID_AVD_HOME="$root/.tools/avd"
$env:ANDROID_SDK_HOME="$root/.tools/user"
$env:ANDROID_USER_HOME="$root/.tools/user/.android"
$env:ANDROID_EMULATOR_HOME="$root/.tools/emulator-home"
$avd="$env:ANDROID_AVD_HOME/Qingri_API35.avd"
@($avd,$env:ANDROID_USER_HOME,$env:ANDROID_EMULATOR_HOME,"$root/build/qa") | ForEach-Object {New-Item -ItemType Directory -Force -Path $_ | Out-Null}
$image=(Get-ChildItem -LiteralPath "$root/.tools/system-image" -Filter system.img -Recurse | Select-Object -First 1).Directory.FullName
if(!$image){throw 'Run python scripts/setup_emulator.py first.'}
@"
avd.ini.encoding=UTF-8
path=$avd
target=android-35
"@ | Set-Content -LiteralPath "$env:ANDROID_AVD_HOME/Qingri_API35.ini" -Encoding ascii
@"
AvdId=Qingri_API35
avd.ini.displayname=Qingri API 35
abi.type=x86_64
tag.id=default
image.sysdir.1=$image
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=2048
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader
hw.keyboard=yes
hw.audioInput=no
hw.sdCard=no
hw.mainKeys=no
disk.dataPartition.size=4G
vm.heapSize=256
showDeviceFrame=no
"@ | Set-Content -LiteralPath "$avd/config.ini" -Encoding ascii
$arguments=@('-avd','Qingri_API35','-no-window','-no-audio','-no-snapshot','-no-boot-anim','-gpu','swiftshader','-port','5556')
if($Software){$arguments+=@('-accel','off')}
$process=Start-Process -FilePath "$root/.tools/emulator/emulator/emulator.exe" -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput "$root/build/qa/emulator.log" -RedirectStandardError "$root/build/qa/emulator-error.log"
Write-Output "Emulator PID: $($process.Id)"
