param([switch]$WithTests)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
. "$root/scripts/tool_paths.ps1"
function Run-Checked($program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) { throw "Build step failed: $program ($LASTEXITCODE)" }
}
$out = "$root/build/apk"
@($out,"$out/generated","$out/classes","$out/dex","$root/dist","$root/.tools/signing") | ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }
Run-Checked "$buildTools/aapt2.exe" @('compile','--dir',"$root/app/src/main/res",'-o',"$out/resources.zip")
Run-Checked "$buildTools/aapt2.exe" @('link','-o',"$out/base.apk",'-I',$androidJar,'--manifest',"$root/app/src/main/AndroidManifest.xml",'--java',"$out/generated",'--min-sdk-version','26','--target-sdk-version','35',"$out/resources.zip")
$sources = @(Get-ChildItem -Path "$root/app/src/main/java","$out/generated" -Filter *.java -Recurse | ForEach-Object { $_.FullName })
$sourceList = "$out/sources.txt"
$sources | ForEach-Object { '"' + $_.Replace('\','/') + '"' } | Set-Content -LiteralPath $sourceList -Encoding utf8
Run-Checked "$jdk/bin/javac.exe" @('-encoding','UTF-8','-Xlint:-options','--release','8','-cp',$androidJar,'-d',"$out/classes","@$sourceList")
Run-Checked "$jdk/bin/jar.exe" @('cf',"$out/classes.jar",'-C',"$out/classes",'.')
Run-Checked "$jdk/bin/java.exe" @('-cp',"$buildTools/lib/d8.jar",'com.android.tools.r8.D8','--release','--min-api','26','--lib',$androidJar,'--output',"$out/dex","$out/classes.jar")
$keystore = "$root/.tools/signing/qingri-local.keystore"
if (!(Test-Path -LiteralPath $keystore)) {
    Run-Checked "$jdk/bin/keytool.exe" @('-genkeypair','-keystore',$keystore,'-storepass','android','-alias','qingri-local','-keypass','android','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Qingri Local Build, O=Personal, C=CN')
}
function Package-Apk($baseApk,$dexDir,$prefix,$targetApk) {
    Copy-Item -LiteralPath $baseApk -Destination "$out/$prefix-unsigned.apk" -Force
    $archive = [System.IO.Compression.ZipFile]::Open("$out/$prefix-unsigned.apk",[System.IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($dex in Get-ChildItem -LiteralPath $dexDir -Filter *.dex) {
            $existingEntry = $archive.GetEntry($dex.Name)
            if ($existingEntry) { $existingEntry.Delete() }
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$dex.FullName,$dex.Name,[System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $archive.Dispose() }
    Run-Checked "$buildTools/zipalign.exe" @('-f','4',"$out/$prefix-unsigned.apk","$out/$prefix-aligned.apk")
    Run-Checked "$jdk/bin/java.exe" @('-jar',"$buildTools/lib/apksigner.jar",'sign','--ks',$keystore,'--ks-key-alias','qingri-local','--ks-pass','pass:android','--key-pass','pass:android','--out',$targetApk,"$out/$prefix-aligned.apk")
    Run-Checked "$jdk/bin/java.exe" @('-jar',"$buildTools/lib/apksigner.jar",'verify','--verbose',$targetApk)
}
Package-Apk "$out/base.apk" "$out/dex" 'app' "$root/dist/qingri-1.4.0.apk"
if ($WithTests) {
    @("$out/test-classes","$out/test-dex") | ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }
    $testSources=@(Get-ChildItem -LiteralPath "$root/tests/android" -Filter *.java -Recurse | ForEach-Object {$_.FullName})
    Run-Checked "$jdk/bin/javac.exe" (@('-encoding','UTF-8','-Xlint:-options','--release','8','-cp',"$androidJar;$out/classes",'-d',"$out/test-classes")+$testSources)
    Run-Checked "$jdk/bin/jar.exe" @('cf',"$out/tests.jar",'-C',"$out/test-classes",'.')
    Run-Checked "$jdk/bin/java.exe" @('-cp',"$buildTools/lib/d8.jar",'com.android.tools.r8.D8','--min-api','26','--lib',$androidJar,'--classpath',"$out/classes.jar",'--output',"$out/test-dex","$out/tests.jar")
    Run-Checked "$buildTools/aapt2.exe" @('link','-o',"$out/test-base.apk",'-I',$androidJar,'--manifest',"$root/tests/android/AndroidManifest.xml",'--min-sdk-version','26','--target-sdk-version','35')
    Package-Apk "$out/test-base.apk" "$out/test-dex" 'tests' "$out/qingri-tests.apk"
}
Write-Output "APK: $root/dist/qingri-1.4.0.apk"
