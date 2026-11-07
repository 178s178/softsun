$projectRoot = Split-Path -Parent $PSScriptRoot
$jdkCandidates = @("$projectRoot/.tools/jdk", 'C:/Program Files/Android/openjdk', $env:JAVA_HOME) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
$javacPath = $jdkCandidates | ForEach-Object { Get-ChildItem -LiteralPath $_ -Filter javac.exe -Recurse -ErrorAction SilentlyContinue } | Select-Object -First 1
if (!$javacPath) { throw 'JDK missing. Run python scripts/setup_tools.py.' }
$jdk = $javacPath.Directory.Parent.FullName
$androidJar = (Get-ChildItem -Path "$projectRoot/.tools/platform" -Filter android.jar -Recurse | Select-Object -First 1).FullName
$buildTools = (Get-ChildItem -Path "$projectRoot/.tools/build-tools" -Filter aapt2.exe -Recurse | Select-Object -First 1).Directory.FullName
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk/bin;$env:PATH"
