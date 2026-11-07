$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/tool_paths.ps1"
$out = "$root/build/tests"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$javaSources = @('Event.java','Occurrence.java','ScheduleEngine.java','StudyTimer.java','AlarmPlan.java','TimeRecord.java','TimeStats.java','AgentPlanner.java') | ForEach-Object { "$root/app/src/main/java/cn/qingri/app/$_" }
& "$jdk/bin/javac.exe" -encoding UTF-8 -Xlint:-options --release 8 -d $out @javaSources "$root/tests/CoreTests.java" "$root/tests/TimeTests.java" "$root/tests/PlannerTests.java"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& "$jdk/bin/java.exe" -ea -cp $out cn.qingri.app.CoreTests
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed.' }
& "$jdk/bin/java.exe" -ea -cp $out cn.qingri.app.TimeTests
if ($LASTEXITCODE -ne 0) { throw 'Time record tests failed.' }
& "$jdk/bin/java.exe" -ea -cp $out cn.qingri.app.PlannerTests
if ($LASTEXITCODE -ne 0) { throw 'Planner tests failed.' }
