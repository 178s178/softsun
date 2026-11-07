param([string]$Mode='storage')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$env:ANDROID_USER_HOME="$root/.tools/user/.android"
$adb="$root/.tools/platform-tools/platform-tools/adb.exe"
function Adb([string[]]$arguments){ & $adb -s emulator-5556 @arguments; if($LASTEXITCODE -ne 0){throw 'ADB command failed'} }
& $adb start-server
$connected=$false
for($attempt=0;$attempt -lt 45;$attempt++){
    $devices=(& $adb devices | Out-String)
    if($devices -match 'emulator-5556\s+device'){$connected=$true;break}
    Start-Sleep -Seconds 1
}
if(!$connected){throw 'The QA emulator emulator-5556 is not connected.'}
Adb @('install','-r',"$root/dist/qingri-1.4.0.apk")
Adb @('install','-r',"$root/build/apk/qingri-tests.apk")
if($Mode -eq 'ui' -or $Mode -eq 'regression') {
    Adb @('shell','pm','grant','cn.qingri.app','android.permission.POST_NOTIFICATIONS')
    Adb @('shell','appops','set','cn.qingri.app','SCHEDULE_EXACT_ALARM','allow')
    Adb @('shell','am','force-stop','cn.qingri.app')
}
if($Mode -eq 'denied') {
    Adb @('shell','pm','revoke','cn.qingri.app','android.permission.POST_NOTIFICATIONS')
    Adb @('shell','appops','set','cn.qingri.app','SCHEDULE_EXACT_ALARM','deny')
}
$runner='SmokeInstrumentation'
if($Mode -eq 'records'){$runner='RecordInstrumentation'}
if($Mode -eq 'records-ui'){$runner='RecordsUiInstrumentation'}
if($Mode -eq 'upgrade'){$runner='UpgradeInstrumentation'}
if($Mode -eq 'agent'){$runner='AgentInstrumentation'}
if($Mode -eq 'agent-ui'){$runner='AgentUiInstrumentation'}
$extra=@('-e','mode',$Mode)
if($Mode -eq 'upgrade'){$extra=@('-e','phase','after')}
$result=(& $adb -s emulator-5556 shell am instrument -w @extra "cn.qingri.app.tests/cn.qingri.app.tests.$runner" 2>&1 | Out-String)
New-Item -ItemType Directory -Force -Path "$root/build/qa" | Out-Null
$result | Set-Content -LiteralPath "$root/build/qa/$Mode-tests.log" -Encoding utf8
Write-Output $result
$expected=@{storage='Android storage assertions';ui='Android UI and alarm assertions';denied='denied-permission assertions';channel='channel-permission assertions';regression='review regression assertions';records='time storage assertions';'records-ui'='time UI assertions';agent='agent assertions';'agent-ui'='agent UI assertions'}[$Mode]
$passed=$expected -and $result -match ('PASS: \d+ '+[regex]::Escape($expected))
if($Mode -eq 'upgrade'){$passed=$result -match 'PASS: upgrade after'}
if(!$passed -or $result -match 'FAIL:'){throw "Android $Mode tests failed."}
